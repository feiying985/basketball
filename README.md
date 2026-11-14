# Basketball — 篮球进球识别

安卓端 + 服务端 monorepo，目标是把「打篮球 → 录制视频 → 自动识别进球」做成可用的 MVP。

> **当前状态：App 端 MVP 主流程已实现，服务端已接入经典 CV 进球识别基线、任务持久化、手机号账号体系与进球片段剪辑。**
> 安卓端支持手机号验证码注册、账号/验证码登录、修改密码、登录门禁、录制/相册选择、
> 视频校验、上传与轮询、结果页、进球片段播放/保存和本地历史记录；
> 服务端基于 OpenCV 做颜色检测 + 轨迹 + 穿筐判定，无需模型文件即可运行；
> 分析完成后用系统 ffmpeg 为每个命中球生成前后 5 秒的 MP4；
> 分析任务和账号保存到 SQLite，重启不丢结果，并支持注册/登录与任务归属。
> GitHub 现成方案调研见 [docs/research/shot-recognition.md](docs/research/shot-recognition.md)，
> 商业竞品调研见 [docs/research/competitor-analysis.md](docs/research/competitor-analysis.md)，
> 算法与限制见 [docs/shot-detection.md](docs/shot-detection.md)，
> 进球片段设计见 [docs/shot-clips.md](docs/shot-clips.md)，
> 持久化与账号设计见 [docs/server-persistence-accounts.md](docs/server-persistence-accounts.md)，
> 评估与标注见 [server/tools/README.md](server/tools/README.md)，
> 个人开发者推广运营策略见 [docs/promotion-operations-strategy.md](docs/promotion-operations-strategy.md)。

## 目录结构

```text
.
├── android/                  # 安卓 App（Kotlin + Jetpack Compose）
│   └── app/src/main/java/com/feiying/basketball/
│       ├── MainActivity.kt
│       ├── ui/               # 首页、录制、确认、分析、结果、历史与主题
│       └── data/             # HTTP、Room、视频导入、设置
├── server/                   # 服务端（Python 3.11 + FastAPI）
│   ├── app/
│   │   ├── api/              # HTTP 路由与鉴权依赖
│   │   ├── services/         # SQLite、账号、任务、分析流程、检测器
│   │   └── schemas.py        # 请求/响应模型
│   └── tests/
├── docs/
│   ├── research/             # 进球识别方案调研
│   ├── design/               # App 交互流程与账号方案设计
│   ├── shot-detection.md     # 算法与限制说明
│   ├── shot-clips.md         # 进球片段生成与 App 播放
│   ├── server-persistence-accounts.md  # 任务持久化与账号体系
│   ├── deployment-verification.md  # 阿里云部署、运维与验证指引
│   └── promotion-operations-strategy.md  # 个人开发者推广运营策略
└── .github/workflows/        # 服务端测试 + 安卓构建 CI
```

## 架构

```text
┌────────────────────┐      multipart 视频      ┌──────────────────────────┐
│  Android App       │ ───────────────────────▶ │  FastAPI Server          │
│  录制/选择视频      │                          │  /api/v1/analyses        │
│  展示识别结果       │ ◀─────────────────────── │  任务状态 + ShotEvent[]   │
└────────────────────┘        JSON 轮询           └────────────┬─────────────┘
                                                               │
                                                    ┌──────────▼───────────┐
                                                    │  ShotDetector 接口    │
                                                    │  当前：经典 CV 基线   │
                                                    │  下一步：YOLO + 跟踪  │
                                                    └──────────────────────┘
```

MVP 先做**服务端离线分析**：App 只负责采集/上传，识别逻辑全部放在 Python 服务端，便于快速迭代模型。
端侧实时识别放到后续阶段。

任务状态、识别结果和账号保存在服务端 SQLite 中，服务重启后仍可查询；登录用户的任务与账号绑定，
不同账号之间互相不可见。App 0.6.0 已接入登录门禁；生产环境设置
`BASKETBALL_REQUIRE_AUTH=true` 让服务端也强制鉴权。设计细节见
[docs/server-persistence-accounts.md](docs/server-persistence-accounts.md)。

