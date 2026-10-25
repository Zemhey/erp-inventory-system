-- ============================================================================
-- V19 · 骨架组（FR-V3-23 组织架构 / FR-V3-24 系统参数 / FR-V3-25 单号规则 /
--        FR-V3-29 登录日志）+ app_users 挂部门岗位
-- 范围：新增 5 张表 + app_users 加列 + 权限/配置/单号规则种子
--
-- ★ 幂等与租户：本文件在「裸 psql 验证」与「Flyway 在应用内执行」两条路径下都必须可跑。
--   裸 psql 没有 TenantSql 改写、也没有会话租户变量，因此本文件**所有 INSERT 都显式带
--   tenant_id**（新表）或与 tenants 做 CROSS JOIN（配置/单号规则按租户各造一份）。
--   Flyway 执行时 TenantSql 看到列里已有 tenant_id，不会重复注入（见 TenantSql 注释）。
-- ============================================================================

-- 兜底：若某些环境通过 Flyway 跑且会话租户未设置，让触发器有值可填（裸 psql 下被显式列覆盖，无副作用）
SET erp.tenant_id = '1';

-- ─────────────────────────── ① 组织架构 ───────────────────────────

CREATE TABLE sys_department (
    id           BIGSERIAL    PRIMARY KEY,
    tenant_id    BIGINT       NOT NULL,
    parent_id    BIGINT       REFERENCES sys_department (id) ON DELETE SET NULL,
    code         VARCHAR(32)  NOT NULL,
    name         VARCHAR(64)  NOT NULL,
    sort_no      INTEGER      NOT NULL DEFAULT 0,
    leader_user_id BIGINT,
    enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_sys_department_code UNIQUE (tenant_id, code)
);
COMMENT ON TABLE sys_department IS '组织架构：部门树（FR-V3-23）。parent_id 自引用，支持多级';

CREATE TABLE sys_position (
    id           BIGSERIAL    PRIMARY KEY,
    tenant_id    BIGINT       NOT NULL,
    department_id BIGINT      REFERENCES sys_department (id) ON DELETE CASCADE,
    code         VARCHAR(32)  NOT NULL,
    name         VARCHAR(64)  NOT NULL,
    level        INTEGER      NOT NULL DEFAULT 1,
    enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_sys_position_code UNIQUE (tenant_id, code)
);
COMMENT ON TABLE sys_position IS '组织架构：岗位（挂在部门下，FR-V3-23）';

-- ─────────────────────────── ② 系统参数 ───────────────────────────

CREATE TABLE sys_config (
    id           BIGSERIAL    PRIMARY KEY,
    tenant_id    BIGINT       NOT NULL,
    config_group VARCHAR(32)  NOT NULL,
    config_key   VARCHAR(64)  NOT NULL,
    config_value TEXT,
    value_type   VARCHAR(16)  NOT NULL DEFAULT 'STRING',
    remark       VARCHAR(200),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_sys_config_key UNIQUE (tenant_id, config_group, config_key)
);
COMMENT ON TABLE sys_config IS '系统参数（FR-V3-24）：按组展示的键值对，可编辑';
CREATE INDEX idx_sys_config_group ON sys_config (tenant_id, config_group);

-- ─────────────────────────── ③ 单号规则 ───────────────────────────

CREATE TABLE sys_number_rule (
    id           BIGSERIAL    PRIMARY KEY,
    tenant_id    BIGINT       NOT NULL,
    doc_type     VARCHAR(32)  NOT NULL,
    prefix       VARCHAR(16)  NOT NULL,
    date_pattern VARCHAR(16)  NOT NULL DEFAULT 'yyyyMMdd',
    seq_length   INTEGER      NOT NULL DEFAULT 4 CHECK (seq_length BETWEEN 2 AND 12),
    reset_cycle  VARCHAR(16)  NOT NULL DEFAULT 'DAILY' CHECK (reset_cycle IN ('NONE','DAILY','MONTHLY','YEARLY')),
    enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_sys_number_rule_doc UNIQUE (tenant_id, doc_type)
);
COMMENT ON TABLE sys_number_rule IS '单号规则（FR-V3-25）：定义前缀/日期格式/流水位长；实际流水仍落在 document_sequences';

-- ─────────────────────────── ④ 登录日志 ───────────────────────────

