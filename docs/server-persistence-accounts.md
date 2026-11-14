# 服务端任务持久化与账号体系

> - **适用版本**：`server` 0.6.0（SQLite 持久化 + 手机号账号/短信验证码 + Bearer 会话）；
>   0.5.0 增加任务阶段 `stage` 与片段元数据 `result.shots[].clip`
> - **更新日期**：2026-11-08
> - **范围**：本文描述服务端任务持久化与账号体系。安卓 App 0.6.0 起已接入登录门禁、
>   手机号注册/验证码登录/改密；生产环境建议 `BASKETBALL_REQUIRE_AUTH=true`。
>   完整产品与技术方案见 [design/accounts-auth.md](design/accounts-auth.md)。

## 1. 改动概览

| 能力 | 0.2.0 | 0.3.0 |
| --- | --- | --- |
| 任务状态 / 识别结果 | 进程内存，重启丢失 | SQLite，重启后仍可查询 |
| 重启时未完成的任务 | 直接消失 | 启动时标记为 `failed` 并写入原因 |
| 任务列表 | 无 | `GET /api/v1/analyses`（仅本人任务） |
| 账号 | 无 | 注册 / 登录 / 当前用户 / 退出 |
| 凭据 | 无 | Bearer token（随机、数据库只存摘要、可过期） |
| 任务归属 | 无 | 登录用户的任务与账号绑定，跨账号不可见 |
| 匿名调用 | 可用 | 仍可用；可用 `BASKETBALL_REQUIRE_AUTH=true` 强制登录 |

服务端仍然只跑单进程：分析任务由 FastAPI `BackgroundTasks` 在 API 进程内执行。持久化解决的是
「任务和结果重启不丢」，不是「任务队列」。

0.6.0 在 0.3.0 账号基础上增加：

- 手机号验证码注册；`users.phone` 唯一、`phone_verified_at` 记录验证时间；
- 用户名改为可选（旧用户名账号继续兼容）；
- 账号（手机号/用户名）+ 密码、手机号 + 验证码两种登录；
- 已登录修改密码、手机验证码重置密码；
- `sms_codes` 表、验证码单次/过期/尝试上限、手机号/IP/全站限流；
- 阿里云短信与 dev 日志两种发送实现。

## 2. 任务持久化

### 2.1 存储

- 使用 Python 标准库 `sqlite3`，不新增依赖；数据库默认开启 WAL 模式。
- 数据库文件由 `BASKETBALL_DB_PATH` 指定，默认
  `<系统临时目录>/basketball-data/basketball.db`。生产环境建议放到持久化目录，例如
  `/var/lib/basketball/data/basketball.db`。
- 每次操作打开一个连接，进程内用锁串行化写入；适合单进程 MVP。

`jobs` 表：

| 字段 | 说明 |
| --- | --- |
| `id` | 任务 ID（32 位十六进制） |
| `owner_id` | 任务所属用户；匿名为 `NULL` |
| `status` | `queued` / `processing` / `completed` / `failed` |
| `stage` | 0.5.0 新增：`detecting` / `clipping` / `NULL`（任务结束） |
| `filename` | 上传文件名 |
| `created_at` / `updated_at` | UTC ISO-8601 时间 |
| `result_json` | 识别结果 + 片段元数据 JSON（`AnalysisResult`） |
| `error` | 失败原因 |

旧数据库没有 `stage` 列；启动时用 `PRAGMA table_info(jobs)` 检测并 `ALTER TABLE` 补列，
已有任务和结果不受影响。

### 2.2 重启语义

- `completed` / `failed` 的任务及其结果**会保留**，重启后仍可通过
  `GET /api/v1/analyses/{id}` 查询。
- `queued` / `processing` 的任务在进程启动时统一改成 `failed`，`error` 为
  `服务重启导致任务中断，请重新上传视频`。
- 为什么不做断点续跑：当前分析是进程内后台任务，没有独立队列；上传视频路径也没有写入数据库。
  引入 Redis/Celery 或 RQ 后再支持持久队列和重试。

### 2.3 新增的任务列表接口

