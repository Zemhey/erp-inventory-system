#!/usr/bin/env bash
# ════════════════════════════════════════════════════════════════════════════
# build-local-images：镜像仓库不可达时，用本机已有镜像重建生产制品
#
# 本脚本**不是**生产部署路径。生产用 scripts/deploy.sh，直接拉取官方基础镜像。
# 它解决的问题：本机 Docker Hub / GitHub / npmjs 不可达，但需要在本机证明
# backend/server/Dockerfile 与 frontend/web/Dockerfile 是真能构建的。
#
# 做法：
#   以本地已有的 postgres:16-alpine 为底座，从可达的 Alpine 镜像站安装
#   JRE/JDK/nginx/node，做成几个本地基础镜像；然后**原样使用生产 Dockerfile**，
#   只替换 FROM 的 base image 与 npm registry。
#
# 前置：
#   - 能访问 Alpine 镜像站（默认华为云，可 APK_MIRROR 覆盖）
#   - 能访问 npm 镜像（默认 npmmirror，可 NPM_REGISTRY 覆盖）
#   - 本机有 Gradle 缓存（GRADLE_USER_HOME，默认 ~/.gradle），否则 JDK 构建阶段
#     无法离线解析依赖；此时请改用有网络的环境跑正式 docker build。
#
# 用法：
#   ./scripts/build-local-images.sh
#   TAG=m6-verify ./scripts/build-local-images.sh
#   REBUILD_BASE=1 REBUILD_JDK=1 ./scripts/build-local-images.sh
# ════════════════════════════════════════════════════════════════════════════
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
OFFLINE_DIR="$ROOT_DIR/infra/deploy/offline"
CONTEXT_DIR="$OFFLINE_DIR/.context"
TAG="${TAG:-local}"
APK_MIRROR="${APK_MIRROR:-https://repo.huaweicloud.com}"
NPM_REGISTRY="${NPM_REGISTRY:-https://registry.npmmirror.com}"
GRADLE_HOME_DIR="${GRADLE_USER_HOME:-$HOME/.gradle}"
# Git Bash 里 GRADLE_USER_HOME 常是 Windows 反斜杠路径；tar -C 需要 POSIX 路径
case "$GRADLE_HOME_DIR" in
  [A-Za-z]:[\\/]*) GRADLE_HOME_DIR="$(cygpath -u "$GRADLE_HOME_DIR" 2>/dev/null || printf '%s' "$GRADLE_HOME_DIR")" ;;
esac

BASE_IMAGE="erp-offline-base:local"
JDK_IMAGE="erp-offline-jdk:local"
NODE_IMAGE="erp-offline-node:local"
NGINX_IMAGE="erp-offline-nginx:local"

if ! docker info >/dev/null 2>&1; then
  echo "✗ Docker 守护进程未运行" >&2
  exit 1
fi

step() { printf '\n--- %s\n' "$*"; }
elapsed() { echo "    耗时: $((SECONDS - $1))s"; }

echo "=== 离线重建生产镜像（本机验证专用） ==="
echo "    TAG=$TAG"
echo "    APK_MIRROR=$APK_MIRROR"
echo "    NPM_REGISTRY=$NPM_REGISTRY"

# ── 1. 基础镜像：Alpine + JRE17 + nginx ───────────────────────────────────
if [ "${REBUILD_BASE:-0}" = "1" ] || ! docker image inspect "$BASE_IMAGE" >/dev/null 2>&1; then
  step "[1/6] 构建 $BASE_IMAGE"
  start=$SECONDS
  docker build -t "$BASE_IMAGE" --build-arg "APK_MIRROR=$APK_MIRROR" \
    -f "$OFFLINE_DIR/Dockerfile.base" "$OFFLINE_DIR"
  elapsed "$start"
else
  step "[1/6] 复用 $BASE_IMAGE（REBUILD_BASE=1 强制重建）"
fi

# ── 2. nginx 运行时：去掉 Alpine 默认 server，避免与生产配置抢 80 ────────
if ! docker image inspect "$NGINX_IMAGE" >/dev/null 2>&1; then
  step "[2/6] 构建 $NGINX_IMAGE"
  start=$SECONDS
  mkdir -p "$CONTEXT_DIR/nginx"
  # 官方 nginx 镜像把 conf.d 放在 http{} 内；Alpine 包默认放在外层。
  # 这里用一份与官方等价的最小主配置，保证生产 frontend/web/nginx.conf
  # （server 块，含 set/if）能原样加载。
  cat > "$CONTEXT_DIR/nginx/nginx-main.conf" <<'EOF'