## App 端流程

首次启动先登录/注册：手机号 + 验证码 + 密码注册，支持「账号（手机号/用户名）+ 密码」或
「手机号 + 验证码」登录；登录后才进入主页。主页「开始分析」→ 录制或从相册选择 → 确认视频
（格式、大小、时长校验）→ 上传并轮询任务 → 结果页查看命中/未中时间点与命中率 →
结果自动保存到本地记录。底部导航保留「分析」「记录」两个入口，右上角进入账号页。

- 界面采用固定的黑色主题（近黑背景 + 橙色强调），不跟随系统浅色设置；
- 账号页支持修改密码和退出登录；忘记密码可通过手机验证码重置；
- 登录 token 使用 Android Keystore 加密后存 DataStore；401 会自动回登录页；
- 录制使用 CameraX，最长 **20 分钟**，少于 1 秒不生成视频；
- 相册选择使用系统 Photo Picker，不需要存储权限，选中后复制到应用缓存；
- 上传上限与服务端一致（200 MB），显示真实字节进度，可取消；
- 确认视频时可以**手动圈定篮筐**：拖动框选画面中的篮筐开口，坐标随上传一起提交，
  用于自动检测找错篮筐或篮筐不是橙色的场景；不圈定则继续使用自动识别；
- 结果页在命中事件旁显示「片段」，顶部提供「进球片段」卡片；点击进入播放页，
  可**保存到相册**（Android 10+ 无需存储权限，Android 8-9 首次保存时申请）；
- 本地历史使用 Room 保存结果快照，服务端重启后仍可回看；
- 服务端地址默认由 `build.gradle.kts` 配置。真机调试需要改地址时，在首页**长按服务状态**打开开发者设置；
  未登录时可在登录页**长按标题**打开同一设置，普通用户看不到该入口。
- release 构建关闭了 cleartext HTTP，正式包必须使用 HTTPS 服务端地址（debug 仍可访问模拟器地址）。

## 快速开始

### 服务端

```bash
cd server
python -m venv .venv
# Windows: .venv\Scripts\activate
# macOS/Linux:
source .venv/bin/activate
pip install -r requirements-dev.txt
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

打开 <http://127.0.0.1:8000/docs> 查看并调试接口。

### 安卓端

用 Android Studio 打开 `android/` 目录，等待 Gradle 同步后运行 `app`；或命令行构建：

```bash
cd android
./gradlew :app:assembleDebug
```

模拟器通过 `http://10.0.2.2:8000` 访问宿主机服务；真机调试时可在首页长按服务状态打开开发者设置修改地址，
或把 `android/app/build.gradle.kts` 里的 `SERVER_BASE_URL` 改成电脑的局域网 IP。

## 部署

服务端部署到阿里云 ECS 的完整步骤（Ubuntu 24.04 + Nginx + systemd + SQLite，含短信配置、
备份恢复、升级回滚、日志监控和验收清单）见
[docs/deployment-verification.md](docs/deployment-verification.md)。

## API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/api/v1/health` | 健康检查，返回服务版本、当前检测器名称与 `clips`（ffmpeg 是否可用） |
| `POST` | `/api/v1/analyses` | 上传视频（multipart 字段 `file`），可选 `hoop_roi=x,y,w,h` 手动指定篮筐，创建分析任务，返回 `202` |
| `GET` | `/api/v1/analyses` | 当前用户的任务列表（需要登录） |
| `GET` | `/api/v1/analyses/{id}` | 查询任务状态、处理阶段与识别结果 |
| `GET` | `/api/v1/analyses/{id}/clips/{index}` | 播放/下载一个进球片段；支持 HTTP Range（ExoPlayer 可拖动） |
| `POST` | `/api/v1/auth/sms-code` | 发送注册/登录/重置密码验证码 |
| `POST` | `/api/v1/auth/register` | 手机号 + 验证码 + 密码注册并返回 Bearer token |
| `POST` | `/api/v1/auth/login` | 账号（手机号/用户名）+ 密码登录 |
| `POST` | `/api/v1/auth/login/code` | 手机号 + 验证码登录 |
| `POST` | `/api/v1/auth/password` | 已登录修改密码 |
| `POST` | `/api/v1/auth/password/reset` | 手机验证码重置密码 |
| `GET` | `/api/v1/auth/me` | 当前登录用户 |
| `POST` | `/api/v1/auth/logout` | 吊销当前 token |

