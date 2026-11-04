# Basketball — 篮球进球识别

安卓端 + 服务端 monorepo，目标是把「打篮球 → 录制视频 → 自动识别进球」做成可用的 MVP。

> **当前状态：项目脚手架（MVP 第一步）。**
> 安卓端与服务端骨架已就绪；进球识别检测器仍是占位实现，尚未接入 CV 模型。
> GitHub 现成方案调研见 [docs/research/shot-recognition.md](docs/research/shot-recognition.md)。

## 目录结构

```text
.
├── android/                  # 安卓 App（Kotlin + Jetpack Compose）
│   └── app/src/main/java/com/feiying/basketball/
│       ├── MainActivity.kt
│       ├── ui/               # 首页与主题
│       └── data/             # 服务端连通性检查
├── server/                   # 服务端（Python 3.11 + FastAPI）
│   ├── app/
│   │   ├── api/routes.py     # HTTP 接口
│   │   ├── services/         # 任务存储、分析流程、检测器接口
│   │   └── schemas.py        # 请求/响应模型
│   └── tests/
├── docs/
│   ├── research/             # 进球识别方案调研
│   └── design/               # App 交互流程设计
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
                                                    │  当前：占位实现       │
                                                    │  下一步：YOLO + 跟踪  │
                                                    └──────────────────────┘
```

MVP 先做**服务端离线分析**：App 只负责采集/上传，识别逻辑全部放在 Python 服务端，便于快速迭代模型。
端侧实时识别放到后续阶段。

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

模拟器通过 `http://10.0.2.2:8000` 访问宿主机服务；真机调试时把
`android/app/build.gradle.kts` 里的 `SERVER_BASE_URL` 改成电脑的局域网 IP。

## API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/api/v1/health` | 健康检查，返回服务版本与当前检测器名称 |
| `POST` | `/api/v1/analyses` | 上传视频（multipart 字段 `file`），创建分析任务，返回 `202` |
| `GET` | `/api/v1/analyses/{id}` | 查询任务状态与识别结果 |

任务状态：`queued → processing → completed / failed`。

**注意**：当前检测器是占位实现，分析完成后 `result.shots` 为空，并带有 `note` 说明。
这样接口和任务流程可以先联调，同时避免把「空结果」误认为真实识别。

支持格式：`.mp4`、`.mov`、`.m4v`、`.webm`、`.avi`、`.mkv`；单文件默认上限 200 MB
（可用 `BASKETBALL_MAX_UPLOAD_BYTES` 调整，上传目录可用 `BASKETBALL_UPLOAD_DIR` 调整）。

## 测试

```bash
cd server && python -m pytest -q     # 服务端接口测试
cd android && ./gradlew :app:assembleDebug   # 安卓编译（需要 Android SDK）
```

CI 会在 PR 上分别运行服务端测试和安卓构建：
[.github/workflows/server-ci.yml](.github/workflows/server-ci.yml)、
[.github/workflows/android-ci.yml](.github/workflows/android-ci.yml)。

## 下一步

1. **接入识别算法**：YOLO 检测篮球/篮筐 → ByteTrack 跟踪 → 轨迹穿筐判定，替换 `StubShotDetector`。
   技术路线与候选项目见 [调研文档](docs/research/shot-recognition.md)。
2. **App 采集流程**：录像/相册选择、上传进度、结果列表与人工纠正。
   交互设计见 [docs/design/app-interaction-flow.md](docs/design/app-interaction-flow.md)。
3. **数据集与评估**：公开数据集冷启动 + 自采场地数据微调，建立逐球准确率评估集。
4. **端侧实时**：TFLite / NCNN 集成（第二阶段）。

## 技术选型说明

- **服务端用 Python**：进球识别的开源生态（YOLO、OpenCV、跟踪算法）以 Python 为主，模型迭代最快；
  当前只暴露轻量 REST API，后续可平滑拆出推理服务。
- **安卓用 Kotlin + Compose**：官方推荐技术栈，单 Activity 起步，后续接 CameraX 与上传。
- **视频处理放服务端**：MVP 阶段避免端侧模型体积、功耗和兼容性问题。

## 许可

本仓库尚未添加开源许可证。调研中提到的第三方项目/模型有各自的许可限制
（例如 Ultralytics YOLO 为 AGPL-3.0，多数参考仓库无 License），引入代码或模型前请先确认。
