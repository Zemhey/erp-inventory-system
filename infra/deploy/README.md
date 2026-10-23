# M6 运行手册 · ERP 生产部署

> **适用对象**：第一次接手这台服务器的运维/未来的你。
> **目标**：只看本文，能把「PostgreSQL + Spring Boot + nginx」部署到一台 Linux 服务器，
> 并完成日常发布、回滚、备份、HTTPS 与排障。
>
> **当前状态（2026-10-23）**：制品与整套 compose 已在本地验证；**尚未部署到公网**。
> 真实部署还需要：服务器 SSH 凭据、备案通过、域名解析。见 §12。
>
> 配套文档：架构与取舍见 [`.plan/M6-计划书.md`](../../.plan/M6-计划书.md)；
> 本地离线验证见 [`infra/deploy/offline/README.md`](offline/README.md)。

---

## 0. 一页速查

| 我要…… | 命令 |
|---|---|
| 首次部署 | `cp infra/deploy/.env.example infra/deploy/.env` → 改密码/密钥 → `./scripts/deploy.sh` |
| 发布新版本 | `git checkout <tag>` → `./scripts/deploy.sh` |
| 回滚上一版 | `./scripts/rollback.sh` |
| 备份数据库 | `./scripts/backup-db.sh` |
| 看整套是否可用 | `curl -fsS http://127.0.0.1:18080/readyz` |
| 看日志 | `docker compose --env-file infra/deploy/.env -f infra/deploy/docker-compose.prod.yml logs -f --tail=200 backend` |
| 起管理员 | `ADMIN_PASSWORD='<强密码>' ./scripts/seed-admin.sh` |
| 入口 | 未备案：`http://<服务器IP>:18080`；备案后：宿主 nginx `https://<域名>` |

> **不要**把 8080（后端）或 15435（PG）开到公网。公网只开 22 / 80 / 443
> （备案前临时开 18080）。

---

## 1. 架构、端口与文件

```
浏览器
  │  http(s)://域名 或 http://IP:18080
  ▼
┌────────────────────────────────────────────────────────────┐
│ 云服务器（Ubuntu 22.04/24.04，2C4G 起）                     │
│                                                            │
│  [可选] 宿主 nginx :80/:443  ── 备案后 + Let's Encrypt      │
│        │ proxy_pass 127.0.0.1:18080                        │
│        ▼                                                   │
│  compose 项目 erp-prod                                     │
│  ┌─────────────────────────────┐                           │
│  │ web (nginx:1.27-alpine)     │  :80 → 宿主 18080          │
│  │  静态文件 + SPA fallback    │                           │
│  │  /api/ → backend:8080       │                           │
│  └──────────────┬──────────────┘                           │
│                 ▼                                          │
│  ┌─────────────────────────────┐                           │
│  │ backend (Spring Boot 3)     │  仅内网 :8080（不 publish）│
│  └──────────────┬──────────────┘                           │
│                 ▼ JDBC                                     │
│  ┌─────────────────────────────┐                           │
│  │ postgres 16                 │  宿主 127.0.0.1:15435      │
│  └─────────────────────────────┘                           │
└────────────────────────────────────────────────────────────┘
```

| 服务 | 镜像 | 容器端口 | 宿主 | 对外 |
|---|---|---|---|---|
| `web` | `erp-web:<tag>` | 80 | `${WEB_BIND_IP}:${WEB_PORT}`（默认 `0.0.0.0:18080`） | 是（或只给宿主 nginx） |
| `backend` | `erp-backend:<tag>` | 8080 | 不发布 | 否 |
| `postgres` | `postgres:16-alpine` | 5432 | `127.0.0.1:15435` | 否 |

**为什么没有 Redis / RabbitMQ**：后端代码零引用（`grep -rniE "redis|rabbit|amqp"` 只命中测试数据源），
放了只会多两个故障点。**为什么没有 Prometheus/Grafana**：没有指标端点、2C4G 没有资源预算；
M6 用 `/readyz` 拨测 + Docker 日志 + `docker stats` 覆盖。

