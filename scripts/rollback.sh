#!/usr/bin/env bash
# ════════════════════════════════════════════════════════════════════════════
# rollback：按 tag 切回上一版（默认 .deploy-state 的 PREVIOUS_TAG）
#
# 用法：
#   ./scripts/rollback.sh                  # 回到上一版
#   ./scripts/rollback.sh <tag>            # 回到指定版本
#
# ★ 镜像回滚 ≠ 数据库回滚。Flyway 只向前迁移；若本次发布包含不兼容迁移，
#   必须先按 Runbook §8 从备份恢复数据库，再回滚镜像。
# ════════════════════════════════════════════════════════════════════════════
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
# shellcheck source=lib/deploy-common.sh
. "$ROOT_DIR/scripts/lib/deploy-common.sh"

require_docker
load_env

CURRENT="$(read_state_field CURRENT_TAG)"
TARGET="${1:-${ROLLBACK_TAG:-$(read_state_field PREVIOUS_TAG)}}"
[ -n "$TARGET" ] || die "没有可回滚的版本：.deploy-state 里没有 PREVIOUS_TAG，请显式传 tag"

for img in "erp-backend:$TARGET" "erp-web:$TARGET"; do
  docker image inspect "$img" >/dev/null 2>&1 || die "本机没有镜像 $img；先显式构建该 tag，或改用其他版本"
done

log "回滚 erp-prod"
info "当前版本：${CURRENT:-未知}"
info "目标版本：$TARGET"
printf '\n⚠ 数据库迁移不会自动回滚；若本次发布含不兼容迁移，请先按 Runbook §8 恢复备份。\n\n'

IMAGE_TAG="$TARGET" compose up -d --remove-orphans --no-build

if health_wait "${ROLLBACK_HEALTH_TIMEOUT:-120}"; then
  write_state "$TARGET" "${CURRENT:-}"
  log "回滚成功"
  info "当前版本：$TARGET"
  print_services
  exit 0
fi

print_failure_logs
die "回滚后健康检查仍未通过；请人工介入（Runbook §9）"
