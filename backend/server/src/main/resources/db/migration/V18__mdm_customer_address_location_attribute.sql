-- ============================================================================
-- V18 · Batch B · B2 主数据字段扩展（客户收货地址 / 仓库库位 / 商品规格属性）
--
-- 依据：.plan/B2-主数据字段-计划书.md
-- 迁移排号：B1 占 V17，本泳道固定 V18（即使 V17 最终为空也不改号）。
--
-- 设计原则（延续 V1~V17）：
--   ① **纯追加**：不改 V1~V17、不删既有列/约束；新列带默认值，存量库可平滑升级；
--   ② 三张新表都带 tenant_id，唯一约束一律以 (tenant_id, ...) 开头；
--      新表装 V16 的 erp_tenant_fill() BEFORE INSERT 触发器（会话租户缺失时
--      NOT NULL 立即报错，而不是静默落进默认租户）；
--   ③ 历史可追溯：地址/库位只软停用，不提供物理删除；外键 ON DELETE RESTRICT；
--   ④ 可回滚（见文件末尾 Rollback 注释）。
--
-- 守恒口径（库位）：SUM(启用库位 capacity_qty) == warehouses.capacity_qty。
--   warehouses.capacity_qty = 0 表示“未设定容量”，此时不建上限；
--   核对端点照实返回等式结果，不把 0 特判成“一致”。
-- ============================================================================

-- ─────────────────────────── ① 仓库容量 ───────────────────────────
-- 存量行自动填 0（= 未设定），不需要回填脚本。
ALTER TABLE warehouses
    ADD COLUMN IF NOT EXISTS capacity_qty BIGINT NOT NULL DEFAULT 0;
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conname = 'ck_warehouse_capacity_non_negative'
           AND conrelid = 'warehouses'::regclass) THEN
        ALTER TABLE warehouses
            ADD CONSTRAINT ck_warehouse_capacity_non_negative CHECK (capacity_qty >= 0);
    END IF;
END $$;
COMMENT ON COLUMN warehouses.capacity_qty IS
    '仓库容量（件）。0 = 未设定；>0 时启用库位容量合计不得超过该值（V18）';

-- ─────────────────────────── ② 客户收货地址（一对多） ───────────────────────────
CREATE TABLE IF NOT EXISTS customer_addresses (
    id                BIGSERIAL PRIMARY KEY,
    tenant_id         BIGINT       NOT NULL,
    customer_id       BIGINT       NOT NULL REFERENCES customers (id) ON DELETE RESTRICT,
    address_key       VARCHAR(32)  NOT NULL,
    recipient_name    VARCHAR(64)  NOT NULL,
    phone             VARCHAR(32)  NOT NULL,
    province          VARCHAR(32)  NOT NULL,
    city              VARCHAR(32)  NOT NULL,
    district          VARCHAR(32),
    detail_address    VARCHAR(200) NOT NULL,
    is_default        BOOLEAN      NOT NULL DEFAULT FALSE,
    enabled           BOOLEAN      NOT NULL DEFAULT TRUE,
    create_request_id VARCHAR(64),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_customer_address_key UNIQUE (tenant_id, customer_id, address_key),
    CONSTRAINT ck_customer_address_text CHECK (
        btrim(address_key) <> '' AND btrim(recipient_name) <> ''
        AND btrim(phone) <> '' AND btrim(province) <> ''
        AND btrim(city) <> '' AND btrim(detail_address) <> ''),
    -- 默认地址必须是启用状态；停用必须先摘掉默认（应用层在同一事务里完成）
    CONSTRAINT ck_customer_address_default_enabled CHECK (NOT is_default OR enabled)
);
COMMENT ON TABLE customer_addresses IS
    '客户收货地址（一对多）。同一客户最多一个默认地址（部分唯一索引），停用不删除';
COMMENT ON COLUMN customer_addresses.address_key IS
    '地址业务键，客户内唯一；创建后不可修改（与商品/仓库编码同规则）';

-- 同一客户最多一个默认地址 —— 应用层切换默认时也靠它做最终兜底
CREATE UNIQUE INDEX IF NOT EXISTS uq_customer_address_default
    ON customer_addresses (tenant_id, customer_id) WHERE is_default;

-- 建单幂等键（同一租户内一个键只能建一条地址；跨客户复用由应用层翻成 409）
CREATE UNIQUE INDEX IF NOT EXISTS uq_customer_address_create_request
    ON customer_addresses (tenant_id, create_request_id) WHERE create_request_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_customer_address_customer
    ON customer_addresses (tenant_id, customer_id, id);

