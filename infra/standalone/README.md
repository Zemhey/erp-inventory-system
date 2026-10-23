# 单机版运行手册（M8）

> **适用对象**：第一次在这台电脑上部署 ERP 的人。
> **目标**：不依赖云服务、域名或外网，一条命令启动 PostgreSQL + 后端 + Web，
> 数据保存在本机 Docker 卷，可备份、可升级、可排障。
>
> 本目录只做单机版编排；公网部署、域名、备案、HTTPS 见
> [`infra/deploy/README.md`](../deploy/README.md)。两者共用同一套后端/前端镜像。

---

## 0. 一页速查

| 我要…… | Windows（PowerShell 5.1） | Linux / Git Bash |
|---|---|---|
| 首次启动 | `.\infra\standalone\start-standalone.ps1` | `./infra/standalone/start-standalone.sh` |
| 指定管理员密码 | `... -AdminPassword 'S3cure-Passw0rd'` | `... --admin-password 'S3cure-Passw0rd'` |
| 看状态 | `.\infra\standalone\status-standalone.ps1` | `./infra/standalone/status-standalone.sh` |
| 停止（保留数据） | `.\infra\standalone\stop-standalone.ps1` | `./infra/standalone/stop-standalone.sh` |
| 备份数据库 | `.\infra\standalone\backup-standalone.ps1` | `./infra/standalone/backup-standalone.sh` |
| 删除数据卷（危险） | `... -RemoveData -Yes` | `... --remove-data --yes` |
| 打开系统 | `http://127.0.0.1:18090/` | `http://127.0.0.1:18090/` |
| 看日志 | 见 §5.1 | 见 §5.1 |

默认端口：**Web 18090**、**PostgreSQL 127.0.0.1:15439**。后端 8080 不发布到宿主。
默认只监听回环，局域网/公网都访问不到；需要局域网访问见 §4.4。

---

## 1. 前置条件

| 项 | 要求 |
|---|---|
| 操作系统 | Windows 10/11（Docker Desktop + WSL2）或 Linux（Docker Engine 24+） |
| Docker | Docker Desktop 4.x / Docker Engine 24+，含 `docker compose` v2 |
| 资源 | 建议 2 CPU / 4 GB 内存 / 5 GB 可用磁盘（镜像约 2 GB + 数据/备份） |
| 端口 | 18090、15439 未被占用（15439 只绑 127.0.0.1） |
| 宿主软件 | 不需要安装 Java、Node.js、PostgreSQL；全部在容器内 |

检查命令：

```bash
docker --version
docker compose version
docker info
```

Windows 上如果 `docker info` 报错，先启动 Docker Desktop，等托盘图标变为运行中。
Linux 上如果权限不足，把当前用户加入 `docker` 组或使用 `sudo`（不推荐长期 sudo）。

检查端口：

```powershell
# Windows PowerShell
Get-NetTCPConnection -LocalPort 18090,15439 -ErrorAction SilentlyContinue
```

```bash
# Linux / Git Bash
ss -ltnp | grep -E ':(18090|15439)\s' || echo "ports free"
```

---

## 2. 目录、架构与端口

```
infra/standalone/
├── docker-compose.standalone.yml   # PG + backend + web（项目名 erp-standalone）
├── .env.example                    # 手工配置模板（启动脚本会自动生成 .env）
├── seed-admin.sql                  # 管理员初始化 SQL（密码由 psql 变量传入）
├── start-standalone.sh / .ps1      # 一键启动 + 首次管理员
├── stop-standalone.sh / .ps1       # 停止；--remove-data 删除数据卷
├── status-standalone.sh / .ps1     # 状态 / 健康检查 / 卷 / 最近备份
├── backup-standalone.sh / .ps1     # pg_dump -Fc + 校验 + 保留策略
├── lib/                            # sh/ps1 公共库
└── backups/                        # 备份产物（不入库，首次备份时创建）
```