**关键文件**：

| 文件 | 作用 |
|---|---|
| `infra/deploy/docker-compose.prod.yml` | 生产编排（三服务） |
| `infra/deploy/.env.example` | 变量模板（真实 `.env` 不入库） |
| `infra/deploy/.deploy-state` | 当前/上一版 tag（脚本维护，不入库） |
| `infra/deploy/backups/` | 数据库备份（不入库） |
| `backend/server/Dockerfile` | 后端多阶段镜像 |
| `frontend/web/Dockerfile` + `nginx.conf` | 前端镜像 + 反代/缓存/SPA 配置 |
| `backend/server/src/main/resources/application-prod.yml` | 生产 profile（只读 env） |

---

## 2. 服务器初始化（一次性）

### 2.1 规格与网络

- 建议：**2 vCPU / 4 GB RAM / 40 GB SSD**，Ubuntu 22.04 LTS 或 24.04 LTS。
- 安全组/防火墙：
  - 必需：`22/tcp`（SSH）；
  - 备案后：`80/tcp`、`443/tcp`；
  - 备案前用 IP 演示：临时开 `18080/tcp`，切域名后关闭；
  - **不要**开 `8080`、`15435`。
- 确认时间同步：`timedatectl` 显示 `System clock synchronized: yes`（JWT 过期校验依赖时钟）。

### 2.2 安装 Docker Engine + Compose 插件

```bash
# 官方脚本（需要能访问 get.docker.com；国内网络可改用云厂商的 Docker 镜像源/预装镜像）
curl -fsSL https://get.docker.com | sudo sh

sudo systemctl enable --now docker
docker version
docker compose version      # 必须能看到 v2.x
```

把部署用户加入 docker 组（重新登录生效）：

```bash
sudo usermod -aG docker "$USER"
```

> 如果 `docker compose` 报 `unknown command`：安装的是老版 `docker-compose`，
> 本文所有命令都要求 **Compose v2 插件**（`docker compose`，不是 `docker-compose`）。

### 2.3 获取代码

```bash
sudo mkdir -p /opt/erp
sudo chown "$USER":"$USER" /opt/erp
git clone <repo-url> /opt/erp
cd /opt/erp
git log --oneline -1        # 记录部署的 commit
```

私有仓库用 SSH deploy key 或只读 token；**不要把 token 写进 URL 或提交到 Git**。

### 2.4 目录与权限

```bash
cd /opt/erp
mkdir -p infra/deploy/backups
chmod 700 infra/deploy/backups
```

`.env` 与备份文件都应是 `600`；只有部署用户可读。

---

## 3. 配置 `.env`（密钥只在这里）

```bash
cd /opt/erp
cp infra/deploy/.env.example infra/deploy/.env
chmod 600 infra/deploy/.env

# 生成两个强随机值（不要用示例里的占位值）
PW=$(openssl rand -base64 32 | tr -d '\n')
JWT=$(openssl rand -base64 48 | tr -d '\n')
sed -i "s|^POSTGRES_PASSWORD=.*|POSTGRES_PASSWORD=$PW|" infra/deploy/.env
sed -i "s|^ERP_JWT_SECRET=.*|ERP_JWT_SECRET=$JWT|" infra/deploy/.env

# 按需改绑定（首次用 IP 演示保持 0.0.0.0；接宿主 nginx 时改 127.0.0.1）
grep -E '^(POSTGRES_|ERP_JWT|WEB_|PG_HOST|TZ|IMAGE_TAG)' infra/deploy/.env | sed 's/=.*/=<hidden>/'
```