任务状态：`queued → processing → completed / failed`。
处理期间 `stage` 为 `detecting`（识别中）或 `clipping`（生成片段中），任务结束时为空。

分析完成后 `result.shots` 返回识别到的投篮事件（`made=true` 为命中，
`timestamp_seconds`/`frame_index` 指向球穿过篮筐附近的时刻）。
命中事件还会带 `clip`：`ready` 表示片段已生成并可播放，`failed` 带 `error`；数组顺序按
`timestamp_seconds` 升序，片段接口的 `index` 就是这个数组下标。
`result.note` 在未检测到篮筐、篮球或投篮时说明原因。
`result.clip_note` 在服务端未安装/未启用 ffmpeg 时说明整体未生成片段。
`result.hoop_confidence` 是自动找筐的置信度（0-1，手动圈筐时为 1）：几个橙色候选
得分接近时它会变低，此时服务端不猜测，直接提示手动圈定篮筐，避免把灯柱/海报当成篮筐
后返回错误事件。

**当前基线限制**：依赖画面中的橙色篮球和橙色/红色篮筐、假设机位基本固定，
只识别轨迹经过篮筐附近的投篮（远距离偏出的球可能不报）。算法和调参说明见
[docs/shot-detection.md](docs/shot-detection.md)。

支持格式：`.mp4`、`.mov`、`.m4v`、`.webm`、`.avi`、`.mkv`；单文件默认上限 200 MB
（可用 `BASKETBALL_MAX_UPLOAD_BYTES` 调整，上传目录可用 `BASKETBALL_UPLOAD_DIR` 调整）。

### 账号与持久化

```text
POST /api/v1/auth/sms-code   →  发送短信验证码
POST /api/v1/auth/register   →  { access_token, user }
POST /api/v1/auth/login      →  { access_token, user }
Authorization: Bearer <token>
POST /api/v1/analyses        →  任务归属当前账号
GET  /api/v1/analyses        →  当前账号的历史任务
```

- 注册必须通过手机号验证码；新账号以手机号为主身份，用户名可选，旧用户名账号继续兼容；
- 登录支持「账号（手机号/用户名）+ 密码」和「手机号 + 验证码」；
- 登录后可在账号页修改密码；忘记密码可用手机验证码重置；
- 验证码 5 分钟有效、单次使用、最多 5 次尝试，并按手机号/IP/全站限流；
- 密码用 scrypt 加盐哈希，token 只在数据库保存 SHA-256 摘要，默认 30 天过期；
- Android App 登录后才可进入业务页面，token 使用 Android Keystore 加密存储；
- 开发/测试默认使用 `BASKETBALL_SMS_PROVIDER=dev`（验证码只写服务端日志），生产切换阿里云短信；
- 生产环境应设置 `BASKETBALL_REQUIRE_AUTH=true`，服务端也强制分析接口登录。

完整接口、数据表、重启语义与限制见
[docs/server-persistence-accounts.md](docs/server-persistence-accounts.md)。

### 检测器配置