```
浏览器
  │  http://127.0.0.1:18090
  ▼
┌──────────────────────────────────────────────────────────┐
│ compose 项目 erp-standalone                              │
│  web (erp-web:<tag>)         :80 → 宿主 127.0.0.1:18090   │
│    ├─ 静态资源 + SPA fallback                            │
│    └─ /api/ → backend:8080                               │
│  backend (erp-backend:<tag>) 仅内网 8080，不发布          │
│  postgres (postgres:16-alpine) 宿主 127.0.0.1:15439       │
│    命名卷 erp-standalone-pgdata                           │
└──────────────────────────────────────────────────────────┘
```

| 服务 | 镜像 | 宿主端口 | 说明 |
|---|---|---|---|
| web | `erp-web:${IMAGE_TAG}` | `127.0.0.1:18090` | 唯一入口；`/healthz`、`/readyz` |
| backend | `erp-backend:${IMAGE_TAG}` | 不发布 | 只经 web 的 `/api/` 反代 |
| postgres | `postgres:16-alpine` | `127.0.0.1:15439` | 本机排障/备份；不对局域网开放 |

数据卷：`erp-standalone-pgdata`（固定名，停止/重启/升级保留）。
备份目录：`infra/standalone/backups/`（宿主文件，不放进卷）。

---

## 3. 镜像准备（离线优先）

单机版运行期**不会联网拉镜像**（compose 设置了 `pull_policy: never`，启动脚本也传
`--pull never`）。第一次使用前必须让本机具备以下镜像：

| 镜像 | 用途 |
|---|---|
| `erp-backend:standalone` | 后端（Spring Boot） |
| `erp-web:standalone` | 前端（nginx + Vue 构建产物） |
| `postgres:16-alpine` | 数据库 |

### 3.1 方式 A：联网机器构建，导出镜像包（推荐给目标机）

在**有网络、有 Docker** 的机器上：

```bash
# 仓库根目录；.env.example 里的占位值只用于解析 compose，不会启动服务
docker compose --env-file infra/standalone/.env.example \
  -f infra/standalone/docker-compose.standalone.yml build backend web
docker pull postgres:16-alpine

# 三个镜像打进一个 tar（约 2 GB，未压缩）
docker save -o erp-standalone-images.tar \
  erp-backend:standalone erp-web:standalone postgres:16-alpine
```

Windows PowerShell 等价写法（每行一条命令）：

```powershell
docker compose --env-file infra/standalone/.env.example -f infra/standalone/docker-compose.standalone.yml build backend web
docker pull postgres:16-alpine
docker save -o erp-standalone-images.tar erp-backend:standalone erp-web:standalone postgres:16-alpine
```

把**仓库目录 + `erp-standalone-images.tar`** 拷到目标机（U 盘/内网共享均可），然后：

```bash
docker load -i erp-standalone-images.tar
docker image ls | grep -E 'erp-(backend|web)|postgres'
```

> 如果构建时改了 `IMAGE_TAG`，`docker save` 与启动参数要使用同一个 tag。
> 导出 tar 属于二进制大文件，不要提交 Git。

### 3.2 方式 B：目标机可联网，首次构建一次

首次启动加 `--build` / `-Build`：脚本会生成 `.env`、构建 backend/web，并在缺少 PG 镜像时
显式 `docker pull postgres:16-alpine`。

```powershell
.\infra\standalone\start-standalone.ps1 -Build
```

```bash
./infra/standalone/start-standalone.sh --build
```

构建完成后再次启动不需要网络。构建使用仓库内的两份生产 Dockerfile
（`backend/server/Dockerfile`、`frontend/web/Dockerfile`），与 M6 生产镜像同源。

### 3.3 方式 C：本机 Docker Hub 不可达（M6 离线垫片）

本仓库的 M6 泳道提供了一套**仅用于网络受限开发机**的离线基础镜像垫片：

```bash
# Git Bash；需要宿主已有 Gradle 缓存与本地 postgres:16-alpine
TAG=standalone ./scripts/build-local-images.sh
# 然后用默认 tag 启动；或 TAG=standalone-local 配合 --image-tag standalone-local
```

原理与限制见 [`infra/deploy/offline/README.md`](../deploy/offline/README.md)。要点：

