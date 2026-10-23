#!/usr/bin/env bash
# ============================================================================
# 单机版脚本公共库：路径、.env 生成/加载、compose 包装、健康检查、管理员初始化
#
# 被 start-standalone.sh / stop-standalone.sh / status-standalone.sh /
# backup-standalone.sh source。所有路径都从本文件定位，不依赖调用者 cwd。
# ============================================================================
set -euo pipefail

STANDALONE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_ROOT="$(cd "$STANDALONE_DIR/../.." && pwd)"
COMPOSE_FILE="$STANDALONE_DIR/docker-compose.standalone.yml"
ENV_FILE="$STANDALONE_DIR/.env"
ENV_EXAMPLE="$STANDALONE_DIR/.env.example"
BACKUP_DIR="${BACKUP_DIR:-$STANDALONE_DIR/backups}"
SEED_SQL="$STANDALONE_DIR/seed-admin.sql"
PG_CONTAINER="erp-standalone-postgres"
DATA_VOLUME="erp-standalone-pgdata"

log() { printf '\n=== %s ===\n' "$*"; }
info() { printf '  %s\n' "$*"; }
warn() { printf '  [warn] %s\n' "$*" >&2; }
die() { printf '\n[x] %s\n' "$*" >&2; exit 1; }

require_docker() {
  command -v docker >/dev/null 2>&1 || die "未找到 docker，请先安装 Docker Desktop / Docker Engine"
  docker info >/dev/null 2>&1 || die "Docker 守护进程未运行（Windows 请先启动 Docker Desktop）"
  docker compose version >/dev/null 2>&1 || die "未找到 docker compose 插件（需要 Compose v2）"
}

compose() {
  docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" "$@"
}

random_hex() {
  local bytes="$1"
  if command -v od >/dev/null 2>&1; then
    od -An -tx1 -N "$bytes" /dev/urandom | tr -d ' \n'
  elif command -v openssl >/dev/null 2>&1; then
    openssl rand -hex "$bytes"
  else
    die "生成随机密钥需要 od 或 openssl"
  fi
}

load_env() {
  [ -f "$ENV_FILE" ] || die "找不到 $ENV_FILE；请先运行 ./infra/standalone/start-standalone.sh（会自动生成）"
  local tmp
  tmp="$(mktemp)"
  # 去掉 Windows 编辑器可能引入的 CR，避免端口值变成 "18090\r"
  tr -d '\r' < "$ENV_FILE" > "$tmp"
  set -a
  # shellcheck disable=SC1090
  . "$tmp"
  set +a
  rm -f "$tmp"
}

validate_secrets() {
  : "${POSTGRES_PASSWORD:?POSTGRES_PASSWORD 未设置（见 infra/standalone/.env.example）}"
  : "${ERP_JWT_SECRET:?ERP_JWT_SECRET 未设置（见 infra/standalone/.env.example）}"
  case "$POSTGRES_PASSWORD" in
    change-me*|erp|password|postgres)
      die "POSTGRES_PASSWORD 仍是占位/弱值，拒绝启动；请修改 $ENV_FILE" ;;
  esac
  case "$ERP_JWT_SECRET" in
    change-me*|dev-only*|*change-me-in-production*)
      die "ERP_JWT_SECRET 仍是占位/开发默认值，拒绝启动；请修改 $ENV_FILE" ;;
  esac
  [ "${#ERP_JWT_SECRET}" -ge 32 ] || die "ERP_JWT_SECRET 至少需要 32 字节（当前 ${#ERP_JWT_SECRET}）"
}

initialize_env() {
  if [ ! -f "$ENV_FILE" ]; then
    umask 077
    local pg_password jwt_secret
    pg_password="$(random_hex 24)"
    jwt_secret="$(random_hex 32)"
    cat > "$ENV_FILE" <<EOF
# 由 start-standalone 自动生成（$(date '+%Y-%m-%d %H:%M:%S'))
# 本文件含真实凭据，已被 .gitignore 忽略；不要提交、不要发到聊天工具。
POSTGRES_DB=erp
POSTGRES_USER=erp
POSTGRES_PASSWORD=$pg_password
ERP_JWT_SECRET=$jwt_secret

WEB_BIND_IP=127.0.0.1
WEB_PORT=18090
PG_HOST_PORT=15439

TZ=Asia/Shanghai
IMAGE_TAG=standalone
BACKUP_RETENTION_DAYS=14
EOF
    chmod 600 "$ENV_FILE" 2>/dev/null || true
    info "已生成 $ENV_FILE（随机数据库密码与 JWT 密钥，权限 600）"
  fi
  load_env
  validate_secrets
}

web_port() { printf '%s' "${WEB_PORT:-18090}"; }
pg_port() { printf '%s' "${PG_HOST_PORT:-15439}"; }

http_code() {
  local url="$1"
  if command -v curl >/dev/null 2>&1; then
    curl -sS -o /dev/null -w '%{http_code}' --max-time 5 "$url" 2>/dev/null || printf '000'
  elif command -v wget >/dev/null 2>&1; then
    if wget -q -O /dev/null --timeout=5 "$url" 2>/dev/null; then printf '200'; else printf '000'; fi
  else
    die "需要 curl 或 wget 之一来做健康检查"
  fi
}

http_ok() {
  [ "$(http_code "$1")" = "200" ]
}

wait_ready() {
  local timeout="${1:-120}"
  local url="http://127.0.0.1:$(web_port)/readyz"
  local deadline=$((SECONDS + timeout))
  while [ "$SECONDS" -lt "$deadline" ]; do
    if http_ok "$url"; then
      info "整套已就绪：$url"
      return 0
    fi
    sleep 3
  done
  printf '  健康检查超时（%ss）：%s\n' "$timeout" "$url" >&2
  return 1
}