| 变量 | 必填 | 说明 |
|---|---|---|
| `POSTGRES_PASSWORD` | 是 | 数据库密码；部署脚本拒绝 `change-me`/`erp` 等弱值 |
| `ERP_JWT_SECRET` | 是 | ≥32 字节；更换后所有旧 token 立即失效 |
| `POSTGRES_DB` / `POSTGRES_USER` | 否 | 默认 `erp` / `erp` |
| `WEB_BIND_IP` | 否 | 默认 `0.0.0.0`；接宿主 nginx 时改 `127.0.0.1` |
| `WEB_PORT` | 否 | 默认 `18080` |
| `PG_HOST_PORT` | 否 | 默认 `15435`，只绑 `127.0.0.1` |
| `IMAGE_TAG` | 否 | 部署脚本会覆盖为 `<git短SHA>-<时间>` |
| `BACKUP_RETENTION_DAYS` | 否 | 默认 14 |
| `TZ` | 否 | 默认 `Asia/Shanghai` |
| `JAVA_OPTS` | 否 | 默认按容器内存 75% 设堆；一般不用改 |
| `BACKEND_BUILD_IMAGE` / `BACKEND_RUNTIME_IMAGE` / `WEB_BUILD_IMAGE` / `WEB_RUNTIME_IMAGE` | 否 | Docker Hub 不可达时换内网镜像/镜像站 |

**三个失败关闭**：

1. compose 的 `${POSTGRES_PASSWORD:?}` / `${ERP_JWT_SECRET:?}` 缺值直接拒绝启动；
2. `application-prod.yml` 对 `DB_URL`/`DB_USER`/`DB_PASSWORD`/`ERP_JWT_SECRET` **不写默认值**，缺 env 时 Spring 启动失败；
3. `deploy.sh` / `deploy.ps1` 在构建前拒绝占位/弱值。

> **`.env` 永远不要提交**。`.gitignore` 已忽略 `.env`、`.env.*`（`.env.example` 除外）、`*.pem`、`secrets/`。

---

## 4. 首次部署

### 4.1 构建并启动

```bash
cd /opt/erp
./scripts/deploy.sh
```

脚本按顺序做：

1. 校验 `.env` 与 Docker；
2. 生成 tag（`<git短SHA>-<yyyyMMdd-HHmmss>`）；
3. `docker compose build backend web`（首次会拉基础镜像 + Gradle/npm 依赖，5~15 分钟取决于网络）；
4. `docker compose up -d --remove-orphans --no-build`；
5. 轮询 `http://127.0.0.1:18080/readyz`（backend readiness 包含 DB），最多 180s；
6. 成功写 `infra/deploy/.deploy-state`（当前/上一版）；
7. **失败默认自动回滚**到上一版（首次没有上一版则保留现场并打印日志）。

首次成功输出示例：

```
=== 部署成功 ===
  当前版本：5e8a74d-20261023-163012
  上一版本：（无）
  入口：http://127.0.0.1:18080/
  就绪探针：http://127.0.0.1:18080/readyz
```

**如果 Docker Hub 超时**：

- 给 Docker 配国内 registry mirror（`/etc/docker/daemon.json` 的 `registry-mirrors`，改完 `systemctl reload docker`）；
- 或给基础镜像换可达地址：
  ```bash
  BACKEND_BUILD_IMAGE=<mirror>/eclipse-temurin:17-jdk-alpine \
  BACKEND_RUNTIME_IMAGE=<mirror>/eclipse-temurin:17-jre-alpine \
  WEB_BUILD_IMAGE=<mirror>/node:22-alpine \
  WEB_RUNTIME_IMAGE=<mirror>/nginx:1.27-alpine \
  ./scripts/deploy.sh
  ```
  （这些变量也可以写进 `.env`。）
- 本仓库的 `infra/deploy/offline/` 是**本机离线验证**的垫片，**不要**在服务器上用它冒充生产镜像。

### 4.2 创建管理员账号

V3 迁移刻意不预置账号（避免弱口令进版本库）。首次部署后显式创建：

```bash
cd /opt/erp
ADMIN_PASSWORD='换成你的强密码' ./scripts/seed-admin.sh
```

