# 离线镜像构建（仅本机验证，不是生产部署）

> **什么时候用**：本机 Docker Hub / GitHub / npmjs 不可达，但要用**生产 compose**
> 在本机起整套做验收。
>
> **生产环境**请直接用 `scripts/deploy.sh` + 官方基础镜像，不要用本目录的垫片。

---

## 1. 本机约束（2026-10-23 实测）

| 资源 | 状态 |
|---|---|
| Docker Hub `registry-1.docker.io` | ❌ 连接超时 |
| GitHub / nginx.org / registry.npmjs.org | ❌ 连接超时 |
| Maven Aliyun | ✅ 可达 |
| Alpine 镜像站（华为云/清华/中科大/腾讯） | ✅ 可达（华为云最快，约 1 MB/s） |
| npm 镜像 `registry.npmmirror.com` | ✅ 可达 |
| 本地已有 Docker 镜像 | `postgres:16-alpine`（其余基础镜像均无） |
| 宿主 JDK | Windows JDK 17（**不能**直接拷进 Linux 容器） |

因此本机无法 `docker pull eclipse-temurin / node / nginx`。做法是：

1. 以本地 `postgres:16-alpine` 为底座，从 Alpine 镜像站安装
   `openjdk17-jre-headless`、`openjdk17-jdk`、`nginx`、`nodejs`、`npm`，做成
   `erp-offline-base/jdk/node/nginx:local` 四个本地基础镜像；
2. **原样使用生产 Dockerfile**（`backend/server/Dockerfile`、`frontend/web/Dockerfile`），
   只通过 build-arg 替换 `FROM` 的基础镜像与 npm registry；
3. 后端构建阶段的 Gradle wrapper 与依赖来自宿主 `GRADLE_USER_HOME` 缓存（约 518 MB），
   这样构建阶段不需要访问 Gradle/Maven 网络。

> 这样验证的是**同一份 Dockerfile、同一份 nginx.conf、同一个 compose**；
> 差异只有基础镜像的发行版/版本（见 §4）。

---

## 2. 一键构建

前置：本机已跑过一次 Gradle 构建（`gradlew.bat bootJar`），缓存已在
`%GRADLE_USER_HOME%`；Docker 可用。

```bash
cd "D:/MajorCacheFolder/Company system/erp-m6-deploy"   # 或你的 worktree
./scripts/build-local-images.sh
```

脚本会：

1. 构建 `erp-offline-base:local`（Alpine + JRE17 + nginx + tzdata）；
2. 构建 `erp-offline-nginx:local`（最小主配置，把 `conf.d` 放在 `http{}` 内，镜像官方镜像布局）；
3. 构建 `erp-offline-node:local`（node 24 / npm 11）；
4. 构建 `erp-offline-jdk:local`（JDK17 + 宿主 Gradle 缓存）；
5. **用生产 Dockerfile** 构建 `erp-backend:<TAG>` 与 `erp-web:<TAG>`。

常用变量：

```bash
TAG=m6-verify ./scripts/build-local-images.sh      # 自定义 tag
REBUILD_BASE=1 ./scripts/build-local-images.sh     # 基础镜像重建
REBUILD_JDK=1 ./scripts/build-local-images.sh      # 依赖变化后刷新 Gradle 缓存
NPM_REGISTRY=https://registry.npmmirror.com ...    # 默认已是 npmmirror
APK_MIRROR=https://repo.huaweicloud.com ...        # 默认华为云
KEEP_CONTEXT=1 ./scripts/build-local-images.sh     # 保留 .context/jdk 的 518MB 缓存副本
```

---

## 3. 起整套（生产 compose 原样）

```bash
# .env 必须存在；示例里默认 18080 / 15435
cp -n infra/deploy/.env.example infra/deploy/.env
# 改掉 POSTGRES_PASSWORD / ERP_JWT_SECRET（生产脚本会拒绝弱值，本地也建议改）

IMAGE_TAG=local docker compose --env-file infra/deploy/.env \
  -f infra/deploy/docker-compose.prod.yml up -d --no-build --wait --wait-timeout 180

curl -sS http://127.0.0.1:18080/healthz   # ok
curl -sS http://127.0.0.1:18080/readyz    # {"status":"UP"}
```

创建本地管理员并走业务链路：

