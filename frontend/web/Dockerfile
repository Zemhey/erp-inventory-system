# ════════════════════════════════════════════════════════════════════════════
# 前端镜像（多阶段）
#
# 构建上下文：frontend/web/
#   docker build -t erp-web:local frontend/web
#
# 构建阶段用 Node 产出 dist；运行阶段只留 nginx + 静态文件。
# nginx 配置见同目录 nginx.conf（生产与离线验证共用同一份）。
# ════════════════════════════════════════════════════════════════════════════
ARG NODE_IMAGE=node:22-alpine
ARG NGINX_IMAGE=nginx:1.27-alpine
FROM ${NODE_IMAGE} AS build

WORKDIR /app

# 国内网络可覆盖为 https://registry.npmmirror.com；
# 生产默认仍走官方 registry，避免把镜像源写死。
ARG NPM_REGISTRY=https://registry.npmjs.org

# 依赖层可缓存：package.json / lock 不变就不重复 npm ci
COPY package.json package-lock.json ./
RUN npm ci --registry="$NPM_REGISTRY"

COPY . .
RUN npm run build

# ── 运行阶段 ──────────────────────────────────────────────────────────────
FROM ${NGINX_IMAGE}

# 覆盖官方镜像的默认 server 配置；保留其 nginx.conf 的 user/worker 设置
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80

# 官方 nginx 镜像自带同样的 CMD；这里显式写出，使镜像不依赖基础镜像的默认值
CMD ["nginx", "-g", "daemon off;"]

HEALTHCHECK --interval=15s --timeout=5s --start-period=10s --retries=5 \
    CMD wget -q -O /dev/null http://127.0.0.1/healthz || exit 1