- 默认用户名 `admin`、角色 `ADMIN`、显示名「管理员」；
- 用 PostgreSQL `pgcrypto` 生成 BCrypt（`$2a$10$`），与后端 `BCryptPasswordEncoder` 兼容；
- **重复执行 = 重置该账号密码**，并清空失败次数/锁定；
- 可用 `ADMIN_USERNAME`、`ADMIN_ROLE`、`ADMIN_DISPLAY_NAME` 覆盖；
- 密码通过环境变量传入，不出现在命令历史里（但会短暂出现在容器进程表，创建完即结束）。

验证登录：

```bash
curl -sS -X POST http://127.0.0.1:18080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"<你设置的密码>"}'
# 期望：{"code":"OK",...,"data":{"token":"...","roles":["ADMIN"],...}}
```

### 4.3 首次验收清单

```bash
curl -sS -o /dev/null -w '%{http_code}\n' http://127.0.0.1:18080/healthz   # 200
curl -sS http://127.0.0.1:18080/readyz                                     # {"status":"UP"}
curl -sS -o /dev/null -w '%{http_code}\n' http://127.0.0.1:18080/purchase-orders  # 200（SPA fallback）
curl -sS -o /dev/null -w '%{http_code}\n' http://127.0.0.1:18080/api/v1/inventory # 401（未登录）
```

再用浏览器或 `curl` 完成：登录 → 库存查询 → 新建一张进货单。
完整业务链路（登录/建主数据/建进货单/入库/查库存）见 `.plan/M6-计划书.md` §7.3 的实测记录。

### 4.4 关于 V2 迁移里的演示主数据

`V2__mdm_and_documents.sql` 会插入少量演示主数据（供应商/客户/仓库/商品/SKU），
方便新环境一打开就有数据可点。**账号不在迁移里**。

正式对外营业前，请先决定这些演示数据是保留还是清理：

```bash
docker exec -it erp-prod-postgres psql -U erp -d erp -c "
SELECT 'suppliers' t, count(*) FROM suppliers UNION ALL
SELECT 'customers', count(*) FROM customers UNION ALL
SELECT 'warehouses', count(*) FROM warehouses UNION ALL
SELECT 'products', count(*) FROM products UNION ALL
SELECT 'skus', count(*) FROM skus;"
```

清理要按外键依赖顺序、且先备份；本手册**不提供**一键清空脚本（避免误删真实数据）。
如果希望从空库开始，建议在正式录入前删除演示数据并重新备份，而不是上线后再清。

---

## 5. 日常发布与回滚

### 5.1 发布新版本

```bash
cd /opt/erp
git fetch --tags
git checkout <新版本tag或commit>
./scripts/deploy.sh
```

- 每次部署生成新 tag；`.deploy-state` 记录 current/previous；
- 健康检查失败默认自动回滚到上一版；
- 单实例重建期间会有**数秒**的 502/503（backend/web 容器重建），课程/单店场景可接受；
  需要零中断时应改成滚动多实例 + 负载均衡（超出 M6 范围）。
- 发布前建议先备份：`./scripts/backup-db.sh`。

### 5.2 回滚

```bash
cd /opt/erp
./scripts/rollback.sh                 # 回到 .deploy-state 的 PREVIOUS_TAG
./scripts/rollback.sh <tag>           # 回到指定版本
```

> **镜像回滚 ≠ 数据库回滚**。Flyway 只向前迁移；如果本次发布包含不兼容迁移，
> 必须先按 §8.2 从备份恢复数据库，再回滚镜像。M6 未新增迁移，但以后要记住这条。

### 5.3 镜像保留与磁盘

- 每次部署保留两个 tag（current / previous）；旧 tag 不会自动删除；
- 查看：`docker image ls | grep -E 'erp-(backend|web)'`；
- 清理（**只删不在 current/previous 里的旧 tag**）：
  ```bash
  docker image ls --format '{{.Repository}}:{{.Tag}}' \
    | grep -E '^erp-(backend|web):' \
    | grep -v -E ":($(sed -n 's/^CURRENT_TAG=//p' infra/deploy/.deploy-state)|$(sed -n 's/^PREVIOUS_TAG=//p' infra/deploy/.deploy-state))$" \
    | xargs -r docker image rm
  ```
  删除前确认没有容器在用；`docker image rm` 失败通常是因为还被引用。

