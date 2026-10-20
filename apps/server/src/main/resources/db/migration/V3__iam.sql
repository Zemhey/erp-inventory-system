-- ============================================================================
-- V3 · IAM：登录 / 角色 / 权限（FR-IAM-01、FR-IAM-02）
-- 依据：docs/02-领域模型、计划书 §2.1 批次 D
--
-- 设计要点：
--   ① 密码只存 BCrypt 哈希，永不存明文，也没有"可逆加密"的余地；
--   ② 账号锁定用**数据库列**记录（failed_attempts / locked_until），
--      不用内存计数 —— 否则多实例部署时锁定形同虚设，重启即解锁；
--   ③ 角色 → 权限用关联表，权限码是字符串（如 "pur:approve"），
--      便于新增功能时不动表结构。
-- ============================================================================

CREATE TABLE app_users (
    id               BIGSERIAL PRIMARY KEY,
    username         VARCHAR(64)  NOT NULL,
    -- ★ BCrypt 哈希（60 字符），长度留余量以支持未来更强的算法（如 argon2 前缀
    --   的 Spring 委托编码器，格式为 {argon2}... 会更长）
    password_hash    VARCHAR(100) NOT NULL,
    display_name     VARCHAR(64),
    enabled          BOOLEAN      NOT NULL DEFAULT TRUE,
    -- 密码错误计数与锁定截止时间（FR-IAM-01 密码错误锁定）
    failed_attempts  INTEGER      NOT NULL DEFAULT 0 CHECK (failed_attempts >= 0),
    locked_until     TIMESTAMPTZ,
    last_login_at    TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_app_user_username UNIQUE (username)
);
COMMENT ON TABLE app_users IS '登录账号。密码仅存 BCrypt 哈希';
COMMENT ON COLUMN app_users.locked_until IS '账号锁定截止时间；NULL 表示未锁定。锁在数据库层，多实例共享';

CREATE TABLE app_roles (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(32)  NOT NULL,
    name        VARCHAR(64)  NOT NULL,
    description VARCHAR(200),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_app_role_code UNIQUE (code)
);
COMMENT ON TABLE app_roles IS '角色（RBAC）。如 ADMIN / PURCHASER / SALESPERSON / VIEWER';

CREATE TABLE app_user_roles (
    user_id BIGINT NOT NULL REFERENCES app_users (id) ON DELETE CASCADE,
    role_id BIGINT NOT NULL REFERENCES app_roles (id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, role_id)
);
COMMENT ON TABLE app_user_roles IS '用户-角色关联（多对多）';

CREATE TABLE app_role_permissions (
    role_id     BIGINT      NOT NULL REFERENCES app_roles (id) ON DELETE CASCADE,
    permission  VARCHAR(64) NOT NULL,   -- 如 "pur:create" / "sal:ship" / "mdm:*"
    PRIMARY KEY (role_id, permission)
);
COMMENT ON TABLE app_role_permissions IS '角色-权限关联。权限码采用 "模块:动作"，支持 "模块:*" 通配';

-- ─────────────────────────── 种子角色（开发/测试用） ───────────────────────────
-- 注意：不在此插入任何用户账号 —— 账号必须由初始化流程创建，
--       把"admin/admin"这类默认弱口令写进迁移脚本是常见的生产事故来源。
--       本地开发用 scripts/seed-dev-user.ps1 显式创建。

INSERT INTO app_roles (code, name, description) VALUES
    ('ADMIN',       '系统管理员', '全部权限'),
    ('PURCHASER',   '采购员',     '具有主数据查看与进货全流程权限'),
    ('SALESPERSON', '销售员',     '具有主数据查看与出货全流程权限'),
    ('VIEWER',      '只读用户',   '仅可查看，不可做任何写操作');

INSERT INTO app_role_permissions (role_id, permission)
SELECT r.id, p.permission FROM app_roles r
JOIN (VALUES
    ('ADMIN', 'mdm:*'), ('ADMIN', 'pur:*'), ('ADMIN', 'sal:*'), ('ADMIN', 'inv:*'),
    ('PURCHASER', 'mdm:read'), ('PURCHASER', 'pur:create'), ('PURCHASER', 'pur:submit'),
    ('PURCHASER', 'pur:approve'), ('PURCHASER', 'pur:receive'), ('PURCHASER', 'pur:void'),
    ('PURCHASER', 'inv:read'),
    ('SALESPERSON', 'mdm:read'), ('SALESPERSON', 'sal:create'), ('SALESPERSON', 'sal:submit'),
    ('SALESPERSON', 'sal:ship'), ('SALESPERSON', 'sal:void'), ('SALESPERSON', 'inv:read'),
    ('VIEWER', 'mdm:read'), ('VIEWER', 'inv:read'), ('VIEWER', 'pur:read'), ('VIEWER', 'sal:read')
) AS p(role_code, permission) ON r.code = p.role_code;
