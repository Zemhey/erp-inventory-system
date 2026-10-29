# 云裳进销存 · 服务器完整部署教程（公网可访问）

> 这份文件是给**刚下载这个压缩包的人**看的入口，目标：只看本文能把整套系统部署到一台
> Linux 服务器上并对外提供 HTTPS 访问。仓库里还有一份更细的运维手册
> [`infra/deploy/README.md`](infra/deploy/README.md)（发布、回滚、备份、排障），两者配合用。
> 只想在自己电脑上跑一份、不给别人访问的，看 `README-START-standalone.md`。

---

## 这套东西长什么样

```
浏览器 ──443──> 宿主 nginx（按域名分流）
                  ├─ 静态前端产物  →  容器 erp-prod-web:18080（内含 nginx）
                  └─ /api/v1、/readyz ──> 同一个 18080 ──> 后端 Spring Boot:8080（不对外）
                                                            └─> PostgreSQL:5432（容器网络内，宿主只绑 127.0.0.1:15435）
```

**只有 22 / 80 / 443 对公网开放**，`18080`、`8080`、`15435` 一律只绑回环或留在容器网络里。

---

## 你需要什么

| 项 | 要求 |
|---|---|
| 服务器 | 2 核 4 GB 起步。2 GB 内存也能跑，但必须用 `docker-compose.2g.yml` 覆盖层并加 swap |
| 系统 | 任意 Linux + Docker Engine 24+ 与 `docker compose` v2（Alibaba Cloud Linux 4 的官方装法是 `dnf install -y moby docker-compose-plugin`） |
| 域名 | 一个已解析到服务器公网 IP 的域名（子域名即可） |
| **备案** | 在中国大陆节点的 80/443 上跑域名，**必须完成 ICP 备案且备案接入商是本机所在云厂商**。没备案时 80 会返回拦截页、443 会被直接 reset |
| 磁盘 | 10 GB 起（镜像约 2 GB + 数据库 + 备份） |

> 备案是**以周计的行政流程**，不能靠工程手段压缩。先启动它，再动别的。
> 另外「备案通过」的短信 ≠ 立刻生效：同步到接入商要数小时到 24 小时，期间照样被拦。

---

## 部署步骤

### 1) 放代码

把本压缩包解压到服务器，例如 `/opt/erp`。

### 2) 配环境变量

```bash
cd /opt/erp
cp infra/deploy/.env.example infra/deploy/.env
```

必须改的三项（**不要用示例值**）：

| 键 | 怎么生成 |
|---|---|
| `POSTGRES_PASSWORD` | `openssl rand -base64 24` |
| `ERP_JWT_SECRET` | `openssl rand -base64 48` |
| `IMAGE_TAG` | 自己定一个版本标记，例如 `prod-20261029-v1` |

其余键：`POSTGRES_DB`、`POSTGRES_USER`、`WEB_BIND_IP`（保持 `127.0.0.1`）、`WEB_PORT`（默认 18080）、
`PG_HOST_PORT`（默认 15435）、`TZ`、`BACKUP_RETENTION_DAYS`。

```bash
chmod 600 infra/deploy/.env     # 里面有口令，别留成全局可读
```

### 3) 构建并发布

```bash
./scripts/deploy.sh                      # tag 默认 <git短SHA>-<时间>
./scripts/deploy.sh --tag prod-20261029  # 或者指定 tag
```

脚本会构建后端与前端镜像、起 compose、跑健康检查，**失败自动回滚到上一个可用版本**
（不想自动回退加 `--no-auto-rollback` 保留现场）。

小内存机器（2 GB）多带一层覆盖：

```bash
docker compose --env-file infra/deploy/.env \
  -f infra/deploy/docker-compose.prod.yml -f infra/deploy/docker-compose.2g.yml up -d
```

> ⚠️ **不要在 2 GB 内存的机器上构建镜像**，会 OOM。正确做法是在本地构建、`docker save` 成 tar、
> 传到服务器 `docker load`，再 `./scripts/deploy.sh --no-build`。
> ⚠️ compose 里前后端**共用同一个 `IMAGE_TAG`**：只重建其中一个时，另一个也要打同名 tag，
> 否则 `up` 会报 `No such image`，而 `/readyz` 还是 UP —— 这种失败是静默的。

### 4) 建管理员、灌演示数据

```bash
ADMIN_PASSWORD='<强口令>' ./scripts/seed-admin.sh
ERP_API=http://127.0.0.1:18080/api/v1 ERP_USER=admin ERP_PASS='<强口令>' node scripts/seed-demo.mjs
```

演示数据（商品 / 库存流水 / 单据 / 凭证）全部是**虚构**的，不含任何真实客户或员工信息。

### 5) 配宿主 nginx（域名 → 18080）