- 原样使用生产 Dockerfile，只替换 `FROM` 的基础镜像与 npm registry；
- 构建阶段使用宿主 Gradle 缓存，前端走 `registry.npmmirror.com`；
- 产物比官方基础镜像大（后端约 818 MB、Web 约 671 MB），**不要用于真实服务器**；
- 真实部署仍应按 M6 Runbook 使用官方基础镜像。

---

## 4. 首次启动

### 4.1 Windows（PowerShell 5.1）

```powershell
cd "D:\MajorCacheFolder\Company system\自选"
powershell -NoProfile -ExecutionPolicy Bypass -File .\infra\standalone\start-standalone.ps1
```

指定管理员密码（至少 8 位，字符集见 §4.3）：

```powershell
.\infra\standalone\start-standalone.ps1 -AdminPassword 'S3cure-Passw0rd'
# 或：
$env:ERP_ADMIN_PASSWORD = 'S3cure-Passw0rd'
.\infra\standalone\start-standalone.ps1
```

常用参数：

| 参数 | 作用 |
|---|---|
| `-AdminUsername ops` | 管理员用户名（默认 `admin`） |
| `-AdminPassword '...'` | 创建/重置管理员密码；省略时首次自动生成 |
| `-ImageTag standalone-local` | 覆盖 `.env` 的 `IMAGE_TAG` |
| `-Build` | 本机可联网时构建 backend/web 并拉取 PG |

> 如果系统禁止执行脚本，`-ExecutionPolicy Bypass` 只对本次进程生效，不修改系统策略。

### 4.2 Linux / Git Bash

```bash
chmod +x infra/standalone/*.sh infra/standalone/lib/*.sh
./infra/standalone/start-standalone.sh
```

指定管理员密码：

```bash
./infra/standalone/start-standalone.sh --admin-password 'S3cure-Passw0rd'
# 或：
ERP_ADMIN_PASSWORD='S3cure-Passw0rd' ./infra/standalone/start-standalone.sh
```

### 4.3 首次启动做了什么

1. 检查 Docker / compose；
2. 若 `infra/standalone/.env` 不存在，生成随机 `POSTGRES_PASSWORD` 与 `ERP_JWT_SECRET`
   （48/64 位十六进制），权限 600；
3. 检查三个镜像是否存在；缺失时**直接失败并给出导入/构建命令**，不会隐式联网；
4. `docker compose up -d --no-build --pull never --wait`，等待 PG/backend/web healthy；
5. 等 `http://127.0.0.1:18090/readyz` 返回 200；
6. 初始化管理员：
   - 账号不存在 + 未提供密码：生成 32 位随机密码，**只打印一次**；
   - 账号不存在 + 提供密码：使用提供的密码；
   - 账号已存在 + 未提供密码：跳过，不重置；
   - 账号已存在 + 提供密码：重置密码并清空锁定状态。

管理员密码字符集：字母、数字与 `. _ @ # % + = : -`（至少 8 位）。
限制字符集是为了让密码安全地作为命令行参数传给容器；随机生成的密码不受影响。
如果忘记密码，重新执行启动脚本并带 `-AdminPassword` / `--admin-password` 即可重置。
命令行传密码会进入 shell 历史；共享机器上优先用 `ERP_ADMIN_PASSWORD` 环境变量，并注意清理历史。

### 4.4 局域网访问（可选）

默认 `WEB_BIND_IP=127.0.0.1`，只有本机能访问。需要让同一局域网的其他电脑访问：

1. 编辑 `infra/standalone/.env`，改 `WEB_BIND_IP=0.0.0.0`；
2. 重启：`stop-standalone` → `start-standalone`；
3. 在系统防火墙放行 18090（Windows 防火墙/云安全组）；
4. 用 `http://<本机IP>:18090/` 访问。

**不要把 18090 或 15439 直接映射到公网**。公网访问应走 M6 的域名 + HTTPS + 宿主 nginx。

### 4.5 启动后自检

```bash
curl -fsS http://127.0.0.1:18090/healthz        # ok
curl -fsS http://127.0.0.1:18090/readyz         # {"status":"UP"}
curl -fsS -o /dev/null -w '%{http_code}\n' http://127.0.0.1:18090/purchase-orders   # 200
```