---

## 6. 备份、恢复与演练

### 6.1 备份

```bash
cd /opt/erp
./scripts/backup-db.sh
# 产物：infra/deploy/backups/erp-<UTC时间戳>Z.dump（自定义格式，已压缩）
```

- 脚本会用 `pg_dump -Fc` 导出，并用 `pg_restore -l` 验证 dump 可读且含表数据；
- 保留天数由 `BACKUP_RETENTION_DAYS` 控制（默认 14）；
- 文件名用 **UTC**（`...Z`），避免服务器时区差异造成误读。

定时任务（每天 03:00，服务器本地时区）：

```bash
crontab -e
# 加入：
0 3 * * * cd /opt/erp && ./scripts/backup-db.sh >> /var/log/erp-backup.log 2>&1
```

异地副本（至少一种）：

```bash
# 例：同步到另一台机器/对象存储（需要先配置免密）
rsync -av /opt/erp/infra/deploy/backups/ backup-host:/backup/erp/
# 或 rclone copy /opt/erp/infra/deploy/backups/ remote:erp-backup/
```

备份文件包含全部业务数据与账号哈希，按敏感数据管理：目录 `700`、文件 `600`、传输加密。

### 6.2 恢复（真实恢复）

> 先确认：备份文件时间、目标库、当前是否有人在用；**恢复前再备份一次当前库**。

```bash
cd /opt/erp
./scripts/backup-db.sh                 # 先保底

# 1) 停应用，避免写入
docker compose --env-file infra/deploy/.env -f infra/deploy/docker-compose.prod.yml stop backend web

# 2) 用备份覆盖现有 schema（--clean 会先删对象；--no-owner 避免角色不存在）
BACKUP=infra/deploy/backups/erp-YYYYMMDDTHHMMSSZ.dump
docker exec -i erp-prod-postgres pg_restore -U erp -d erp \
  --clean --if-exists --no-owner --no-acl < "$BACKUP"

# 3) 起应用；Flyway 会校验/补齐迁移
./scripts/deploy.sh --tag "$(sed -n 's/^CURRENT_TAG=//p' infra/deploy/.deploy-state)" --no-build

# 4) 验收
curl -fsS http://127.0.0.1:18080/readyz
```

恢复后抽查：

```bash
docker exec erp-prod-postgres psql -U erp -d erp -c "
SELECT 'purchase_orders' t, count(*) FROM purchase_orders UNION ALL
SELECT 'inventory_ledger', count(*) FROM inventory_ledger UNION ALL
SELECT 'app_users', count(*) FROM app_users;"
```

### 6.3 恢复演练（不动生产库）

```bash
BACKUP=infra/deploy/backups/erp-YYYYMMDDTHHMMSSZ.dump
docker exec erp-prod-postgres psql -U erp -d postgres \
  -c "DROP DATABASE IF EXISTS erp_restore_test;" \
  -c "CREATE DATABASE erp_restore_test;"
docker exec -i erp-prod-postgres pg_restore -U erp -d erp_restore_test \
  --no-owner --no-acl < "$BACKUP"
docker exec erp-prod-postgres psql -U erp -d erp_restore_test \
  -c "SELECT count(*) FROM purchase_orders;"
docker exec erp-prod-postgres psql -U erp -d postgres \
  -c "DROP DATABASE erp_restore_test;"
```

建议每月做一次，并记录耗时（RTO 参考值）。

### 6.4 RPO / RTO

- **RPO**：每日备份 = 最坏丢 1 天（若改 cron 频率可缩短）；
- **RTO**：单机恢复约数分钟（取决于 dump 大小与磁盘）；
- 单机没有高可用：PG/主机故障期间服务中断，这是课程范围内的取舍。

