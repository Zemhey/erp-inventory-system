#!/usr/bin/env bash
# ════════════════════════════════════════════════════════════════════════════
# 部署脚本共用的路径、.env 解析、compose 包装与健康检查
#
# 被 deploy.sh / rollback.sh / backup-db.sh / seed-admin.sh source。
# 原则：所有脚本都从仓库根定位，不依赖调用者的 cwd。
# ════════════════════════════════════════════════════════════════════════════
set -euo pipefail

ROOT_DIR="${ROOT_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
COMPOSE_FILE="$ROOT_DIR/infra/deploy/docker-compose.prod.yml"
ENV_FILE="$ROOT_DIR/infra/deploy/.env"
STATE_FILE="$ROOT_DIR/infra/deploy/.deploy-state"
BACKUP_DIR="${BACKUP_DIR:-$ROOT_DIR/infra/deploy/backups}"

log() { printf '\n=== %s ===\n' "$*"; }
info() { printf '  %s\n' "$*"; }
die() { printf '\n✗ %s\n' "$*" >&2; exit 1; }

require_docker() {
  command -v docker >/dev/null 2>&1 || die "未找到 docker，请先安装 Docker Engine / Docker Desktop"
  docker info >/dev/null 2>&1 || die "Docker 守护进程未运行"
  docker compose version >/dev/null 2>&1 || die "未找到 docker compose 插件（需要 Docker Compose v2）"
}

require_files() {
  [ -f "$COMPOSE_FILE" ] || die "找不到 compose 文件：$COMPOSE_FILE"
  [ -f "$ENV_FILE" ] || die "找不到 .env：$ENV_FILE（先 cp infra/deploy/.env.example infra/deploy/.env）"
}

load_env() {
  require_files
  set -a
  # shellcheck disable=SC1090
  . "$ENV_FILE"
  set +a
}

validate_secrets() {
  : "${POSTGRES_PASSWORD:?POSTGRES_PASSWORD 未设置（见 infra/deploy/.env.example）}"
  : "${ERP_JWT_SECRET:?ERP_JWT_SECRET 未设置（openssl rand -base64 48）}"
  case "$POSTGRES_PASSWORD" in
    change-me*|erp|password|postgres)
      die "POSTGRES_PASSWORD 仍是占位/弱值，拒绝部署" ;;
  esac
  case "$ERP_JWT_SECRET" in
    change-me*|dev-only*|*change-me-in-production*)
      die "ERP_JWT_SECRET 仍是占位/开发默认值，拒绝部署" ;;
  esac
  if [ "${#ERP_JWT_SECRET}" -lt 32 ]; then
    die "ERP_JWT_SECRET 至少需要 32 字节（当前 ${#ERP_JWT_SECRET}）"
  fi
}

compose() {
  docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" "$@"
}

web_port() { printf '%s' "${WEB_PORT:-18080}"; }

http_ok() {
  local url="$1"
  if command -v curl >/dev/null 2>&1; then
    curl -fsS --max-time 5 "$url" >/dev/null
  elif command -v wget >/dev/null 2>&1; then
    wget -q -O /dev/null --timeout=5 "$url"
  else
    die "需要 curl 或 wget 之一来做健康检查"
  fi
}

# 等待 /readyz（web + backend + DB）返回 2xx；超时返回 1
health_wait() {
  local timeout="${1:-180}"
  local url="http://127.0.0.1:$(web_port)/readyz"
  local deadline=$((SECONDS + timeout))
  while [ "$SECONDS" -lt "$deadline" ]; do
    if http_ok "$url" 2>/dev/null; then
      info "健康检查通过：$url"
      return 0
    fi
    sleep 3
  done
  printf '  健康检查超时（%ss）：%s\n' "$timeout" "$url" >&2
  return 1
}

read_state_field() {
  local field="$1"
  [ -f "$STATE_FILE" ] || return 0
  sed -n "s/^${field}=//p" "$STATE_FILE" | head -n 1
}

write_state() {
  local current="$1" previous="$2"
  umask 077
  cat > "$STATE_FILE" <<EOF
CURRENT_TAG=$current
PREVIOUS_TAG=$previous
DEPLOYED_AT=$(date +%Y-%m-%dT%H:%M:%S%z)
EOF
}

print_services() {
  compose ps
}

print_failure_logs() {
  printf '\n--- compose ps ---\n' >&2
  compose ps >&2 || true
  printf '\n--- backend / web 最近 100 行日志 ---\n' >&2
  compose logs --tail=100 backend web >&2 || true
}