浏览器打开 `http://127.0.0.1:18090/`，用管理员账号登录，依次检查：
商品/SKU、供应商/客户/仓库、进货单、出货单、库存查询与流水。

---

## 5. 日常操作

### 5.1 状态与日志

```powershell
.\infra\standalone\status-standalone.ps1
```

```bash
./infra/standalone/status-standalone.sh
```

脚本输出：容器状态、`/healthz`、`/readyz`、管理员是否存在、数据卷、最近备份。
退出码 0 表示 `/readyz` 200。

看日志（两种平台命令相同，在仓库根目录执行）：

```bash
docker compose --env-file infra/standalone/.env \
  -f infra/standalone/docker-compose.standalone.yml logs -f --tail=200 backend web
```

只看数据库：

```bash
docker compose --env-file infra/standalone/.env \
  -f infra/standalone/docker-compose.standalone.yml logs --tail=200 postgres
```

### 5.2 停止、重启

```powershell
.\infra\standalone\stop-standalone.ps1       # 停止并删除容器/网络，保留数据卷
.\infra\standalone\start-standalone.ps1      # 再次启动，数据还在
```

```bash
./infra/standalone/stop-standalone.sh
./infra/standalone/start-standalone.sh
```

`stop` 等价于 `docker compose down`（不带 `-v`）；数据卷 `erp-standalone-pgdata` 保留。
Docker 重启后容器会自动拉起（`restart: unless-stopped`），但前提是 Docker Desktop 已启动。

### 5.3 删除数据（不可恢复）

仅在确认不再需要数据、且已备份时执行：

```powershell
.\infra\standalone\stop-standalone.ps1 -RemoveData -Yes
```

```bash
./infra/standalone/stop-standalone.sh --remove-data --yes
```

这会删除容器、网络与数据卷；`infra/standalone/backups/` 中的备份文件不受影响。

---

## 6. 备份与恢复

### 6.1 备份

```powershell
.\infra\standalone\backup-standalone.ps1
```

```bash
./infra/standalone/backup-standalone.sh
```

产物：`infra/standalone/backups/erp-standalone-YYYYMMDDTHHMMSSZ.dump`（UTC 时间戳）。
脚本使用 `pg_dump -Fc`，并用 `pg_restore -l` 校验能读到表数据；失败会退出非 0。
保留天数由 `.env` 的 `BACKUP_RETENTION_DAYS`（默认 14）控制。

建议每天自动备份一次：

Windows 任务计划（管理员 PowerShell）：

```powershell
$script = 'D:\MajorCacheFolder\Company system\自选\infra\standalone\backup-standalone.ps1'
$action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument "-NoProfile -ExecutionPolicy Bypass -File `"$script`""
$trigger = New-ScheduledTaskTrigger -Daily -At 03:00
Register-ScheduledTask -TaskName 'ERP Standalone Backup' -Action $action -Trigger $trigger -RunLevel Highest
```

Linux cron：

```cron
0 3 * * * cd /opt/erp && ./infra/standalone/backup-standalone.sh >> infra/standalone/backups/backup.log 2>&1
```

### 6.2 恢复

恢复会覆盖现有数据库对象。**先停止 backend/web，保留 postgres 运行**，并确认备份文件可用：

```bash
# 仓库根目录；定义 compose 命令，避免重复
alias erp-m8='docker compose --env-file infra/standalone/.env -f infra/standalone/docker-compose.standalone.yml'

erp-m8 stop backend web
```

Linux / macOS / Git Bash（bash 下二进制 stdin 安全）：

```bash
erp-m8 exec -T postgres pg_restore --clean --if-exists --no-owner --no-acl \
  -U erp -d erp < infra/standalone/backups/erp-standalone-20261023T092140Z.dump
