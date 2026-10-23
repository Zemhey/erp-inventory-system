#!/usr/bin/env bash
# ============================================================================
# 单机版停止
#
# 默认：停止并删除容器/网络，**保留**数据卷与备份；下次 start 数据还在。
# --remove-data：连同数据卷 erp-standalone-pgdata 一起删除（不可恢复，需 --yes）。
#
# 用法：
#   ./infra/standalone/stop-standalone.sh
#   ./infra/standalone/stop-standalone.sh --remove-data --yes
# ============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/standalone-common.sh
. "$SCRIPT_DIR/lib/standalone-common.sh"

REMOVE_DATA=0
ASSUME_YES=0

while [ $# -gt 0 ]; do
  case "$1" in
    --remove-data) REMOVE_DATA=1 ;;
    --yes|-y) ASSUME_YES=1 ;;
    -h|--help)
      cat <<'EOF'
单机版停止

默认：停止并删除容器/网络，保留数据卷与备份。
--remove-data：连同数据卷 erp-standalone-pgdata 一起删除（不可恢复，需 --yes）。

用法：
  ./infra/standalone/stop-standalone.sh
  ./infra/standalone/stop-standalone.sh --remove-data --yes
EOF
      exit 0 ;;
    *) die "未知参数：$1（--help 查看用法）" ;;
  esac
  shift
done

require_docker
[ -f "$ENV_FILE" ] || die "找不到 $ENV_FILE；单机版尚未初始化"
load_env

if [ "$REMOVE_DATA" = "1" ] && [ "$ASSUME_YES" != "1" ]; then
  if [ -t 0 ]; then
    printf '这会删除数据卷 %s 及全部业务数据（备份目录不受影响）。\n' "$DATA_VOLUME"
    printf '确认请输入 yes：'
    read -r answer
    [ "$answer" = "yes" ] || die "已取消"
  else
    die "非交互模式删除数据卷必须同时传 --yes"
  fi
fi

if [ "$REMOVE_DATA" = "1" ]; then
  log "停止单机版并删除数据卷"
  compose down -v --remove-orphans
  info "已删除容器、网络与数据卷 $DATA_VOLUME"
  info "备份目录未删除：$BACKUP_DIR"
else
  log "停止单机版（保留数据）"
  compose down --remove-orphans
  info "容器与网络已删除；数据卷 $DATA_VOLUME 保留"
  info "下次启动：./infra/standalone/start-standalone.sh"
fi