-- ─────────────────────────── ③ 仓库库位 ───────────────────────────
CREATE TABLE IF NOT EXISTS warehouse_locations (
    id                BIGSERIAL PRIMARY KEY,
    tenant_id         BIGINT       NOT NULL,
    warehouse_id      BIGINT       NOT NULL REFERENCES warehouses (id) ON DELETE RESTRICT,
    location_code     VARCHAR(32)  NOT NULL,
    name              VARCHAR(64),
    -- 库位容量必须为正：守恒口径是“启用库位容量合计 vs 仓库容量”，0 会让合计失去意义
    capacity_qty      BIGINT       NOT NULL CHECK (capacity_qty > 0),
    enabled           BOOLEAN      NOT NULL DEFAULT TRUE,
    create_request_id VARCHAR(64),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_warehouse_location_code
        UNIQUE (tenant_id, warehouse_id, location_code),
    CONSTRAINT ck_warehouse_location_code_not_blank CHECK (btrim(location_code) <> '')
);
COMMENT ON TABLE warehouse_locations IS
    '仓库库位。编码仓库内唯一；库存余额维度不含库位（V1 口径），停用不影响历史库存';
COMMENT ON COLUMN warehouse_locations.capacity_qty IS
    '库位容量（件，必须 > 0）。启用库位容量合计不得超过 warehouses.capacity_qty';

CREATE UNIQUE INDEX IF NOT EXISTS uq_warehouse_location_create_request
    ON warehouse_locations (tenant_id, create_request_id) WHERE create_request_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_warehouse_location_warehouse
    ON warehouse_locations (tenant_id, warehouse_id, id);

-- ─────────────────────────── ④ 商品规格属性（键值） ───────────────────────────
CREATE TABLE IF NOT EXISTS product_attribute_values (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT       NOT NULL,
    product_id      BIGINT       NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    attribute_key   VARCHAR(32)  NOT NULL,
    attribute_value VARCHAR(256) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_product_attribute_key
        UNIQUE (tenant_id, product_id, attribute_key),
    CONSTRAINT ck_product_attribute_key_not_blank CHECK (btrim(attribute_key) <> '')
);
COMMENT ON TABLE product_attribute_values IS
    '商品级扩展规格属性（键值）。不参与 SKU 颜色×尺码唯一键，也不替代 season/band';

CREATE INDEX IF NOT EXISTS idx_product_attribute_product
    ON product_attribute_values (tenant_id, product_id, id);

-- ─────────────────────────── ⑤ 租户填充触发器（复用 V16 函数） ───────────────────────────
-- 与 V16 的 55 张表同一语义：显式给了 tenant_id 就用它；否则取会话租户。
-- 应用路径由 TenantSql 在 INSERT 列清单/值里注入租户，这里是数据库层兜底。
DROP TRIGGER IF EXISTS trg_customer_addresses_tenant_fill ON customer_addresses;
CREATE TRIGGER trg_customer_addresses_tenant_fill BEFORE INSERT ON customer_addresses
    FOR EACH ROW EXECUTE FUNCTION erp_tenant_fill();

DROP TRIGGER IF EXISTS trg_warehouse_locations_tenant_fill ON warehouse_locations;
CREATE TRIGGER trg_warehouse_locations_tenant_fill BEFORE INSERT ON warehouse_locations
    FOR EACH ROW EXECUTE FUNCTION erp_tenant_fill();

DROP TRIGGER IF EXISTS trg_product_attribute_values_tenant_fill ON product_attribute_values;
CREATE TRIGGER trg_product_attribute_values_tenant_fill BEFORE INSERT ON product_attribute_values
    FOR EACH ROW EXECUTE FUNCTION erp_tenant_fill();

-- ─────────────────────────── ⑥ 迁移自检 ───────────────────────────
-- 新表此刻为空，断言的是“表存在且 tenant_id 为 NOT NULL”；
-- 存量 warehouses 的 capacity_qty 不允许为 NULL。
DO $$
DECLARE n bigint;
BEGIN
    SELECT count(*) INTO n FROM information_schema.columns
     WHERE table_schema = 'public'
       AND table_name IN ('customer_addresses', 'warehouse_locations', 'product_attribute_values')
       AND column_name = 'tenant_id'
       AND is_nullable = 'YES';
    IF n > 0 THEN
        RAISE EXCEPTION 'V18 自检失败：新表 tenant_id 必须 NOT NULL（发现 % 列可空）', n;
    END IF;

    SELECT count(*) INTO n FROM warehouses WHERE capacity_qty IS NULL;
    IF n > 0 THEN
        RAISE EXCEPTION 'V18 自检失败：warehouses.capacity_qty 仍有 % 行为 NULL', n;
    END IF;
END $$;

-- ============================================================================
-- Rollback（手工执行；Flyway 社区版不自动回滚）：
--   DROP TABLE IF EXISTS product_attribute_values;
--   DROP TABLE IF EXISTS warehouse_locations;
--   DROP TABLE IF EXISTS customer_addresses;
--   ALTER TABLE warehouses DROP CONSTRAINT IF EXISTS ck_warehouse_capacity_non_negative;
--   ALTER TABLE warehouses DROP COLUMN IF EXISTS capacity_qty;
-- 回滚会丢失三块扩展字段的数据；V1~V17 的结构与数据不受影响。
-- ============================================================================
