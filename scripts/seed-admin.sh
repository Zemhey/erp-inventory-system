#!/usr/bin/env bash
# ════════════════════════════════════════════════════════════════════════════
# seed-admin：在生产库里创建/重置一个管理员账号
#
# 为什么不在 Flyway 迁移里预置账号：迁移会进入版本库，弱口令/固定哈希是
# 经典的生产事故来源。账号必须由运维显式创建（Runbook §5.2）。
#
# 实现方式：直接用 PostgreSQL 的 pgcrypto 生成 BCrypt（$2a$10$），
# 不依赖宿主机是否有 Java/Python；Spring Security 的 BCryptPasswordEncoder
# 支持 $2a$ 前缀（本地已验证可登录）。
#
# 用法：
#   ADMIN_PASSWORD='<强密码>' ./scripts/seed-admin.sh
#   ADMIN_USERNAME=admin ADMIN_PASSWORD='...' ADMIN_ROLE=ADMIN ./scripts/seed-admin.sh
#
# 注意：
#   - 重复执行 = 重置该账号密码，并清空失败次数/锁定状态；
#   - 密码作为 psql 参数传入容器，短暂出现在容器进程表中；创建完即结束；
#   - 不要把这行命令留在 shell history 里（可先 `set +o history` 或用环境变量）。
# ════════════════════════════════════════════════════════════════════════════
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
# shellcheck source=lib/deploy-common.sh
. "$ROOT_DIR/scripts/lib/deploy-common.sh"

require_docker
load_env

ADMIN_USERNAME="${ADMIN_USERNAME:-admin}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-}"
ADMIN_ROLE="${ADMIN_ROLE:-ADMIN}"
ADMIN_DISPLAY_NAME="${ADMIN_DISPLAY_NAME:-管理员}"

[ -n "$ADMIN_PASSWORD" ] || die "请通过环境变量提供 ADMIN_PASSWORD（不要写进命令行参数）"
if [ "${#ADMIN_PASSWORD}" -lt 8 ]; then
  die "ADMIN_PASSWORD 至少 8 位（建议 12 位以上）"
fi
case "$ADMIN_USERNAME" in
  *[!A-Za-z0-9._-]*) die "ADMIN_USERNAME 只能包含字母、数字、点、下划线、连字符" ;;
esac
case "$ADMIN_ROLE" in
  *[!A-Z0-9_]*) die "ADMIN_ROLE 只能包含大写字母、数字、下划线" ;;
esac

log "创建/重置管理员账号"
info "用户名：$ADMIN_USERNAME"
info "角色：$ADMIN_ROLE"
info "显示名：$ADMIN_DISPLAY_NAME"

compose exec -T postgres psql \
  -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  -v ON_ERROR_STOP=1 \
  -v username="$ADMIN_USERNAME" \
  -v password="$ADMIN_PASSWORD" \
  -v display_name="$ADMIN_DISPLAY_NAME" \
  -v role="$ADMIN_ROLE" <<'SQL'
CREATE EXTENSION IF NOT EXISTS pgcrypto;

INSERT INTO app_users (username, password_hash, display_name, enabled)
VALUES (:'username', crypt(:'password', gen_salt('bf', 10)), :'display_name', TRUE)
ON CONFLICT (username) DO UPDATE
   SET password_hash   = EXCLUDED.password_hash,
       display_name    = EXCLUDED.display_name,
       enabled         = TRUE,
       failed_attempts = 0,
       locked_until    = NULL,
       updated_at      = now();

INSERT INTO app_user_roles (user_id, role_id)
SELECT u.id, r.id
  FROM app_users u, app_roles r
 WHERE u.username = :'username'
   AND r.code = :'role'
ON CONFLICT DO NOTHING;

SELECT u.id AS user_id, u.username, r.code AS role
  FROM app_users u
  LEFT JOIN app_user_roles ur ON ur.user_id = u.id
  LEFT JOIN app_roles r ON r.id = ur.role_id
 WHERE u.username = :'username';
SQL

log "完成"
info "用该账号登录前端，或："
info "  curl -sS -X POST http://127.0.0.1:$(web_port)/api/v1/auth/login \\"
info "    -H 'Content-Type: application/json' \\"
info "    -d '{\"username\":\"$ADMIN_USERNAME\",\"password\":\"<你设置的密码>\"}'"
