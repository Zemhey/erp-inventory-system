-- ============================================================================
-- Create/reset an administrator account for the standalone edition.
--
-- Invoked by start-standalone.sh / .ps1 (and scripts/seed-admin.sh) with psql
-- variables: username, password, display_name, role
--
-- Why not a Flyway migration: a migration would ship a fixed password hash in
-- version control. Accounts are created explicitly by the operator instead.
--
-- !! 多租户（V16）之后，这三张表都带 tenant_id，且都是 NOT NULL：
--    app_users.tenant_id / app_user_tenants(user_id, tenant_id) / app_user_roles.tenant_id。
--    老脚本只插 username/password_hash/display_name/enabled，在全新库里必然撞
--    "null value in column tenant_id ... violates not-null constraint"，
--    也就是说**单机版第一次启动根本建不出可登录的账号**（实测在这台机器上复现）。
--    开发库能一直正常，是因为它的 admin 行早就存在，ON CONFLICT 走的是 UPDATE 分支，
--    压根没碰 NOT NULL 的 INSERT 路径 —— 这种"只有新库才炸"的缺陷必须靠空库首启来暴露。
--    租户落点：优先沿用已存在账号自己的租户（重置密码不该把人挪租户），
--    否则取 code='default' 的租户（迁移种下的第一个），再兜底最小 id。
--    app_user_tenants 上的 BEFORE INSERT 触发器只在 tenant_id 为 NULL 时补会话租户，
--    显式给值不会被覆盖。
-- ============================================================================
CREATE EXTENSION IF NOT EXISTS pgcrypto;

INSERT INTO app_users (username, password_hash, display_name, enabled, tenant_id)
SELECT :'username',
       crypt(:'password', gen_salt('bf', 10)),
       :'display_name',
       TRUE,
       COALESCE(
           (SELECT tenant_id FROM app_users WHERE username = :'username'),
           (SELECT id FROM tenants WHERE code = 'default' ORDER BY id LIMIT 1),
           (SELECT id FROM tenants ORDER BY id LIMIT 1))
  FROM (SELECT 1) seed
ON CONFLICT (username) DO UPDATE
   SET password_hash   = EXCLUDED.password_hash,
       display_name    = EXCLUDED.display_name,
       enabled         = TRUE,
       failed_attempts = 0,
       locked_until    = NULL,
       updated_at      = now();

-- 账号 → 租户可见性：登录时按 (user_id, tenant_id) 查 membership，缺了就登不进去
INSERT INTO app_user_tenants (user_id, tenant_id)
SELECT u.id, u.tenant_id
  FROM app_users u
 WHERE u.username = :'username'
ON CONFLICT (user_id, tenant_id) DO NOTHING;

-- 角色必须落在**同一个租户**里：app_roles 是按租户各一份的（ADMIN 在租户 1 和 2 都有）
INSERT INTO app_user_roles (user_id, role_id, tenant_id)
SELECT u.id, r.id, u.tenant_id
  FROM app_users u
  JOIN app_roles r ON r.code = :'role' AND r.tenant_id = u.tenant_id
 WHERE u.username = :'username'
ON CONFLICT DO NOTHING;

SELECT u.id AS user_id, u.username, u.tenant_id,
       (SELECT count(*) FROM app_user_tenants m WHERE m.user_id = u.id) AS tenant_scopes,
       r.code AS role
  FROM app_users u
  LEFT JOIN app_user_roles ur ON ur.user_id = u.id AND ur.tenant_id = u.tenant_id
  LEFT JOIN app_roles r ON r.id = ur.role_id
 WHERE u.username = :'username';