erp-m8 start backend web
```

Windows PowerShell（用 `docker cp` 把文件放进容器，避免二进制经过 PowerShell 管道）：

```powershell
docker compose --env-file infra/standalone/.env -f infra/standalone/docker-compose.standalone.yml stop backend web
docker cp "infra\standalone\backups\erp-standalone-20261023T092140Z.dump" erp-standalone-postgres:/tmp/restore.dump
docker compose --env-file infra/standalone/.env -f infra/standalone/docker-compose.standalone.yml exec -T postgres pg_restore --clean --if-exists --no-owner --no-acl -U erp -d erp /tmp/restore.dump
docker compose --env-file infra/standalone/.env -f infra/standalone/docker-compose.standalone.yml start backend web
```

恢复后检查：

```bash
curl -fsS http://127.0.0.1:18090/readyz
# 浏览器登录，抽查进货单/出货单/库存流水
```

建议每月做一次恢复演练：把备份恢复到临时库并比对关键表计数，不要只验证文件存在。
（本次 M8 实测：备份恢复到临时库后 `purchase_orders=1, sales_orders=1, inventory_ledger=2, app_users=1`，
与源库一致；详见 `.plan/M8-计划书.md` §7。）

---

## 7. 升级与回滚

### 7.1 升级（保留数据）

1. **先备份**：`backup-standalone`；
2. 在联网机器构建/导出新镜像，目标机 `docker load`；
3. 给新镜像一个**不同的 tag**（例如 `standalone-20261030`），避免覆盖旧镜像；
4. 启动时指定新 tag：

```powershell
.\infra\standalone\start-standalone.ps1 -ImageTag standalone-20261030
```

```bash
./infra/standalone/start-standalone.sh --image-tag standalone-20261030
```

Compose 会因镜像名变化重建容器；数据卷不动。Flyway 在 backend 启动时自动执行迁移。

如果必须复用同一个 tag（例如重新构建了 `standalone`），用下面命令强制重建：

```bash
docker compose --env-file infra/standalone/.env \
  -f infra/standalone/docker-compose.standalone.yml \
  up -d --no-build --pull never --force-recreate
```

升级后执行 `status`、`/readyz` 与一次登录冒烟。

### 7.2 回滚

- 镜像回滚：保留旧 tag，把启动参数改回旧 tag（或改 `.env` 的 `IMAGE_TAG`），启动即可；
- **数据库只向前迁移**：应用回滚不会自动回滚 Flyway。若新版本做了破坏性迁移，
  先停服，用升级前的备份恢复数据库，再回滚镜像；
- 不要删除旧镜像，直到确认新版本稳定。

---

## 8. 离线能力说明

| 阶段 | 是否需要网络 | 说明 |
|---|---|---|
| 首次构建镜像 | 需要（或使用离线垫片/预置镜像） | `docker compose build` 需要拉基础镜像与依赖 |
| 导出/导入镜像 | 不需要 | `docker save` / `docker load` |
| 日常启动 | **不需要** | `pull_policy: never` + `--pull never`，镜像缺失直接报错 |
| 业务运行 | **不需要** | 只连本机 PG；无对象存储、无云 API、无遥测 |
| 升级 | 不需要 | 导入新镜像 tar 即可 |

边界：Docker Desktop / Docker Engine 自身的许可校验与更新检查不属于本应用控制范围；
本单机版只保证 ERP 应用与编排不依赖云服务。

---

## 9. 故障排查

| 现象 | 原因 | 处理 |
|---|---|---|
| `Docker 守护进程未运行` | Docker Desktop 未启动 / Linux 服务未起 | 启动 Docker Desktop；Linux `systemctl start docker` |
| `未找到 docker compose 插件` | 只有旧版 `docker-compose` | 安装 Compose v2 / 升级 Docker Desktop |
| 缺少镜像 | 未导入 tar / tag 不一致 | 按 §3 导入；确认 `.env` 的 `IMAGE_TAG` 与镜像 tag 一致 |
| `port is already allocated` | 18090/15439 被占用 | `netstat`/`ss` 查占用；改 `.env` 的 `WEB_PORT`/`PG_HOST_PORT` 后重启 |
| backend 一直 unhealthy | `.env` 凭据被改、PG 未健康、磁盘满 | `logs backend postgres`；检查磁盘；确认没有改过已初始化卷的 PG 密码 |
| 改了 `POSTGRES_PASSWORD` 后 PG 起不来 | initdb 只在首次初始化时使用密码，卷里仍是旧密码 | 改回旧值；或在容器内 `ALTER USER erp WITH PASSWORD ...` 后再改 `.env` |
| 管理员密码丢失 | 首次生成的密码只打印一次 | `start -AdminPassword '新密码'` / `start --admin-password '新密码'` 重置 |
| 浏览器只能本机访问 | 默认 `WEB_BIND_IP=127.0.0.1` | 见 §4.4；不要直接暴露公网 |
| PowerShell 中文输出在 Git Bash 里乱码 | Git Bash 按 UTF-8 解码 PS 的 OEM 输出 | 在 PowerShell 窗口运行，或 `[Console]::OutputEncoding=[Text.Encoding]::UTF8`；不影响功能 |
| `.ps1` 无法执行 | 执行策略 | 使用 `-ExecutionPolicy Bypass`，不要改系统全局策略 |
| 备份失败 | PG 未运行 / 磁盘满 / dump 校验失败 | `status`、`logs postgres`、检查磁盘；失败不会留下半成品 `.dump` |
| 磁盘占用大 | 镜像/日志/备份/数据卷 | `docker system df`；清理旧镜像；调整备份保留天数 |

更多容器日志：

```bash
docker compose --env-file infra/standalone/.env \
  -f infra/standalone/docker-compose.standalone.yml ps
