#!/usr/bin/env bash
# ════════════════════════════════════════════════════════════════════════════
# deploy：构建 → 启动 → 等 /readyz → 记录版本；失败默认自动回滚上一版
#
# 用法：
#   ./scripts/deploy.sh                         # tag = <git短SHA>-<时间>
#   ./scripts/deploy.sh --tag m6-20261023       # 指定 tag
#   ./scripts/deploy.sh --no-build              # 只重启已有镜像
#   ./scripts/deploy.sh --no-auto-rollback      # 失败时保留现场，不自动回退
#
# 前置：infra/deploy/.env（从 .env.example 复制并改掉密码/密钥）
# ════════════════════════════════════════════════════════════════════════════
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
# shellcheck source=lib/deploy-common.sh
. "$ROOT_DIR/scripts/lib/deploy-common.sh"

usage() {
  sed -n '2,16p' "$0" | sed 's/^# \{0,1\}//'
}

TAG=""
NO_BUILD=0
AUTO_ROLLBACK=1
while [ "$#" -gt 0 ]; do
  case "$1" in
    --tag)
      [ "$#" -ge 2 ] || die "--tag 需要参数"
      TAG="$2"; shift 2 ;;
    --tag=*)
      TAG="${1#*=}"; shift ;;
    --no-build) NO_BUILD=1; shift ;;
    --no-auto-rollback) AUTO_ROLLBACK=0; shift ;;
    -h|--help) usage; exit 0 ;;
    *) die "未知参数：$1（--help 查看用法）" ;;
  esac
done

require_docker
load_env
validate_secrets

if [ -z "$TAG" ]; then
  TAG="${DEPLOY_TAG:-}"
fi
if [ -z "$TAG" ]; then
  SHA="$(git -C "$ROOT_DIR" rev-parse --short HEAD 2>/dev/null || echo manual)"
  TAG="${SHA}-$(date +%Y%m%d-%H%M%S)"
fi
case "$TAG" in
  *[!A-Za-z0-9._-]*) die "tag 只能包含字母、数字、点、下划线、连字符：$TAG" ;;
esac

CURRENT="$(read_state_field CURRENT_TAG)"
PREVIOUS="$(read_state_field PREVIOUS_TAG)"

log "部署 erp-prod · tag=$TAG"
info "上一版：${CURRENT:-（无，首次部署）}"

if [ "$NO_BUILD" -eq 0 ]; then
  log "构建镜像（backend + web）"
  IMAGE_TAG="$TAG" compose build backend web
else
  log "跳过构建（--no-build）"
  for img in "erp-backend:$TAG" "erp-web:$TAG"; do
    docker image inspect "$img" >/dev/null 2>&1 || die "本机没有镜像 $img，无法 --no-build"
  done
fi

log "启动 / 滚动更新"
IMAGE_TAG="$TAG" compose up -d --remove-orphans --no-build

if health_wait "${DEPLOY_HEALTH_TIMEOUT:-180}"; then
  PREV_FOR_STATE="$CURRENT"
  if [ "$CURRENT" = "$TAG" ]; then
    PREV_FOR_STATE="$PREVIOUS"
  fi
  write_state "$TAG" "$PREV_FOR_STATE"
  log "部署成功"
  info "当前版本：$TAG"
  info "上一版本：${PREV_FOR_STATE:-（无）}"
  info "入口：http://127.0.0.1:$(web_port)/  （绑定 ${WEB_BIND_IP:-0.0.0.0}）"
  info "就绪探针：http://127.0.0.1:$(web_port)/readyz"
  print_services
  exit 0
fi

# ── 失败处理 ──────────────────────────────────────────────────────────────
print_failure_logs

if [ -n "$CURRENT" ] && [ "$AUTO_ROLLBACK" -eq 1 ] && [ "$CURRENT" != "$TAG" ]; then
  log "健康检查失败，自动回滚到 $CURRENT"
  IMAGE_TAG="$CURRENT" compose up -d --remove-orphans --no-build
  if health_wait 120; then
    write_state "$CURRENT" "$PREVIOUS"
    die "新版本 $TAG 部署失败，已自动回滚到 $CURRENT（日志见上）"
  fi
  die "自动回滚也失败；请立即人工介入（Runbook §9 常见故障）"
fi

die "部署失败，未自动回滚（当前版本：${CURRENT:-无}）"
