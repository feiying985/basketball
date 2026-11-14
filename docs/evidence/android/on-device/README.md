# 本地识别模式 · 改动前后截图

所有截图均为 Robolectric + Compose 渲染的真实应用界面，使用同一台设备配置：

- viewport：`w411dp-h1400dp-xhdpi`
- 主题：深色（`BasketballTheme(darkTheme = true)`）
- 同一段视频信息：`night-game.mp4`、95 秒、42 MB、相册来源
- 同一个篮筐手动 ROI：`0.43,0.27,0.14,0.09`

| 截图 | 版本 | 说明 |
| --- | --- | --- |
| `before-review.png` | `52d60c9`（origin/main） | 改动前的确认视频页，只有篮筐位置，没有识别方式 |
| `after-review-cloud.png` | 本 PR | 确认视频页，默认选择「云端识别」 |
| `after-review-local.png` | 本 PR | 确认视频页，选择「本地识别」，提示视频不会上传 |
| `after-result-local.png` | 本 PR | 本地识别的结果页，显示「视频没有上传」提示与本地片段说明 |

对比重点：同一页面、同一视频、同一篮筐 ROI、同一 viewport/主题下，「识别方式」由无到有；
两个选项都保留可访问的单选语义，默认云端不改变原有链路。

本地结果截图使用本地检测器返回的 3 次投篮（2 中 1 未中）和无片段说明，展示本地模式的结果状态；
其余页面（分析进度）在 `OnDeviceScreenshotTest` 之外沿用既有分析页截图逻辑。