docker compose --env-file infra/standalone/.env \
  -f infra/standalone/docker-compose.standalone.yml logs --tail=200 backend postgres web
```

---

## 10. 验收命令清单（对应 AC-M8）

| 验收 | 命令/操作 | 预期 |
|---|---|---|
| 一键启动 | `start-standalone` | 三服务 healthy，`/readyz` 200 |
| 管理员初始化 | 启动输出中的账号/密码登录 | 登录成功，角色 ADMIN |
| 业务链路 | 登录 → 建进货单 → 入库 → 建出货单 → 出货 → 查库存/流水 | 见 `.plan/M8-计划书.md` §7 |
| 数据卷 | `docker volume inspect erp-standalone-pgdata` | 卷存在；重启后数据还在 |
| 断网启动 | 断网/无新镜像时启动 | 不联网拉取；缺镜像直接报错 |
| 端口隔离 | `docker ps` / `netstat` | 18090、127.0.0.1:15439；backend 不发布 |
| 停止保留数据 | `stop-standalone` → `start-standalone` | 数据与账号仍在 |
| 清理 | `stop-standalone --remove-data --yes` | 容器/卷删除；端口释放 |
| 备份 | `backup-standalone` | 非空 dump，`pg_restore -l` 可读 |
| 双平台 | `.ps1` 与 `.sh` 各跑一次 | 语法与执行均通过 |

---

## 11. 与 M6 生产部署的区别

| 项 | 单机版（本目录） | 生产版（`infra/deploy`） |
|---|---|---|
| 项目名 | `erp-standalone` | `erp-prod` |
| Web 端口 | 18090，默认 127.0.0.1 | 18080，按需 0.0.0.0 |
| PG 端口 | 15439，127.0.0.1 | 15435，127.0.0.1 |
| 数据卷 | `erp-standalone-pgdata` | `erp-prod_erp-pgdata` |
| 入口 | 本机浏览器 | 宿主 nginx + 域名 + HTTPS |
| 拉镜像 | `pull_policy: never` | 部署脚本按 tag 拉取/构建 |
| 部署/回滚 | 一键脚本 + 镜像 tag | `deploy.sh` / `rollback.sh` + `.deploy-state` |
| 适用 | 单店/课程演示/离线使用 | 云服务器对外服务 |

两套编排可以同机共存（项目名、容器名、卷名、端口均不同），但不要同时用同一个 PG 卷。

---

## 12. 卸载

1. 先备份：`backup-standalone`；
2. 停止并删除数据卷：`stop-standalone -RemoveData -Yes`（或 `--remove-data --yes`）；
3. 删除镜像（可选）：

```bash
docker image rm erp-backend:standalone erp-web:standalone
```

4. 删除仓库目录与 `infra/standalone/backups/` 中不再需要的备份。

`postgres:16-alpine` 可能被其他项目使用，删除前先确认。
