#!/usr/bin/env bash
# ============================================================================
# 单机版状态检查
#
# 输出：服务容器状态、/healthz 与 /readyz、管理员、数据卷、最近备份。
# 退出码：0 = /readyz 200；1 = 未就绪/未初始化。
#
# 用法：./infra/standalone/status-standalone.sh
# ============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/standalone-common.sh
. "$SCRIPT_DIR/lib/standalone-common.sh"

if [ "$#" -gt 0 ]; then
  case "$1" in
    -h|--help)
      cat <<'EOF'
单机版状态检查

输出：服务容器状态、/healthz 与 /readyz、管理员、数据卷、最近备份。
退出码：0 = /readyz 200；1 = 未就绪/未初始化。

用法：./infra/standalone/status-standalone.sh
EOF
      exit 0 ;;
    *) die "未知参数：$1" ;;
  esac
fi

require_docker
if [ ! -f "$ENV_FILE" ]; then
  printf '单机版尚未初始化：%s 不存在。\n' "$ENV_FILE"
  printf '首次启动：./infra/standalone/start-standalone.sh\n'
  exit 1
fi

load_env

log "容器状态"
compose ps

PORT="$(web_port)"
HEALTH_CODE="$(http_code "http://127.0.0.1:${PORT}/healthz")"
READY_CODE="$(http_code "http://127.0.0.1:${PORT}/readyz")"

printf '\n=== 健康检查 ===\n'
info "GET /healthz -> $HEALTH_CODE"
info "GET /readyz  -> $READY_CODE  (http://127.0.0.1:${PORT}/readyz)"

printf '\n=== 运行信息 ===\n'
info "配置 IMAGE_TAG：${IMAGE_TAG:-standalone}（实际镜像见上方容器状态）"
info "Web 入口：http://127.0.0.1:${PORT}/"
info "PostgreSQL：127.0.0.1:$(pg_port)（仅本机）"

if [ -n "$(compose ps --status running -q postgres 2>/dev/null || true)" ]; then
  COUNT="$(admin_count "${ERP_ADMIN_USERNAME:-admin}" 2>/dev/null || printf '?')"
  info "管理员 ${ERP_ADMIN_USERNAME:-admin}：$(if [ "$COUNT" = "0" ]; then printf '不存在'; else printf '存在'; fi)"
fi

if docker volume inspect "$DATA_VOLUME" >/dev/null 2>&1; then
  MOUNT="$(docker volume inspect -f '{{.Mountpoint}}' "$DATA_VOLUME" 2>/dev/null || true)"
  info "数据卷 $DATA_VOLUME：存在"
  [ -n "$MOUNT" ] && info "  挂载点（Docker VM 内）：$MOUNT"
else
  info "数据卷 $DATA_VOLUME：不存在（首次启动时创建）"
fi

LATEST="$(ls -1t "$BACKUP_DIR"/erp-standalone-*.dump 2>/dev/null | head -n 1 || true)"
if [ -n "$LATEST" ]; then
  info "最近备份：$LATEST"
else
  info "最近备份：无（运行 ./infra/standalone/backup-standalone.sh）"
fi

if [ "$READY_CODE" = "200" ]; then
  exit 0
fi
exit 1
