# Basketball — 篮球进球识别

安卓端 + 服务端 monorepo，目标是把「打篮球 → 录制视频 → 自动识别进球」做成可用的 MVP。

> **当前状态：App 端 MVP 主流程已实现，服务端已接入经典 CV 进球识别基线。**
> 安卓端支持录制/相册选择、视频校验、上传与轮询、结果页和本地历史记录；
> 服务端基于 OpenCV 做颜色检测 + 轨迹 + 穿筐判定，无需模型文件即可运行。
> GitHub 现成方案调研见 [docs/research/shot-recognition.md](docs/research/shot-recognition.md)，
> 算法与限制见 [docs/shot-detection.md](docs/shot-detection.md)。

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
│   │   ├── api/routes.py     # HTTP 接口
│   │   ├── services/         # 任务存储、分析流程、跟踪/判定/检测器
│   │   └── schemas.py        # 请求/响应模型
│   └── tests/
├── docs/                     # 进球识别方案调研 + 算法说明
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

## App 端流程

主页「开始分析」→ 录制或从相册选择 → 确认视频（格式、大小、时长校验）→ 上传并轮询任务 →
结果页查看命中/未中时间点与命中率 → 结果自动保存到本地记录。底部导航保留「分析」「记录」两个入口。

- 界面采用固定的黑色主题（近黑背景 + 橙色强调），不跟随系统浅色设置；
- 录制使用 CameraX，最长 **20 分钟**，少于 1 秒不生成视频；
- 相册选择使用系统 Photo Picker，不需要存储权限，选中后复制到应用缓存；
- 上传上限与服务端一致（200 MB），显示真实字节进度，可取消；
- 本地历史使用 Room 保存结果快照，服务端重启后仍可回看；
- 服务端地址默认由 `build.gradle.kts` 配置。真机调试需要改地址时，在首页**长按服务状态**打开开发者设置，
  普通用户看不到该入口。

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

## API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/api/v1/health` | 健康检查，返回服务版本与当前检测器名称 |
| `POST` | `/api/v1/analyses` | 上传视频（multipart 字段 `file`），创建分析任务，返回 `202` |
| `GET` | `/api/v1/analyses/{id}` | 查询任务状态与识别结果 |

任务状态：`queued → processing → completed / failed`。

分析完成后 `result.shots` 返回识别到的投篮事件（`made=true` 为命中，
`timestamp_seconds`/`frame_index` 指向球穿过篮筐附近的时刻）。
`result.note` 在未检测到篮筐、篮球或投篮时说明原因。

**当前基线限制**：依赖画面中的橙色篮球和橙色/红色篮筐、假设机位基本固定，
只识别轨迹经过篮筐附近的投篮（远距离偏出的球可能不报）。算法和调参说明见
[docs/shot-detection.md](docs/shot-detection.md)。

支持格式：`.mp4`、`.mov`、`.m4v`、`.webm`、`.avi`、`.mkv`；单文件默认上限 200 MB
（可用 `BASKETBALL_MAX_UPLOAD_BYTES` 调整，上传目录可用 `BASKETBALL_UPLOAD_DIR` 调整）。

### 检测器配置

服务端通过环境变量配置检测器（都有默认值，可不设置）：

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `BASKETBALL_DETECTOR` | `classical` | `classical` 为 OpenCV 基线；`stub` 为不做识别的占位实现 |
| `BASKETBALL_SAMPLE_FPS` | `10` | 抽帧分析的帧率，越高越准也越慢 |
| `BASKETBALL_SHOT_COOLDOWN_SECONDS` | `1.5` | 两次进球判定的最小间隔，避免同一球重复计数 |
| `BASKETBALL_HOOP_ROI` | 空 | 手动指定篮筐区域，格式 `x,y,width,height`（0-1 归一化）；用于白色/非橙色篮筐 |
| `BASKETBALL_MAX_UPLOAD_BYTES` | `209715200` | 单文件上传上限（字节） |
| `BASKETBALL_UPLOAD_DIR` | 系统临时目录 | 上传视频保存目录 |

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

CI 会在 PR 上分别运行服务端测试和安卓构建：
[.github/workflows/server-ci.yml](.github/workflows/server-ci.yml)、
[.github/workflows/android-ci.yml](.github/workflows/android-ci.yml)。

## 下一步

1. **升级识别算法**：当前 OpenCV 基线可用，但受颜色和机位限制；下一步用
   YOLO/RT-DETR 检测篮球/篮筐 → ByteTrack 跟踪 → 轨迹穿筐判定，替换 `ClassicalShotDetector`。
   技术路线与候选项目见 [调研文档](docs/research/shot-recognition.md)。
2. **结果人工纠正**：在结果页本地覆盖命中/未中，不写回服务端（P1）。
3. **数据集与评估**：公开数据集冷启动 + 自采场地数据微调，建立逐球准确率评估集。
4. **端侧实时**：TFLite / NCNN 集成（第二阶段）。

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