---

## 7. HTTPS（备案通过后）

### 7.1 前置

1. 域名 **备案已通过**（大陆服务器上，未备案域名不能对外提供 80/443 服务）；
2. DNS A 记录指向服务器公网 IP，`dig +short erp.example.com` 返回该 IP；
3. 安全组放行 80/443；
4. `.env` 里 `WEB_BIND_IP=127.0.0.1`（容器 web 只给宿主 nginx）。

### 7.2 宿主 nginx + Let's Encrypt

```bash
sudo apt update
sudo apt install -y nginx certbot python3-certbot-nginx

sudo tee /etc/nginx/sites-available/erp >/dev/null <<'EOF'
server {
    listen 80;
    server_name erp.example.com;

    client_max_body_size 10m;

    location / {
        proxy_pass http://127.0.0.1:18080;
        proxy_http_version 1.1;
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 60s;
    }
}
EOF

sudo ln -sf /etc/nginx/sites-available/erp /etc/nginx/sites-enabled/erp
sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t
sudo systemctl reload nginx

# 申请证书并让 certbot 改成 443 + HTTP→HTTPS
sudo certbot --nginx -d erp.example.com --redirect
```

验证与续期：

```bash
curl -I https://erp.example.com/            # 200，证书有效
curl -I http://erp.example.com/             # 301/308 到 https
sudo systemctl list-timers | grep certbot   # 自动续期
sudo certbot renew --dry-run
```

> 容器内 nginx 已用 `set/if` 透传上游 `X-Forwarded-Proto`；宿主 nginx 传 `$scheme`，
> 后端能正确识别 https。若以后再加一层 CDN，请确保该头不被覆盖。

### 7.3 备案前的 IP 访问

- `.env`：`WEB_BIND_IP=0.0.0.0`、`WEB_PORT=18080`；
- 安全组临时开 18080；
- 只能 `http://<IP>:18080`，没有 HTTPS（没有域名无法签 Let's Encrypt）；
- **不要**在未备案时把域名解析到大陆服务器对外提供网站服务。

### 7.4 域名切换检查清单

- [ ] 备案通过，域名 A 记录指向服务器
- [ ] `.env` 改 `WEB_BIND_IP=127.0.0.1`，重新部署/重建 web
- [ ] 宿主 nginx `server_name` 与证书域名一致
- [ ] `curl -I https://域名` 200，证书链完整
- [ ] HTTP 自动跳 HTTPS
- [ ] 安全组关闭 18080（如不再需要）
- [ ] 登录 → 库存 → 建进货单 复测
- [ ] 外部拨测指向 `https://域名/` 或 `/readyz`
- [ ] 证书续期 dry-run 通过

> HSTS 建议在 HTTPS 稳定运行一段时间后再加，避免证书/域名问题把自己锁死。

---

## 8. 日志、监控与磁盘

```bash
cd /opt/erp
# 应用日志（json-file，单服务 20MB × 5 自动轮转）
docker compose --env-file infra/deploy/.env -f infra/deploy/docker-compose.prod.yml logs -f --tail=200 backend
docker compose --env-file infra/deploy/.env -f infra/deploy/docker-compose.prod.yml logs --tail=100 web
# 宿主 nginx
sudo journalctl -u nginx -n 100 --no-pager
tail -f /var/log/nginx/access.log

# 资源
docker stats --no-stream
df -h
docker system df
```

- **外部拨测**：`https://域名/readyz`（期望 200 + `{"status":"UP"}`）；
  `/healthz` 只代表 web 进程活着，不能代表业务可用。
- **容器健康**：`docker compose ps` 三个服务都应为 `healthy`；
- **PG 连接/慢查询**（按需）：
  ```bash
  docker exec -it erp-prod-postgres psql -U erp -d erp -c "
  SELECT state, count(*) FROM pg_stat_activity GROUP BY state;
  SELECT pid, now()-query_start AS dur, left(query,80) FROM pg_stat_activity
   WHERE state='active' ORDER BY dur DESC LIMIT 5;"
  ```