image_exists() {
  docker image inspect "$1" >/dev/null 2>&1
}

ensure_images() {
  local build="${1:-0}"
  local tag="${IMAGE_TAG:-standalone}"
  local images=("postgres:16-alpine" "erp-backend:$tag" "erp-web:$tag")
  local missing=0 img

  for img in "${images[@]}"; do
    image_exists "$img" || missing=1
  done

  if [ "$missing" = "1" ] && [ "$build" = "1" ]; then
    log "构建后端/前端镜像（需要网络或本地基础镜像）"
    compose build backend web
    if ! image_exists "postgres:16-alpine"; then
      info "显式拉取 postgres:16-alpine（需要网络）"
      docker pull postgres:16-alpine
    fi
  fi

  missing=0
  for img in "${images[@]}"; do
    if ! image_exists "$img"; then
      warn "缺少镜像：$img"
      missing=1
    fi
  done

  if [ "$missing" = "1" ]; then
    cat >&2 <<EOF

[x] 单机版不会隐式联网拉镜像。请先准备镜像，三选一：

  方式 A（推荐，目标机断网）：在联网机器上构建并导出，然后拷到本机导入
    docker save -o erp-standalone-images.tar \\
      erp-backend:$tag erp-web:$tag postgres:16-alpine
    docker load -i erp-standalone-images.tar

  方式 B（本机 Docker Hub 不可达）：用 M6 离线垫片构建
    TAG=$tag ./scripts/build-local-images.sh
    # 然后用 --image-tag $tag 或修改 .env 的 IMAGE_TAG

  方式 C（本机可联网）：加 --build 启动，脚本会构建 backend/web 并拉取 PG
    ./infra/standalone/start-standalone.sh --build

  当前 IMAGE_TAG=${tag}（可在 $ENV_FILE 修改）
EOF
    exit 1
  fi
}

validate_admin_username() {
  case "$1" in
    ''|*[!A-Za-z0-9._-]*) die "管理员用户名只能包含字母、数字、点、下划线、连字符：$1" ;;
  esac
}

validate_admin_password() {
  local password="$1"
  [ "${#password}" -ge 8 ] || die "管理员密码至少 8 位（建议 16 位以上）"
  # 限制字符集是为了让密码安全地作为 argv 传给 docker/psql（含空格/引号会被 shell 层破坏）
  case "$password" in
    *[!A-Za-z0-9._@#%+=:-]*)
      die "管理员密码只允许字母、数字与 . _ @ # % + = : -（避免空格/引号在命令行转义时被破坏）" ;;
  esac
}

admin_count() {
  local username="$1"
  compose exec -T postgres psql \
    -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc \
    "SELECT count(*) FROM app_users WHERE username='$username'" | tr -d '[:space:]'
}

admin_role_count() {
  local username="$1"
  compose exec -T postgres psql \
    -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc \
    "SELECT count(*) FROM app_users u
       JOIN app_user_roles ur ON ur.user_id = u.id
       JOIN app_roles r ON r.id = ur.role_id
      WHERE u.username='$username' AND r.code='ADMIN'" | tr -d '[:space:]'
}

seed_admin() {
  local username="$1" password="$2" display_name="$3"
  # 用 stdin 传 SQL，而不是 -f /tmp/...：Git Bash 会把容器内绝对路径改写成
  # Windows 临时路径（psql: C:/Users/...: No such file or directory）。
  compose exec -T postgres psql \
    -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
    -v ON_ERROR_STOP=1 \
    -v username="$username" \
    -v password="$password" \
    -v display_name="$display_name" \
    -v role=ADMIN < "$SEED_SQL" >/dev/null
  [ "$(admin_role_count "$username")" -ge 1 ] || die "管理员 $username 已写入，但 ADMIN 角色绑定失败"
}

initialize_admin() {
  local username="$1" password="$2" explicit="$3"
  local count
  count="$(admin_count "$username")"

  if [ "$count" -gt 0 ]; then
    if [ "$explicit" = "1" ]; then
      seed_admin "$username" "$password" "Administrator"
      info "已重置管理员 $username 的密码"
    else
      info "管理员 $username 已存在，跳过初始化（重置密码：--admin-password '<新密码>'）"
    fi
    return 0
  fi

  if [ -z "$password" ]; then
    password="$(random_hex 16)"
    info "未提供管理员密码，已生成随机密码（只显示这一次）"
  fi
  seed_admin "$username" "$password" "Administrator"
  info "管理员已创建：$username"

  if [ "$explicit" = "0" ]; then
    printf '\n  管理员初始密码：%s\n' "$password"
    printf '  请登录后尽快修改；忘记可用 --admin-password 重置。\n\n'
  fi
}

print_endpoints() {
  local port bind
  port="$(web_port)"
  bind="${WEB_BIND_IP:-127.0.0.1}"
  cat <<EOF

=== 单机版已启动 ===
  入口（本机）：http://127.0.0.1:${port}/
  健康检查：    http://127.0.0.1:${port}/readyz
  PostgreSQL：  127.0.0.1:$(pg_port)（仅本机）
  数据卷：      $DATA_VOLUME
  备份：        ./infra/standalone/backup-standalone.sh
  状态：        ./infra/standalone/status-standalone.sh
  停止：        ./infra/standalone/stop-standalone.sh
EOF
  if [ "$bind" = "0.0.0.0" ]; then
    info "WEB_BIND_IP=0.0.0.0：局域网可通过本机 IP:${port} 访问，请自行确认防火墙"
  fi
}