```http
GET /api/v1/analyses?limit=50&offset=0
Authorization: Bearer <token>
```

- 必须登录，只返回当前用户的任务，按 `created_at` 倒序。
- `limit` 范围 1–200，默认 50；`offset` 默认 0。
- 匿名任务不归属任何账号，因此不会出现在列表里；只能凭任务 ID 查询。

## 3. 账号体系

### 3.1 数据模型

`users` 表：

| 字段 | 说明 |
| --- | --- |
| `id` | 用户 ID（32 位十六进制） |
| `phone` | 规范化手机号（`+86` + 11 位），唯一；旧账号为 `NULL` |
| `phone_verified_at` | 手机号验证通过时间；旧账号为 `NULL` |
| `username` | 可选展示用户名；旧账号保留原用户名 |
| `username_key` | `username.casefold()`，唯一；没有用户名时为 `NULL` |
| `password_hash` | scrypt 哈希，格式 `scrypt$n$r$p$salt$digest` |
| `created_at` | 注册时间 |

`sms_codes` 表：

| 字段 | 说明 |
| --- | --- |
| `id` | 验证码记录 ID |
| `phone` | 规范化手机号 |
| `purpose` | `register` / `login` / `reset` |
| `code_hash` | HMAC-SHA256(pepper, `phone:purpose:code`)，不存明文验证码 |
| `created_at` / `expires_at` | 签发/过期时间（默认 5 分钟） |
| `attempts` / `max_attempts` | 已尝试次数与上限（默认 5） |
| `consumed_at` | 消费时间；`NULL` 表示未使用 |
| `request_ip` | 发码请求 IP，用于限流 |

`sessions` 表：

| 字段 | 说明 |
| --- | --- |
| `token_hash` | token 的 SHA-256 摘要（数据库不保存明文 token） |
| `user_id` | 所属用户，外键级联删除 |
| `created_at` | 签发时间 |
| `expires_at` | 过期时间；`NULL` 表示不过期 |

### 3.2 密码、验证码与 token

- 密码使用 **scrypt**（`n=2^14`、`r=8`、`p=1`、32 字节输出，每个用户独立随机盐），
  校验使用常量时间比较。
- 登录成功后返回 32 字节随机 token（URL-safe Base64），客户端后续用
  `Authorization: Bearer <token>` 访问。
- 服务端只保存 token 的 SHA-256 摘要；即使数据库泄露也拿不到可用 token。
- token 默认 30 天过期，可用 `BASKETBALL_TOKEN_TTL_DAYS` 调整；设为 `0` 表示不过期。
- 修改密码会吊销该用户除当前会话外的所有会话；重置密码会吊销全部会话。
- 验证码 6 位数字、单次使用、5 分钟过期、最多 5 次尝试；发新码使旧码失效。
- 验证码发送限制：同手机号 60 秒间隔、每小时 5 次、每天 10 次；同 IP 每小时 30 次；
  全站每天 1000 次（均可用环境变量调整）。
- dev 模式只把验证码写入服务端日志；生产使用阿里云短信，必须配置签名、模板和
  `BASKETBALL_SMS_CODE_PEPPER`。
- 开发/验收模式额外接受固定调试验证码 `666666`（可用 `BASKETBALL_SMS_DEBUG_CODE` 修改，
  设为 `off` 关闭）：仍需先调用发码接口，且受有效期、尝试次数和限流约束。生产模式和
  真实短信实现下该固定码自动禁用。

### 3.3 接口

| 方法 | 路径 | 鉴权 | 说明 | 常见状态码 |
| --- | --- | --- | --- | --- |
| `POST` | `/api/v1/auth/sms-code` | 否 | 发送注册/登录/重置验证码 | `200`；`422`；`429`；`503` |
| `POST` | `/api/v1/auth/register` | 否 | 手机号 + 验证码 + 密码注册并返回 token | `201`；验证码错误 `400`；冲突 `409`；`429` |
| `POST` | `/api/v1/auth/login` | 否 | 账号（手机号/用户名）+ 密码登录 | `200`；失败 `401`；`429` |
| `POST` | `/api/v1/auth/login/code` | 否 | 手机号 + 验证码登录 | `200`；验证码错误 `400`；未注册 `404`；`429` |
| `POST` | `/api/v1/auth/password` | 是 | 修改密码（需当前密码） | `204`；错误 `401`；新旧相同 `400` |
| `POST` | `/api/v1/auth/password/reset` | 否 | 手机验证码重置密码 | `204`；验证码错误 `400`；未注册 `404` |
| `GET` | `/api/v1/auth/me` | 是 | 当前用户信息 | `200`；未登录/失效 `401` |
| `POST` | `/api/v1/auth/logout` | 是 | 吊销当前 token | `204`；未登录 `401` |

