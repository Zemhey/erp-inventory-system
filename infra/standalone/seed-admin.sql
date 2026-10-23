-- ============================================================================
-- Create/reset an administrator account for the standalone edition.
--
-- Invoked by start-standalone.sh / .ps1 with psql variables:
--   username, password, display_name, role
--
-- Why not a Flyway migration: a migration would ship a fixed password hash in
-- version control. Accounts are created explicitly by the operator instead.
-- ============================================================================
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