user nginx;
worker_processes auto;
error_log /var/log/nginx/error.log notice;
events { worker_connections 1024; }
http {
    include /etc/nginx/mime.types;
    default_type application/octet-stream;
    sendfile on;
    keepalive_timeout 65;
    include /etc/nginx/conf.d/*.conf;
}
EOF
  cat > "$CONTEXT_DIR/nginx/Dockerfile" <<'EOF'
FROM erp-offline-base:local
RUN rm -f /etc/nginx/http.d/default.conf && mkdir -p /usr/share/nginx/html
COPY nginx-main.conf /etc/nginx/nginx.conf
RUN nginx -t
ENTRYPOINT []
STOPSIGNAL SIGQUIT
EOF
  docker build -t "$NGINX_IMAGE" "$CONTEXT_DIR/nginx"
  elapsed "$start"
else
  step "[2/6] 复用 $NGINX_IMAGE"
fi

# ── 3. Node 构建阶段：nodejs + npm ────────────────────────────────────────
if ! docker image inspect "$NODE_IMAGE" >/dev/null 2>&1; then
  step "[3/6] 构建 $NODE_IMAGE"
  start=$SECONDS
  mkdir -p "$CONTEXT_DIR/node"
  cat > "$CONTEXT_DIR/node/Dockerfile" <<'EOF'
FROM erp-offline-base:local
RUN apk add --no-cache nodejs npm && node -v && npm -v
ENTRYPOINT []
STOPSIGNAL SIGTERM
EOF
  docker build -t "$NODE_IMAGE" "$CONTEXT_DIR/node"
  elapsed "$start"
else
  step "[3/6] 复用 $NODE_IMAGE"
fi

# ── 4. JDK 构建阶段：JDK17 + 本机 Gradle 缓存（wrapper + 依赖） ──────────
if [ "${REBUILD_JDK:-0}" = "1" ] || ! docker image inspect "$JDK_IMAGE" >/dev/null 2>&1; then
  step "[4/6] 构建 $JDK_IMAGE（含 Gradle 缓存，首次较慢）"
  [ -d "$GRADLE_HOME_DIR/caches/modules-2" ] || {
    echo "✗ 找不到 Gradle 缓存：$GRADLE_HOME_DIR/caches/modules-2" >&2
    echo "  请先在宿主执行一次 gradlew bootJar，或改到有网络的环境构建。" >&2
    exit 1
  }
  start=$SECONDS
  rm -rf "$CONTEXT_DIR/jdk"
  mkdir -p "$CONTEXT_DIR/jdk/gradle-home/caches" "$CONTEXT_DIR/jdk/gradle-home/wrapper"
  # 排除正在被 Gradle daemon 持有的锁文件，否则 tar 会报 Device or resource busy
  tar -C "$GRADLE_HOME_DIR" --exclude='modules-2.lock' -cf - caches/modules-2 \
    | tar -C "$CONTEXT_DIR/jdk/gradle-home" -xf -
  cp -R "$GRADLE_HOME_DIR/wrapper/dists" "$CONTEXT_DIR/jdk/gradle-home/wrapper/"
  cat > "$CONTEXT_DIR/jdk/Dockerfile" <<'EOF'
FROM erp-offline-base:local
RUN apk add --no-cache openjdk17-jdk && javac -version 2>&1
COPY gradle-home /root/.gradle
ENV GRADLE_USER_HOME=/root/.gradle
ENTRYPOINT []
EOF
  docker build -t "$JDK_IMAGE" "$CONTEXT_DIR/jdk"
  elapsed "$start"
  # Gradle 缓存副本有 500MB+，镜像已构建后默认清掉；KEEP_CONTEXT=1 可保留
  if [ "${KEEP_CONTEXT:-0}" != "1" ]; then
    rm -rf "$CONTEXT_DIR/jdk/gradle-home"
  fi
else
  step "[4/6] 复用 $JDK_IMAGE（REBUILD_JDK=1 在依赖变化后重建）"
fi

# ── 5. 后端：原样使用生产多阶段 Dockerfile ────────────────────────────────
step "[5/6] 构建 erp-backend:$TAG（生产 Dockerfile）"
start=$SECONDS
docker build -t "erp-backend:$TAG" \
  --build-arg "BUILD_IMAGE=$JDK_IMAGE" \
  --build-arg "RUNTIME_IMAGE=$BASE_IMAGE" \
  "$ROOT_DIR/backend/server"
elapsed "$start"

# ── 6. 前端：原样使用生产多阶段 Dockerfile ────────────────────────────────
step "[6/6] 构建 erp-web:$TAG（生产 Dockerfile）"
start=$SECONDS
docker build -t "erp-web:$TAG" \
  --build-arg "NODE_IMAGE=$NODE_IMAGE" \
  --build-arg "NGINX_IMAGE=$NGINX_IMAGE" \
  --build-arg "NPM_REGISTRY=$NPM_REGISTRY" \
  "$ROOT_DIR/frontend/web"
elapsed "$start"

echo ""
echo "=== 完成 ==="
docker image ls --format '  {{.Repository}}:{{.Tag}}  {{.Size}}' | grep -E "^(erp-backend|erp-web|erp-offline-)" || true
cat <<EOF

下一步（生产 compose 原样使用，只是不重新 build）：
  IMAGE_TAG=$TAG docker compose --env-file infra/deploy/.env \\
    -f infra/deploy/docker-compose.prod.yml up -d --no-build

生产环境请改用 scripts/deploy.sh，直接拉官方基础镜像。
EOF
