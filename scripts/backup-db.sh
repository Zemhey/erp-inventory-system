#!/usr/bin/env bash
# ════════════════════════════════════════════════════════════════════════════
# backup-db：pg_dump 自定义格式 + 可读性校验 + 按天数保留
#
# 用法：
#   ./scripts/backup-db.sh
#   BACKUP_RETENTION_DAYS=30 ./scripts/backup-db.sh
#
# 产物：infra/deploy/backups/erp-YYYYMMDD-HHMMSS.dump（已在 .gitignore）
# 恢复：见 infra/deploy/README.md §8（pg_restore --clean --if-exists）
# ════════════════════════════════════════════════════════════════════════════
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
# shellcheck source=lib/deploy-common.sh
. "$ROOT_DIR/scripts/lib/deploy-common.sh"

require_docker
load_env

RETENTION="${BACKUP_RETENTION_DAYS:-14}"
case "$RETENTION" in
  ''|*[!0-9]*) die "BACKUP_RETENTION_DAYS 必须是整数：$RETENTION" ;;
esac

mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR" 2>/dev/null || true

FILE="$BACKUP_DIR/erp-$(date -u +%Y%m%dT%H%M%SZ).dump"
TMP="$FILE.part"
trap 'rm -f "$TMP"' ERR

log "备份 PostgreSQL"
info "目标文件：$FILE"

# -Fc = 自定义格式（压缩、支持选择性恢复）；走 stdout 重定向，bash 下二进制安全
compose exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > "$TMP"

[ -s "$TMP" ] || die "dump 为空，备份失败"

# 可读性校验：pg_restore -l 能列出目录且至少含表数据，才算一份可用备份
#
# ★ 不能写成 `pg_restore -l | grep -q ...`：本脚本开了 pipefail，grep -q 一命中就退出，
#   上游 pg_restore 收到 SIGPIPE 以 141 结束，整条管道被判失败 —— 于是一份**完全正常**的
#   dump 会被报成"备份失败"（2026-10-29 服务器上实测复现）。先把 TOC 落到变量里再判。
TOC="$(compose exec -T postgres pg_restore -l < "$TMP" 2>/dev/null || true)"
if ! printf '%s\n' "$TOC" | grep -i 'table data' >/dev/null; then
  die "dump 无法被 pg_restore 解析或没有表数据，备份失败"
fi
info "TOC 校验通过：$(printf '%s\n' "$TOC" | grep -ci 'TABLE DATA') 个表数据条目"

mv "$TMP" "$FILE"
chmod 600 "$FILE" 2>/dev/null || true

# 保留策略：删除 mtime 超过 N 天的 erp-*.dump
DELETED="$(find "$BACKUP_DIR" -maxdepth 1 -type f -name 'erp-*.dump' -mtime "+$RETENTION" -print -delete | wc -l | tr -d ' ')"
info "清理过期备份：${DELETED} 个（保留 ${RETENTION} 天）"

log "备份完成"
ls -lh "$FILE"
info "当前备份目录："
ls -1 "$BACKUP_DIR"/erp-*.dump 2>/dev/null | tail -n 10 || true