```bash
ADMIN_PASSWORD='<一个强密码>' ./scripts/seed-admin.sh
# 然后浏览器打开 http://127.0.0.1:18080，或看 .plan/M6-计划书.md §7.3 的 curl 记录
```

停止并**清掉本地测试数据**（会删除 `erp-prod_erp-pgdata` 卷，确认不再需要后再执行）：

```bash
IMAGE_TAG=local docker compose --env-file infra/deploy/.env \
  -f infra/deploy/docker-compose.prod.yml down -v
```

---

## 4. 与生产环境的差异（如实记录）

| 项 | 生产 | 本机离线验证 |
|---|---|---|
| 基础镜像 | `eclipse-temurin:17-*-alpine` / `node:22-alpine` / `nginx:1.27-alpine` | `postgres:16-alpine` + Alpine 包（JRE 17.0.20 / JDK 17 / nginx 1.30.6 / node 24.18.1 / npm 11.12.1） |
| 后端构建 | 构建阶段联网拉 Gradle 发行版与依赖 | 构建阶段使用宿主 Gradle 缓存（wrapper + modules-2） |
| 前端 npm | 默认 `registry.npmjs.org` | `registry.npmmirror.com`（build-arg） |
| nginx 版本 | 1.27.x | 1.30.6 |
| 镜像大小 | 后端约数百 MB、前端更小 | 后端 818 MB、Web 671 MB（底座含 JRE+nginx+node，仅本机用） |
| 适用 | 真实服务器 | 只在本机验证配置与业务链路 |

**没有**验证到的：官方基础镜像的实际拉取、真实服务器网络/磁盘/权限、Let's Encrypt。
这些都在 `infra/deploy/README.md` 中列为交接项。

---

## 5. 实测记录与踩坑

| 事 | 结果/耗时 |
|---|---|
| 基础镜像（JRE+nginx） | 28.6s 构建 + 16.2s 导出 |
| 后端生产 Dockerfile（本地 JDK 缓存） | **106s**（Gradle 编译 32s，7 个 task） |
| 前端生产 Dockerfile（npm ci via npmmirror） | 首次 **39s**；改 CMD 后重建 62s；纯缓存 2~3s |
| 整套 compose `up --wait` | PG healthy + backend healthy + web healthy，约 20~30s |
| 后端 `gradlew check --rerun-tasks --no-build-cache` | **221 tests / 0 failures**，2m24s |
| 备份 | 93 KB（自定义格式，264 TOC entries），恢复到临时库计数一致 |

**踩坑 1：Dockerfile 的 ARG 作用域**
`ARG RUNTIME_IMAGE` / `ARG NGINX_IMAGE` 必须声明在**第一个 `FROM` 之前**（全局 ARG），
否则第二个 `FROM ${...}` 为空，报 `base name should not be blank`。

**踩坑 2：Alpine 包版 nginx 的 `conf.d` 不在 `http{}` 内**
本机底座里 `/etc/nginx/conf.d/*.conf` 被 include 在顶层，导致生产 `nginx.conf` 里的
`server` / `map` 报 `directive is not allowed here`。生产官方 nginx 镜像没有这个问题。
本目录的 `erp-offline-nginx` 用一份最小主配置把 `conf.d` 放进 `http{}`，镜像官方布局；
同时生产 `nginx.conf` 已去掉 `map`，改用 location 内 `set/if`，两种布局都能加载。

**踩坑 3：Windows 宿主 `npm run build` 的 esbuild `Access is denied`**
本机在 M6 期间出现 `vite:esbuild-transpile remove ...: Access is denied`
（主 worktree 与 M6 worktree 都复现；不是本泳道代码改动）。手动删除临时文件可以，
但 esbuild 自己删就失败；判断为 Windows 临时文件句柄/安全软件占用。
本泳道改为**在 Linux 容器里跑生产 Dockerfile 的 `npm run build`**，构建成功；
宿主 `npm run check`（vue-tsc + 状态机镜像 86 项）仍通过。
生产 Dockerfile 不受影响（它本来就在 Linux 里构建）。

**踩坑 4：PowerShell 5.1 读 `.env` 与二进制 dump**
`.ps1` 统一 UTF-8 BOM + CRLF；`backup-db.ps1` 不用 stdout 重定向写二进制，
而是容器内 `pg_dump -f` + `docker cp`，避免 PowerShell 文本编码破坏 dump。
