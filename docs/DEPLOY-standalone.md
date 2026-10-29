# 云裳进销存 · 单机版部署教程（数据在自己电脑上，不出网）

> 这份文件是给**刚下载这个压缩包的人**看的入口。仓库里还有一份更细的运行手册
> [`infra/standalone/README.md`](infra/standalone/README.md)，卡住时去查它。
> 想要公网可访问的那套（域名 + HTTPS + 多人访问），看 `README-START-server.md`。

---

## 「单机版」是什么意思

**整套系统跑在你自己这一台电脑上**：数据库、后端、网页界面全部在你本机的 Docker 容器里，
数据存在你本机的 Docker 卷里，浏览器用 `http://127.0.0.1:18090` 打开。
拔了网线照样能用，不会向任何云服务器发一个请求。

和另外两种形态的区别：

| 形态 | 数据在哪 | 别人能不能访问 | 你要准备什么 |
|---|---|---|---|
| **单机版**（本文件） | 你自己电脑 | 默认不能（只监听 127.0.0.1） | Docker Desktop |
| 服务器完整部署 | 云服务器 | 能，给域名即可 | 一台 Linux 服务器 + 已备案域名 |
| 开发模式（`启动系统.cmd`） | 你自己电脑 | 局域网可 | 还要装 JDK 17 与 Node.js |

---

## 你需要什么

| 项 | 要求 |
|---|---|
| 操作系统 | Windows 10/11（Docker Desktop + WSL2）**或** Linux（Docker Engine 24+） |
| Docker | 含 `docker compose` v2（`docker compose version` 能出号就行） |
| 资源 | 建议 2 核 / 4 GB 内存 / 5 GB 可用磁盘（镜像约 2 GB + 数据与备份） |
| 端口 | `18090`（Web）与 `15439`（数据库，只绑 127.0.0.1）没被占用 |
| 宿主软件 | **不需要**装 Java、Node.js、PostgreSQL —— 全在容器里 |
| 网络 | 首次构建要联网拉 Maven / npm 依赖，实测 5~15 分钟 |

没装 Docker Desktop 的话：`https://www.docker.com/products/docker-desktop/`，装完按提示启用 WSL2 并重启。

---

## 三步跑起来

解压后在仓库根目录操作（PowerShell）：

```powershell
# 1) 启动（首次会构建镜像并等数据库就绪）
.\infra\standalone\start-standalone.ps1

# 2) 顺手指定管理员口令（不指定则脚本会生成一个并打印出来）
.\infra\standalone\start-standalone.ps1 -AdminUsername admin -AdminPassword 'S3cure-Passw0rd'

# 3) 打开浏览器
start http://127.0.0.1:18090/
```

Linux / Git Bash 用同名 `.sh`：`./infra/standalone/start-standalone.sh`。

看到登录页就算成功了。用第 2 步的账号登录。

### 想要一批演示数据（可选，但强烈建议）

空库里只有账号，页面会很空。灌一批 12 个月的虚构业务数据（商品、库存流水、单据、凭证）：

```powershell
$env:ERP_API  = "http://127.0.0.1:18090/api/v1"
$env:ERP_USER = "admin"
$env:ERP_PASS = "你上面设的口令"
node scripts/seed-demo.mjs          # 需要 Node 18+；只想验接口通用 --quick
```

数据是幂等的（重复跑会跳过已灌过的部分）；`--reset` 会先清空业务数据再重灌（主数据与账号保留）。

---

## 日常操作

| 我要…… | Windows | Linux / Git Bash |
|---|---|---|
| 看状态 | `.\infra\standalone\status-standalone.ps1` | `./infra/standalone/status-standalone.sh` |
| 停止（**保留数据**） | `.\infra\standalone\stop-standalone.ps1` | `./infra/standalone/stop-standalone.sh` |
| 备份数据库 | `.\infra\standalone\backup-standalone.ps1` | `./infra/standalone/backup-standalone.sh` |
| 彻底删除数据（危险） | `.\infra\standalone\stop-standalone.ps1 -RemoveData -Yes` | `./infra/standalone/stop-standalone.sh --remove-data --yes` |
| 看日志 | `docker compose --env-file infra/standalone/.env -f infra/standalone/docker-compose.standalone.yml logs -f --tail=200` | 同左 |

配置都在 `infra/standalone/.env`（从 `.env.example` 复制而来）。要改端口/口令/镜像 tag 就改它，
键名：`POSTGRES_DB`、`POSTGRES_USER`、`POSTGRES_PASSWORD`、`ERP_JWT_SECRET`、`WEB_BIND_IP`、
`WEB_PORT`、`PG_HOST_PORT`、`TZ`、`IMAGE_TAG`、`BACKUP_RETENTION_DAYS`。

想让别人从局域网访问你的机器：把 `WEB_BIND_IP` 从 `127.0.0.1` 改成 `0.0.0.0` 并重启，
然后给他们 `http://<你电脑的内网IP>:18090`。**数据库端口 15439 不要放开。**

---

## 卡住了先查这四条

| 症状 | 大概率原因 | 怎么确认 / 怎么修 |
|---|---|---|
| 报 `docker compose` 不是内部命令 | Docker Desktop 没启动 | 启动它，托盘图标变绿再跑 |
| 端口占用 | 18090 / 15439 被别的程序占 | `netstat -ano \| findstr ":18090 :15439"`，改 `.env` 里的端口 |
| 构建阶段拉不到依赖 | 公司网/镜像源问题 | 后端依赖走 Maven 镜像、前端走 `registry.npmmirror.com`（见 `infra/deploy/offline/README.md` 的离线做法） |
| 容器反复重启 | 内存不够，PostgreSQL 被 OOM 杀 | `docker stats --no-stream`；给 Docker Desktop 至少 4 GB（设置 → Resources） |

---

## 已知限制（如实说明）

- 单机版**没有做真实离线环境验证**（在开发机上跑通过，但没在一台干净的、没装过任何东西的电脑上重装验证过）。
  你在干净机器上遇到本文件没覆盖的问题，请把 `docker compose logs` 的最后 30 行留好。
- 首次启动要构建镜像，慢是正常现象，不是卡死；判断依据是日志里有没有新行输出。
- 管理员口令、JWT 密钥这些**每个环境都要自己设**，不要沿用示例值。
