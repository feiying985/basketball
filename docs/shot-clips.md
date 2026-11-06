# 进球前后 5 秒片段

> - **适用版本**：`server` 0.5.0、安卓 App（`main` 上本功能合入后的版本）
> - **范围**：只做进球片段的服务端生成、App 内播放与保存到相册。
> - **已确认的默认值**：只剪 `made=true` 的球；前后固定各 5 秒；只保存到相册，
>   暂不做分享；不做网页端；不为旧任务自动补生成片段（可重新分析生成）。

## 1. 处理流程

1. `POST /api/v1/analyses` 创建任务，后台任务开始识别；
2. 识别期间任务 `status=processing`、`stage=detecting`；
3. 识别结果先写入 SQLite（防止后续失败丢失），`stage=clipping`；
4. 服务端按 `timestamp_seconds` 升序排列 `result.shots`，保证片段下标稳定；
5. 对每个命中球计算窗口并调用系统 ffmpeg 生成 MP4；
6. 片段全部处理完后写入带 `clip` 的结果，任务 `completed`、`stage=null`。

单个片段失败只记录在该事件的 `clip.error`，不影响识别结果和任务状态。ffmpeg 不可用时
任务仍正常完成，`result.clip_note` 说明未生成片段的原因。

## 2. 片段窗口与生成

```text
start = max(0, t - pre)
end   = min(video_duration, t + post)
if end - start < 0.5s: 标记 failed
```

- `pre`/`post` 默认 5 秒，可分别用 `BASKETBALL_CLIP_PRE_SECONDS` /
  `BASKETBALL_CLIP_POST_SECONDS` 调整（0-30）。
- 视频时长优先用 ffprobe 读取，失败时回退到 OpenCV 的帧数/fps。
- 超出视频边界自动截断；多个进球间隔很近时片段可能重叠，本期保留多份。
- 编码参数：H.264 + AAC、MP4、`+faststart`，兼容 Android 并支持拖动播放。
- 命令示例：

```bash
ffmpeg -hide_banner -loglevel error -nostdin \
  -ss 12.500 -i source.mp4 -t 10.000 \
  -map 0:v:0 -map 0:a:0? \
  -c:v libx264 -preset veryfast -crf 23 -pix_fmt yuv420p \
  -c:a aac -b:a 128k -movflags +faststart \
  -y 3.part.mp4
```

- 先写临时文件，成功后再原子替换；已存在且大于 0 字节的片段直接复用（幂等）。
- 全局信号量串行编码，避免小型服务器被并发任务打满；单个片段默认 120 秒超时。
- 单任务默认最多 50 个片段，防止长视频/多球占满 CPU。

## 3. 数据模型

```json
{
  "status": "ready",
  "start_seconds": 0.0,
  "end_seconds": 9.5,
  "duration_seconds": 9.5,
  "size_bytes": 2400000,
  "url": "/api/v1/analyses/<job_id>/clips/0",
  "error": null
}
```

- `ShotEvent.clip` 为空：该事件未生成片段（未命中，或旧任务）。
- `status=failed`：`error` 给出失败原因；`status=ready` 才能播放。
- `AnalysisJob.stage` 为 `detecting` / `clipping` / `null`。
- `AnalysisResult.clip_note`：整体未生成片段时的说明。
- 旧 `result_json` 没有这些字段，Pydantic 使用默认值读取，不需要迁移数据。

## 4. 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/api/v1/analyses/{id}/clips/{index}` | 返回片段 MP4，支持 HTTP Range/206 |
| `GET` | `/api/v1/analyses/{id}` | 结果中带 `stage` 与 `shots[].clip` |
| `GET` | `/api/v1/health` | 增加 `clips: bool`，表示 ffmpeg 是否可用 |

- 权限与 `GET /api/v1/analyses/{id}` 相同：非本人任务 404；越界 404；片段未就绪/失败 409；
  文件被清理 404。
- 响应头：`Content-Type: video/mp4`、`Content-Disposition: inline`、
  `Cache-Control: private, max-age=3600`，并支持 `Range`。
- `index` 是 `result.shots` 数组下标（服务端已按时间升序）。

## 5. 存储与清理

- 默认目录：`<BASKETBALL_UPLOAD_DIR>/clips/<job_id>/<index>.mp4`。
- 约 2-4 MB/10 秒（720p），50 个约 100-200 MB/场；与原视频使用同一保留策略。
- 部署文档的清理 cron 已同时清理 `uploads` 与 `clips`；片段过期后 App 显示
  「片段已失效」，识别结果仍可查看。
- 数据库只记录相对 URL，不记录服务器绝对路径。

## 6. App 行为

- 结果页顶部新增「进球片段 N」卡片，只展示 `made` 且 `ready` 的片段；
- 命中事件行保留「点按 seek 原视频」，并额外提供「片段」按钮；
- 片段播放页显示球序、原视频时间与片段范围，播放服务端 MP4；
- 「保存到相册」：Android 10+ 用 MediaStore 写入 `Movies/Basketball`；Android 8-9
  首次保存时申请 `WRITE_EXTERNAL_STORAGE`，拒绝则提示，不降级到私有目录；
- 下载显示进度、可取消；失败按 404/409/网络错误提示；
- 记录页完成摘要增加「N 个片段」；
- 片段播放需要服务端在线；本地历史快照仍可在离线时查看识别结果。
- 暂不做分享、网页端和旧任务批量补生成；旧记录没有片段时按无片段处理。

## 7. 兼容性

- 旧 App 连新服务端：`ignoreUnknownKeys` 忽略 `clip`/`stage`/`clip_note`，行为不变。
- 新 App 连旧服务端：没有 `clip` 字段，按无片段处理，不显示片段区。
- `BASKETBALL_REQUIRE_AUTH=true` 时片段接口同样需要 token；当前 App 未接入登录，
  播放器已预留 `headers` 参数，后续接 token 时补上即可。

## 8. 覆盖的测试

- 片段窗口：正常、开头不足 5 秒、结尾不足 5 秒、视频短于窗口、窗口过短、时间越界。
- ffmpeg：命令构建、成功、非零退出、超时、空文件、已有文件复用、缺失 ffmpeg、
  关闭片段、未知时长、包含未中与单任务上限。
- 真实 ffmpeg（CI 安装后运行）：合成视频生成片段并用 ffprobe 校验时长。
- API：结果带 `clip`、200 + `video/mp4`、Range 206、越界 404、未就绪 409、
  文件清理 404、跨账号 404、`health.clips`。
- 安卓：新旧 JSON 解析、片段状态映射、URL 拼接、下载进度/错误、格式化、
  结果页与播放页截图。
