#!/usr/bin/env bash
# ============================================================================
# 单机版一键启动
#
# 做的事：
#   1. 检查 Docker / compose；
#   2. 首次运行时生成 infra/standalone/.env（随机数据库密码与 JWT 密钥）；
#   3. 检查镜像是否存在（不会隐式联网拉取；--build 才会构建/拉取）；
#   4. 启动 PG + backend + web，等待 /readyz；
#   5. 首次初始化管理员（密码可由参数/环境变量传入，否则随机生成并只显示一次）。
#
# 用法：
#   ./infra/standalone/start-standalone.sh
#   ./infra/standalone/start-standalone.sh --admin-password 'S3cure-Passw0rd'
#   ERP_ADMIN_PASSWORD='S3cure-Passw0rd' ./infra/standalone/start-standalone.sh
#   ./infra/standalone/start-standalone.sh --build          # 本机可联网时
#   ./infra/standalone/start-standalone.sh --image-tag standalone-local
#
# 管理员密码字符集：字母、数字与 . _ @ # % + = : -（至少 8 位）。
# ============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/standalone-common.sh
. "$SCRIPT_DIR/lib/standalone-common.sh"

BUILD=0
ADMIN_USERNAME="${ERP_ADMIN_USERNAME:-admin}"
ADMIN_PASSWORD="${ERP_ADMIN_PASSWORD:-}"
IMAGE_TAG_OVERRIDE=""

usage() {
  cat <<'EOF'
单机版一键启动

用法：
  ./infra/standalone/start-standalone.sh
  ./infra/standalone/start-standalone.sh --admin-password 'S3cure-Passw0rd'
  ERP_ADMIN_PASSWORD='S3cure-Passw0rd' ./infra/standalone/start-standalone.sh
  ./infra/standalone/start-standalone.sh --build
  ./infra/standalone/start-standalone.sh --image-tag standalone-local

管理员密码字符集：字母、数字与 . _ @ # % + = : -（至少 8 位）。
EOF
}

while [ $# -gt 0 ]; do
  case "$1" in
    --build) BUILD=1 ;;
    --admin-username) ADMIN_USERNAME="${2:-}"; shift ;;
    --admin-password) ADMIN_PASSWORD="${2:-}"; shift ;;
    --image-tag) IMAGE_TAG_OVERRIDE="${2:-}"; shift ;;
    -h|--help) usage; exit 0 ;;
    *) die "未知参数：$1（--help 查看用法）" ;;
  esac
  shift
done

require_docker
initialize_env

if [ -n "$IMAGE_TAG_OVERRIDE" ]; then
  IMAGE_TAG="$IMAGE_TAG_OVERRIDE"
  export IMAGE_TAG
fi

validate_admin_username "$ADMIN_USERNAME"
if [ -n "$ADMIN_PASSWORD" ]; then
  validate_admin_password "$ADMIN_PASSWORD"
fi

ensure_images "$BUILD"

log "启动单机版（tag=${IMAGE_TAG:-standalone}）"
if ! compose up -d --no-build --pull never --remove-orphans --wait --wait-timeout 240; then
  printf '\n--- compose ps ---\n' >&2
  compose ps >&2 || true
  printf '\n--- backend / web 最近 100 行日志 ---\n' >&2
  compose logs --tail=100 backend web >&2 || true
  die "启动失败；请按上面的日志排查（常见原因：端口被占用、镜像缺失、.env 凭据被改）"
fi

if ! wait_ready 120; then
  compose ps >&2 || true
  compose logs --tail=100 backend web >&2 || true
  die "服务已启动但 /readyz 未通过；请检查 backend 日志与数据库连接"
fi

if [ -n "$ADMIN_PASSWORD" ]; then
  initialize_admin "$ADMIN_USERNAME" "$ADMIN_PASSWORD" 1
else
  initialize_admin "$ADMIN_USERNAME" "" 0
fi

print_endpoints