- 没有 Prometheus/Grafana：课程范围内用上述命令 + 外部拨测即可；
  若以后加指标，需在 backend 引入 `micrometer-registry-prometheus` 并只对内网暴露 `/actuator/prometheus`。

**磁盘告警线**：使用率 > 80% 时先查 `docker system df` 与 `du -sh infra/deploy/backups`，
按 §5.3 清理旧镜像、按保留策略清理备份，不要直接 `docker system prune -a`（会删掉回滚镜像）。

---

## 9. 常见故障

| 现象 | 可能原因 | 处理 |
|---|---|---|
| `/readyz` 502/504 | backend 未起/正在启动/DB 不可用 | `docker compose ps`；`logs backend`；`logs postgres`；等 30~60s |
| web 容器反复重启 | nginx 配置错误（`server`/`map` 不允许等） | `docker compose logs web`；在容器网络内 `nginx -t`；确认使用的是官方 nginx 基础镜像 |
| backend 启动即退出 | `.env` 缺 `DB_URL`/`DB_USER`/`DB_PASSWORD`/`ERP_JWT_SECRET`；JWT <32 字节；DB 密码错 | `docker compose logs backend`；核对 `.env`；见下一行 |
| `password authentication failed` | PG 卷已用旧密码初始化，改 `.env` 不会改数据库用户密码 | 容器内本地 socket 执行 `ALTER USER erp WITH PASSWORD '<新密码>';`；或（数据可丢时）删卷重建 |
| Flyway `Validate failed` / checksum mismatch | 改了已应用的迁移文件；恢复了旧库 | **不要改历史迁移**；用备份恢复；必要时 `flyway repair`（先备份） |
| `Port is already allocated`（18080/15435） | 端口被别的进程/容器占用 | 改 `.env` 的 `WEB_PORT`/`PG_HOST_PORT`；查 `ss -lntp`；不要杀用户进程 |
| Docker Hub 拉取超时 | 网络不可达 | 配 registry mirror，或用 `*_BUILD_IMAGE`/`*_RUNTIME_IMAGE` 指向内网镜像 |
| `413 Request Entity Too Large` | 上传/请求体超过 nginx 限制 | 调整 `client_max_body_size`（容器与宿主两层） |
| 部署后所有人 401 | `ERP_JWT_SECRET` 变了 | 预期行为；重新登录。不要为了“兼容旧 token”把密钥回滚 |
| 域名 502 但本机 18080 正常 | `WEB_BIND_IP` 仍是 127.0.0.1 且宿主 nginx 未配；或安全组/防火墙 | 检查 `curl 127.0.0.1:18080`、`nginx -t`、`ufw status` |
| 备份脚本 `permission denied` | 备份目录属主不对 | `chown -R $USER:$USER infra/deploy/backups`；`chmod 700` |
| 磁盘满 | 镜像/备份/日志堆积 | `docker system df`；按 §5.3 清理旧 tag；清理过期备份；检查 json-file 日志 |
| 时间不对导致 token 立即过期 | 服务器时钟漂移 | `timedatectl`；启用 NTP；确认 `TZ` 与 JVM `-Duser.timezone` |
| 本地 `npm run build` 报 esbuild `Access is denied` | Windows 临时文件删除被占用（本机已知） | 用生产 Dockerfile 在 Linux 构建（§4.1），或把 `TEMP/TMP` 指到短路径后重试；见 `.plan/M6-计划书.md` §8 |

**排障顺序**（不要跳）：

1. `docker compose ps` —— 谁不健康；
2. `docker compose logs --tail=200 <service>` —— 第一个 ERROR；
3. `curl 127.0.0.1:18080/readyz` —— 区分 web/backend/DB；
4. `docker exec ... pg_isready`、`psql -c 'select 1'`；
5. 资源：`docker stats`、`df -h`；
6. 最后才怀疑代码；多数事故是 env/端口/磁盘/网络。

