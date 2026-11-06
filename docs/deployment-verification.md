# 后端部署与进球识别验证指引

> - **适用代码**：`server` 0.3.0（经典 CV 检测器 + SQLite 任务持久化 + 账号体系）；安卓端为 `main` 上的 App MVP。
> - **更新日期**：2026-11-06
> - **目标环境**：阿里云 ECS（Ubuntu 24.04 LTS，2 vCPU / 2 GB 起）
> - **文档定位**：把「部署后端 → 验证接口 → 验证进球识别 → 用 App 走通全流程」串成一次可执行的验收；同时说明当前完成度和还缺的功能。
> - **0.3.0 变更**：任务状态与结果写入 SQLite（重启不丢），新增注册/登录/Bearer token 与任务归属。
>   默认 `BASKETBALL_REQUIRE_AUTH=false`，现有 App 不需要改；设计与接口见
>   [server-persistence-accounts.md](server-persistence-accounts.md)。

## 0. 先回答两个问题

### 0.1 现在可以验证进球识别了吗？

**可以。`main` 已包含经典 CV 检测器（PR #2 已合入）。**

- 0.3.0 默认 `BASKETBALL_DETECTOR=classical`，任务完成时会返回 `result.shots`；
- 部署后先确认 `GET /api/v1/health` 返回 `version=0.3.0`、`detector=classical`。如果
  `detector` 是 `stub` 或版本更旧，说明代码不是最新 `main`，先 `git pull` 再重启；
- 仍想复现 PR #2 合入前的行为时，可检出对应历史提交；[附录 A](#附录-a不合并-main-也能验证)
  保留为历史说明，正常部署不再需要。

