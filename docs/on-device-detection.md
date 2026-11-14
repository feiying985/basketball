# App 本地识别模式（不上传视频）

> - **状态**：已实现（App 0.7.0）
> - **范围**：Android App 的识别方式选择、端侧检测器、进度与结果呈现。
> - **不包含**：端侧实时识别、端侧进球片段生成、模型训练。

## 1. 背景与目标

原来的 App 只有一条链路：视频复制到应用缓存 → 上传到 FastAPI → 服务端
`ClassicalShotDetector` 识别 → 轮询结果。球馆网络差、流量敏感或用户不想把视频传到
服务器时，这条链路不可用。

本功能在**确认视频页**增加识别方式选择：

- **云端识别**（默认）：保持原链路，上传后可生成进球片段；
- **本地识别**：视频只从应用缓存读取，识别在手机上完成，**不调用上传接口**。

两种模式共用同一套轨迹/几何规则，结果都写入本地 Room 历史记录，可离线回看。

## 2. 交互

| 页面 | 变化 |
| --- | --- |
| 确认视频 | 新增「识别方式」两张可选卡片；本地模式显示「视频不会上传，识别在手机本地完成；本地模式不生成进球片段」；主按钮变为「开始本地识别」 |
| 分析页 | 本地模式显示「正在本地识别…」、真实进度百分比和已用时间；提示「视频只在手机本地处理，不会上传」；返回后任务继续在后台运行 |
| 结果页 | 顶部显示「本地识别：视频没有上传，处理都在手机上完成」；事件列表与时间轴照常可用，点击事件可在原视频中定位 |
| 记录页 | 记录保存 `AnalysisMode`；重试沿用原模式，重启 App 后处理中的本地记录会重新进入分析页并恢复 |

## 3. 实现结构

```
domain/Models.kt                              CLOUD / ON_DEVICE
data/local/AppDatabase.kt                     mode 列 + MIGRATION_3_4
data/AnalysisRepository.kt                    createRecord(mode) / runOnDevice()
data/OnDeviceAnalyzer.kt                      可注入的端侧分析接口
data/ondevice/OnDeviceShotAnalyzer.kt          取帧 + 编排检测管线
detection/DetectionConfig.kt                   与服务端一致的参数
detection/PersistenceMap.kt                    静止橙色物体的指数平均图
detection/Vision.kt                            HSV 掩码、形态学、距离变换、候选球、找筐
detection/BallTracking.kt                      候选关联与轨迹过滤
detection/ShotGeometry.kt                      顶点/过筐/命中判定
```

### 3.1 数据与状态

- `AnalysisRecord.mode` 持久化到 Room；迁移把历史记录标为 `CLOUD`，不丢数据。
- 本地记录创建即进入 `PROCESSING`，不经过 `UPLOADING`；`retry` 重置为 `PROCESSING`。
- 进度使用 `ActiveAnalysis.fraction`（0..1），界面显示真实百分比，不使用假进度。
- 本地分析在 application scope 的协程中执行；离开分析页不会取消，进程被杀后记录保留，
  下次打开分析页会重新运行本地检测。
- `runOnDevice` 只读取本地文件，不调用 `AnalysisApi.upload`；Robolectric 测试断言
  没有发往 `/api/v1/analyses` 的请求。

### 3.2 端侧检测管线

服务端基线（`server/app/services/classical_shot_detection.py`）是 OpenCV 实现。为了
避免引入 OpenCV/模型文件带来的 APK 体积和 ABI 维护成本，App 用纯 Kotlin 复刻了同一套
规则：

1. `MediaMetadataRetriever` 按 10 FPS 取帧；按 `METADATA_KEY_VIDEO_ROTATION` 手动旋转
   （与手动圈筐使用的取帧逻辑一致），并把画面缩到最大宽度 640；
2. RGB → HSV，取橙色掩码（H 0–38、S ≥ 70、V ≥ 50，与服务端同一阈值）；
3. 5×5 闭运算 + 3×3 开运算清理噪声；
4. 对掩码做精确欧氏距离变换，在距离峰值处取圆形候选；用指数平均持久度图排除灯柱、
   海报等静止橙色物体；每帧最多 8 个候选；
5. 用常速度预测 + 最近邻关联候选，按尺寸比例门限和最小垂直位移过滤轨迹；
6. 有手动篮筐 ROI 时直接换算到分析帧坐标；否则在持久度图上找最像篮筐的橙色矩形，
   置信度不足时返回「请手动圈定篮筐」而不是给出错误结果；
7. 复用服务端的顶点、过筐平面、命中/未中与冷却去重规则。

检测器名称在结果中记为 `on-device`，便于区分云端 `classical` 结果。

## 4. 隐私

- 本地模式不发起任何与视频相关的网络请求；视频仅从 `cacheDir/videos` 读取。
- 视频副本随本地记录保留，删除记录时一起删除，不触碰相册原文件。
- 应用仍沿用登录门禁：首次使用需要登录；登录会话保存在本地，之后即使服务端不可达，
  本地识别本身也不需要联网（上传接口不在链路上）。

## 5. 已知限制

- **不生成进球片段**：端侧不做视频重封装；结果页点击事件会把原视频定位到对应时间。
  云端模式仍可在服务端生成片段。
- 检测精度与服务端经典基线相同，仍受橙色球/橙色篮筐、固定机位、单人单球等前提限制；
  自动找筐不确定时应手动圈定。
- 本地分析耗时与视频长度、设备性能相关，长视频会更久；界面只显示真实百分比，不预估剩余时间。
- 目前是「先录制/选择，再本地分析」，不是相机实时识别（实时识别仍为后续阶段）。

## 6. 验证

- `detection/ShotGeometryTest.kt`：移植服务端轨迹几何测试（命中、未中、运球、筐弹、去重、冷却、远距离）。
- `detection/BallTrackingTest.kt`：移动球关联、短缺失桥接、静止橙色物体过滤。
- `detection/PersistenceMapTest.kt`：首帧种子、静止像素保持、移动像素衰减。
- `detection/DetectionPipelineTest.kt`：合成橙色掩码序列走完持久度、候选、跟踪、找筐和命中判定。
- `detection/VisionTest.kt`：HSV 橙色掩码、欧氏距离变换、圆球候选、持久篮筐矩形、噪声清理。
- `data/OnDeviceAnalysisRepositoryTest.kt`：本地模式完成结果落库、分析器被调用、没有上传请求。
- `screenshot/OnDeviceScreenshotTest.kt`：确认页云端/本地两种选择、本地结果页横幅；改动前后截图见
  [docs/evidence/android/on-device/](evidence/android/on-device/README.md)。
