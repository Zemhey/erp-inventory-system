-- ═══════════════════════════════════════════════════════════════════════════
-- V21 · V3-3 泳道：PRM 价格方案与促销活动
--
-- 口径说明（答辩会问，先写死在这里）：
--   * 金额列一律用 BIGINT「分」，与 sal 域（skus.retail_price_amount、
--     quote/unitPriceAmount、bundle priceAmount、creditLimit）保持同一口径；
--     numeric(18,2) 是 cust/set 域的口径，新表跨不过去，混用必错 100 倍。
--   * 折扣用 INTEGER「万分比」（10000 = 不打折），对齐 customers.discount_rate。
--   * 所有新表 tenant_id 允许为空（V20 的 sys_login_log NOT NULL 教训）：
--     写入时租户上下文未解析也不能打挂主链路，隔离查询靠 TenantSql 改写。
-- ═══════════════════════════════════════════════════════════════════════════

CREATE TABLE IF NOT EXISTS prm_price_plan (
    id             BIGSERIAL    PRIMARY KEY,
    tenant_id      BIGINT,
    code           VARCHAR(32)  NOT NULL,
    name           VARCHAR(128) NOT NULL,
    plan_type      VARCHAR(24)  NOT NULL,
    customer_id    BIGINT,
    customer_level VARCHAR(16),
    priority       INTEGER      NOT NULL DEFAULT 0,
    start_date     DATE,
    end_date       DATE,
    enabled        BOOLEAN      NOT NULL DEFAULT TRUE,
    remark         VARCHAR(500),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_prm_plan_type CHECK (plan_type IN ('AGREEMENT', 'CUSTOMER', 'CUSTOMER_LEVEL'))
);
COMMENT ON TABLE prm_price_plan IS '价格方案头（FR-V3-20）。AGREEMENT=客户协议价 / CUSTOMER=客户专属价 / CUSTOMER_LEVEL=客户等级价';
COMMENT ON COLUMN prm_price_plan.priority IS '同类型内优先级，越大越优先；类型间优先级固定在代码里（协议>专属>等级>标准售价）';

CREATE UNIQUE INDEX IF NOT EXISTS uk_prm_price_plan_code ON prm_price_plan (code);
CREATE INDEX IF NOT EXISTS idx_prm_price_plan_tenant ON prm_price_plan (tenant_id);

CREATE TABLE IF NOT EXISTS prm_price_plan_item (
    id           BIGSERIAL   PRIMARY KEY,
    tenant_id    BIGINT,
    plan_id      BIGINT      NOT NULL REFERENCES prm_price_plan (id),
    sku_id       BIGINT      NOT NULL,
    price_amount BIGINT      NOT NULL CHECK (price_amount >= 0),
    discount_bp  INTEGER     NOT NULL DEFAULT 10000 CHECK (discount_bp BETWEEN 0 AND 10000),
    min_qty      INTEGER     NOT NULL DEFAULT 0 CHECK (min_qty >= 0),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON COLUMN prm_price_plan_item.price_amount IS '单价（分），与 skus.retail_price_amount 同口径';
COMMENT ON COLUMN prm_price_plan_item.discount_bp IS '万分比折扣（10000=不打折），生效价 = price_amount × discount_bp ÷ 10000，四舍五入到分';
COMMENT ON COLUMN prm_price_plan_item.min_qty IS '数量门槛：请求量 < min_qty 时该明细行不参与取价';

CREATE UNIQUE INDEX IF NOT EXISTS uk_prm_plan_item ON prm_price_plan_item (plan_id, sku_id);
CREATE INDEX IF NOT EXISTS idx_prm_plan_item_sku ON prm_price_plan_item (sku_id);

CREATE TABLE IF NOT EXISTS prm_promotion (
    id         BIGSERIAL    PRIMARY KEY,
    tenant_id  BIGINT,
    code       VARCHAR(32)  NOT NULL,
    name       VARCHAR(128) NOT NULL,
    promo_type VARCHAR(24)  NOT NULL,
    start_time TIMESTAMPTZ,
    end_time   TIMESTAMPTZ,
    priority   INTEGER      NOT NULL DEFAULT 0,
    status     VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    remark     VARCHAR(500),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_prm_promo_type CHECK (promo_type IN ('TIME_DISCOUNT', 'FULL_REDUCE', 'QTY_DISCOUNT')),
    CONSTRAINT ck_prm_promo_status CHECK (status IN ('DRAFT', 'ACTIVE', 'STOPPED'))
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_prm_promotion_code ON prm_promotion (code);
CREATE INDEX IF NOT EXISTS idx_prm_promotion_tenant ON prm_promotion (tenant_id);

CREATE TABLE IF NOT EXISTS prm_promotion_rule (
    id             BIGSERIAL   PRIMARY KEY,
    tenant_id      BIGINT,
    promotion_id   BIGINT      NOT NULL REFERENCES prm_promotion (id),
    condition_json TEXT,
    benefit_json   TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE prm_promotion_rule IS '促销规则明细：condition/benefit 存 JSON 字符串（作业口径，不做结构化校验）';
CREATE INDEX IF NOT EXISTS idx_prm_promo_rule_promo ON prm_promotion_rule (promotion_id);

-- ─────────────────────────── 权限种子（幂等） ───────────────────────────
-- ADMIN 在 PermissionAspect 有 ROLE_ADMIN 短路，但前端菜单按权限码过滤、
-- 且 prm:* 从未种过；这里补齐，界面/审计数据上才算完整（同 V14 做法）。
-- tenant_id 取角色自己的租户（V16 已把存量角色回填为 1）。
INSERT INTO app_role_permissions (role_id, permission, tenant_id)
SELECT r.id, p.permission, r.tenant_id FROM app_roles r
JOIN (VALUES
    ('ADMIN', 'prm:*'), ('ADMIN', 'sys:read'),
    ('SALESPERSON', 'prm:read'),
    ('VIEWER', 'prm:read')
) AS p(role_code, permission) ON r.code = p.role_code
WHERE NOT EXISTS (
    SELECT 1 FROM app_role_permissions x
    WHERE x.role_id = r.id AND x.permission = p.permission
)
ON CONFLICT DO NOTHING;
