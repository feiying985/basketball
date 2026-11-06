# 服务端任务持久化与账号体系

> - **适用版本**：`server` 0.3.0（SQLite 持久化 + 账号/Bearer 会话）
> - **更新日期**：2026-11-06
> - **范围**：本文只描述服务端。安卓 App 目前仍以匿名方式调用分析接口，登录界面与
>   token 存储尚未接入；服务端默认 `BASKETBALL_REQUIRE_AUTH=false`，因此现有 App 不受影响。

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
| `filename` | 上传文件名 |
| `created_at` / `updated_at` | UTC ISO-8601 时间 |
| `result_json` | 识别结果 JSON（`AnalysisResult`） |
| `error` | 失败原因 |

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
| `username` | 展示用户名（保留原始大小写） |
| `username_key` | `username.casefold()`，唯一索引，保证大小写不敏感的重名判断 |
| `password_hash` | scrypt 哈希，格式 `scrypt$n$r$p$salt$digest` |
| `created_at` | 注册时间 |

`sessions` 表：

| 字段 | 说明 |
| --- | --- |
| `token_hash` | token 的 SHA-256 摘要（数据库不保存明文 token） |
| `user_id` | 所属用户，外键级联删除 |
| `created_at` | 签发时间 |
| `expires_at` | 过期时间；`NULL` 表示不过期 |

### 3.2 密码与 token

- 密码使用 **scrypt**（`n=2^14`、`r=8`、`p=1`、32 字节输出，每个用户独立随机盐），
  校验使用常量时间比较。
- 登录成功后返回 32 字节随机 token（URL-safe Base64），客户端后续用
  `Authorization: Bearer <token>` 访问。
- 服务端只保存 token 的 SHA-256 摘要；即使数据库泄露也拿不到可用 token。
- token 默认 30 天过期，可用 `BASKETBALL_TOKEN_TTL_DAYS` 调整；设为 `0` 表示不过期。
- 退出登录只吊销当前 token，同一账号在其他设备上的会话不受影响。

### 3.3 接口

| 方法 | 路径 | 鉴权 | 说明 | 常见状态码 |
| --- | --- | --- | --- | --- |
| `POST` | `/api/v1/auth/register` | 否 | 注册并直接返回 token | `201`；重名 `409`；参数不合法 `422` |
| `POST` | `/api/v1/auth/login` | 否 | 用户名密码登录 | `200`；失败 `401` |
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
    "username": "demo",
    "created_at": "2026-11-06T02:00:00Z"
  }
}
```

用户名规则：去掉首尾空白后 3–32 个字符，不能包含空白字符；密码 8–128 个字符。

### 3.4 任务归属与可见性

| 场景 | 行为 |
| --- | --- |
| 带 token 上传 | 任务 `owner_id` = 当前用户 |
| 不带 token 上传（`require_auth=false`） | 任务 `owner_id = NULL`，凭任务 ID 可查询 |
| 查询他人任务 | 返回 `404 分析任务不存在`（不暴露任务是否存在） |
| 查询自己的任务 | 正常返回 |
| 查询自己的任务列表 | 只返回自己的任务 |
| `BASKETBALL_REQUIRE_AUTH=true` | 上传、查询任务、任务列表都要求登录；匿名旧任务需要登录后访问 |

默认 `BASKETBALL_REQUIRE_AUTH=false` 是为了兼容尚未接入登录的安卓 App。等 App 完成登录和
token 存储后，生产环境应打开该开关。

### 3.5 与现有 App 的兼容性

- 现有 App 不带 `Authorization` 头，默认配置下上传/轮询行为不变。
- 新增的响应字段（如 token 响应）只出现在新接口；任务响应结构未变，App 的
  `ignoreUnknownKeys = true` 也不受影响。
- App 的本地历史仍是本地 Room 快照；接入账号后可以把 `GET /api/v1/analyses` 作为云端历史来源。

## 4. 本地试用

```bash
cd server
python -m venv .venv
source .venv/bin/activate          # Windows: .venv\Scripts\activate
pip install -r requirements-dev.txt
uvicorn app.main:app --reload --port 8000
```

另开一个终端：

```bash
# 注册（用户名/密码按需修改）
TOKEN=$(curl -s -X POST http://127.0.0.1:8000/api/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"demo","password":"demo-password"}' | jq -r .access_token)

# 查看当前用户
curl -s http://127.0.0.1:8000/api/v1/auth/me -H "Authorization: Bearer $TOKEN" | jq .

# 带 token 上传
curl -s -F "file=@test.mp4;type=video/mp4" \
  -H "Authorization: Bearer $TOKEN" \
  http://127.0.0.1:8000/api/v1/analyses | jq .

# 查看自己的任务列表
curl -s http://127.0.0.1:8000/api/v1/analyses \
  -H "Authorization: Bearer $TOKEN" | jq .

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
  标记为失败、应用启动生命周期生效。
- `tests/test_auth.py`：注册/重名/参数校验、登录失败与成功、退出只吊销当前会话、过期与非法
  token、任务仅本人可见、匿名调用兼容、`require_auth` 开关生效。

测试通过 `tests/conftest.py` 使用独立的临时数据库，不会读写开发者本地数据。

## 6. 已知限制与后续

1. **仍是单进程**：SQLite 支持多进程读，但分析任务由 API 进程执行；多 worker 下上传和轮询可能
   落到不同进程，后台任务也缺少重试。扩容前应换 PostgreSQL + 任务队列。
2. **没有刷新 token / 设备管理 / 密码找回 / 邮箱验证**：当前是最小账号闭环。
3. **没有登录限流和验证码**：公网暴露前应在 Nginx 或应用层加登录/注册限流。
4. **上传文件不自动清理**：数据库只持久化任务元数据，不改变上传目录的清理策略。
5. **App 未接入**：登录界面、token 安全存储、401 自动登出、云端历史同步仍未实现。
6. **数据库迁移**：当前用 `CREATE TABLE IF NOT EXISTS` 建表，没有版本化迁移；后续改表结构时
   需要引入 Alembic 或手写迁移脚本。