**验证的边界**：你能验证「视频上传 → 后台分析 → 返回投篮事件 → App 展示」这条链路，以及
经典 CV 基线在你场地上的大致效果；**不能**把结果当作最终准确率。当前没有真实场地评估集，
仓库测试用的是合成视频，真实球场上的漏检/误检仍然存在（见 [第 7 节](#7-用真实视频验证识别效果)）。

### 0.2 现在还差什么功能？

一句话：**MVP 的「能跑通」已经具备，缺的是「识别准、结果可信、能长期稳定运行」。**

| 能力 | 状态 | 说明 |
| --- | --- | --- |
| 服务端 API（健康检查 / 上传 / 轮询） | ✅ 已完成 | `main` 可用 |
| 经典 CV 进球识别 | ✅ 已完成 | PR #2 已合入 `main` |
| 安卓 App 主流程（录制/相册、校验、上传、轮询、结果、本地历史） | ✅ 已完成 | PR #4、PR #5 已合入 `main` |
| 安卓端交互流程设计稿 | 🟡 待合入 | PR #3，README 冲突已解决，设计文档，不影响本次部署验证 |
| 真实场地准确率评估与调参 | ❌ 未做 | 需要真实视频样本；当前只有合成视频测试 |
| 结果人工纠正（命中/未中） | ❌ 未做 | 设计里放在 P1 |
| 服务端任务持久化 | ✅ 已完成 | 0.3.0 使用 SQLite，重启后已完成任务仍可查询 |
| 任务队列 / 断点续跑 | ❌ 未做 | 分析仍是进程内任务；重启时未完成任务标记为 `failed` |
| 账号体系（注册/登录/token/任务归属） | 🟡 服务端已完成 | 0.3.0 已提供接口；App 登录与云端历史未接入 |
| 限流 / HTTPS / 上传文件清理 | ❌ 未做 | 公网长期运行前需要补；上传文件目前不自动删除 |
| 云端历史 / 多设备同步 | ❌ 未做 | 服务端已有任务列表接口，App 仍只有本地 Room 历史 |
| 端侧实时识别 | ❌ 未做 | 规划在第二阶段（TFLite / NCNN） |
| 分享、多人/多球统计 | ❌ 未做 | 当前不在 MVP 范围 |

按优先级展开：

- **P0（让这次验证真正有意义）**
  1. 确认部署的是 `main` 0.3.0（健康检查 `version=0.3.0`、`detector=classical`）；
  2. 用真实球场视频跑一批样本，记录「实际命中 vs 识别命中」，作为第一版评估集。
- **P1（让 MVP 可日常使用）**
  1. 结果人工纠正（本地覆盖 `made`，不写回服务端）；
  2. 可持久化的任务队列（重启后续跑）、上传文件过期清理；
  3. App 接入注册/登录与 token 存储，打开 `BASKETBALL_REQUIRE_AUTH`；
  4. 登录限流、HTTPS 域名、App 后台完成通知、断点续传。
- **P2（产品化）**
  1. 学习型检测器（YOLO/RT-DETR + ByteTrack，注意许可证）；
  2. 端侧实时识别；
  3. 云端历史同步、分享、多人统计。

---

## 1. 部署架构与约束

```text
Android App
  │  HTTP/HTTPS  multipart 视频 + JSON 轮询
  ▼
Nginx（公网 80/443）
  │ 反向代理
  ▼
uvicorn（127.0.0.1:8000，单进程）
  │
  ▼
FastAPI + OpenCV 经典 CV 检测器
  │
  ├── 任务/账号：SQLite /var/lib/basketball/data/basketball.db（重启保留）
  └── 上传视频：/var/lib/basketball/uploads（不自动删除）
```

部署前必须知道的三条约束：

1. **仍建议单进程**。任务和账号已持久化到 SQLite，但后台分析仍用 FastAPI `BackgroundTasks`
   在 API 进程内执行，没有持久队列和重试。`uvicorn --workers` 先保持 `1`；需要多实例时先换
   任务队列（Celery/RQ + Redis 或 PostgreSQL），不要只调 worker 数。
2. **默认没有强制鉴权**。0.3.0 已提供账号与 Bearer token，但 `BASKETBALL_REQUIRE_AUTH`
   默认为 `false`，以便现有 App 不改代码即可使用。验证期间请用安全组限制来源 IP；接好 App
   登录后把该变量设为 `true`，并在 Nginx/应用层补登录限流。
3. **上传文件不会自动删除**。每次分析都会在 `BASKETBALL_UPLOAD_DIR` 留一份原视频；
   长时间运行前请按 [5.7 节](#57-清理上传文件可选) 配置定期清理。

---

## 2. 阿里云 ECS 准备

### 2.1 实例与镜像

| 项目 | 建议 |
| --- | --- |
| 地域 | 离你最近的区域（如华东1-杭州），降低上传延迟 |
| 镜像 | **Ubuntu 24.04 LTS 64 位**（自带 Python 3.12，满足 `>=3.11`） |
| 规格 | 2 vCPU / 2 GB 内存起；CPU 型或 burstable 均可，不需要 GPU |
| 系统盘 | 40 GB ESSD 起（视频和 Python 依赖会占空间） |
| 公网带宽 | 按固定带宽或按流量计费；200 MB 视频在 5 Mbps 下约需 5–6 分钟上传 |

> Ubuntu 22.04 默认 Python 3.10，不满足服务端的 `requires-python >=3.11`。如果只能用
> 22.04，请先安装 Python 3.11（见 [2.2 节](#22-如果必须是-ubuntu-2204)），或直接换 24.04。

### 2.2 如果必须是 Ubuntu 22.04

```bash
sudo apt update
sudo apt install -y software-properties-common
sudo add-apt-repository -y ppa:deadsnakes/ppa
sudo apt update
sudo apt install -y python3.11 python3.11-venv
python3.11 --version   # 应显示 3.11.x
```

后续命令把 `python3` 换成 `python3.11`。

### 2.3 安全组

在 ECS 控制台 → 安全组 → 入方向，添加：

| 端口 | 来源 | 用途 |
| --- | --- | --- |
| 22/tcp | 你的公网 IP | SSH 管理 |
| 80/tcp | 0.0.0.0/0 或你的手机网络出口 IP | HTTP（Nginx） |
| 443/tcp | 0.0.0.0/0 | HTTPS（可选，配好域名后） |

- 如果使用 Nginx，**不要**对公网开放 8000；uvicorn 只监听 `127.0.0.1`。
- 如果暂时不装 Nginx、直接暴露 uvicorn，可临时开放 8000，验证完立即关闭。
- 中国大陆地域的 ECS 用域名提供 80/443 服务通常需要 ICP 备案；只想先用 IP 验证时，
  也可以把 Nginx 监听改成 8080 并开放 8080，避开 80 端口。

---

## 3. 安装系统依赖

SSH 登录服务器后执行：

```bash
sudo apt update
sudo apt upgrade -y
sudo apt install -y python3 python3-venv python3-pip nginx git curl jq ffmpeg
python3 --version   # 需要 >= 3.11
```

说明：

- `python3-venv`：创建虚拟环境；
- `nginx`：反向代理；
- `jq`：本文的 JSON 查看/解析命令会用到；
- `ffmpeg`：可选，但强烈建议装。遇到手机 HEVC/H.265 视频解码失败时，可以用它转成 H.264 MP4。

OpenCV 使用的是 `opencv-python-headless`，不需要 `libgl1` 等图形库。

---

## 4. 获取代码并安装依赖

### 4.1 创建运行用户和目录

```bash
sudo useradd --system --create-home --shell /usr/sbin/nologin basketball
sudo mkdir -p /opt/basketball /var/lib/basketball/uploads /var/lib/basketball/data
sudo chown -R basketball:basketball /opt/basketball /var/lib/basketball
```

### 4.2 克隆代码

```bash
sudo -u basketball git clone https://github.com/feiying985/basketball.git /opt/basketball/app
cd /opt/basketball/app
git log --oneline -3
ls server/app/services/classical_shot_detection.py   # 经典 CV 检测器
ls server/app/services/db.py                         # 0.3.0 SQLite 持久化
```

### 4.3 创建虚拟环境并安装依赖

```bash
cd /opt/basketball/app/server
sudo -u basketball python3 -m venv .venv
sudo -u basketball .venv/bin/pip install --upgrade pip
sudo -u basketball .venv/bin/pip install -r requirements.txt
.venv/bin/python -c "import cv2, numpy; print('cv2', cv2.__version__, 'numpy', numpy.__version__)"
```

预期能打印出 `cv2 4.10.x` 和 `numpy 1.26.x`。

---

## 5. 配置并启动服务

### 5.1 环境变量

```bash
sudo tee /etc/basketball.env >/dev/null <<'EOF'
BASKETBALL_UPLOAD_DIR=/var/lib/basketball/uploads
BASKETBALL_DB_PATH=/var/lib/basketball/data/basketball.db
BASKETBALL_MAX_UPLOAD_BYTES=209715200
BASKETBALL_DETECTOR=classical
BASKETBALL_SAMPLE_FPS=10
BASKETBALL_SHOT_COOLDOWN_SECONDS=1.5
# App 接入登录前保持 false；接入后改为 true 强制鉴权
BASKETBALL_REQUIRE_AUTH=false
BASKETBALL_TOKEN_TTL_DAYS=30
# 白色/非橙色篮筐时取消下一行注释，并改成你的篮筐区域
# BASKETBALL_HOOP_ROI=0.42,0.25,0.16,0.05
EOF

sudo chown root:basketball /etc/basketball.env
sudo chmod 640 /etc/basketball.env
```

变量含义见 [附录 B](#附录-b环境变量速查)。注意：配置在进程启动时读取，改完需要重启服务。

### 5.2 systemd 服务

```bash
sudo tee /etc/systemd/system/basketball.service >/dev/null <<'EOF'
[Unit]
Description=Basketball shot recognition API
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=basketball
Group=basketball
WorkingDirectory=/opt/basketball/app/server
EnvironmentFile=/etc/basketball.env
ExecStart=/opt/basketball/app/server/.venv/bin/uvicorn app.main:app --host 127.0.0.1 --port 8000 --workers 1
Restart=on-failure
RestartSec=3
TimeoutStopSec=30

[Install]
WantedBy=multi-user.target
EOF

sudo systemctl daemon-reload
sudo systemctl enable --now basketball
sudo systemctl status basketball --no-pager
```

在本机先验证一次：

```bash
curl -s http://127.0.0.1:8000/api/v1/health
```

应该返回：

```json
{"status":"ok","service":"basketball-server","version":"0.3.0","detector":"classical"}
```

- `version` 低于 `0.3.0` 或 `detector` 是 `stub`：说明部署的不是最新 `main`，先 `git pull` 再重启，见第 10 节。
- 如果启动失败，看日志：`sudo journalctl -u basketball -n 100 --no-pager`。

### 5.3 Nginx 反向代理

```bash
sudo rm -f /etc/nginx/sites-enabled/default

sudo tee /etc/nginx/sites-available/basketball >/dev/null <<'EOF'
server {
    listen 80 default_server;
    listen [::]:80 default_server;
    server_name _;

    # 允许 200 MB 视频 + multipart 开销
    client_max_body_size 201m;
    client_body_timeout 600s;

    # 上传大文件/慢网络时不要过早断开
    proxy_read_timeout 600s;
    proxy_send_timeout 600s;

    location / {
        proxy_pass http://127.0.0.1:8000;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
EOF

sudo ln -sf /etc/nginx/sites-available/basketball /etc/nginx/sites-enabled/basketball
sudo nginx -t
sudo systemctl reload nginx
```

如果 80 端口不可用（例如未备案或运营商限制），把 `listen 80 default_server;` 改成
`listen 8080 default_server;`，删掉 IPv6 那行或同步改 8080，并在安全组开放 8080。
手机端地址相应写成 `http://<公网IP>:8080`。

### 5.4 HTTPS（可选，但有域名时建议）

需要一个已解析到 ECS 公网 IP 的域名（A 记录）。然后：

```bash
sudo apt install -y certbot python3-certbot-nginx
sudo certbot --nginx -d basketball.example.com
```

按提示完成即可。之后 App 里填 `https://basketball.example.com`。
指向纯 IP 时不建议折腾证书，HTTP 也能用（App 已允许明文 HTTP）。

### 5.5 最快验证：跳过 Nginx 直接暴露 uvicorn（可选）

只想先确认服务端能跑，可以临时这样启动：

```bash
cd /opt/basketball/app/server
sudo -u basketball env BASKETBALL_UPLOAD_DIR=/var/lib/basketball/uploads \
  .venv/bin/uvicorn app.main:app --host 0.0.0.0 --port 8000 --workers 1
```

同时在安全组临时开放 8000，手机/电脑访问 `http://<公网IP>:8000`。
验证完请停掉进程并关闭 8000；长期运行仍建议用 systemd + Nginx。

### 5.6 防火墙（可选）

如果启用了 `ufw`：

```bash
sudo ufw allow 22/tcp
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw enable
```

阿里云安全组和系统防火墙是两层，别只配一层。

### 5.7 清理上传文件（可选，但建议）

上传目录不会自动回收。按 7 天保留：

```bash
sudo tee /etc/cron.daily/basketball-cleanup >/dev/null <<'EOF'
#!/bin/sh
find /var/lib/basketball/uploads -type f -mtime +7 -delete
EOF
sudo chmod +x /etc/cron.daily/basketball-cleanup
```

验证阶段可以先不配，但不要让它无限增长。

---

## 6. 服务端验证

下面的 `<SERVER>` 替换成你的地址，例如 `http://1.2.3.4` 或 `https://basketball.example.com`。

### 6.1 健康检查

```bash
curl -s http://<SERVER>/api/v1/health | jq .
```

预期：

```json
{
  "status": "ok",
  "service": "basketball-server",
  "version": "0.3.0",
  "detector": "classical"
}
```

同时打开 `http://<SERVER>/docs`，应能看到 FastAPI 的 Swagger 页面。

### 6.2 上传一段视频

先在本地准备一段 10–30 秒的测试视频（建议 MP4/H.264）。上传：

```bash
curl -s -F "file=@test.mp4;type=video/mp4" http://<SERVER>/api/v1/analyses | tee job.json | jq .
```

预期 HTTP `202`，返回类似：

```json
{
  "id": "9bd35456975247deb752faed14110fe2",
  "status": "queued",
  "filename": "test.mp4",
  "created_at": "2026-11-06T00:15:24.776176Z",
  "result": null,
  "error": null
}
```

保存 `id`：

```bash
JOB_ID=$(jq -r .id job.json)
echo "$JOB_ID"
```

### 6.3 轮询任务

```bash
for i in $(seq 1 120); do
  curl -s "http://<SERVER>/api/v1/analyses/$JOB_ID" -o job-status.json
  STATUS=$(jq -r .status job-status.json)
  echo "$(date +%H:%M:%S) $STATUS"
  case "$STATUS" in
    completed|failed) jq . job-status.json; break ;;
  esac
  sleep 2
done
```

任务状态依次是 `queued → processing → completed`（失败则是 `failed`）。
完成时重点看 `result`：

```json
{
  "id": "9bd35456975247deb752faed14110fe2",
  "status": "completed",
  "filename": "test.mp4",
  "created_at": "2026-11-06T00:15:24.776176Z",
  "result": {
    "detector": "classical",
    "shots": [
      { "timestamp_seconds": 1.5, "frame_index": 45, "made": true }
    ],
    "note": null
  },
  "error": null
}
```

- `shots[].made = true`：命中；`false`：未中；
- `timestamp_seconds` 是视频内时间，`frame_index` 是原始视频帧号；
- `result.note` 非空表示「没有识别到」的原因（例如没检测到篮筐、没跟踪到球），
  这**不是**接口失败。

### 6.4 边界检查

```bash
# 不支持的格式：预期 415
echo hello > note.txt
curl -s -o /dev/null -w '%{http_code}\n' -F "file=@note.txt" http://<SERVER>/api/v1/analyses

# 不存在的任务：预期 404
curl -s -o /dev/null -w '%{http_code}\n' http://<SERVER>/api/v1/analyses/not-exist

# 超过 200 MB：预期 413（Nginx 或应用层返回都算）
truncate -s 210M big.mp4
curl -s -o /dev/null -w '%{http_code}\n' -F "file=@big.mp4;type=video/mp4" http://<SERVER>/api/v1/analyses
```

### 6.5 重启行为（任务持久化）

```bash
sudo systemctl restart basketball
sleep 2
curl -s "http://<SERVER>/api/v1/analyses/$JOB_ID" | jq '{status, result, error}'
```

- 已完成的任务：仍返回原状态和 `result`（0.3.0 写入 SQLite，不再是 404）。
- 重启时仍在 `queued`/`processing` 的任务：返回 `status=failed`，`error` 为
  `服务重启导致任务中断，请重新上传视频`。
- 这是「状态持久化」，不是「任务续跑」；需要续跑见第 11 节的任务队列建议。

---

## 7. 用真实视频验证识别效果

### 7.1 拍摄建议

为了先跑出可解释的结果，第一轮尽量满足：

- **固定机位**：手机用三脚架/靠墙固定，画面不要明显晃动；
- **同时拍到篮筐和投篮者**：篮筐在画面上方（基线上限是画面高度 80% 以内）；
- **橙色篮球 + 橙色/红色篮筐**：这是当前经典 CV 基线的颜色假设；
- **时长 10–30 秒**：包含 2–5 次出手，既能看出效果，处理也不会太久；
- **1080p、MP4/H.264**：避免 4K（会先缩到 640 宽再分析）和 HEVC（部分环境解码失败）；
- **光线充足、背景尽量干净**：减少橙色衣物/地板干扰。

白色、黑色或深色篮筐不是不能验证，但要设置 `BASKETBALL_HOOP_ROI`（见 7.3）。

### 7.2 怎么判断「识别得对不对」

1. 自己先看一遍视频，记下实际命中/未中次数与大概时间点；
2. 上传后对比 `result.shots` 的 `made` 和 `timestamp_seconds`；
3. 建议至少跑 5–10 段不同场景的视频，记录成表：

| 视频 | 实际命中 | 识别命中 | 识别未中 | 漏检 | 误检 | 备注 |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| clip-01.mp4 | 3 | 2 | 1 | 1 | 0 | 远距离偏出未报 |
| clip-02.mp4 | 2 | 2 | 3 | 0 | 3 | 传球经过筐边被误判 |

这张表就是后续调参和换模型的第一版评估集。**没有它，无法判断「识别准不准」。**

### 7.3 调参

改 `/etc/basketball.env`，然后 `sudo systemctl restart basketball`。

| 变量 | 默认 | 什么时候改 |
| --- | --- | --- |
| `BASKETBALL_SAMPLE_FPS` | `10` | 球速快/画面大导致漏帧时调到 15–20；CPU 吃紧时降到 5–8 |
| `BASKETBALL_SHOT_COOLDOWN_SECONDS` | `1.5` | 同一球被重复计数时调大；连续补篮被合并时调小 |
| `BASKETBALL_HOOP_ROI` | 空 | 篮筐不是橙色/红色，或自动检测到错误区域时手动指定 |
| `BASKETBALL_DETECTOR` | `classical` | 只想测接口、不想跑 CV 时设为 `stub` |

**`BASKETBALL_HOOP_ROI` 怎么算**：在视频截图上量出篮筐外接矩形（像素），再分别除以画面宽高，
得到 `x,y,width,height`（都是 0–1，且 `x+width ≤ 1`、`y+height ≤ 1`）。

例：1920×1080 的画面，篮筐矩形为左 800、上 250、宽 320、高 60，则：

```text
x = 800 / 1920 = 0.417
y = 250 / 1080 = 0.231
w = 320 / 1920 = 0.167
h = 60 / 1080  = 0.056

BASKETBALL_HOOP_ROI=0.417,0.231,0.167,0.056
```

ROI 区域应覆盖篮筐开口附近，不要框得过大，否则会把更多轨迹算进筐口。

App 端确认视频时也可以直接点「手动圈定」，在画面里拖动框选篮筐；框选结果只对
当次分析生效，不需要改服务器配置。夜景/偏黄灯光下还可以按真实视频验证报告调整
颜色与半径参数：

```bash
BASKETBALL_HUE_MAX=40
BASKETBALL_SATURATION_MIN=45
BASKETBALL_VALUE_MIN=40
BASKETBALL_BALL_MAX_RADIUS_RATIO=0.05
```

这些参数默认保持基线值，按现场视频逐步调整即可。

### 7.4 识别结果的预期

当前是**经典 CV 基线**，不是训练出来的模型。合理预期：

- 满足颜色和机位假设时，能报出一部分命中和近筐未中；
- 远距离偏出、三不沾且轨迹不经过篮筐附近的球，不会产生事件；
- 传球/运球经过筐边、擦筐、篮筐后方穿过的球可能误判；
- 多人、多球、橙色球衣会干扰；
- 真实命中率需要自己标注样本后统计，合成视频测试不能代表真实效果。

详细算法与限制见 PR #2 中的 `docs/shot-detection.md`（合入后位于仓库 `docs/shot-detection.md`）。

---

## 8. 安卓 App 联调

### 8.1 构建/安装

需要 JDK 17 和 Android SDK 35。命令行构建：

```bash
cd android
./gradlew :app:assembleDebug
# Windows: gradlew.bat :app:assembleDebug
```

APK 在 `android/app/build/outputs/apk/debug/app-debug.apk`，安装：

```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

也可以用 Android Studio 打开 `android/` 直接运行。最低支持 Android 8.0（minSdk 26）。

### 8.2 把 App 指向你的服务器

App 默认地址是 `http://10.0.2.2:8000`，只适用于模拟器访问宿主机。**真机必须改地址：**

1. 打开 App，进入首页；
2. **长按首页底部的「服务端」状态行**（普通用户看不到这个入口）；
3. 在开发者设置里填 `http://<公网IP>` 或 `https://你的域名`，保存；
4. 点一下该行重新检查，应显示在线，并显示版本和检测器，例如
   `服务端 0.3.0 · 检测器 classical`。

如果服务端用 8080：填 `http://<公网IP>:8080`。

### 8.3 走完整流程

1. 首页「开始分析」→「录制视频」或「从相册选择」；
2. 确认页检查格式/大小/时长，点「开始分析」；
3. 分析页显示上传进度，随后是排队/识别中；
4. 完成后进入结果页：命中/未中时间点、命中率、视频播放标记；
5. 结果自动保存到「记录」；即使服务端重启，记录仍可打开。

如果「服务端」状态显示在线，但上传失败：

- 看 Nginx 错误日志：`sudo tail -n 50 /var/log/nginx/error.log`；
- 重点检查 `client_max_body_size`、`proxy_read_timeout`；
- 确认视频不超过 200 MB，格式在允许列表内。

---

## 9. 端到端验收清单

部署完成后逐项打勾：

- [ ] `GET /api/v1/health` 返回 `version=0.3.0`、`detector=classical`
- [ ] `GET /docs` 可打开，能看到 `/api/v1/auth/*` 与 `GET /api/v1/analyses`
- [ ] 注册、登录、`GET /auth/me`、退出登录均返回预期状态码
- [ ] 上传一段真实视频返回 `202`，任务最终 `completed`
- [ ] 清晰命中的球能在 `result.shots` 中出现 `made=true`
- [ ] 没有识别到时 `result.note` 给出了可理解的原因
- [ ] `.txt` 上传返回 `415`
- [ ] 不存在的任务返回 `404`
- [ ] 超过 200 MB 返回 `413`
- [ ] 登录用户的任务列表只包含自己的任务；另一个账号查询该任务返回 `404`
- [ ] 服务端重启后，已完成任务仍能查询，App 本地历史仍能查看
- [ ] App 首页「服务端」状态显示在线，版本/检测器正确
- [ ] App 从录制/相册到结果页全流程走通
- [ ] 公网只开放必要端口；若用 Nginx，8000 未对公网开放
- [ ] `BASKETBALL_DB_PATH` 指向持久化目录；上传目录已配置定期清理或已记录待办

---

## 10. 常见问题

| 现象 | 可能原因 | 处理 |
| --- | --- | --- |
| `detector` 是 `stub` 或 `version` 低于 `0.3.0` | 部署的是旧代码 | `git pull` 最新 `main` 后重启；确认 `server/app/services/db.py` 存在 |
| 502 Bad Gateway | uvicorn 没起来/端口不对 | `systemctl status basketball`、`journalctl -u basketball -n 100`、`curl 127.0.0.1:8000/api/v1/health` |
| App 显示离线 | 地址错、端口没开、安全组没放行 | 手机浏览器先访问 `http://<SERVER>/api/v1/health`；检查安全组和 Nginx |
| 健康检查正常，但上传失败 | Nginx 体积/超时限制 | 调大 `client_max_body_size`、`proxy_read_timeout`；看 Nginx error log |
| `413` | 视频超过 200 MB 或 Nginx 限制 | 压缩视频，或调整 `BASKETBALL_MAX_UPLOAD_BYTES` 与 Nginx 限制 |
| `415` | 扩展名不在允许列表 | 转成 `.mp4`/`.mov`/`.m4v`/`.webm`/`.avi`/`.mkv` |
| `note`: 未检测到篮筐 | 篮筐不是橙色/红色，或镜头晃动导致没有持续区域 | 用橙色/红色篮筐；设置 `BASKETBALL_HOOP_ROI`；固定机位 |
| `note`: 未检测到可跟踪的篮球 | 球不是橙色、太小、光线差 | 换橙色球；靠近拍摄；提高 `BASKETBALL_SAMPLE_FPS` |
| `note`: 检测到轨迹但未识别到投篮 | 投篮轨迹不经过筐口附近，或机位角度问题 | 调整机位让篮筐和球轨迹可见；检查 ROI；远距离偏出属于已知限制 |
| 任务 `failed`：无法打开视频 | 编码不受支持/文件损坏 | 用 `ffmpeg` 转 H.264 MP4：`ffmpeg -i in.mov -c:v libx264 -pix_fmt yuv420p out.mp4` |
| 服务重启后未完成任务变成 `failed` | 分析是进程内任务，重启不会续跑 | 属预期；重新上传。已完成任务不受影响 |
| 登录返回 `401` | 密码错误，或 token 过期/已退出 | 重新登录；检查 `BASKETBALL_TOKEN_TTL_DAYS` |
| 打开 `BASKETBALL_REQUIRE_AUTH=true` 后 App 上传 401 | App 还没接登录 | 先保持 `false`，或换用带 `Authorization` 的客户端 |
| CPU 打满、分析很慢 | 多人同时上传、抽帧率高、视频大 | 单任务串行使用；降低 `BASKETBALL_SAMPLE_FPS`；控制视频时长/分辨率 |
| 上传文件占满磁盘 | 上传目录未清理 | 配置 5.7 的定时清理，或手动删 `uploads` 下旧文件 |

---

## 11. 下一步建议

1. 用第 7 节的方法拍 5–10 段真实视频，建第一版评估表和样本目录；
2. 根据评估结果决定：继续调经典 CV，还是直接上学习型检测器（YOLO/RT-DETR + ByteTrack，
   注意 AGPL 等许可证）；
3. 把 `BackgroundTasks` 换成可持久化队列（Redis/Celery 或 RQ），支持重启后续跑和多实例；
4. App 接入注册/登录与 token 安全存储，打开 `BASKETBALL_REQUIRE_AUTH`，并补登录限流、HTTPS；
5. App 端补 P1：人工纠正、后台通知、断点续传、普通用户设置页、云端历史同步。

---

## 附录 A：不合并 main 也能验证

> **历史说明**：PR #2 已合入 `main`，正常部署不需要本节，保留用于复现旧版本。

如需复现 PR #2 合入前/刚合入时的服务端代码，可检出对应历史提交：

```bash
sudo -u basketball git clone https://github.com/feiying985/basketball.git /opt/basketball/app
cd /opt/basketball/app
git log --oneline --all --grep='classical CV shot detector'
git checkout <包含该提交的 commit>
```

然后按第 4.3 节继续安装依赖、第 5 节配置启动。旧版本没有任务持久化和账号接口，
第 6.5 节、第 9 节中与 SQLite/登录相关的检查项不适用。

## 附录 B：环境变量速查

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `BASKETBALL_DETECTOR` | `classical` | `classical`=OpenCV 基线；`stub`=不识别，只跑通链路 |
| `BASKETBALL_SAMPLE_FPS` | `10` | 抽帧率，越高越准也越慢 |
| `BASKETBALL_SHOT_COOLDOWN_SECONDS` | `1.5` | 两次投篮判定的最小间隔，避免同一球重复计数 |
| `BASKETBALL_HOOP_ROI` | 空 | 手动篮筐区域，格式 `x,y,width,height`（0–1 归一化） |
| `BASKETBALL_MAX_UPLOAD_BYTES` | `209715200`（200 MB） | 单文件上传上限 |
| `BASKETBALL_UPLOAD_DIR` | 系统临时目录 | 上传视频保存目录；生产建议 `/var/lib/basketball/uploads` |
| `BASKETBALL_DB_PATH` | `<临时目录>/basketball-data/basketball.db` | SQLite 数据库；生产建议 `/var/lib/basketball/data/basketball.db` |
| `BASKETBALL_REQUIRE_AUTH` | `false` | `true` 时上传/查询/列表都要求登录；App 接入登录前保持 `false` |
| `BASKETBALL_TOKEN_TTL_DAYS` | `30` | 登录 token 有效天数；`0` 表示不过期 |

变量在进程启动时读取，修改后执行 `sudo systemctl restart basketball`。

## 附录 C：文档验证说明

本文档中的服务端代码路径、39 个服务端测试、`/api/v1/health`、上传/轮询、413/415/404 行为，
以及注册/登录、任务归属、SQLite 重启持久化，已在本地用同一份代码和依赖实际跑通
（Python 3.11、OpenCV 4.10、NumPy 1.26）；持久化与账号细节见
[server-persistence-accounts.md](server-persistence-accounts.md)。
systemd/Nginx/阿里云安全组部分按 Ubuntu 24.04 编写，首次部署请按第 9 节逐项验收；
如果实际环境有差异，以 `journalctl`、`nginx -t` 和健康检查结果为准。
