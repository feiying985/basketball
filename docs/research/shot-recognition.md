# GitHub 现成进球识别方案调研

> 调研时间：2026-11-04。Star 数、最近提交时间来自 GitHub API 实时查询，会随时间变化。
> 结论只针对「篮球投篮/进球（make/miss）识别」这一具体问题。

## 1. 结论摘要

1. **有大量可参考的开源项目，但没有可以直接集成进 Android App 的成熟端到端方案。**
   GitHub 上的实现几乎都是 Python + OpenCV/YOLO 的桌面或服务端脚本，面向视频文件或摄像头，未提供移动端 SDK。
2. **主流技术路线高度一致**：检测篮球与篮筐（2 类）→ 跟踪球心 → 拟合轨迹 → 判断轨迹是否穿过篮筐区域；
   或者直接训练 5 类模型（球、球在篮筐中、球员、篮筐、投篮动作），用「球在篮筐中」类别判定进球。
   代表项目：[avishah3/AI-Basketball-Shot-Detection-Tracker](https://github.com/avishah3/AI-Basketball-Shot-Detection-Tracker)、
   [sPappalard/SwishAI](https://github.com/sPappalard/SwishAI)。
3. **工程成熟度普遍偏低**：多为个人项目/课程作业，硬编码路径、无测试、无模型版本管理；多数仓库**没有 License**，
   按默认版权保留处理，不能直接拷贝进商业产品。少数有明确许可证的又不能商用（AGPL / OpenPose 非商用）。
4. **许可风险是选型的关键约束**：Ultralytics YOLO 系列（v5/v8/v11）是 AGPL-3.0，闭源商用需要购买授权；
   如果产品要商业化，建议改用 YOLOX（Apache-2.0）、RT-DETR（Apache-2.0）等宽松许可模型，或自训练 + ONNX。
5. **建议的 MVP 路径**：App 只负责录像/选视频和上传，服务端用 Python 做离线分析（30 秒级短视频），
   先复用「2 类检测 + 跟踪 + 轨迹穿筐」思路，用 Roboflow 公开数据集冷启动，再逐步采集自有场地数据微调。
   端侧实时识别放到第二阶段。

## 2. 候选项目对比

| 项目 | Stars | 技术栈 | 思路 | License | 最近提交 | 可复用性 |
| --- | ---: | --- | --- | --- | --- | --- |
| [chonyy/AI-basketball-analysis](https://github.com/chonyy/AI-basketball-analysis) | 1304 | Python, Flask, TensorFlow | Faster R-CNN 检测球/篮筐，OpenPose 分析投篮姿势；Web 上传 + REST API | 非商用（依赖 OpenPose） | 2024-09 | 产品形态、API 设计参考；模型老、依赖重 |
| [avishah3/AI-Basketball-Shot-Detection-Tracker](https://github.com/avishah3/AI-Basketball-Shot-Detection-Tracker) | 285 | Python, YOLOv8, OpenCV | 自训 ball/hoop 两类模型 + 数据清洗 + 线性回归轨迹，轨迹与篮筐相交即命中；附 `best.pt` | 无 | 2025-02 | **算法最接近 MVP**，可参考判定逻辑与权重 |
| [chonyy/basketball-shot-detection](https://github.com/chonyy/basketball-shot-detection) | 146 | Python（无 Flask） | 上面项目的纯算法版，Faster R-CNN 检测 + 轨迹判定 | 无 | 2023-05 | 判定逻辑参考 |
| [sPappalard/SwishAI](https://github.com/sPappalard/SwishAI) | 108 | Python, YOLOv11s, FastAPI, React | 5 类检测（球、球在筐中、球员、篮筐、投篮动作）+ 物理/冷却规则，输出 FG% 与高亮视频 | AGPL-3.0 | 2026-08 | **完整 Web 产品架构**参考；5 类思路值得借鉴 |
| [brettfazio/CVBallTracking](https://github.com/brettfazio/CVBallTracking) | 52 | Python, YOLOv3, OpenCV | 篮球跟踪（CSRT/GOTURN），非进球判定 | 无 | 2024-03 | 跟踪部分参考 |
| [nitinhemaraj/Basketball-shot-detection](https://github.com/nitinhemaraj/Basketball-shot-detection) | 22 | Python, YOLOv8 | 与 avishah3 同源的检测 + 轨迹方案 | 无 | 2023-08 | 交叉验证用 |
| [Peachypie98/AI-Basketball-Field-Goal-Tracker](https://github.com/Peachypie98/AI-Basketball-Field-Goal-Tracker) | 21 | Python, YOLO-NAS-L, ReID, Faiss | 检测球员/球/筐/投篮 + 行人重识别，统计每名球员命中率 | 无 | 2025-05 | 多球员统计场景参考 |
| [nvan21/Basketball-Shot-Detection](https://github.com/nvan21/Basketball-Shot-Detection) | 4 | Python, YOLOv8, 分割 | 分割 + 抛物线拟合预测是否命中 | 无 | 2024-08 | 曲线拟合思路 |
| [kylephan5/basketball-shot-tracker](https://github.com/kylephan5/basketball-shot-tracker) | 0 | Python, YOLOv7, CSRT | 单目 make/miss，作者自测 25 球 88% 正确 | 无 | 2024-12 | **失败经验**：正/侧双视角、球出画、擦筐误判 |
| [griftt/ball-yolo](https://github.com/griftt/ball-yolo) | 3 | Python, YOLO11/26 | 训练 ball/rim 模型、导出 CoreML、自动剪辑进球片段 | 无 | 2026-07 | 移动端部署（CoreML→iOS）参考，非 Android |
| [josephattalla/Basketball-Shot-Detection](https://github.com/josephattalla/Basketball-Shot-Detection) | 6 | Python, YOLOv8 notebook | 统计 make/miss 与命中率 | MIT | 2024-08 | 有明确 MIT 许可的少量参考 |
| [muratali016/Yolo_basketball_shot_predictor](https://github.com/muratali016/Yolo_basketball_shot_predictor) | 8 | Python, YOLO | 投篮预测实验 | Apache-2.0 | 2022-09 | 许可清晰，年代较久 |

> License 为「无」表示 GitHub API 未识别到许可证文件；这不等于可以自由使用。

### 关于移动端

用 `basketball android`、`yolo android basketball` 等关键词检索，**没有找到成熟的 Android 端进球识别项目**；
现有移动端相关实现多是「导出 CoreML 给 iOS」（如 ball-yolo）或纯 UI 应用。
Android 端要做实时识别，需要自己集成 TFLite / NCNN / ONNX Runtime Mobile，属于第二阶段工作。

## 3. 技术方案拆解

### 3.1 检测（Detection）

- **2 类方案**：`basketball` + `rim/hoop`。数据标注成本低，YOLOv8n/s 即可；后续靠轨迹和几何判定是否进球。
  代表：avishah3、kylephan5、ball-yolo。
- **5 类方案**：`ball`、`ball in basket`、`player`、`basket`、`player shooting`。把「球在筐中」当成一个可直接检测的状态，
  减少纯几何误判，但对标注质量和数据量要求更高。代表：SwishAI（约 1 万张图训练）。
- **姿态方案**：OpenPose/MediaPipe 提取手肘、膝盖角度，用于投篮动作分析，不直接判定进球。代表：chonyy。

### 3.2 跟踪与轨迹（Tracking / Trajectory）

- 单目标跟踪：CSRT、GOTURN（kylephan5、CVBallTracking）。实现简单，但球出画/被遮挡即丢失。
- 多目标跟踪：ByteTrack、OC-SORT、BoT-SORT（MIT 许可），配合 YOLO 检测更稳，是当前主流选择。
- 轨迹处理：清洗异常点 → 线性回归/抛物线拟合 → 求与篮筐 ROI 的交点；也有项目直接预测抛物线落点。

### 3.3 进球判定（Make/Miss）

常见做法，按可靠性从低到高：

1. 球心轨迹与篮筐矩形相交（最简单，容易把「从筐后穿过」判成进球）。
2. 球心先高于篮筐、后低于篮筐，且水平位置落在筐口宽度内（avishah3、kylephan5 的思路）。
3. 检测「球在篮筐中」类别并做多帧确认（SwishAI）。
4. 多视角/深度相机，消除单目 2D 歧义（成本最高）。

工程细节：进球后加冷却时间（cooldown）避免同一球重复计数；用多帧投票过滤抖动；对篮筐区域做 ROI 限制，减少误检。

### 3.4 端侧 vs 服务端

| | 端侧实时 | 服务端离线 |
| --- | --- | --- |
| 延迟 | 低 | 上传 + 处理，几十秒到几分钟 |
| 精度 | 受模型大小/功耗限制 | 可用更大模型、多帧后处理 |
| 隐私 | 视频不出设备 | 需上传视频 |
| 开发成本 | Android + TFLite/NCNN 集成，较高 | Python 生态成熟，迭代快 |

结论：MVP 先做服务端离线分析，验证识别效果和产品流程；端侧实时作为第二阶段。

## 4. 可复用组件与数据

- 检测框架：[Ultralytics YOLO](https://github.com/ultralytics/ultralytics)（AGPL-3.0）、
  [YOLOX](https://github.com/Megvii-BaseDetection/YOLOX)（Apache-2.0）、
  [RT-DETR](https://github.com/lyuwenyu/RT-DETR)（Apache-2.0）、
  [MMDetection](https://github.com/open-mmlab/mmdetection)（Apache-2.0）。
- 跟踪：[ByteTrack](https://github.com/ifzhang/ByteTrack)（MIT）、
  [OC-SORT](https://github.com/noahcao/OC_SORT)（MIT）、
  [supervision](https://github.com/roboflow/supervision)（MIT，含标注/跟踪工具）。
- 姿态：[MediaPipe](https://github.com/google-ai-edge/mediapipe)（Apache-2.0）。
- 数据集：
  - Roboflow Universe「Basketball Detection」约 1 万张图，CC BY 4.0，SwishAI 使用：
    <https://universe.roboflow.com/basketball-6vyfz/basketball-detection-srfkd>
  - kylephan5 使用的两个约 300 张图的小数据集：
    <https://universe.roboflow.com/034-ganesh-kumar-m-v-cs-r2lwe/basketball-lhqoe/dataset/1>
  - nvan21 使用的数据集：<https://universe.roboflow.com/biomechanics/basketball-annotation-training>
  - 公开数据集普遍缺少「我们自己的场地/机位/光照」分布，必须补充自采数据。

## 5. 建议的 MVP 方案

### 阶段一：服务端离线分析（当前脚手架的直接下一步）

1. App 录制/选择 10–30 秒视频，上传到 `/api/v1/analyses`。
2. 服务端抽帧（如 10–15 FPS），YOLO 检测 `ball` + `rim`。
3. ByteTrack 跟踪球，清洗轨迹，判断是否从筐口上方进入、下方穿出。
4. 输出 `ShotEvent` 列表（时间戳、命中/未命中），App 展示并允许人工纠正。
5. 用公开数据集 + 自采 100–200 段视频建评估集，统计逐球准确率、漏检率、误报率。

### 阶段二：精度与体验

- 增加 `ball in basket` 类别，和几何判定做投票。
- 篮筐自动/手动标定（用户框选一次，后续复用）。
- 处理多人、多球、遮挡、镜头晃动。
- 评估是否引入第二个机位解决正面/侧面歧义。

### 阶段三：端侧实时

- 模型蒸馏/量化到 TFLite 或 NCNN，Android CameraX 实时推理。
- 或者保留「端侧检测 + 云端复核」的混合架构。

## 6. 风险与限制

- **单目 2D 歧义**：从篮筐后方/侧面穿过的球可能被误判；擦筐、弹框、球在筐上旋转都难处理。
- **数据分布**：公开数据集与真实球馆差异大；不同机位、光照、球色都会显著影响准确率。
- **自报指标不可尽信**：项目 README 的 95%/97% 多为自测小样本，需自建评估集复现。
- **许可**：无 License 仓库不能直接复制；AGPL 模型用于闭源服务端也有传染风险。商业化前需要法务确认或替换模型。
- **性能**：服务端 CPU 推理 30 秒视频可能需数十秒到数分钟，需要实测；实时场景需要 GPU 或端侧加速。

## 7. 参考链接

- avishah3/AI-Basketball-Shot-Detection-Tracker：<https://github.com/avishah3/AI-Basketball-Shot-Detection-Tracker>
- chonyy/AI-basketball-analysis：<https://github.com/chonyy/AI-basketball-analysis>
- sPappalard/SwishAI：<https://github.com/sPappalard/SwishAI>
- kylephan5/basketball-shot-tracker：<https://github.com/kylephan5/basketball-shot-tracker>
- brettfazio/CVBallTracking：<https://github.com/brettfazio/CVBallTracking>
- griftt/ball-yolo：<https://github.com/griftt/ball-yolo>
- Roboflow 教程：How to Build a Local AI Basketball Shot Evaluator：<https://blog.roboflow.com/how-to-build-an-ai-basketball-shot-evaluator/>
- Ultralytics 许可说明：<https://github.com/ultralytics/ultralytics/blob/main/LICENSE>