注册 / 登录响应：

```json
{
  "access_token": "qN3...",
  "token_type": "bearer",
  "expires_at": "2026-12-06T02:00:00Z",
  "user": {
    "id": "8f14e45fceea167a5a36dedd4bea2543",
    "phone": "+8613800138000",
    "username": null,
    "display_name": "138****8000",
    "created_at": "2026-11-06T02:00:00Z"
  }
}
```

- 手机号规则：去掉空格/短横线和 `+86`/`86` 前缀后，必须是 `1[3-9]` 开头的 11 位中国大陆号码。
- 用户名可选：3–32 个字符、不含空白、大小写不敏感唯一，且不能是手机号格式。
- 密码 8–128 个字符。
- 账号登录的 `account` 先按用户名查，再按规范化手机号查；旧客户端仍可传 `username` 字段。
- 发码接口无论手机号是否已注册都返回相同结果；注册冲突在验证码通过后才提示。

### 3.4 任务归属与可见性

| 场景 | 行为 |
| --- | --- |
| 带 token 上传 | 任务 `owner_id` = 当前用户 |
| 不带 token 上传（`require_auth=false`） | 任务 `owner_id = NULL`，凭任务 ID 可查询 |
| 查询他人任务 | 返回 `404 分析任务不存在`（不暴露任务是否存在） |
| 查询自己的任务 | 正常返回 |
| 查询自己的任务列表 | 只返回自己的任务 |
| `BASKETBALL_REQUIRE_AUTH=true` | 上传、查询任务、任务列表和片段都要求登录；匿名旧任务需要登录后访问 |

默认 `BASKETBALL_REQUIRE_AUTH=false` 只用于本地兼容；Android 0.6.0 已接入登录，
生产环境应设置为 `true`。

### 3.5 与现有 App / 旧账号的兼容性

- 旧用户名账号迁移后 `phone=NULL`，仍可用用户名 + 密码登录；绑定手机号放在 P1。
- 新账号的手机号是主身份；用户名可选，App 注册页默认只要求手机号、验证码和密码。
- 0.5.0 的任务响应新增 `stage`、`shots[].clip`、`result.clip_note`；旧 App 的
  `ignoreUnknownKeys = true` 会忽略它们。
- App 的本地历史按账号隔离：首次登录时把旧匿名记录归到当前账号，之后只显示当前账号记录。
- App 的 token 使用 Android Keystore 加密后存 DataStore；401 会清理登录态并回到登录页。
- 服务端片段接口要求 Bearer token；App 播放/下载片段时都会带 `Authorization` 头。

## 4. 本地试用

```bash
cd server
python -m venv .venv
source .venv/bin/activate          # Windows: .venv\Scripts\activate
pip install -r requirements-dev.txt
uvicorn app.main:app --reload --port 8000
```

另开一个终端（默认 `BASKETBALL_SMS_PROVIDER=dev`，验证码会出现在 uvicorn 日志里；
开发阶段也可以直接用固定验证码 `666666`，无需翻日志）：