`infra/deploy/nginx-host/` 里是**线上真实生效**的三份配置，照着落：

| 文件 | 干什么 |
|---|---|
| `zz-xuliu.conf` | 桌面网页版：`/api/`、`/readyz` 反代到 18080，管理面 404，登录限流 `limit_req_zone` |
| `zz-erp-h5.conf` | 移动端 H5 站点：静态产物 + 同源 `/api/` 反代；HTML `no-cache`，带 hash 的资源 30 天 |
| `lancang-demo.conf` | 纯静态演示站示例 |

**文件名一定要带 `zz-` 前缀**：`conf.d/*.conf` 按字母序加载，nginx 取第一个 server 块作为该端口的
默认站点。排在主页配置前面，会把"用 IP 直接访问"的默认站点抢走。

```bash
sudo cp infra/deploy/nginx-host/zz-*.conf /etc/nginx/conf.d/
sudo nginx -t && sudo systemctl reload nginx
```

### 6) 上 HTTPS

```bash
nohup /root/.acme.sh/acme.sh --issue -d 你的域名 -w /var/www/letsencrypt > /tmp/acme.log 2>&1 &
tail -f /tmp/acme.log          # 看到 Success 再往下

/root/.acme.sh/acme.sh --install-cert -d 你的域名 --ecc \
  --key-file /etc/nginx/cert/你的域名.key \
  --fullchain-file /etc/nginx/cert/你的域名.pem --reloadcmd "nginx -t && systemctl reload nginx"
```

要点：
- 站点 80 端口里必须先留 `location ^~ /.well-known/acme-challenge/ { root /var/www/letsencrypt; }`，
  而且它**不能被 301 跳转吃掉**（HTTP-01 校验走的是明文 80）；
- 已有 `acme.sh --cron` 的话，新域名会被自动带进续期，不用另加 cron；
- 没配证书的域名走 443 会落到默认站点、**出示别的域名的证书**，浏览器和 App 都会判证书错误 ——
  这正是"页面白屏但服务器一切正常"的经典成因。

### 7) 安全组 / 防火墙

放行 `22`（建议收紧到你的出口 IP）、`80`、`443`。**不要**放行 8080 / 15435 / 18080。
Linux 机器上的 3389（RDP）规则是遗留，删掉。

---

## 日常运维

| 我要…… | 命令 |
|---|---|
| 发布新版本 | `./scripts/deploy.sh`（或换 tag 后重跑） |
| 回滚上一版 | `./scripts/rollback.sh` |
| 备份数据库 | `./scripts/backup-db.sh`（产物在 `infra/deploy/backups/erp-*.dump`，按 `BACKUP_RETENTION_DAYS` 保留） |
| 每天自动备份 | `crontab -e` 加 `0 3 * * * cd /opt/erp && ./scripts/backup-db.sh >> /var/log/erp-backup.log 2>&1` |
| 恢复演练 | `pg_restore --clean --if-exists -d <测试库> <dump>`，抽查行数后再删测试库（**别直接恢复到生产库**） |
| 看是否可用 | `curl -fsS http://127.0.0.1:18080/readyz` → `{"status":"UP"}` |
| 看内存有没有被吃光 | `free -m`、`docker stats --no-stream`、`dmesg -T \| grep -i oom` |

备份脚本自带可读性校验（`pg_restore -l` 能列出目录且含表数据才算成功）。注意校验时
**不要把 `pg_restore -l` 直接管道给 `grep -q`**：`grep -q` 命中即退会让上游收到 SIGPIPE，
在 `set -o pipefail` 下会把一份完全正常的备份判成失败。

---

## 部署完之后别忘了

- **页脚挂 ICP 备案号**（网页端已内置，改 `frontend/web` 里的备案号文案即可），并按当地要求在
  公安备案平台补交网站信息；
- 演示账号权限要收窄：公网访客账号不要给 `iam:read` / `sys:read` / `fin:read`，
  本仓库用一个只含四个读码的 `DEMO_VIEWER` 角色承载它；
- 移动端网页版（`m.域名`）与安卓 App 用的是同一套界面产物，改了 uni-app 记得三端一起重出。

## 已知限制

- 本教程里的每一步都在真实服务器上做过（2C2G 阿里云 + Alibaba Cloud Linux 4 + 已备案域名），
  但**换一家云厂商/另一个发行版时，备案拦截行为与 Docker 安装方式会不同**；
- 2 GB 内存是这套系统的下限，压测下可用余量约 800 MB，再往上加服务要先升配；
- 单机版与服务器版共用同一套后端/前端镜像，但**没有做过"同一份 compose 一键从单机迁到服务器"的验证**，
  迁移请按本文件重新配置环境变量与 nginx。
