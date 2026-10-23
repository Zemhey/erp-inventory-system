#!/usr/bin/env bash
# ============================================================================
# 单机版数据库备份
#
# 产物：infra/standalone/backups/erp-standalone-YYYYMMDDTHHMMSSZ.dump
# 格式：pg_dump -Fc（自定义格式，压缩、可用 pg_restore 选择性恢复）
# 校验：pg_restore -l 必须能列出表数据，否则视为失败
# 保留：BACKUP_RETENTION_DAYS（默认 14 天）
#
# 用法：
#   ./infra/standalone/backup-standalone.sh
#   BACKUP_RETENTION_DAYS=30 ./infra/standalone/backup-standalone.sh
# ============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/standalone-common.sh
. "$SCRIPT_DIR/lib/standalone-common.sh"

if [ "$#" -gt 0 ]; then
  case "$1" in
    -h|--help)
      cat <<'EOF'
单机版数据库备份

产物：infra/standalone/backups/erp-standalone-YYYYMMDDTHHMMSSZ.dump
格式：pg_dump -Fc；pg_restore -l 校验通过才算成功。
保留：BACKUP_RETENTION_DAYS（默认 14 天）。

用法：
  ./infra/standalone/backup-standalone.sh
  BACKUP_RETENTION_DAYS=30 ./infra/standalone/backup-standalone.sh
EOF
      exit 0 ;;
    *) die "未知参数：$1" ;;
  esac
fi

require_docker
load_env

[ -n "$(compose ps --status running -q postgres 2>/dev/null || true)" ] || die "PostgreSQL 未运行；先启动单机版"

RETENTION="${BACKUP_RETENTION_DAYS:-14}"
case "$RETENTION" in
  ''|*[!0-9]*) die "BACKUP_RETENTION_DAYS 必须是整数：$RETENTION" ;;
esac

mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR" 2>/dev/null || true

FILE="$BACKUP_DIR/erp-standalone-$(date -u +%Y%m%dT%H%M%SZ).dump"
PART="$FILE.part"
trap 'rm -f "$PART"' EXIT

log "备份 PostgreSQL"
info "目标文件：$FILE"

# -Fc 走 stdout 重定向，bash 下二进制安全；校验也从 stdin 读，
# 不把 /tmp/... 传给容器（Git Bash 会改写容器内绝对路径）。
compose exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > "$PART"
[ -s "$PART" ] || die "dump 为空，备份失败"

if ! compose exec -T postgres pg_restore -l < "$PART" | grep -qi 'table data'; then
  die "dump 无法被 pg_restore 解析或没有表数据，备份失败"
fi

mv "$PART" "$FILE"
chmod 600 "$FILE" 2>/dev/null || true

DELETED="$(find "$BACKUP_DIR" -maxdepth 1 -type f -name 'erp-standalone-*.dump' -mtime "+$RETENTION" -print -delete | wc -l | tr -d ' ')"
info "清理过期备份：${DELETED} 个（保留 ${RETENTION} 天）"

log "备份完成"
ls -lh "$FILE"
info "备份目录：$BACKUP_DIR"
info "恢复步骤见 infra/standalone/README.md 的「备份与恢复」"