```bash
# 发送注册验证码；从服务端日志找到 [dev-sms] ... code=123456
curl -s -X POST http://127.0.0.1:8000/api/v1/auth/sms-code \
  -H 'Content-Type: application/json' \
  -d '{"phone":"13800138000","purpose":"register"}'

# 注册（替换 CODE；开发阶段可直接填 666666）
TOKEN=$(curl -s -X POST http://127.0.0.1:8000/api/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"phone":"13800138000","code":"CODE","password":"demo-password"}' \
  | jq -r .access_token)

# 查看当前用户
curl -s http://127.0.0.1:8000/api/v1/auth/me -H "Authorization: Bearer $TOKEN" | jq .

# 发送登录验证码后，用手机号 + 验证码登录
curl -s -X POST http://127.0.0.1:8000/api/v1/auth/login/code \
  -H 'Content-Type: application/json' \
  -d '{"phone":"13800138000","code":"LOGIN_CODE"}' | jq .

# 带 token 上传
curl -s -F "file=@test.mp4;type=video/mp4" \
  -H "Authorization: Bearer $TOKEN" \
  http://127.0.0.1:8000/api/v1/analyses | jq .

# 查看自己的任务列表
curl -s http://127.0.0.1:8000/api/v1/analyses \
  -H "Authorization: Bearer $TOKEN" | jq .

# 修改密码（当前密码 + 新密码）
curl -s -o /dev/null -w '%{http_code}\n' -X POST \
  http://127.0.0.1:8000/api/v1/auth/password \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"current_password":"demo-password","new_password":"new-demo-password"}'

# 退出
curl -s -o /dev/null -w '%{http_code}\n' -X POST \
  http://127.0.0.1:8000/api/v1/auth/logout -H "Authorization: Bearer $TOKEN"
```

验证持久化：上传并等待任务 `completed`，重启 uvicorn，再用同一任务 ID 查询，应仍返回
`completed` 和 `result`；重启时仍在 `queued/processing` 的任务会变成 `failed`。

## 5. 测试

```bash
cd server && python -m pytest -q
```

新增测试覆盖：

- `tests/test_persistence.py`：任务结果跨 store 重建仍存在、按 owner 过滤、启动时把中断任务
  标记为失败、应用启动生命周期生效、`stage` 持久化/中断清空、旧库自动补 `stage` 列。
- `tests/test_auth.py`：手机号注册/重名/参数校验、账号登录、验证码登录、改密、重置、
  登录限流、退出只吊销当前会话、过期与非法 token、任务仅本人可见、匿名调用兼容、
  `require_auth` 开关生效。
- `tests/test_sms.py`：手机号规范化、验证码摘要、60 秒重发/限流、过期、错误次数、
  发新码使旧码失效、验证码与手机号绑定、短信服务失败清理。
- `tests/test_db_migration.py`：旧 `users` 表迁移后可登录、旧会话有效、新手机号账号可创建。
- `tests/test_clips.py`、`tests/test_clip_api.py`：片段窗口/ffmpeg 失败路径/真实 ffmpeg、
  片段接口 200/206/404/409 与跨账号隔离。

片段生成细节见 [shot-clips.md](shot-clips.md)。

测试通过 `tests/conftest.py` 使用独立的临时数据库，不会读写开发者本地数据。

## 6. 已知限制与后续

1. **仍是单进程**：SQLite 支持多进程读，但分析任务由 API 进程执行；多 worker 下上传和轮询可能
   落到不同进程，后台任务也缺少重试。扩容前应换 PostgreSQL + 任务队列。
2. **短信依赖外部服务**：阿里云签名/模板尚未在当前仓库配置；生产上线前需要审核通过的签名、
   模板和 RAM AccessKey，并设置 `BASKETBALL_SMS_PROVIDER=aliyun`。
3. **防刷仍需上线后观察**：应用层限流与 Nginx 双层已实现，但验证码轰炸、分散 IP 攻击仍需要
   监控、费用告警；P1 可接人机验证/滑块。
4. **没有刷新 token / 设备管理 / 账号注销 / 旧账号绑定手机号**：旧账号先用用户名 + 密码登录。
5. **上传文件和片段不自动清理**：数据库只持久化任务元数据，不改变上传/片段目录的清理策略。
6. **数据库迁移**：目前用 `CREATE TABLE IF NOT EXISTS` 加针对性补列/重建 users；后续再改表结构时
   应引入 Alembic 或手写版本化迁移脚本。
7. **App 本地历史按账号隔离，但服务端匿名旧任务不会自动归属新账号**；云端历史同步仍未实现。
