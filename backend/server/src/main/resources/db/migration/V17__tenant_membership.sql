-- ============================================================================
-- V17 · 多租户成员与平台租户管理员（Batch B · B1 · FR-TNT-07/08）
--
-- 目标：让"一个人属于多个租户"成为**库层事实**，而不是前端显示出来的假象。
--   · app_users.tenant_id 保留为**主租户**（home tenant），不删不改语义；
--   · app_user_tenants 记录"用户 × 租户"成员关系，主租户也有一行；
--   · 平台租户（id = 1，即 V16 的 default）的 ADMIN 角色获得 tenant:manage，
--     其它租户的 ADMIN **刻意不获得**（自检会挡住"悄悄下放"）。
--
-- 为什么主租户也要落一行，而不是"主租户靠 app_users.tenant_id 隐式成立"：
--   ① 登录/切换时只需要读**一张表**就能回答"这个人属于哪些租户"，
--      不会出现"成员表与主租户字段两套事实、改了一边忘了另一边"；
--   ② 唯一约束 uq_app_user_tenants 对**所有**成员关系生效（含主租户）；
--   ③ 用户插入时由 AFTER INSERT 触发器补主租户行，应用层不需要记得写。
--
-- 为什么 app_user_tenants 也带 tenant_id（它明明是"用户×租户"的关联表）：
--   这是本项目的统一约定 —— 除 tenants 外每一张业务表都带 tenant_id，并纳入
--   TenantTables / TenantSql 的改写范围。这样"按当前租户查成员"自动带谓词，
--   而"列出某人的全部成员关系"必须显式走 TenantContext.callAsSystem（跨租户）。
--   关联表若不带 tenant_id，它就会成为 TenantSchemaIT 的清单漂移点。
--
-- 回滚（Flyway 不自动回滚；需要时人工按序执行）：
--   DROP TRIGGER IF EXISTS trg_app_users_home_membership ON app_users;
--   DROP FUNCTION IF EXISTS erp_user_home_membership();
--   DROP TRIGGER IF EXISTS trg_app_user_tenants_tenant_fill ON app_user_tenants;
--   DROP TABLE IF EXISTS app_user_tenants;
--   DELETE FROM app_role_permissions rp
--    USING app_roles r
--    WHERE rp.role_id = r.id AND rp.permission = 'tenant:manage'
--      AND r.tenant_id = 1 AND r.code = 'ADMIN';
-- ============================================================================

-- ─────────────────────────── ⓪ 成员关系表 ───────────────────────────
CREATE TABLE IF NOT EXISTS app_user_tenants (
    user_id    BIGINT      NOT NULL REFERENCES app_users (id) ON DELETE CASCADE,
    tenant_id  BIGINT      NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 唯一约束是这个模型的硬要求：同一用户在同一租户只能有一条成员关系。
    -- 用主键而不是普通唯一索引，是为了让"重复插入"在库层就是非法状态。
    CONSTRAINT pk_app_user_tenants PRIMARY KEY (user_id, tenant_id)
);
COMMENT ON TABLE app_user_tenants IS
    '用户×租户成员关系（B1）。主租户也有一行；用户的其它租户由此表追加。唯一约束在库层保证不重复';
COMMENT ON COLUMN app_user_tenants.tenant_id IS
    '成员关系所属的租户；该行只在这个租户的上下文中可见（跨租户枚举必须走系统旁路）';

-- 反向查询（某租户有哪些成员）走这个索引；正向查询用主键前缀 user_id 即可。
CREATE INDEX IF NOT EXISTS idx_app_user_tenants_tenant
    ON app_user_tenants (tenant_id, user_id);

-- ─────────────────────────── ① 回填：存量用户都是主租户成员 ─────────────────
-- 与 V16 的"纯追加、可重复执行"保持一致：只补缺的行，已有成员关系一律不动。
INSERT INTO app_user_tenants (user_id, tenant_id)
SELECT u.id, u.tenant_id
  FROM app_users u
ON CONFLICT (user_id, tenant_id) DO NOTHING;

-- ─────────────────────────── ② 与 V16 业务表一致的租户填充触发器 ────────────
-- 显式给了 tenant_id（迁移/运维/造数）就用它；否则取会话租户。
-- 语义与 V16 §⑨ 完全相同，避免"新表漏装填充触发器"导致直接 SQL 插入失败。
DROP TRIGGER IF EXISTS trg_app_user_tenants_tenant_fill ON app_user_tenants;
CREATE TRIGGER trg_app_user_tenants_tenant_fill BEFORE INSERT ON app_user_tenants
    FOR EACH ROW EXECUTE FUNCTION erp_tenant_fill();