服务端通过环境变量配置检测器（都有默认值，可不设置）：

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `BASKETBALL_DETECTOR` | `classical` | `classical` 为 OpenCV 基线；`stub` 为不做识别的占位实现 |
| `BASKETBALL_SAMPLE_FPS` | `10` | 抽帧分析的帧率，越高越准也越慢 |
| `BASKETBALL_SHOT_COOLDOWN_SECONDS` | `1.5` | 两次进球判定的最小间隔，避免同一球重复计数 |
| `BASKETBALL_HOOP_ROI` | 空 | 手动指定篮筐区域，格式 `x,y,width,height`（0-1 归一化）；用于白色/非橙色篮筐 |
| `BASKETBALL_HUE_MIN` / `BASKETBALL_HUE_MAX` | `0` / `38` | 橙色掩码的 HSV 色相范围（OpenCV 0-179）；夜景球偏黄时可把上限调到 40-45 |
| `BASKETBALL_SATURATION_MIN` | `70` | 橙色掩码的最小饱和度；运动模糊导致掉色时可降到 45 |
| `BASKETBALL_VALUE_MIN` | `50` | 橙色掩码的最小亮度；夜景可降到 40 |
| `BASKETBALL_BALL_MIN_RADIUS_RATIO` | `0.010` | 球候选的最小半径（相对画面短边） |
| `BASKETBALL_BALL_MAX_RADIUS_RATIO` | `0.120` | 球候选的最大半径；橙色灯柱/衣物较大时可降到 0.05 |
| `BASKETBALL_STATIC_PERSISTENCE_THRESHOLD` | `0.95` | 候选点所在区域的持续橙色占比达到该值时视为静止噪声并丢弃；默认只过滤常亮物体，避免把与篮筐重叠的球一起拒掉 |
| `BASKETBALL_HOOP_MIN_CONFIDENCE` | `0.25` | 自动找筐的最低置信度（0-1）；低于该值时不出事件，提示手动圈定篮筐；设为 `0` 关闭检查 |
| `BASKETBALL_MAX_UPLOAD_BYTES` | `209715200` | 单文件上传上限（字节） |
| `BASKETBALL_UPLOAD_DIR` | 系统临时目录 | 上传视频保存目录 |
| `BASKETBALL_DB_PATH` | `<临时目录>/basketball-data/basketball.db` | SQLite 数据库文件；生产建议持久化目录 |
| `BASKETBALL_REQUIRE_AUTH` | `false` | `true` 时分析相关接口都要求 Bearer token；生产建议开启 |
| `BASKETBALL_TOKEN_TTL_DAYS` | `30` | 登录 token 有效天数；`0` 表示不过期 |
| `BASKETBALL_ENV` | `development` | `production` 时禁止使用 dev 短信实现 |
| `BASKETBALL_SMS_PROVIDER` | `dev` | `dev`（日志输出验证码）或 `aliyun`（真实短信） |
| `BASKETBALL_SMS_ACCESS_KEY_ID` / `..._SECRET` | 空 | 阿里云 RAM AccessKey；生产必填 |
| `BASKETBALL_SMS_SIGN_NAME` / `..._TEMPLATE_CODE` | 空 | 已审核的短信签名和模板 CODE |
| `BASKETBALL_SMS_CODE_PEPPER` | 空 | 验证码 HMAC 密钥；生产必填 |
| `BASKETBALL_SMS_CODE_TTL_SECONDS` | `300` | 验证码有效期；默认 5 分钟 |
| `BASKETBALL_SMS_RESEND_SECONDS` | `60` | 同一手机号重发间隔 |
| `BASKETBALL_SMS_MAX_ATTEMPTS` | `5` | 单个验证码最大尝试次数 |
| `BASKETBALL_SMS_PHONE_HOURLY_LIMIT` / `..._DAILY_LIMIT` | `5` / `10` | 单手机号每小时/每天发码上限 |
| `BASKETBALL_SMS_IP_HOURLY_LIMIT` | `30` | 单 IP 每小时发码上限 |
| `BASKETBALL_SMS_DAILY_LIMIT` | `1000` | 全站每天发码上限 |
| `BASKETBALL_LOGIN_MAX_FAILURES` / `..._WINDOW_SECONDS` | `10` / `900` | 登录失败次数/窗口 |
| `BASKETBALL_CLIP_ENABLED` | `true` | 关闭后不生成进球片段，识别任务照常完成 |
| `BASKETBALL_CLIP_PRE_SECONDS` / `BASKETBALL_CLIP_POST_SECONDS` | `5` / `5` | 进球前后各截取的秒数（0-30） |
| `BASKETBALL_CLIP_DIR` | `<upload_dir>/clips` | 片段保存目录 |
| `BASKETBALL_FFMPEG_PATH` | `ffmpeg` | ffmpeg 可执行文件路径；启动时探测，缺失时 `health.clips=false` |
| `BASKETBALL_CLIP_INCLUDE_MISSES` | `false` | 是否也给未中事件生成片段（默认只剪命中） |
| `BASKETBALL_CLIP_MAX_PER_JOB` | `50` | 单任务最多生成片段数 |
| `BASKETBALL_CLIP_TIMEOUT_SECONDS` | `120` | 单个片段生成超时 |