CREATE TABLE sys_login_log (
    id           BIGSERIAL    PRIMARY KEY,
    tenant_id    BIGINT       NOT NULL,
    user_id      BIGINT,
    username     VARCHAR(64)  NOT NULL,
    ip           VARCHAR(64),
    user_agent   VARCHAR(256),
    login_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    success      BOOLEAN      NOT NULL,
    fail_reason  VARCHAR(128),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
COMMENT ON TABLE sys_login_log IS '登录日志（FR-V3-29）：成功/失败均记录，失败标红';
CREATE INDEX idx_sys_login_log_tenant_time ON sys_login_log (tenant_id, login_at DESC);
CREATE INDEX idx_sys_login_log_user ON sys_login_log (tenant_id, user_id, login_at DESC);

-- ─────────────────────────── ⑤ app_users 挂部门/岗位（加列不加表）───────────────────────────

ALTER TABLE app_users
    ADD COLUMN IF NOT EXISTS department_id BIGINT REFERENCES sys_department (id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS position_id  BIGINT REFERENCES sys_position (id) ON DELETE SET NULL;
COMMENT ON COLUMN app_users.department_id IS '所属部门（FR-V3-23）';
COMMENT ON COLUMN app_users.position_id IS '所属岗位（FR-V3-23）';

-- 新表统一挂租户填充触发器，与既有 59 张表保持一致（app 写入经 TenantSql 已带 tenant_id，触发器仅兜底）
CREATE TRIGGER trg_sys_department_tenant_fill  BEFORE INSERT ON sys_department  FOR EACH ROW EXECUTE FUNCTION erp_tenant_fill();
CREATE TRIGGER trg_sys_position_tenant_fill   BEFORE INSERT ON sys_position   FOR EACH ROW EXECUTE FUNCTION erp_tenant_fill();
CREATE TRIGGER trg_sys_config_tenant_fill     BEFORE INSERT ON sys_config     FOR EACH ROW EXECUTE FUNCTION erp_tenant_fill();
CREATE TRIGGER trg_sys_number_rule_tenant_fill BEFORE INSERT ON sys_number_rule FOR EACH ROW EXECUTE FUNCTION erp_tenant_fill();
CREATE TRIGGER trg_sys_login_log_tenant_fill  BEFORE INSERT ON sys_login_log  FOR EACH ROW EXECUTE FUNCTION erp_tenant_fill();

-- ─────────────────────────── ⑥ 种子：默认组织根部门（每个租户一份）───────────────────────────

INSERT INTO sys_department (tenant_id, parent_id, code, name, sort_no)
SELECT t.id, NULL, 'ROOT', '总部', 0
FROM tenants t
ON CONFLICT (tenant_id, code) DO NOTHING;

INSERT INTO sys_position (tenant_id, department_id, code, name, level)
SELECT t.id, d.id, 'STAFF', '员工', 1
FROM tenants t
JOIN sys_department d ON d.tenant_id = t.id AND d.code = 'ROOT'
ON CONFLICT (tenant_id, code) DO NOTHING;

-- ─────────────────────────── ⑦ 种子：默认系统参数（每个租户一份）───────────────────────────

INSERT INTO sys_config (tenant_id, config_group, config_key, config_value, value_type, remark)
SELECT t.id, v.g, v.k, v.val, v.ty, v.remark
FROM tenants t
CROSS JOIN (VALUES
    ('document', 'companyName', '示例贸易有限公司', 'STRING', '打印在单据抬头的公司名称'),
    ('document', 'companyAddress', '上海市浦东新区世纪大道 1 号', 'STRING', '公司地址'),
    ('document', 'defaultWarehouse', '', 'STRING', '默认出货仓库编码（留空表示不预设）'),
    ('inventory', 'safetyStockDefault', '10', 'INTEGER', '新建 SKU 的默认安全库存下限'),
    ('inventory', 'negativeStockForbidden', 'true', 'BOOLEAN', '是否禁止负库存'),
    ('finance', 'defaultCurrency', 'CNY', 'STRING', '本位币'),
    ('finance', 'creditCheckEnabled', 'true', 'BOOLEAN', '是否启用信用额度检查'),
    ('notice', 'orderPaidSms', 'false', 'BOOLEAN', '订单付款后是否发送短信通知'),
    ('notice', 'lowStockWarn', 'true', 'BOOLEAN', '库存低于安全线是否预警')
) AS v(g, k, val, ty, remark)
ON CONFLICT (tenant_id, config_group, config_key) DO NOTHING;

-- ─────────────────────────── ⑧ 种子：默认单号规则（每个租户一份，前缀与既有枚举一致 → 行为不变）───────────────────────────

INSERT INTO sys_number_rule (tenant_id, doc_type, prefix, date_pattern, seq_length, reset_cycle, enabled)
SELECT t.id, v.dt, v.pf, 'yyyyMMdd', 4, 'DAILY', TRUE
FROM tenants t
CROSS JOIN (VALUES
    ('PURCHASE_ORDER', 'PO'),
    ('PURCHASE_REQUISITION', 'PR'),
    ('PURCHASE_RECEIPT', 'GR'),
    ('PURCHASE_RETURN', 'RT'),
    ('SUPPLIER_PAYABLE', 'AP'),
    ('SUPPLIER_PAYMENT', 'PY'),
    ('SALES_ORDER', 'SO'),
    ('SALES_QUOTE', 'QT'),
    ('SHIPMENT', 'SP'),
    ('RECEIPT', 'RC'),
    ('RETURN_ORDER', 'RO'),
    ('TRANSFER_ORDER', 'TO'),
    ('STOCKTAKE_ORDER', 'ST'),
    ('ADJUSTMENT', 'AJ')
) AS v(dt, pf)
ON CONFLICT (tenant_id, doc_type) DO NOTHING;

-- ─────────────────────────── ⑨ 权限种子：ADMIN 补骨架组新权限码 ───────────────────────────
-- ADMIN 已有 sys:* 通配，这里再显式补 org/config/numberrule/loginlog，便于界面/审计可见；
-- 其他角色（VIEWER/FINANCE 等）不授予这些管理码 —— 它们是管理员职能。

INSERT INTO app_role_permissions (role_id, permission, tenant_id)
SELECT r.id, p.permission, t.id
FROM tenants t
JOIN app_roles r ON r.tenant_id = t.id
JOIN (VALUES
    ('ADMIN', 'sys:org'),
    ('ADMIN', 'sys:config'),
    ('ADMIN', 'sys:numberrule'),
    ('ADMIN', 'sys:loginlog'),
    ('ADMIN', 'dashboard:read')
) AS p(role_code, permission) ON p.role_code = r.code
ON CONFLICT (role_id, permission) DO NOTHING;
