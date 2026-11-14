# 后端阿里云部署与进球识别验证指引

> - **适用代码**：`server` 0.6.0（经典 CV 检测器 + SQLite 任务持久化 + 手机号账号/短信验证码 +
>   进球片段）；安卓端为支持登录门禁的 App。
> - **更新日期**：2026-11-14
> - **目标环境**：阿里云 ECS（Ubuntu 24.04 LTS，2 vCPU / 2 GB 起）
> - **文档定位**：前半部分（第 1–5 节）是阿里云 ECS 部署手册，后半部分（第 6–9 节）验证接口、
>   识别效果和 App 全流程；运维相关的备份、升级、监控、安全加固在 5.8–5.11。只做部署时按
>   [0.3 部署最短路径](#03-部署最短路径) 走。
> - **0.3.0 变更**：任务状态与结果写入 SQLite（重启不丢），新增注册/登录/Bearer token 与任务归属。
>   默认 `BASKETBALL_REQUIRE_AUTH=false`，现有 App 不需要改；设计与接口见
>   [server-persistence-accounts.md](server-persistence-accounts.md)。
> - **0.4.x 变更**：颜色/半径/静止噪声阈值全部可用环境变量调整，支持请求级
>   `hoop_roi` 与 App 手动圈筐；0.4.1 把静止噪声默认阈值改为 0.95（球与篮筐
>   重叠时不再被误删），并新增自动找筐置信度检查：多个橙色候选接近时提示手动圈筐，
>   不再用错误的篮筐返回事件。算法细节见 [shot-detection.md](shot-detection.md)。
> - **0.5.0 变更**：识别完成后为每个命中球生成前后各 5 秒的 MP4，新增
>   `GET /api/v1/analyses/{id}/clips/{index}`（支持 Range）、`jobs.stage`、
>   `result.shots[].clip` 与 `health.clips`；App 结果页可播放片段并保存到相册。
>   部署机必须安装 ffmpeg，细节见 [shot-clips.md](shot-clips.md)。
> - **0.6.0 变更**：手机号验证码注册、账号（手机号/用户名）+ 密码或手机号 + 验证码登录、
>   修改/重置密码、App 登录门禁与 token 加密存储；新增短信验证码表、限流与阿里云/dev
>   发送实现。生产必须配置阿里云短信并打开 `BASKETBALL_REQUIRE_AUTH=true`。

## 0. 先回答两个问题

### 0.1 现在可以验证进球识别了吗？

**可以。`main` 已包含经典 CV 检测器（PR #2 已合入）。**

- 0.6.0 默认 `BASKETBALL_DETECTOR=classical`，任务完成时会返回 `result.shots`；
- 部署后先确认 `GET /api/v1/health` 返回 `version=0.6.0`、`detector=classical`。如果
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
| 服务端任务持久化 | ✅ 已完成 | 0.3.0 起使用 SQLite，重启后已完成任务仍可查询 |
| 进球片段（服务端生成 + App 播放/保存） | ✅ 已完成 | 0.5.0；默认只剪命中、前后各 5 秒，部署需装 ffmpeg |
| 任务队列 / 断点续跑 | ❌ 未做 | 分析仍是进程内任务；重启时未完成任务标记为 `failed` |
| 账号体系（手机号注册/双登录/改密/任务归属） | ✅ 已完成 | 0.6.0；App 登录门禁与 token 加密存储已接入 |
| 短信验证码与登录限流 | ✅ 已完成 | 阿里云/dev 双实现；手机号/IP/全站限流 |
| HTTPS / 上传文件清理 | ❌ 未做 | 生产必须配 HTTPS；上传/片段文件目前不自动删除 |
| 云端历史 / 多设备同步 | ❌ 未做 | 服务端已有任务列表接口，App 仍只有本地 Room 历史 |
| 端侧实时识别 | ❌ 未做 | 规划在第二阶段（TFLite / NCNN） |
| 分享、多人/多球统计 | ❌ 未做 | 当前不在 MVP 范围；片段暂不支持分享 |

按优先级展开：

- **P0（让这次验证真正有意义）**
  1. 确认部署的是 `main` 0.6.0（健康检查 `version=0.6.0`、`detector=classical`）；
  2. 用真实球场视频跑一批样本，记录「实际命中 vs 识别命中」，作为第一版评估集。
- **P1（让 MVP 可日常使用）**
  1. 结果人工纠正（本地覆盖 `made`，不写回服务端）；
  2. 可持久化的任务队列（重启后续跑）、上传文件与片段过期清理；
  3. 旧账号绑定手机号（旧账号目前仍用用户名 + 密码登录）；
  4. HTTPS 域名、App 后台完成通知、断点续传。
- **P2（产品化）**
  1. 学习型检测器（YOLO/RT-DETR + ByteTrack，注意许可证）；
  2. 端侧实时识别；
  3. 云端历史同步、分享、多人统计。

### 0.3 部署最短路径

只想先把后端跑起来、暂不评估识别效果时，按下面 8 步走；每一步的完整命令都在对应小节。

1. **买 ECS**：Ubuntu 24.04 LTS，2 vCPU / 2 GB / 40 GB 系统盘起，安全组只放行 22、80、443
   （[2.1](#21-实例与镜像)、[2.3](#23-安全组)）。
2. **装系统依赖**：`python3`、`python3-venv`、`nginx`、`git`、`curl`、`jq`、`ffmpeg`、`sqlite3`
   （[第 3 节](#3-安装系统依赖)）。
3. **放代码、装 Python 依赖**：建运行用户和持久化目录，克隆仓库，创建虚拟环境；国内 ECS
   先把 pip 指向阿里云镜像，装依赖会快很多（[第 4 节](#4-获取代码并安装依赖)，
   镜像配置见 [4.4](#44-加速依赖安装)）。
4. **选部署模式**（不要跳过）：
   - 验收模式：`BASKETBALL_ENV=development` + `BASKETBALL_SMS_PROVIDER=dev` +
     `BASKETBALL_REQUIRE_AUTH=false`，验证码只写日志，最快跑通；
   - 生产模式：`BASKETBALL_ENV=production` + 阿里云短信 + `BASKETBALL_REQUIRE_AUTH=true`，
     需要提前准备已审核的签名/模板和 RAM AccessKey（[5.1](#51-环境变量)）。
5. **写 `/etc/basketball.env`，启动 systemd 服务**（[5.1](#51-环境变量)、[5.2](#52-systemd-服务)）。
6. **配 Nginx 反向代理**；用域名时加 HTTPS（[5.3](#53-nginx-反向代理)、[5.4](#54-https有域名时强烈建议release-包必须)）。
7. **健康检查**：`curl http://<SERVER>/api/v1/health`，确认 `version=0.6.0`、`detector=classical`、
   `clips=true`（[6.1](#61-健康检查)）。
8. **配好备份、日志监控和安全加固**，再按[第 9 节](#9-端到端验收清单)验收
   （[5.8](#58-数据备份与恢复)、[5.10](#510-日志与监控)、[5.11](#511-安全加固)）。

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
2. **默认没有强制鉴权**。0.3.0 起已提供账号与 Bearer token，但 `BASKETBALL_REQUIRE_AUTH`
   默认为 `false`，以便现有 App 不改代码即可使用。验证期间请用安全组限制来源 IP；接好 App
   登录后把该变量设为 `true`，并在 Nginx/应用层补登录限流。
3. **上传文件和片段不会自动删除**。每次分析都会在 `BASKETBALL_UPLOAD_DIR` 留一份原视频，
   片段写到 `<upload_dir>/clips`；长时间运行前请按 [5.7 节](#57-清理上传文件与片段可选但建议) 配置定期清理。

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
sudo apt install -y python3 python3-venv python3-pip nginx git curl jq ffmpeg sqlite3 openssl
python3 --version   # 需要 >= 3.11
```

说明：

- `python3-venv`：创建虚拟环境；
- `nginx`：反向代理；
- `jq`：本文的 JSON 查看/解析命令会用到；
- `sqlite3`：5.8 备份/恢复 SQLite 数据库时使用（运行时依赖已在 Python 包内，不需要它）；
- `openssl`：生成短信验证码 pepper 等随机密钥；
- `ffmpeg`：**0.5.0 起片段功能的必需依赖**；服务端启动时探测，缺失时
  `health.clips=false`、识别仍可完成但不会生成片段。HEVC/H.265 源视频也由它统一转 H.264。
  安装后可用 `ffmpeg -version` 确认。

OpenCV 使用的是 `opencv-python-headless`，不需要 `libgl1` 等图形库。

---

## 4. 获取代码并安装依赖

### 4.1 创建运行用户和目录

```bash
sudo useradd --system --create-home --shell /usr/sbin/nologin basketball
sudo mkdir -p /opt/basketball /var/lib/basketball/uploads /var/lib/basketball/clips /var/lib/basketball/data
sudo chown -R basketball:basketball /opt/basketball /var/lib/basketball
```

### 4.2 克隆代码

```bash
sudo -u basketball git clone https://github.com/feiying985/basketball.git /opt/basketball/app
cd /opt/basketball/app
git log --oneline -3
ls server/app/services/classical_shot_detection.py   # 经典 CV 检测器
ls server/app/services/db.py                         # 0.3.0+ SQLite 持久化
```

GitHub 在中国大陆访问可能很慢；只想先跑起来可以加 `--depth 1` 只取最新代码，仍慢时见
[4.4.4](#444-其他能省时间的地方)。

### 4.3 创建虚拟环境并安装依赖

```bash
cd /opt/basketball/app/server
sudo -u basketball python3 -m venv .venv
sudo -u basketball .venv/bin/pip install --upgrade pip -i https://mirrors.aliyun.com/pypi/simple/
sudo -u basketball .venv/bin/pip install -r requirements.txt \
  -i https://mirrors.aliyun.com/pypi/simple/ --timeout 60 --retries 5
.venv/bin/python -c "import cv2, numpy; print('cv2', cv2.__version__, 'numpy', numpy.__version__)"
```

`-i` 只对这一次命令生效；想让升级、回滚重装都自动走镜像，按 [4.4](#44-加速依赖安装) 写一份 `pip.conf`。

预期能打印出 `cv2 4.10.x` 和 `numpy 1.26.x`。

### 4.4 加速依赖安装

首次安装主要下载 `opencv-python-headless`（约 50 MB）、`numpy`（约 18 MB）和阿里云 SDK。
ECS 在中国大陆跨境访问 `pypi.org` 经常只有几十 KB/s，装十几分钟并不奇怪。按场景选一种：

| 场景 | 方案 | 效果 |
| --- | --- | --- |
| 正常部署（推荐） | 4.4.1 阿里云 PyPI 镜像 | 通常几十秒装完 |
| 反复重建虚拟环境、频繁改依赖 | 4.4.2 uv（仍指定镜像） | 解析和并行下载更快 |
| ECS 出网很慢、无外网或一次装多台 | 4.4.3 预下载 wheel 离线安装 | 只受上传/内网带宽限制 |

三种方案装完都用 4.3 最后的 `import cv2, numpy` 命令确认。

#### 4.4.1 阿里云 PyPI 镜像（首选）

镜像地址是 `https://mirrors.aliyun.com/pypi/simple/`。写进虚拟环境的 `pip.conf` 后，之后所有
`.venv/bin/pip` 命令都会自动使用镜像：

```bash
cd /opt/basketball/app/server
sudo -u basketball tee .venv/pip.conf >/dev/null <<'EOF'
[global]
index-url = https://mirrors.aliyun.com/pypi/simple/
timeout = 60
retries = 5
EOF
sudo -u basketball .venv/bin/pip install -r requirements.txt
```

- 配置文件放在虚拟环境根目录即可，pip 会读取它；重建 `.venv` 后要重新写一次；
- `requirements.txt` 里的版本都固定了（`==`），镜像同步 PyPI 的包，不需要改版本，也不需要
  `--trusted-host`（镜像走 HTTPS）；
- 只想临时加速时，不改配置，直接在 pip 命令后加 `-i https://mirrors.aliyun.com/pypi/simple/`；
- 镜像也慢时先确认网络：`time curl -s -o /dev/null https://mirrors.aliyun.com/pypi/simple/`；
  也可以临时换腾讯云（`https://mirrors.cloud.tencent.com/pypi/simple/`）或清华源
  （`https://pypi.tuna.tsinghua.edu.cn/simple/`）对比。

#### 4.4.2 用 uv 安装

`uv` 是 Rust 实现的安装器，依赖解析和并行下载比 pip 快，适合反复重建环境。它只替换安装动作，
虚拟环境和启动方式不变：

```bash
cd /opt/basketball/app/server
sudo -u basketball .venv/bin/pip install uv -i https://mirrors.aliyun.com/pypi/simple/
sudo -u basketball .venv/bin/uv pip install --python .venv/bin/python \
  --default-index https://mirrors.aliyun.com/pypi/simple/ -r requirements.txt
```

- uv **不读** `pip.conf`，镜像必须用 `--default-index` 指定（较旧的 uv 用 `-i`）；
- 启动仍是 `.venv/bin/uvicorn ...`，systemd 配置不用改；
- 重建环境仍按 4.3 用 `python3 -m venv`，装好 uv 后把安装命令换成 `uv pip install` 即可；
  删掉旧目录后也可以用 `uv venv .venv --python 3.11 --seed` 建一个自带 pip 的环境。

#### 4.4.3 预下载 wheel 离线安装（多台部署或出网受限）

在一台网络好的 Linux x86_64 机器上把依赖下载成 wheel，再整体传到 ECS 离线安装：

```bash
# 1) 在仓库根目录下载 wheel（镜像可选）
python3 -m pip download -r server/requirements.txt -d wheelhouse \
  -i https://mirrors.aliyun.com/pypi/simple/

# 2) 上传到 ECS；多台机器建议先传 OSS，再从内网下载
scp -r wheelhouse root@<ECS_IP>:/opt/basketball/app/server/

# 3) ECS 上离线安装
cd /opt/basketball/app/server
sudo -u basketball .venv/bin/pip install --no-index --find-links wheelhouse -r requirements.txt
```

- `pip download` 按**当前平台**取 wheel；`numpy`、`opencv` 的 wheel 不能跨操作系统，要在
  Linux x86_64 上下载（macOS/Windows 只适合下纯 Python 包）；
- 多台 ECS 分发 `wheelhouse` 比每台都跨境下载省时；以后新增依赖时再重新生成一次。

#### 4.4.4 其他能省时间的地方

- **系统依赖**：阿里云 ECS 的 Ubuntu 镜像通常已指向内网源 `mirrors.cloud.aliyuncs.com`；
  如果 `apt update`/`apt install` 也慢，先检查 `/etc/apt/sources.list.d/ubuntu.sources`
  是否被改成了境外官方源，改回内网镜像。
- **git clone 慢**：GitHub 在中国大陆访问不稳定。可以 `git clone --depth 1` 只取最新代码
  （见 4.2，升级时 `git pull` 仍可用），或在本地 clone 后 `scp -r` 上传，注意排除 `.venv`
  和视频等大文件。
- **失败重跑不要删 `.venv`**：pip 会把已下载的 wheel 缓存在用户目录，直接重跑会复用；
  加 `--no-cache-dir` 反而会重新下载。
- **不要用 `--no-deps` 或改版本号提速**：会破坏可复现性，之后升级和排障都更麻烦。

---

## 5. 配置并启动服务

### 5.1 环境变量

#### 5.1.1 先选部署模式

| 模式 | 用途 | 关键变量 |
| --- | --- | --- |
| 验收模式 | 先把服务跑通，不接真实短信 | `BASKETBALL_ENV=development`、`BASKETBALL_SMS_PROVIDER=dev`、`BASKETBALL_REQUIRE_AUTH=false`；验证码只写服务端日志，不产生短信费用，App 调试可直接用固定验证码 `666666` |
| 生产模式 | 给真实用户使用 | `BASKETBALL_ENV=production`、`BASKETBALL_SMS_PROVIDER=aliyun`、阿里云短信 4 项 + `BASKETBALL_SMS_CODE_PEPPER`；建议 `BASKETBALL_REQUIRE_AUTH=true` |

`BASKETBALL_ENV=production` 时，`server/app/config.py` 会在启动阶段强制检查短信配置：
仍用 `dev` provider、或缺少 AccessKey/签名/模板/pepper，都会直接报错退出。**不要在还没准备好
短信时把 `BASKETBALL_ENV` 改成 `production`**；先用验收模式跑通，再按 5.1.2 切生产。
验收模式下验证码不会发短信，可用 `sudo journalctl -u basketball | grep dev-sms` 查看，
也可以先点一次「获取验证码」，然后直接输入固定验证码 `666666`（仍受有效期、尝试次数和限流约束）。
固定验证码只在开发/验收模式生效，生产模式和真实短信实现下自动禁用。

#### 5.1.2 阿里云短信准备（生产模式）

1. 在阿里云控制台开通[短信服务](https://dysms.console.aliyun.com/)，申请**签名**和**模板**并等待审核通过；
   模板变量通常为 `${code}`，以控制台实际审核结果为准。
2. 创建一个只用于发短信的 RAM 用户，授予发送短信所需的最小权限（至少 `dysms:SendSms`；
   图省事可用 `AliyunDysmsFullAccess`，但权限更大），为该用户创建 AccessKey。
3. 记下签名名称、模板 CODE 和地域（默认 `cn-hangzhou`）。
4. 生成验证码 pepper（**不要**把 AccessKey、pepper 提交到仓库或发到聊天里）：

```bash
openssl rand -hex 32
```

5. 把 AccessKey、签名、模板 CODE、pepper 填进 5.1.3 的 `/etc/basketball.env`，文件保持
   `root:basketball 640`。

#### 5.1.3 写入配置文件

下面的示例是**生产模式**；只做验收时，把 `BASKETBALL_ENV` 和 `BASKETBALL_SMS_PROVIDER`
改成 `development` / `dev`，并删掉或留空短信相关项与 pepper（`REQUIRE_AUTH` 也可先设 `false`）。

```bash
sudo tee /etc/basketball.env >/dev/null <<'EOF'
BASKETBALL_UPLOAD_DIR=/var/lib/basketball/uploads
BASKETBALL_DB_PATH=/var/lib/basketball/data/basketball.db
BASKETBALL_MAX_UPLOAD_BYTES=209715200
BASKETBALL_DETECTOR=classical
BASKETBALL_SAMPLE_FPS=10
BASKETBALL_SHOT_COOLDOWN_SECONDS=1.5
# 0.6.0 App 已接入登录；生产建议 true，服务端强制分析接口登录
BASKETBALL_REQUIRE_AUTH=true
BASKETBALL_TOKEN_TTL_DAYS=30
BASKETBALL_ENV=production
# 短信：生产用阿里云；签名/模板需先在控制台审核
BASKETBALL_SMS_PROVIDER=aliyun
BASKETBALL_SMS_ACCESS_KEY_ID=替换为RAM_ACCESS_KEY_ID
BASKETBALL_SMS_ACCESS_KEY_SECRET=替换为RAM_ACCESS_KEY_SECRET
BASKETBALL_SMS_SIGN_NAME=替换为已审核签名
BASKETBALL_SMS_TEMPLATE_CODE=替换为已审核模板CODE
BASKETBALL_SMS_CODE_PEPPER=替换为32字节以上随机字符串
BASKETBALL_SMS_REGION=cn-hangzhou
# 进球片段：默认只剪命中球、前后各 5 秒
BASKETBALL_CLIP_ENABLED=true
BASKETBALL_CLIP_PRE_SECONDS=5
BASKETBALL_CLIP_POST_SECONDS=5
BASKETBALL_CLIP_DIR=/var/lib/basketball/clips
# ffmpeg 不在 PATH 时取消注释并改成实际路径
# BASKETBALL_FFMPEG_PATH=/usr/bin/ffmpeg
# 白色/非橙色篮筐时取消下一行注释，并改成你的篮筐区域
# BASKETBALL_HOOP_ROI=0.42,0.25,0.16,0.05
EOF

sudo chown root:basketball /etc/basketball.env
sudo chmod 640 /etc/basketball.env
```

变量含义见 [附录 B](#附录-b环境变量速查)。注意：配置在进程启动时读取，改完需要
`sudo systemctl restart basketball`；启动失败先看 `journalctl -u basketball -n 100 --no-pager`。

打开 `BASKETBALL_REQUIRE_AUTH=true` 前确认 App 是 0.6.0 及以上：旧 App 没有登录态，
上传会返回 401，需要先升级 App，或临时保持 `false`。

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
ExecStart=/opt/basketball/app/server/.venv/bin/uvicorn app.main:app --host 127.0.0.1 --port 8000 --workers 1 --proxy-headers --forwarded-allow-ips=127.0.0.1
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
{"status":"ok","service":"basketball-server","version":"0.6.0","detector":"classical","clips":true}
```

- `version` 低于 `0.6.0` 或 `detector` 是 `stub`：说明部署的不是最新 `main`，先 `git pull` 再重启，见第 10 节。
- `clips=false`：ffmpeg 不可用或已关闭片段功能；执行 `ffmpeg -version` 检查，
  或确认 `BASKETBALL_FFMPEG_PATH` 指向可执行文件。识别功能不受影响。
- 首次启动会在 `BASKETBALL_DB_PATH` 里创建 SQLite 表；目录必须存在且对 `basketball`
  用户可写（[4.1 节](#41-创建运行用户和目录)已创建 `/var/lib/basketball/data`）。
- 如果启动失败，看日志：`sudo journalctl -u basketball -n 100 --no-pager`。

### 5.3 Nginx 反向代理

```bash
sudo rm -f /etc/nginx/sites-enabled/default

sudo tee /etc/nginx/sites-available/basketball >/dev/null <<'EOF'
# sites-available 文件会被 include 进 http 上下文
limit_req_zone $binary_remote_addr zone=basketball_auth:10m rate=30r/m;

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

    # 登录/发码/重置接口限流；应用层还有手机号/IP/验证码次数限制
    location /api/v1/auth/ {
        limit_req zone=basketball_auth burst=10 nodelay;
        proxy_pass http://127.0.0.1:8000;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

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

### 5.4 HTTPS（有域名时强烈建议；release 包必须）

**先明确一点**：debug 包允许 `http://`，release 包关闭了明文 HTTP，只能连 HTTPS
（见 README 的 release 构建说明）。所以：

- 只用 debug 包验收：可以先走 `http://<公网IP>`，跳过本节；
- 要发 release 包或长期使用：必须有域名 + HTTPS（中国大陆地域的域名通常还需 ICP 备案），
  不建议只给 IP 配证书。

需要一个已解析到 ECS 公网 IP 的域名（A 记录）。然后：

```bash
sudo apt install -y certbot python3-certbot-nginx
sudo certbot --nginx -d basketball.example.com
```

按提示完成即可。certbot 会自动改写 Nginx 配置并安装续期定时器，可用
`systemctl status certbot.timer` 确认。之后 App 的开发者设置里填
`https://basketball.example.com`。

如果只是临时验证、用 debug 包，`http://<公网IP>` 也能用；但不要把这个地址发给使用
release 包的用户。

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

### 5.7 清理上传文件与片段（可选，但建议）

上传目录和片段目录都不会自动回收。按 7 天保留：

```bash
sudo tee /etc/cron.daily/basketball-cleanup >/dev/null <<'EOF'
#!/bin/sh
find /var/lib/basketball/uploads -type f -mtime +7 -delete
find /var/lib/basketball/clips -type f -mtime +7 -delete
find /var/lib/basketball/clips -mindepth 1 -type d -empty -delete
EOF
sudo chmod +x /etc/cron.daily/basketball-cleanup
```

如果片段目录用了别的路径，同步修改 `find` 的路径。片段文件被清理后，App 会显示
「片段已失效」，识别结果仍可正常查看。

### 5.8 数据备份与恢复

需要备份的只有三样：SQLite 数据库（账号、任务、验证码）、上传/片段目录（可选，体积大且
可以重新生成）、`/etc/basketball.env`（含密钥，单独加密保管）。

数据库使用 WAL 模式，**不要直接 `cp` 正在写入的 `.db` 文件**；用 `sqlite3 .backup` 做一致性快照。
先安装 sqlite3（[第 3 节](#3-安装系统依赖)已包含）。

```bash
sudo mkdir -p /var/backups/basketball
sudo chown basketball:basketball /var/backups/basketball

sudo tee /etc/cron.daily/basketball-backup >/dev/null <<'EOF'
#!/bin/sh
set -e
umask 077
STAMP=$(date +%F)
sqlite3 /var/lib/basketball/data/basketball.db ".backup '/var/backups/basketball/basketball-$STAMP.db'"
# 上传和片段体积大，按需备份；不需要就保持注释
# tar -czf "/var/backups/basketball/media-$STAMP.tar.gz" -C /var/lib/basketball uploads clips
find /var/backups/basketball -type f -mtime +14 -delete
EOF
sudo chmod 700 /etc/cron.daily/basketball-backup
```

- 数据库通常只有几 MB，保留 14 天；按磁盘容量调整保留天数；
- 生产建议把备份同步到阿里云 OSS（例如 `ossutil cp -r /var/backups/basketball oss://<bucket>/basketball/`）
  或另一台机器，避免 ECS 整机故障时备份一起丢；
- 备份含用户手机号等个人信息，权限保持 600/700，不要放在公开目录。

恢复时先停服务，再覆盖数据库：

```bash
sudo systemctl stop basketball
sudo rm -f /var/lib/basketball/data/basketball.db-wal /var/lib/basketball/data/basketball.db-shm
sudo cp /var/backups/basketball/basketball-<日期>.db /var/lib/basketball/data/basketball.db
sudo chown basketball:basketball /var/lib/basketball/data/basketball.db
# 如也备份了媒体：sudo tar -xzf /var/backups/basketball/media-<日期>.tar.gz -C /var/lib/basketball
sudo systemctl start basketball
curl -s http://127.0.0.1:8000/api/v1/health
```

恢复旧库会丢失备份点之后的新账号和任务，恢复前先确认要回到的时间点。

### 5.9 升级与回滚

升级前先做一次 [5.8 节](#58-数据备份与恢复)的数据库备份。服务是单进程，升级期间有短暂停机；
停机时仍在 `queued`/`processing` 的任务会变成 `failed`，已完成任务和账号不受影响。

```bash
# 先抓取新代码，尽量减少停机时间；服务器工作区应保持干净（git status 无改动）
sudo -u basketball git -C /opt/basketball/app fetch --all --tags
sudo -u basketball git -C /opt/basketball/app log --oneline -3 origin/main

sudo systemctl stop basketball
sudo -u basketball git -C /opt/basketball/app checkout <要部署的 commit 或 tag>
sudo -u basketball /opt/basketball/app/server/.venv/bin/pip install \
  -r /opt/basketball/app/server/requirements.txt
sudo systemctl start basketball
sleep 2
curl -s http://127.0.0.1:8000/api/v1/health
```

- 数据库迁移在服务启动时自动执行（`server/app/services/db.py` 用 `CREATE TABLE IF NOT EXISTS`
  和补列），不需要手动跑迁移脚本；
- 升级后确认 `version`、`detector`、`clips` 三项，再按[第 9 节](#9-端到端验收清单)抽查；
- **回滚**：`git checkout <上一个 commit>`、重装依赖、重启即可；如果新版本改过表结构且旧代码
  不兼容，先停服务、用 5.8 的备份恢复数据库，再启动旧代码；
- 按 [4.4](#44-加速依赖安装) 配过镜像后，升级和回滚时的重装依赖会走镜像与本地缓存，通常很快；
- 不要用 `git pull` 直接覆盖服务器上的本地改动；如果服务器访问 GitHub 不稳定，可以在本地打
  tar 包上传后解压覆盖，仍要保持 `.env` 和数据库不在仓库里。

### 5.10 日志与监控

```bash
sudo journalctl -u basketball -n 100 --no-pager      # 最近日志
sudo journalctl -u basketball -f                     # 实时日志
sudo journalctl -u basketball --since '1 hour ago'   # 按时间
sudo journalctl --disk-usage                         # journal 占用
sudo tail -n 50 /var/log/nginx/error.log
sudo tail -n 50 /var/log/nginx/access.log
```

- journald 和 Nginx 日志默认会轮转；磁盘紧张时可以给 journald 设上限：

```bash
sudo mkdir -p /etc/systemd/journald.conf.d
sudo tee /etc/systemd/journald.conf.d/basketball.conf >/dev/null <<'EOF'
[Journal]
SystemMaxUse=500M
EOF
sudo systemctl restart systemd-journald
```

- 建议加一个最小健康检查（每 5 分钟失败时写系统日志）；如果使用阿里云云监控，也可以直接配
  「站点监控」探测 `/api/v1/health`：

```bash
sudo tee /etc/cron.d/basketball-health >/dev/null <<'EOF'
*/5 * * * * root curl -fsS --max-time 10 http://127.0.0.1:8000/api/v1/health >/dev/null || logger -t basketball-health "health check failed"
EOF
```

- 关注磁盘：`df -h`、`du -sh /var/lib/basketball/*`；上传和片段不自动删除，5.7 的清理必须配置，
  否则磁盘会持续增长；
- 关注 CPU/内存：2 vCPU 上单个分析任务会吃满一两个核，多人同时上传会排队变慢；需要扩容时先换
  任务队列，**不要只加 `--workers`**（见[第 1 节](#1-部署架构与约束)约束）；
- 短信费用：在阿里云短信控制台设置日限额和费用告警，应用层限流不是费用保险。

### 5.11 安全加固

- 安全组只放行 22（限你的 IP）、80、443；**不要对公网开放 8000**，uvicorn 只监听 `127.0.0.1`；
- SSH 用密钥登录：`/etc/ssh/sshd_config` 设 `PasswordAuthentication no`、
  `PermitRootLogin prohibit-password`，改完先 `sudo sshd -t` 校验、保持当前会话不要断开，
  再 `sudo systemctl reload ssh`；云控制台 VNC 作为兜底；
- 启用了 `ufw` 时按 [5.6 节](#56-防火墙可选)放行，阿里云安全组和 ufw 两层都要配；
- `/etc/basketball.env` 保持 `root:basketball 640`，不要把 AccessKey/pepper 写进仓库、聊天或工单；
- 阿里云 RAM 用户只授短信发送所需权限，定期轮换 AccessKey，不要使用主账号 AccessKey；
- 生产必须 `BASKETBALL_ENV=production`、`BASKETBALL_REQUIRE_AUTH=true`，要发 release 包时配
  HTTPS（[5.4 节](#54-https有域名时强烈建议release-包必须)）；
- 定期 `sudo apt update && sudo apt upgrade`，或开启 `unattended-upgrades`；升级内核后重启服务；
- Nginx 的 `limit_req` 和应用层限流都要保留；发现验证码轰炸时先在安全组/Nginx 封 IP，
  再考虑接人机验证。

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
  "version": "0.6.0",
  "detector": "classical",
  "clips": true
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
  "stage": null,
  "filename": "test.mp4",
  "created_at": "2026-11-06T00:15:24.776176Z",
  "result": {
    "detector": "classical",
    "shots": [
      {
        "timestamp_seconds": 1.5,
        "frame_index": 45,
        "made": true,
        "clip": {
          "status": "ready",
          "start_seconds": 0.0,
          "end_seconds": 6.5,
          "duration_seconds": 6.5,
          "size_bytes": 1800000,
          "url": "/api/v1/analyses/9bd35456975247deb752faed14110fe2/clips/0",
          "error": null
        }
      }
    ],
    "note": null,
    "clip_note": null,
    "hoop_confidence": 1.0
  },
  "error": null
}
```

- `status=processing` 时 `stage` 可能是 `detecting` 或 `clipping`；完成/失败后为 `null`；
- `shots[].made = true`：命中；`false`：未中；
- `shots[].clip.status=ready`：片段已生成；`failed` 时看 `clip.error`；
- `shots` 按 `timestamp_seconds` 升序，片段接口的 `index` 就是这个数组下标；
- `timestamp_seconds` 是视频内时间，`frame_index` 是原始视频帧号；
- `result.note` 非空表示「没有识别到」的原因（例如没检测到篮筐、没跟踪到球），
  这**不是**接口失败；
- `result.clip_note` 非空表示整体未生成片段（通常是 ffmpeg 不可用/已关闭）；
- `result.hoop_confidence` 是自动找筐置信度（0-1，手动圈筐时为 1）；低于
  `BASKETBALL_HOOP_MIN_CONFIDENCE` 时服务端不出事件，并在 `note` 里要求手动圈筐。

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

### 6.5 验证进球片段

```bash
# 取第一个就绪片段的数组下标
CLIP_INDEX=$(jq -r '.result.shots | to_entries[] | select(.value.made and .value.clip.status=="ready") | .key' job-status.json | head -n1)
echo "clip index: $CLIP_INDEX"

# 完整下载：预期 200，文件可被 ffprobe 读取
curl -s -o clip.mp4 -w '%{http_code}\n' \
  "http://<SERVER>/api/v1/analyses/$JOB_ID/clips/$CLIP_INDEX"
ffprobe -v error -show_entries format=duration -of default=nw=1:nk=1 clip.mp4

# Range：预期 206，响应头带 Content-Range
curl -s -o /dev/null -D - -H 'Range: bytes=0-1023' \
  "http://<SERVER>/api/v1/analyses/$JOB_ID/clips/$CLIP_INDEX" | head -n 20
```

预期：

- 片段时长约为 `min(t+5, 视频时长) - max(t-5, 0)`，第一球可能不足 10 秒；
- 完整下载返回 `200`、`Content-Type: video/mp4`；
- Range 请求返回 `206` 且带 `Content-Range: bytes 0-1023/<size>`；
- 越界下标返回 `404`；未命中/未生成片段的事件返回 `409`；文件被清理后返回 `404`。

### 6.6 重启行为（任务持久化）

```bash
sudo systemctl restart basketball
sleep 2
curl -s "http://<SERVER>/api/v1/analyses/$JOB_ID" | jq '{status, result, error}'
```

- 已完成的任务：仍返回原状态和 `result`（0.3.0 起写入 SQLite，不再是 404）。
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
| `BASKETBALL_HOOP_MIN_CONFIDENCE` | `0.25` | 自动找筐置信度低于该值时提示手动圈筐；设为 `0` 关闭检查 |
| `BASKETBALL_HUE_MIN` / `BASKETBALL_HUE_MAX` | `0` / `38` | 橙色掩码色相范围；夜景偏黄时上限可调到 40–45 |
| `BASKETBALL_SATURATION_MIN` | `70` | 最小饱和度；运动模糊掉色时可降到 45 |
| `BASKETBALL_VALUE_MIN` | `50` | 最小亮度；夜景可降到 40 |
| `BASKETBALL_BALL_MAX_RADIUS_RATIO` | `0.120` | 球候选最大半径；橙色灯柱较大时降到 0.05 |
| `BASKETBALL_STATIC_PERSISTENCE_THRESHOLD` | `0.95` | 静止噪声过滤阈值；过低会把与篮筐重叠的球一起删掉 |
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

这些参数默认保持基线值，按现场视频逐步调整即可。静止噪声阈值默认已是 0.95
（0.4.1 起，避免球贴筐时被误删）；橙色灯柱/海报多的夜景建议直接手动圈筐，
或把 `BASKETBALL_HOOP_MIN_CONFIDENCE` 保留默认值，让服务端在找筐歧义时明确提示。

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
   `服务端 0.5.0 · 检测器 classical`。

如果服务端用 8080：填 `http://<公网IP>:8080`。

### 8.3 走完整流程

1. 首页「开始分析」→「录制视频」或「从相册选择」；
2. 确认页检查格式/大小/时长，点「开始分析」；
3. 分析页显示上传进度，随后是排队/识别中；若服务端在生成片段，会显示「正在生成进球片段…」；
4. 完成后进入结果页：命中/未中时间点、命中率、视频播放标记；
5. 结果页「进球片段」卡片可进入播放页；点「保存到相册」，Android 10+ 直接保存，
   Android 8-9 首次会申请存储权限；
6. 结果自动保存到「记录」；即使服务端重启，记录仍可打开。

片段需要服务端在线才能播放；被清理后播放页显示「片段已失效」，识别结果不受影响。

如果「服务端」状态显示在线，但上传失败：

- 看 Nginx 错误日志：`sudo tail -n 50 /var/log/nginx/error.log`；
- 重点检查 `client_max_body_size`、`proxy_read_timeout`；
- 确认视频不超过 200 MB，格式在允许列表内。

---

## 9. 端到端验收清单

部署完成后逐项打勾：

- [ ] `GET /api/v1/health` 返回 `version=0.6.0`、`detector=classical`、`clips=true`
- [ ] `GET /docs` 可打开，能看到 `/api/v1/auth/*`、`GET /api/v1/analyses`
  与 `GET /api/v1/analyses/{id}/clips/{index}`
- [ ] 注册、登录、`GET /auth/me`、退出登录均返回预期状态码
- [ ] 上传一段真实视频返回 `202`，任务最终 `completed`
- [ ] 清晰命中的球能在 `result.shots` 中出现 `made=true`
- [ ] 命中事件带 `clip.status=ready`，片段时长约为前后各 5 秒且被视频边界截断
- [ ] 片段接口完整下载返回 `200`/`video/mp4`，Range 请求返回 `206`
- [ ] 没有识别到时 `result.note` 给出了可理解的原因
- [ ] `.txt` 上传返回 `415`
- [ ] 不存在的任务返回 `404`
- [ ] 超过 200 MB 返回 `413`
- [ ] 登录用户的任务列表只包含自己的任务；另一个账号查询该任务/片段返回 `404`
- [ ] 服务端重启后，已完成任务仍能查询，App 本地历史仍能查看
- [ ] App 首页「服务端」状态显示在线，版本/检测器正确
- [ ] App 从录制/相册到结果页全流程走通，片段可播放并保存到相册
- [ ] 公网只开放必要端口；若用 Nginx，8000 未对公网开放
- [ ] `BASKETBALL_DB_PATH` 指向持久化目录；上传目录和片段目录已配置定期清理或已记录待办
- [ ] 生产模式：`BASKETBALL_ENV=production`、`BASKETBALL_REQUIRE_AUTH=true`，真实手机能收到验证码
- [ ] 备份任务已成功跑过一次，`/var/backups/basketball` 有当天数据库；恢复步骤演练过
- [ ] `journalctl -u basketball` 没有持续报错；健康检查 cron 或云监控已配置
- [ ] 运维安全：安全组 22 限来源 IP、`/etc/basketball.env` 为 `640`、RAM AccessKey 非主账号
- [ ] 发给 release 包用户的地址是 HTTPS；debug 包才使用 HTTP 地址

---

## 10. 常见问题

| 现象 | 可能原因 | 处理 |
| --- | --- | --- |
| `detector` 是 `stub` 或 `version` 低于 `0.6.0` | 部署的是旧代码 | `git pull` 最新 `main` 后重启；确认 `server/app/services/sms.py` 存在 |
| `clips=false` 或片段全部失败 | 没有 ffmpeg、路径不对或版本过旧 | `ffmpeg -version`；设置 `BASKETBALL_FFMPEG_PATH`；重新分析 |
| 结果页没有片段区 | 识别到的都是未中球、旧任务未生成、或 ffmpeg 不可用 | 看 `result.clip_note`/`clip.status`；旧任务重新分析；装好 ffmpeg 后重新分析 |
| 502 Bad Gateway | uvicorn 没起来/端口不对 | `systemctl status basketball`、`journalctl -u basketball -n 100`、`curl 127.0.0.1:8000/api/v1/health` |
| App 显示离线 | 地址错、端口没开、安全组没放行 | 手机浏览器先访问 `http://<SERVER>/api/v1/health`；检查安全组和 Nginx |
| 健康检查正常，但上传失败 | Nginx 体积/超时限制 | 调大 `client_max_body_size`、`proxy_read_timeout`；看 Nginx error log |
| `413` | 视频超过 200 MB 或 Nginx 限制 | 压缩视频，或调整 `BASKETBALL_MAX_UPLOAD_BYTES` 与 Nginx 限制 |
| `415` | 扩展名不在允许列表 | 转成 `.mp4`/`.mov`/`.m4v`/`.webm`/`.avi`/`.mkv` |
| `note`: 未检测到篮筐 | 篮筐不是橙色/红色，或镜头晃动导致没有持续区域 | 用橙色/红色篮筐；设置 `BASKETBALL_HOOP_ROI`；固定机位 |
| `note`: 未检测到可跟踪的篮球 | 球不是橙色、太小、光线差 | 换橙色球；靠近拍摄；提高 `BASKETBALL_SAMPLE_FPS` |
| `note`: 检测到轨迹但未识别到投篮 | 投篮轨迹不经过筐口附近，或机位角度问题 | 调整机位让篮筐和球轨迹可见；检查 ROI；远距离偏出属于已知限制 |
| `note`: 自动找筐置信度不足 | 画面里有多个相似的橙色物体（灯柱/海报），自动找筐无法确定 | 在 App 里手动圈定篮筐，或设置 `BASKETBALL_HOOP_ROI`；确认无误可在服务端调低 `BASKETBALL_HOOP_MIN_CONFIDENCE` |
| 任务 `failed`：无法打开视频 | 编码不受支持/文件损坏 | 用 `ffmpeg` 转 H.264 MP4：`ffmpeg -i in.mov -c:v libx264 -pix_fmt yuv420p out.mp4` |
| 服务重启后未完成任务变成 `failed` | 分析是进程内任务，重启不会续跑 | 属预期；重新上传。已完成任务不受影响 |
| 登录返回 `401` | 密码错误，或 token 过期/已退出 | 重新登录；检查 `BASKETBALL_TOKEN_TTL_DAYS` |
| 打开 `BASKETBALL_REQUIRE_AUTH=true` 后 App 上传 401 | App 还没接登录（低于 0.6.0） | 升级 App；或临时保持 `false`，或换用带 `Authorization` 的客户端 |
| 服务启动失败：`生产环境必须使用真实的短信服务` | `BASKETBALL_ENV=production` 但仍是 `dev` 短信 | 按 5.1.1 改成验收模式，或配好阿里云短信后再切生产 |
| 服务启动失败：`阿里云短信缺少配置: ...` | 生产模式缺少 AccessKey/签名/模板/pepper | 补齐 5.1.3 对应变量；注意变量名不要拼错 |
| 服务启动失败：某个 `BASKETBALL_*` 需要 0-1/范围/整数 | 环境变量超出允许范围 | 按附录 B 的默认值和范围检查 `/etc/basketball.env` |
| 发验证码返回 `503` 或日志有 `isv.BUSINESS_LIMIT_CONTROL` | 短信 AccessKey/权限/签名/模板/余额/频率问题 | 看 `journalctl -u basketball -n 100`；到短信控制台核对签名模板、RAM 权限和发送量 |
| 发验证码返回 `429` | 触发手机号/IP/全站限流 | 等待重试；调整 `BASKETBALL_SMS_*_LIMIT` 前先确认不是被刷 |
| release 包提示明文 HTTP 被禁止/连不上 | release 构建只允许 HTTPS | 按 5.4 配域名和证书，App 地址改成 `https://` |
| 日志出现 `database is locked` | 多个进程/worker 同时写同一个 SQLite | 保持 `--workers 1`，不要同时启动手动 uvicorn 和 systemd 服务 |
| 启动报 `Address already in use` | 8000 端口被旧进程占用 | 用 `sudo ss -lntp` 找到占用 8000 的进程并停掉，再重启服务 |
| CPU 打满、分析很慢 | 多人同时上传、抽帧率高、视频大 | 单任务串行使用；降低 `BASKETBALL_SAMPLE_FPS`；控制视频时长/分辨率 |
| 上传文件/片段占满磁盘 | 上传或片段目录未清理 | 配置 5.7 的定时清理，或手动删 `uploads`/`clips` 下旧文件 |
| 安装依赖很慢、`pip install` 反复超时 | 跨境访问 `pypi.org` 慢或网络抖动 | 按 [4.4](#44-加速依赖安装) 换阿里云 PyPI 镜像；失败后直接重跑会复用已下载的 wheel |

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
第 6.6 节、第 9 节中与 SQLite/登录/片段相关的检查项不适用。

## 附录 B：环境变量速查

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `BASKETBALL_DETECTOR` | `classical` | `classical`=OpenCV 基线；`stub`=不识别，只跑通链路 |
| `BASKETBALL_SAMPLE_FPS` | `10` | 抽帧率，越高越准也越慢 |
| `BASKETBALL_SHOT_COOLDOWN_SECONDS` | `1.5` | 两次投篮判定的最小间隔，避免同一球重复计数 |
| `BASKETBALL_HOOP_ROI` | 空 | 手动篮筐区域，格式 `x,y,width,height`（0–1 归一化） |
| `BASKETBALL_HOOP_MIN_CONFIDENCE` | `0.25` | 自动找筐最低置信度；低于该值提示手动圈筐，`0` 关闭检查 |
| `BASKETBALL_HUE_MIN` / `BASKETBALL_HUE_MAX` | `0` / `38` | 橙色掩码色相范围（OpenCV 0–179） |
| `BASKETBALL_SATURATION_MIN` | `70` | 橙色掩码最小饱和度 |
| `BASKETBALL_VALUE_MIN` | `50` | 橙色掩码最小亮度 |
| `BASKETBALL_BALL_MIN_RADIUS_RATIO` | `0.010` | 球候选最小半径（相对画面短边） |
| `BASKETBALL_BALL_MAX_RADIUS_RATIO` | `0.120` | 球候选最大半径（相对画面短边） |
| `BASKETBALL_STATIC_PERSISTENCE_THRESHOLD` | `0.95` | 静止橙色噪声过滤阈值 |
| `BASKETBALL_MAX_UPLOAD_BYTES` | `209715200`（200 MB） | 单文件上传上限 |
| `BASKETBALL_UPLOAD_DIR` | 系统临时目录 | 上传视频保存目录；生产建议 `/var/lib/basketball/uploads` |
| `BASKETBALL_DB_PATH` | `<临时目录>/basketball-data/basketball.db` | SQLite 数据库；生产建议 `/var/lib/basketball/data/basketball.db` |
| `BASKETBALL_REQUIRE_AUTH` | `false` | `true` 时上传/查询/列表都要求登录；生产建议 `true`，旧 App（<0.6.0）先保持 `false` |
| `BASKETBALL_TOKEN_TTL_DAYS` | `30` | 登录 token 有效天数；`0` 表示不过期 |
| `BASKETBALL_ENV` | `development` | `production` 时禁止 `dev` 短信，并强制检查阿里云短信配置 |
| `BASKETBALL_SMS_PROVIDER` | `dev` | `dev`=验证码只写日志；`aliyun`=真实短信 |
| `BASKETBALL_SMS_DEBUG_CODE` | 空（开发默认 `666666`） | 调试用固定验证码；仅开发/验收模式生效，生产禁用；设为 `off` 关闭 |
| `BASKETBALL_SMS_ACCESS_KEY_ID` / `..._SECRET` | 空 | 阿里云 RAM AccessKey；生产必填 |
| `BASKETBALL_SMS_SIGN_NAME` / `..._TEMPLATE_CODE` | 空 | 已审核的短信签名和模板 CODE；生产必填 |
| `BASKETBALL_SMS_REGION` | `cn-hangzhou` | 短信服务地域 |
| `BASKETBALL_SMS_CODE_PEPPER` | 空 | 验证码 HMAC 密钥；生产必填，用 `openssl rand -hex 32` 生成 |
| `BASKETBALL_SMS_CODE_TTL_SECONDS` | `300` | 验证码有效期（60–1800 秒） |
| `BASKETBALL_SMS_RESEND_SECONDS` | `60` | 同一手机号重发间隔（30–600 秒） |
| `BASKETBALL_SMS_MAX_ATTEMPTS` | `5` | 单个验证码最大尝试次数（1–10） |
| `BASKETBALL_SMS_PHONE_HOURLY_LIMIT` / `..._DAILY_LIMIT` | `5` / `10` | 单手机号每小时/每天发码上限 |
| `BASKETBALL_SMS_IP_HOURLY_LIMIT` | `30` | 单 IP 每小时发码上限 |
| `BASKETBALL_SMS_DAILY_LIMIT` | `1000` | 全站每天发码上限 |
| `BASKETBALL_LOGIN_MAX_FAILURES` / `..._WINDOW_SECONDS` | `10` / `900` | 登录失败次数上限与统计窗口（秒） |
| `BASKETBALL_CLIP_ENABLED` | `true` | 关闭后不生成片段，识别任务照常完成 |
| `BASKETBALL_CLIP_PRE_SECONDS` / `BASKETBALL_CLIP_POST_SECONDS` | `5` / `5` | 进球前后各截取秒数（0–30） |
| `BASKETBALL_CLIP_DIR` | `<upload_dir>/clips` | 片段保存目录；生产建议 `/var/lib/basketball/clips` |
| `BASKETBALL_FFMPEG_PATH` | `ffmpeg` | ffmpeg 可执行文件路径 |
| `BASKETBALL_CLIP_INCLUDE_MISSES` | `false` | 是否也给未中事件生成片段 |
| `BASKETBALL_CLIP_MAX_PER_JOB` | `50` | 单任务最多片段数 |
| `BASKETBALL_CLIP_TIMEOUT_SECONDS` | `120` | 单片段生成超时 |

变量在进程启动时读取，修改后执行 `sudo systemctl restart basketball`。

## 附录 C：文档验证说明

本文档中的服务端代码路径、服务端测试、`/api/v1/health`、上传/轮询、413/415/404 行为，
手机号注册/双登录/改密、任务归属、SQLite 重启持久化，以及 0.5.0 的片段生成、Range 播放和 `health.clips`，
已在本地用同一份代码和依赖实际跑通（Python 3.11、OpenCV 4.10、NumPy 1.26、ffmpeg）；
持久化与账号细节见 [server-persistence-accounts.md](server-persistence-accounts.md)，
片段细节见 [shot-clips.md](shot-clips.md)。
systemd/Nginx/阿里云安全组部分按 Ubuntu 24.04 编写，首次部署请按第 9 节逐项验收；
如果实际环境有差异，以 `journalctl`、`nginx -t` 和健康检查结果为准。

4.3/4.4 的依赖安装命令用同一份 `server/requirements.txt` 验证过：从 `mirrors.aliyun.com` 能
下载全部 wheel（含传递依赖），uv 也能完成解析；但未在真实 ECS 上计时，实际速度取决于地域和
运营商，首次部署以实际下载耗时为准。

5.8–5.11 的备份、升级、监控和安全加固是通用运维步骤，未在真实 ECS 上逐条执行；首次执行前
建议先在测试机验证，尤其是 SSH 加固时保持当前会话不要断开，避免把自己锁在服务器外。

## 附录 D：部署记录（交付时填写）

> 只记录部署信息，**不要**在这里写 AccessKey、数据库密码、验证码 pepper 等密钥。

| 项目 | 值 |
| --- | --- |
| 部署日期 / 负责人 |  |
| 阿里云地域 / 实例 ID |  |
| 公网 IP / 域名 |  |
| 部署 commit / tag |  |
| 部署模式（验收 / 生产） |  |
| `BASKETBALL_DB_PATH` |  |
| 上传 / 片段目录 |  |
| 备份目录 / 保留天数 |  |
| 短信签名 / 模板 CODE |  |
| 健康检查地址 |  |

部署完成后把本表填好，和 `/etc/basketball.env` 分开保管；密钥只放在服务器配置和密码管理器中。