片段生成细节、存储与清理见 [docs/shot-clips.md](docs/shot-clips.md)。

例如白色篮筐的球场：

```bash
BASKETBALL_HOOP_ROI=0.42,0.25,0.16,0.05 uvicorn app.main:app --host 0.0.0.0 --port 8000
```

## 测试

```bash
cd server && python -m pytest -q     # 服务端接口测试
cd android && ./gradlew :app:assembleDebug        # 安卓编译（需要 Android SDK）
cd android && ./gradlew :app:testDebugUnitTest    # 安卓单元测试（校验、格式化、轮询策略、JSON）
```

真实视频的识别评估（需要本地标注，视频和标注不入库）：

```bash
cd server
python -m tools.evaluate \
  --dataset /path/to/videos \
  --labels /path/to/labels.csv \
  --detector classical \
  --out reports/baseline
```

格式、标注步骤和指标定义见 [server/tools/README.md](server/tools/README.md)。

CI 会在 PR 上分别运行服务端测试和安卓构建：
[.github/workflows/server-ci.yml](.github/workflows/server-ci.yml)、
[.github/workflows/android-ci.yml](.github/workflows/android-ci.yml)。

## 下一步

1. **升级识别算法**：当前 OpenCV 基线可用，但受颜色和机位限制；下一步用
   YOLO/RT-DETR 检测篮球/篮筐 → ByteTrack 跟踪 → 轨迹穿筐判定，替换 `ClassicalShotDetector`。
   技术路线与候选项目见 [调研文档](docs/research/shot-recognition.md)。
2. **结果人工纠正**：在结果页本地覆盖命中/未中，不写回服务端（P1）。
   App 端交互流程设计见 [docs/design/app-interaction-flow.md](docs/design/app-interaction-flow.md)。
3. **任务队列与账号增强**：App 登录门禁、手机号注册、验证码登录和改密已经接入；下一步把
   `BackgroundTasks` 换成可持久化的队列（多实例部署前必须做），并补齐旧账号绑定手机号。
   设计见 [docs/design/accounts-auth.md](docs/design/accounts-auth.md)。
4. **数据集与评估**：公开数据集冷启动 + 自采场地数据微调，建立逐球准确率评估集。
5. **端侧实时**：TFLite / NCNN 集成（第二阶段）。

## 技术选型说明

- **服务端用 Python**：进球识别的开源生态（YOLO、OpenCV、跟踪算法）以 Python 为主，模型迭代最快；
  当前只暴露轻量 REST API，后续可平滑拆出推理服务。
- **安卓用 Kotlin + Compose**：单 Activity + Navigation Compose，CameraX 采集、Media3 播放、Room 本地历史。
- **视频处理放服务端**：MVP 阶段避免端侧模型体积、功耗和兼容性问题。

## 许可

本仓库尚未添加开源许可证。当前服务端运行时依赖 OpenCV（Apache-2.0）与
NumPy（BSD），未引入 AGPL 模型或权重。调研中提到的第三方项目/模型有各自的
许可限制（例如 Ultralytics YOLO 为 AGPL-3.0，多数参考仓库无 License），
引入代码或模型前请先确认。