-- ─────────────────────────── ③ 用户插入 ⇒ 自动成为主租户成员 ─────────────────
-- 为什么用 AFTER INSERT 触发器而不是要求每个建号路径自己插入：
--   建号路径不止一条（UserAdminService、测试造数、运维脚本），漏一条的症状是
--   "新账号能建出来但登录 403/查不到成员" —— 而漏的那条不会有编译错误。
--   把不变量放进库层，新增路径自动成立。
CREATE OR REPLACE FUNCTION erp_user_home_membership() RETURNS trigger AS $$
BEGIN
    INSERT INTO app_user_tenants (user_id, tenant_id)
    VALUES (NEW.id, NEW.tenant_id)
    ON CONFLICT (user_id, tenant_id) DO NOTHING;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION erp_user_home_membership() IS
    'app_users 插入后自动写入主租户成员关系；只补缺，不覆盖已有的额外成员关系';

DROP TRIGGER IF EXISTS trg_app_users_home_membership ON app_users;
CREATE TRIGGER trg_app_users_home_membership AFTER INSERT ON app_users
    FOR EACH ROW EXECUTE FUNCTION erp_user_home_membership();

-- ─────────────────────────── ④ 平台租户管理员权限（只种平台租户） ─────────────
-- 平台租户 = 默认租户 id = 1（V16 的存量归属）。平台管理员不是"跨租户业务数据
-- 超级管理员"：tenant:manage 只允许读写 tenants 元数据，任何业务表仍由 TenantSql
-- 按当前租户过滤 —— 这条边界由 TenantAdminIT 的跨租户 404 用例守着。
--
-- ★ 刻意只给 tenant_id = 1 的 ADMIN，不给租户 2 的 ADMIN：
--   V16 把默认租户的角色/权限复制到演示租户，若这里按 role.code 全局加，
--   演示租户的管理员也会拿到平台权限，而需求明确要求"普通租户管理员看不到、调不通"。
INSERT INTO app_role_permissions (role_id, permission, tenant_id)
SELECT r.id, 'tenant:manage', r.tenant_id
  FROM app_roles r
 WHERE r.tenant_id = 1 AND r.code = 'ADMIN'
ON CONFLICT (role_id, permission) DO NOTHING;

-- ─────────────────────────── ⑤ 迁移自检（不变量必须立即可断言） ─────────────
DO $$
DECLARE
    missing bigint;
    platform int;
    ordinary int;
BEGIN
    -- ① 每个账号都必须是其主租户的成员（回填/触发器漏了都会在这里炸）
    SELECT count(*) INTO missing
      FROM app_users u
     WHERE NOT EXISTS (
           SELECT 1 FROM app_user_tenants ut
            WHERE ut.user_id = u.id AND ut.tenant_id = u.tenant_id);
    IF missing > 0 THEN
        RAISE EXCEPTION 'V17 自检失败：% 个账号缺少主租户成员关系', missing;
    END IF;

    -- ② 平台租户的 ADMIN 必须恰好拥有 tenant:manage
    SELECT count(*) INTO platform
      FROM app_role_permissions rp
      JOIN app_roles r ON r.id = rp.role_id
     WHERE r.tenant_id = 1 AND r.code = 'ADMIN' AND rp.permission = 'tenant:manage';
    IF platform <> 1 THEN
        RAISE EXCEPTION 'V17 自检失败：平台租户 ADMIN 的 tenant:manage 种入行数为 %（应为 1）', platform;
    END IF;

    -- ③ 普通租户（演示租户）的 ADMIN **不能**拥有平台权限。
    --    这条断言让"为了图省事把权限全员下发"在迁移期就失败，而不是等验收时才发现。
    SELECT count(*) INTO ordinary
      FROM app_role_permissions rp
      JOIN app_roles r ON r.id = rp.role_id
     WHERE r.tenant_id <> 1 AND r.code = 'ADMIN' AND rp.permission = 'tenant:manage';
    IF ordinary > 0 THEN
        RAISE EXCEPTION 'V17 自检失败：非平台租户的 ADMIN 获得了 tenant:manage（% 行）', ordinary;
    END IF;
END $$;