---

## 10. 安全基线

- `.env`、备份、SSH 私钥：`600`，不进 Git；提交前 `git status` 检查；
- 公网只开 22/80/443（备案前临时 18080）；PG/backend 不 publish；
- 生产 profile 关闭 Swagger/OpenAPI（`springdoc.*.enabled=false`）；actuator 只暴露 health/info 且 `show-details=never`；
- 管理员强密码；`seed-admin.sh` 重置密码后确认旧密码失效；
- 登录已有 5 次失败锁定 15 分钟（`erp.iam.max-failed-attempts` / `lock-minutes`）；
- SSH 只允许密钥登录，禁用 root 密码登录（`/etc/ssh/sshd_config`）；可选 fail2ban；
- 系统与镜像定期更新：`apt upgrade`、`docker compose pull postgres` 后重新部署并验收；
- 不在服务器上保存仓库以外的明文凭据；如使用 CI，密钥放 GitHub Secrets/环境变量，不写 workflow 文件。

---

## 11. 文件索引

| 路径 | 说明 |
|---|---|
| `scripts/deploy.sh` / `.ps1` | 构建、启动、健康检查、记录版本、失败自动回滚 |
| `scripts/rollback.sh` / `.ps1` | 按 tag 回滚（默认上一版） |
| `scripts/backup-db.sh` / `.ps1` | `pg_dump -Fc` + 可读性校验 + 保留策略 |
| `scripts/seed-admin.sh` | 用 pgcrypto 创建/重置管理员 |
| `scripts/lib/deploy-common.sh` | shell 脚本共用函数（路径/env/健康检查） |
| `scripts/build-local-images.sh` | **仅本机离线**重建生产镜像（Docker Hub 不可达时） |
| `infra/deploy/docker-compose.prod.yml` | 生产编排 |
| `infra/deploy/.env.example` | 变量模板 |
| `infra/deploy/offline/` | 离线验证垫片与复现说明（非生产） |
| `backend/server/Dockerfile` | 后端多阶段镜像 |
| `frontend/web/Dockerfile` | 前端多阶段镜像 |
| `frontend/web/nginx.conf` | SPA fallback / `/api` 反代 / gzip / 缓存 / 健康检查 |
| `backend/server/src/main/resources/application-prod.yml` | 生产 profile |

---

## 12. 交接：真实部署还缺什么

M6 本次交付的是「可部署制品 + 本地全栈验证 + 本手册」，**没有**把服务发到公网。真正上线还差：

| 缺口 | 需要谁 | 说明 |
|---|---|---|
| 服务器 SSH 主机/用户/密钥 | 用户 | 仓库里没有凭据；需用户提供并单独授权 |
| 服务器规格与系统 | 用户 | 建议 2C4G/40GB，Ubuntu 22.04/24.04；确认 Docker 可用 |
| 备案状态 | 用户 | 备案通过前只能 IP:18080 演示，不能绑域名对外 |
| 域名与 DNS 管理权限 | 用户 | A 记录指向服务器公网 IP |
| Let's Encrypt 邮箱/证书 | 用户 | 备案 + DNS 就绪后执行 §7 |
| 安全组/防火墙变更 | 用户/云控制台 | 放行 22/80/443（备案前临时 18080） |
| 是否保留 V2 演示主数据 | 用户/业务 | 见 §4.4；上线前决定 |

**建议的下一步**（有凭据后）：

1. 按 §2 初始化服务器，§3 配 `.env`；
2. `./scripts/deploy.sh` → §4.3 验收 → `./scripts/seed-admin.sh`；
3. 先用 `http://<IP>:18080` 做一次真实浏览器验收（登录/库存/进货单）；
4. 备案通过后按 §7 配 HTTPS、切域名，再跑 §7.4 清单；
5. 配好 §6.1 的每日备份与异地副本，做一次 §6.3 恢复演练；
6. 把本次部署的 commit、tag、`.deploy-state`、备份路径记录到交付说明里。
