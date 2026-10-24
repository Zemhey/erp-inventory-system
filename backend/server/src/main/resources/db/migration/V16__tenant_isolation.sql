-- ============================================================================
-- V16 · 多租户隔离（V2-7 · FR-TNT-01~06）
-- 生成器：.plan/_gen_v16.py（纯文本拼装，表名 / 约束名 / 列清单全部显式声明）
--
-- ★ 三个硬要求（下发包 §5），本文件逐条对应：
--   ① 不碎库，分步：ADD COLUMN（可空、无默认）→ 回填 → 建索引 → 复合唯一
--      → 装填充触发器 → 自检 → 最后 NOT NULL 与 RLS。任何一步失败都不会留下半张表。
--      **不加静态 DEFAULT**，改为「BEFORE INSERT 触发器按会话租户填充」：
--      静态 DEFAULT 1 会把"应用层漏写租户"变成静默落进默认租户
--      （租户放错是这套系统里最危险的一类缺陷）；动态默认才是多租户的正确语义。
--   ② 对存量库友好：回填全部带 WHERE tenant_id IS NULL；DDL 全部 IF NOT EXISTS /
--      存在性判断；同一文件可重复执行。
--   ③ audit_logs：只**追加**索引与 RLS 策略，不 ADD COLUMN（V13 已有列）、
--      不回填、不改 NOT NULL / 默认值、不动 append-only 触发器与 V13 的三条既有索引。
--      它上面任何 UPDATE 都会被 audit_logs_append_only() 拒绝，回填在这里根本不可行。
--
-- ★ 隔离的强度落在**数据库层（RLS）**，而不是靠改 366 个 JdbcTemplate 调用点。
--   本项目没有实体映射层可挂 @TenantId，靠人肉给每条 SQL 补 WHERE 必然漏一处，
--   而漏掉的那一处就是跨租户泄露。RLS + FORCE 让**每一条**查询（含子查询、JOIN、
--   聚合、EXISTS、ON CONFLICT）在内核里被强制过滤，新写的仓储自动安全。
--
-- 设计原则（延续 V1~V15）：**纯追加**。不删列、不删表、不改任何既有 CHECK。
-- ============================================================================

-- ─────────────────────────── ⓪ 租户实体（FR-TNT-01） ───────────────────────────
-- 唯一一张**没有** tenant_id 的业务表：它就是租户的定义本身。
CREATE TABLE IF NOT EXISTS tenants (
    id         BIGSERIAL    PRIMARY KEY,
    code       VARCHAR(32)  NOT NULL,
    name       VARCHAR(128) NOT NULL,
    enabled    BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_tenant_code UNIQUE (code)
);
COMMENT ON TABLE tenants IS
    '租户（FR-TNT-01）。除本表外所有业务表都带 tenant_id，由 RLS 强制过滤';
COMMENT ON COLUMN tenants.code IS
    'default = 存量单租户历史数据的归属；demo = 演示与隔离验收用的第二套数据';

-- 两套种子：默认租户（存量归属）+ 演示租户（隔离验收的第二套）。
-- 显式给 id 是为了让 Java 常量（TenantId.DEFAULT = 1 / DEMO = 2）与库内取值一致。
INSERT INTO tenants (id, code, name) VALUES
    (1, 'default', '默认租户'),
    (2, 'demo',    '演示租户')
ON CONFLICT (id) DO NOTHING;
-- 序列推到已用最大值之后，否则下一次 INSERT 会撞 id = 1 / 2
SELECT setval('tenants_id_seq',
              GREATEST((SELECT COALESCE(MAX(id), 1) FROM tenants), 2));

-- ─────────────────────────── ⓪' 两个 helper 函数 ───────────────────────────────
-- current_setting(..., true) = "不存在时返回 NULL 而不是报错"。
-- ★ 这里刻意**不**回落默认租户：回落到 1 会让"漏写租户"的写入静默落进默认租户，
--   而那正是本泳道最不能接受的缺陷。未设置会话变量时返回 NULL → 下游的
--   NOT NULL 约束立刻报错，把"没有租户归属"变成**响亮**的失败。
--   （应用侧不依赖它：所有写入都由 TenantSql 把 tenant_id 显式写进列清单。）
CREATE OR REPLACE FUNCTION erp_tenant_current() RETURNS bigint
    LANGUAGE sql STABLE AS $$
        SELECT NULLIF(current_setting('erp.tenant_id', true), '')::bigint
    $$;
COMMENT ON FUNCTION erp_tenant_current() IS
    '当前会话的租户 id。未设置时返回 NULL（不回落默认租户：宁可报错也不静默落错租户）';

-- ★ 旁路开关，只给"登录时按用户名跨租户定位账号"与运维脚本用。
--   它不参与业务读写：业务读写的租户维一律由 TenantSql 注入。
CREATE OR REPLACE FUNCTION erp_tenant_bypass() RETURNS boolean
    LANGUAGE sql STABLE AS $$
        SELECT COALESCE(current_setting('erp.tenant_bypass', true), 'off') = 'on'
    $$;
COMMENT ON FUNCTION erp_tenant_bypass() IS
    '系统旁路标记（仅供诊断与登录定位账号）；业务读写路径不得打开';


-- ─────────────────────────── ① 加列（可空，无默认值） ────────────────
-- 可空是刻意的：存量行在这一步全部是 NULL，回填的边界因此精确、可断言。

ALTER TABLE warehouses ADD COLUMN IF NOT EXISTS tenant_id BIGINT;
ALTER TABLE products ADD COLUMN IF NOT EXISTS tenant_id BIGINT;
ALTER TABLE skus ADD COLUMN IF NOT EXISTS tenant_id BIGINT;
ALTER TABLE suppliers ADD COLUMN IF NOT EXISTS tenant_id BIGINT;
ALTER TABLE customers ADD COLUMN IF NOT EXISTS tenant_id BIGINT;
ALTER TABLE inventory_balances ADD COLUMN IF NOT EXISTS tenant_id BIGINT;
ALTER TABLE inventory_ledger ADD COLUMN IF NOT EXISTS tenant_id BIGINT;
ALTER TABLE inventory_reservations ADD COLUMN IF NOT EXISTS tenant_id BIGINT;
ALTER TABLE inventory_transfers ADD COLUMN IF NOT EXISTS tenant_id BIGINT;
ALTER TABLE transfer_lines ADD COLUMN IF NOT EXISTS tenant_id BIGINT;
ALTER TABLE stocktakes ADD COLUMN IF NOT EXISTS tenant_id BIGINT;
ALTER TABLE stocktake_lines ADD COLUMN IF NOT EXISTS tenant_id BIGINT;
ALTER TABLE inventory_adjustments ADD COLUMN IF NOT EXISTS tenant_id BIGINT;
ALTER TABLE inventory_freezes ADD COLUMN IF NOT EXISTS tenant_id BIGINT;
ALTER TABLE idempotency_keys ADD COLUMN IF NOT EXISTS tenant_id BIGINT;
ALTER TABLE document_sequences ADD COLUMN IF NOT EXISTS tenant_id BIGINT;
ALTER TABLE purchase_orders ADD COLUMN IF NOT EXISTS tenant_id BIGINT;
ALTER TABLE purchase_order_lines ADD COLUMN IF NOT EXISTS tenant_id BIGINT;
ALTER TABLE po_receipts ADD COLUMN IF NOT EXISTS tenant_id BIGINT;
ALTER TABLE po_receipt_lines ADD COLUMN IF NOT EXISTS tenant_id BIGINT;
ALTER TABLE supplier_payables ADD COLUMN IF NOT EXISTS tenant_id BIGINT;
ALTER TABLE payable_allocations ADD COLUMN IF NOT EXISTS tenant_id BIGINT;
ALTER TABLE purchase_returns ADD COLUMN IF NOT EXISTS tenant_id BIGINT;
ALTER TABLE sales_orders ADD COLUMN IF NOT EXISTS tenant_id BIGINT;
ALTER TABLE sales_order_lines ADD COLUMN IF NOT EXISTS tenant_id BIGINT;
ALTER TABLE sales_order_line_reservations ADD COLUMN IF NOT EXISTS tenant_id BIGINT;
ALTER TABLE product_bundles ADD COLUMN IF NOT EXISTS tenant_id BIGINT;
ALTER TABLE bundle_items ADD COLUMN IF NOT EXISTS tenant_id BIGINT;
ALTER TABLE sales_quotes ADD COLUMN IF NOT EXISTS tenant_id BIGINT;
ALTER TABLE sales_quote_lines ADD COLUMN IF NOT EXISTS tenant_id BIGINT;
ALTER TABLE so_shipment_plans ADD COLUMN IF NOT EXISTS tenant_id BIGINT;
ALTER TABLE so_shipments ADD COLUMN IF NOT EXISTS tenant_id BIGINT;
ALTER TABLE shipment_lines ADD COLUMN IF NOT EXISTS tenant_id BIGINT;
ALTER TABLE customer_receivables ADD COLUMN IF NOT EXISTS tenant_id BIGINT;
ALTER TABLE customer_receipts ADD COLUMN IF NOT EXISTS tenant_id BIGINT;
ALTER TABLE receivable_allocations ADD COLUMN IF NOT EXISTS tenant_id BIGINT;
ALTER TABLE customer_credit_snapshots ADD COLUMN IF NOT EXISTS tenant_id BIGINT;
ALTER TABLE return_orders ADD COLUMN IF NOT EXISTS tenant_id BIGINT;
ALTER TABLE return_order_lines ADD COLUMN IF NOT EXISTS tenant_id BIGINT;
ALTER TABLE settlement_periods ADD COLUMN IF NOT EXISTS tenant_id BIGINT;
ALTER TABLE daily_settlements ADD COLUMN IF NOT EXISTS tenant_id BIGINT;
ALTER TABLE cost_layers ADD COLUMN IF NOT EXISTS tenant_id BIGINT;
ALTER TABLE cost_monthly ADD COLUMN IF NOT EXISTS tenant_id BIGINT;
ALTER TABLE margin_records ADD COLUMN IF NOT EXISTS tenant_id BIGINT;
ALTER TABLE dict_types ADD COLUMN IF NOT EXISTS tenant_id BIGINT;
ALTER TABLE dict_items ADD COLUMN IF NOT EXISTS tenant_id BIGINT;
ALTER TABLE data_scopes ADD COLUMN IF NOT EXISTS tenant_id BIGINT;
ALTER TABLE app_users ADD COLUMN IF NOT EXISTS tenant_id BIGINT;
ALTER TABLE app_roles ADD COLUMN IF NOT EXISTS tenant_id BIGINT;
ALTER TABLE app_user_roles ADD COLUMN IF NOT EXISTS tenant_id BIGINT;
ALTER TABLE app_role_permissions ADD COLUMN IF NOT EXISTS tenant_id BIGINT;
ALTER TABLE approval_flows ADD COLUMN IF NOT EXISTS tenant_id BIGINT;
ALTER TABLE approval_instances ADD COLUMN IF NOT EXISTS tenant_id BIGINT;
ALTER TABLE approval_steps ADD COLUMN IF NOT EXISTS tenant_id BIGINT;

-- ─────────────────────────── ② 回填：存量单租户数据归默认租户 ─────────────
-- 每条都带 WHERE tenant_id IS NULL → 可重复执行。跑完这一节，库里不存在「没有租户的行」。
-- audit_logs 刻意不在其中：它有 append-only 触发器，UPDATE 会直接报错。

UPDATE warehouses SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE products SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE skus SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE suppliers SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE customers SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE inventory_balances SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE inventory_ledger SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE inventory_reservations SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE inventory_transfers SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE transfer_lines SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE stocktakes SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE stocktake_lines SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE inventory_adjustments SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE inventory_freezes SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE idempotency_keys SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE document_sequences SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE purchase_orders SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE purchase_order_lines SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE po_receipts SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE po_receipt_lines SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE supplier_payables SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE payable_allocations SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE purchase_returns SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE sales_orders SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE sales_order_lines SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE sales_order_line_reservations SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE product_bundles SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE bundle_items SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE sales_quotes SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE sales_quote_lines SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE so_shipment_plans SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE so_shipments SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE shipment_lines SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE customer_receivables SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE customer_receipts SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE receivable_allocations SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE customer_credit_snapshots SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE return_orders SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE return_order_lines SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE settlement_periods SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE daily_settlements SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE cost_layers SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE cost_monthly SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE margin_records SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE dict_types SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE dict_items SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE data_scopes SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE app_users SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE app_roles SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE app_user_roles SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE app_role_permissions SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE approval_flows SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE approval_instances SET tenant_id = 1 WHERE tenant_id IS NULL;
UPDATE approval_steps SET tenant_id = 1 WHERE tenant_id IS NULL;

-- ─────────────────────────── ③ 单列索引（租户） ───────────────────────────
CREATE INDEX IF NOT EXISTS idx_warehouses_tenant ON warehouses (tenant_id);
CREATE INDEX IF NOT EXISTS idx_products_tenant ON products (tenant_id);
CREATE INDEX IF NOT EXISTS idx_skus_tenant ON skus (tenant_id);
CREATE INDEX IF NOT EXISTS idx_suppliers_tenant ON suppliers (tenant_id);
CREATE INDEX IF NOT EXISTS idx_customers_tenant ON customers (tenant_id);
CREATE INDEX IF NOT EXISTS idx_inventory_balances_tenant ON inventory_balances (tenant_id);
CREATE INDEX IF NOT EXISTS idx_inventory_ledger_tenant ON inventory_ledger (tenant_id);
CREATE INDEX IF NOT EXISTS idx_inventory_reservations_tenant ON inventory_reservations (tenant_id);
CREATE INDEX IF NOT EXISTS idx_inventory_transfers_tenant ON inventory_transfers (tenant_id);
CREATE INDEX IF NOT EXISTS idx_transfer_lines_tenant ON transfer_lines (tenant_id);
CREATE INDEX IF NOT EXISTS idx_stocktakes_tenant ON stocktakes (tenant_id);
CREATE INDEX IF NOT EXISTS idx_stocktake_lines_tenant ON stocktake_lines (tenant_id);
CREATE INDEX IF NOT EXISTS idx_inventory_adjustments_tenant ON inventory_adjustments (tenant_id);
CREATE INDEX IF NOT EXISTS idx_inventory_freezes_tenant ON inventory_freezes (tenant_id);
CREATE INDEX IF NOT EXISTS idx_idempotency_keys_tenant ON idempotency_keys (tenant_id);
CREATE INDEX IF NOT EXISTS idx_document_sequences_tenant ON document_sequences (tenant_id);
CREATE INDEX IF NOT EXISTS idx_purchase_orders_tenant ON purchase_orders (tenant_id);
CREATE INDEX IF NOT EXISTS idx_purchase_order_lines_tenant ON purchase_order_lines (tenant_id);
CREATE INDEX IF NOT EXISTS idx_po_receipts_tenant ON po_receipts (tenant_id);
CREATE INDEX IF NOT EXISTS idx_po_receipt_lines_tenant ON po_receipt_lines (tenant_id);
CREATE INDEX IF NOT EXISTS idx_supplier_payables_tenant ON supplier_payables (tenant_id);
CREATE INDEX IF NOT EXISTS idx_payable_allocations_tenant ON payable_allocations (tenant_id);
CREATE INDEX IF NOT EXISTS idx_purchase_returns_tenant ON purchase_returns (tenant_id);
CREATE INDEX IF NOT EXISTS idx_sales_orders_tenant ON sales_orders (tenant_id);
CREATE INDEX IF NOT EXISTS idx_sales_order_lines_tenant ON sales_order_lines (tenant_id);
CREATE INDEX IF NOT EXISTS idx_sales_order_line_reservations_tenant ON sales_order_line_reservations (tenant_id);
CREATE INDEX IF NOT EXISTS idx_product_bundles_tenant ON product_bundles (tenant_id);
CREATE INDEX IF NOT EXISTS idx_bundle_items_tenant ON bundle_items (tenant_id);
CREATE INDEX IF NOT EXISTS idx_sales_quotes_tenant ON sales_quotes (tenant_id);
CREATE INDEX IF NOT EXISTS idx_sales_quote_lines_tenant ON sales_quote_lines (tenant_id);
CREATE INDEX IF NOT EXISTS idx_so_shipment_plans_tenant ON so_shipment_plans (tenant_id);
CREATE INDEX IF NOT EXISTS idx_so_shipments_tenant ON so_shipments (tenant_id);
CREATE INDEX IF NOT EXISTS idx_shipment_lines_tenant ON shipment_lines (tenant_id);
CREATE INDEX IF NOT EXISTS idx_customer_receivables_tenant ON customer_receivables (tenant_id);
CREATE INDEX IF NOT EXISTS idx_customer_receipts_tenant ON customer_receipts (tenant_id);
CREATE INDEX IF NOT EXISTS idx_receivable_allocations_tenant ON receivable_allocations (tenant_id);
CREATE INDEX IF NOT EXISTS idx_customer_credit_snapshots_tenant ON customer_credit_snapshots (tenant_id);
CREATE INDEX IF NOT EXISTS idx_return_orders_tenant ON return_orders (tenant_id);
CREATE INDEX IF NOT EXISTS idx_return_order_lines_tenant ON return_order_lines (tenant_id);
CREATE INDEX IF NOT EXISTS idx_settlement_periods_tenant ON settlement_periods (tenant_id);
CREATE INDEX IF NOT EXISTS idx_daily_settlements_tenant ON daily_settlements (tenant_id);
CREATE INDEX IF NOT EXISTS idx_cost_layers_tenant ON cost_layers (tenant_id);
CREATE INDEX IF NOT EXISTS idx_cost_monthly_tenant ON cost_monthly (tenant_id);
CREATE INDEX IF NOT EXISTS idx_margin_records_tenant ON margin_records (tenant_id);
CREATE INDEX IF NOT EXISTS idx_dict_types_tenant ON dict_types (tenant_id);
CREATE INDEX IF NOT EXISTS idx_dict_items_tenant ON dict_items (tenant_id);
CREATE INDEX IF NOT EXISTS idx_data_scopes_tenant ON data_scopes (tenant_id);
CREATE INDEX IF NOT EXISTS idx_app_users_tenant ON app_users (tenant_id);
CREATE INDEX IF NOT EXISTS idx_app_roles_tenant ON app_roles (tenant_id);
CREATE INDEX IF NOT EXISTS idx_app_user_roles_tenant ON app_user_roles (tenant_id);
CREATE INDEX IF NOT EXISTS idx_app_role_permissions_tenant ON app_role_permissions (tenant_id);
CREATE INDEX IF NOT EXISTS idx_approval_flows_tenant ON approval_flows (tenant_id);
CREATE INDEX IF NOT EXISTS idx_approval_instances_tenant ON approval_instances (tenant_id);
CREATE INDEX IF NOT EXISTS idx_approval_steps_tenant ON approval_steps (tenant_id);

-- ─────────────────────────── ④ 复合唯一：全局唯一 → (tenant_id, ...) ─────
-- ★ 顺序不可颠倒：「建新复合唯一索引（临时名）→ 删旧约束 / 旧索引 → 把新索引
--   改名为旧名」。反过来做的话，中间窗口里"同编码两行"是合法的，并发写入可能塞进
--   真重复。改名保留旧名是**必须**的：DbErrors.translate 按约束名翻译错误码
--   （uq_product_code → MDM_CODE_DUPLICATED），改名会让一整类 409 静默退化成 500。

-- document_sequences：主键也按租户独立（单号序列 / 幂等键 / 账期）
DO $$
DECLARE pk_name text;
        pk_def  text;
BEGIN
    SELECT c.conname, pg_get_constraintdef(c.oid)
      INTO pk_name, pk_def
      FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
     WHERE t.relname = 'document_sequences' AND c.contype = 'p';
    IF pk_name IS NULL THEN
        ALTER TABLE document_sequences ADD CONSTRAINT document_sequences_pkey PRIMARY KEY (tenant_id, doc_prefix, day_key);
    ELSIF pk_def NOT LIKE '%tenant_id%' THEN
        EXECUTE format('ALTER TABLE document_sequences DROP CONSTRAINT %I', pk_name);
        ALTER TABLE document_sequences ADD CONSTRAINT document_sequences_pkey PRIMARY KEY (tenant_id, doc_prefix, day_key);
    END IF;
END $$;

-- idempotency_keys：主键也按租户独立（单号序列 / 幂等键 / 账期）
DO $$
DECLARE pk_name text;
        pk_def  text;
BEGIN
    SELECT c.conname, pg_get_constraintdef(c.oid)
      INTO pk_name, pk_def
      FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
     WHERE t.relname = 'idempotency_keys' AND c.contype = 'p';
    IF pk_name IS NULL THEN
        ALTER TABLE idempotency_keys ADD CONSTRAINT idempotency_keys_pkey PRIMARY KEY (tenant_id, request_id);
    ELSIF pk_def NOT LIKE '%tenant_id%' THEN
        EXECUTE format('ALTER TABLE idempotency_keys DROP CONSTRAINT %I', pk_name);
        ALTER TABLE idempotency_keys ADD CONSTRAINT idempotency_keys_pkey PRIMARY KEY (tenant_id, request_id);
    END IF;
END $$;

-- settlement_periods：主键也按租户独立（单号序列 / 幂等键 / 账期）
DO $$
DECLARE pk_name text;
        pk_def  text;
BEGIN
    SELECT c.conname, pg_get_constraintdef(c.oid)
      INTO pk_name, pk_def
      FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
     WHERE t.relname = 'settlement_periods' AND c.contype = 'p';
    IF pk_name IS NULL THEN
        ALTER TABLE settlement_periods ADD CONSTRAINT settlement_periods_pkey PRIMARY KEY (tenant_id, period);
    ELSIF pk_def NOT LIKE '%tenant_id%' THEN
        EXECUTE format('ALTER TABLE settlement_periods DROP CONSTRAINT %I', pk_name);
        ALTER TABLE settlement_periods ADD CONSTRAINT settlement_periods_pkey PRIMARY KEY (tenant_id, period);
    END IF;
END $$;

-- warehouses.uq_warehouse_code → (tenant_id, code)
DROP INDEX IF EXISTS uq_warehouse_code__tnt;
CREATE UNIQUE INDEX uq_warehouse_code__tnt ON warehouses (tenant_id, code);
DO $$
DECLARE cname text;
BEGIN
    SELECT c.conname INTO cname
      FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
     WHERE t.relname = 'warehouses' AND c.conname = 'uq_warehouse_code';
    IF cname IS NOT NULL THEN
        EXECUTE format('ALTER TABLE warehouses DROP CONSTRAINT %I', cname);
    END IF;
END $$;
DROP INDEX IF EXISTS uq_warehouse_code;
ALTER INDEX uq_warehouse_code__tnt RENAME TO uq_warehouse_code;

-- products.uq_product_code → (tenant_id, code)
DROP INDEX IF EXISTS uq_product_code__tnt;
CREATE UNIQUE INDEX uq_product_code__tnt ON products (tenant_id, code);
DO $$
DECLARE cname text;
BEGIN
    SELECT c.conname INTO cname
      FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
     WHERE t.relname = 'products' AND c.conname = 'uq_product_code';
    IF cname IS NOT NULL THEN
        EXECUTE format('ALTER TABLE products DROP CONSTRAINT %I', cname);
    END IF;
END $$;
DROP INDEX IF EXISTS uq_product_code;
ALTER INDEX uq_product_code__tnt RENAME TO uq_product_code;

-- suppliers.uq_supplier_code → (tenant_id, code)
DROP INDEX IF EXISTS uq_supplier_code__tnt;
CREATE UNIQUE INDEX uq_supplier_code__tnt ON suppliers (tenant_id, code);
DO $$
DECLARE cname text;
BEGIN
    SELECT c.conname INTO cname
      FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
     WHERE t.relname = 'suppliers' AND c.conname = 'uq_supplier_code';
    IF cname IS NOT NULL THEN
        EXECUTE format('ALTER TABLE suppliers DROP CONSTRAINT %I', cname);
    END IF;
END $$;
DROP INDEX IF EXISTS uq_supplier_code;
ALTER INDEX uq_supplier_code__tnt RENAME TO uq_supplier_code;

-- customers.uq_customer_code → (tenant_id, code)
DROP INDEX IF EXISTS uq_customer_code__tnt;
CREATE UNIQUE INDEX uq_customer_code__tnt ON customers (tenant_id, code);
DO $$
DECLARE cname text;
BEGIN
    SELECT c.conname INTO cname
      FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
     WHERE t.relname = 'customers' AND c.conname = 'uq_customer_code';
    IF cname IS NOT NULL THEN
        EXECUTE format('ALTER TABLE customers DROP CONSTRAINT %I', cname);
    END IF;
END $$;
DROP INDEX IF EXISTS uq_customer_code;
ALTER INDEX uq_customer_code__tnt RENAME TO uq_customer_code;

-- product_bundles.uq_bundle_code → (tenant_id, code)
DROP INDEX IF EXISTS uq_bundle_code__tnt;
CREATE UNIQUE INDEX uq_bundle_code__tnt ON product_bundles (tenant_id, code);
DO $$
DECLARE cname text;
BEGIN
    SELECT c.conname INTO cname
      FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
     WHERE t.relname = 'product_bundles' AND c.conname = 'uq_bundle_code';
    IF cname IS NOT NULL THEN
        EXECUTE format('ALTER TABLE product_bundles DROP CONSTRAINT %I', cname);
    END IF;
END $$;
DROP INDEX IF EXISTS uq_bundle_code;
ALTER INDEX uq_bundle_code__tnt RENAME TO uq_bundle_code;

-- app_roles.uq_app_role_code → (tenant_id, code)
DROP INDEX IF EXISTS uq_app_role_code__tnt;
CREATE UNIQUE INDEX uq_app_role_code__tnt ON app_roles (tenant_id, code);
DO $$
DECLARE cname text;
BEGIN
    SELECT c.conname INTO cname
      FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
     WHERE t.relname = 'app_roles' AND c.conname = 'uq_app_role_code';
    IF cname IS NOT NULL THEN
        EXECUTE format('ALTER TABLE app_roles DROP CONSTRAINT %I', cname);
    END IF;
END $$;
DROP INDEX IF EXISTS uq_app_role_code;
ALTER INDEX uq_app_role_code__tnt RENAME TO uq_app_role_code;

-- dict_types.uq_dict_types_code → (tenant_id, code)
DROP INDEX IF EXISTS uq_dict_types_code__tnt;
CREATE UNIQUE INDEX uq_dict_types_code__tnt ON dict_types (tenant_id, code);
DO $$
DECLARE cname text;
BEGIN
    SELECT c.conname INTO cname
      FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
     WHERE t.relname = 'dict_types' AND c.conname = 'uq_dict_types_code';
    IF cname IS NOT NULL THEN
        EXECUTE format('ALTER TABLE dict_types DROP CONSTRAINT %I', cname);
    END IF;
END $$;
DROP INDEX IF EXISTS uq_dict_types_code;
ALTER INDEX uq_dict_types_code__tnt RENAME TO uq_dict_types_code;

-- approval_flows.uq_approval_flow_code → (tenant_id, code)
DROP INDEX IF EXISTS uq_approval_flow_code__tnt;
CREATE UNIQUE INDEX uq_approval_flow_code__tnt ON approval_flows (tenant_id, code);
DO $$
DECLARE cname text;
BEGIN
    SELECT c.conname INTO cname
      FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
     WHERE t.relname = 'approval_flows' AND c.conname = 'uq_approval_flow_code';
    IF cname IS NOT NULL THEN
        EXECUTE format('ALTER TABLE approval_flows DROP CONSTRAINT %I', cname);
    END IF;
END $$;
DROP INDEX IF EXISTS uq_approval_flow_code;
ALTER INDEX uq_approval_flow_code__tnt RENAME TO uq_approval_flow_code;

-- purchase_orders.uq_purchase_order_no → (tenant_id, order_no)
DROP INDEX IF EXISTS uq_purchase_order_no__tnt;
CREATE UNIQUE INDEX uq_purchase_order_no__tnt ON purchase_orders (tenant_id, order_no);
DO $$
DECLARE cname text;
BEGIN
    SELECT c.conname INTO cname
      FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
     WHERE t.relname = 'purchase_orders' AND c.conname = 'uq_purchase_order_no';
    IF cname IS NOT NULL THEN
        EXECUTE format('ALTER TABLE purchase_orders DROP CONSTRAINT %I', cname);
    END IF;
END $$;
DROP INDEX IF EXISTS uq_purchase_order_no;
ALTER INDEX uq_purchase_order_no__tnt RENAME TO uq_purchase_order_no;

-- sales_orders.uq_sales_order_no → (tenant_id, order_no)
DROP INDEX IF EXISTS uq_sales_order_no__tnt;
CREATE UNIQUE INDEX uq_sales_order_no__tnt ON sales_orders (tenant_id, order_no);
DO $$
DECLARE cname text;
BEGIN
    SELECT c.conname INTO cname
      FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
     WHERE t.relname = 'sales_orders' AND c.conname = 'uq_sales_order_no';
    IF cname IS NOT NULL THEN
        EXECUTE format('ALTER TABLE sales_orders DROP CONSTRAINT %I', cname);
    END IF;
END $$;
DROP INDEX IF EXISTS uq_sales_order_no;
ALTER INDEX uq_sales_order_no__tnt RENAME TO uq_sales_order_no;

-- return_orders.uq_return_order_no → (tenant_id, order_no)
DROP INDEX IF EXISTS uq_return_order_no__tnt;
CREATE UNIQUE INDEX uq_return_order_no__tnt ON return_orders (tenant_id, order_no);
DO $$
DECLARE cname text;
BEGIN
    SELECT c.conname INTO cname
      FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
     WHERE t.relname = 'return_orders' AND c.conname = 'uq_return_order_no';
    IF cname IS NOT NULL THEN
        EXECUTE format('ALTER TABLE return_orders DROP CONSTRAINT %I', cname);
    END IF;
END $$;
DROP INDEX IF EXISTS uq_return_order_no;
ALTER INDEX uq_return_order_no__tnt RENAME TO uq_return_order_no;

-- inventory_transfers.uq_transfer_no → (tenant_id, transfer_no)
DROP INDEX IF EXISTS uq_transfer_no__tnt;
CREATE UNIQUE INDEX uq_transfer_no__tnt ON inventory_transfers (tenant_id, transfer_no);
DO $$
DECLARE cname text;
BEGIN
    SELECT c.conname INTO cname
      FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
     WHERE t.relname = 'inventory_transfers' AND c.conname = 'uq_transfer_no';
    IF cname IS NOT NULL THEN
        EXECUTE format('ALTER TABLE inventory_transfers DROP CONSTRAINT %I', cname);
    END IF;
END $$;
DROP INDEX IF EXISTS uq_transfer_no;
ALTER INDEX uq_transfer_no__tnt RENAME TO uq_transfer_no;

-- stocktakes.uq_stocktake_no → (tenant_id, stocktake_no)
DROP INDEX IF EXISTS uq_stocktake_no__tnt;
CREATE UNIQUE INDEX uq_stocktake_no__tnt ON stocktakes (tenant_id, stocktake_no);
DO $$
DECLARE cname text;
BEGIN
    SELECT c.conname INTO cname
      FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
     WHERE t.relname = 'stocktakes' AND c.conname = 'uq_stocktake_no';
    IF cname IS NOT NULL THEN
        EXECUTE format('ALTER TABLE stocktakes DROP CONSTRAINT %I', cname);
    END IF;
END $$;
DROP INDEX IF EXISTS uq_stocktake_no;
ALTER INDEX uq_stocktake_no__tnt RENAME TO uq_stocktake_no;

-- inventory_adjustments.uq_adjust_no → (tenant_id, adjust_no)
DROP INDEX IF EXISTS uq_adjust_no__tnt;
CREATE UNIQUE INDEX uq_adjust_no__tnt ON inventory_adjustments (tenant_id, adjust_no);
DO $$
DECLARE cname text;
BEGIN
    SELECT c.conname INTO cname
      FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
     WHERE t.relname = 'inventory_adjustments' AND c.conname = 'uq_adjust_no';
    IF cname IS NOT NULL THEN
        EXECUTE format('ALTER TABLE inventory_adjustments DROP CONSTRAINT %I', cname);
    END IF;
END $$;
DROP INDEX IF EXISTS uq_adjust_no;
ALTER INDEX uq_adjust_no__tnt RENAME TO uq_adjust_no;

-- sales_quotes.uq_quote_no → (tenant_id, quote_no)
DROP INDEX IF EXISTS uq_quote_no__tnt;
CREATE UNIQUE INDEX uq_quote_no__tnt ON sales_quotes (tenant_id, quote_no);
DO $$
DECLARE cname text;
BEGIN
    SELECT c.conname INTO cname
      FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
     WHERE t.relname = 'sales_quotes' AND c.conname = 'uq_quote_no';
    IF cname IS NOT NULL THEN
        EXECUTE format('ALTER TABLE sales_quotes DROP CONSTRAINT %I', cname);
    END IF;
END $$;
DROP INDEX IF EXISTS uq_quote_no;
ALTER INDEX uq_quote_no__tnt RENAME TO uq_quote_no;

-- so_shipment_plans.uq_shipment_plan_no → (tenant_id, plan_no)
DROP INDEX IF EXISTS uq_shipment_plan_no__tnt;
CREATE UNIQUE INDEX uq_shipment_plan_no__tnt ON so_shipment_plans (tenant_id, plan_no);
DO $$
DECLARE cname text;
BEGIN
    SELECT c.conname INTO cname
      FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
     WHERE t.relname = 'so_shipment_plans' AND c.conname = 'uq_shipment_plan_no';
    IF cname IS NOT NULL THEN
        EXECUTE format('ALTER TABLE so_shipment_plans DROP CONSTRAINT %I', cname);
    END IF;
END $$;
DROP INDEX IF EXISTS uq_shipment_plan_no;
ALTER INDEX uq_shipment_plan_no__tnt RENAME TO uq_shipment_plan_no;

-- so_shipments.uq_shipment_no → (tenant_id, shipment_no)
DROP INDEX IF EXISTS uq_shipment_no__tnt;
CREATE UNIQUE INDEX uq_shipment_no__tnt ON so_shipments (tenant_id, shipment_no);
DO $$
DECLARE cname text;
BEGIN
    SELECT c.conname INTO cname
      FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
     WHERE t.relname = 'so_shipments' AND c.conname = 'uq_shipment_no';
    IF cname IS NOT NULL THEN
        EXECUTE format('ALTER TABLE so_shipments DROP CONSTRAINT %I', cname);
    END IF;
END $$;
DROP INDEX IF EXISTS uq_shipment_no;
ALTER INDEX uq_shipment_no__tnt RENAME TO uq_shipment_no;

-- po_receipts.uq_po_receipt_no → (tenant_id, receipt_no)
DROP INDEX IF EXISTS uq_po_receipt_no__tnt;
CREATE UNIQUE INDEX uq_po_receipt_no__tnt ON po_receipts (tenant_id, receipt_no);
DO $$
DECLARE cname text;
BEGIN
    SELECT c.conname INTO cname
      FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
     WHERE t.relname = 'po_receipts' AND c.conname = 'uq_po_receipt_no';
    IF cname IS NOT NULL THEN
        EXECUTE format('ALTER TABLE po_receipts DROP CONSTRAINT %I', cname);
    END IF;
END $$;
DROP INDEX IF EXISTS uq_po_receipt_no;
ALTER INDEX uq_po_receipt_no__tnt RENAME TO uq_po_receipt_no;

-- purchase_returns.uq_purchase_return_no → (tenant_id, return_no)
DROP INDEX IF EXISTS uq_purchase_return_no__tnt;
CREATE UNIQUE INDEX uq_purchase_return_no__tnt ON purchase_returns (tenant_id, return_no);
DO $$
DECLARE cname text;
BEGIN
    SELECT c.conname INTO cname
      FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
     WHERE t.relname = 'purchase_returns' AND c.conname = 'uq_purchase_return_no';
    IF cname IS NOT NULL THEN
        EXECUTE format('ALTER TABLE purchase_returns DROP CONSTRAINT %I', cname);
    END IF;
END $$;
DROP INDEX IF EXISTS uq_purchase_return_no;
ALTER INDEX uq_purchase_return_no__tnt RENAME TO uq_purchase_return_no;

-- supplier_payables.uq_supplier_payable_no → (tenant_id, payable_no)
DROP INDEX IF EXISTS uq_supplier_payable_no__tnt;
CREATE UNIQUE INDEX uq_supplier_payable_no__tnt ON supplier_payables (tenant_id, payable_no);
DO $$
DECLARE cname text;
BEGIN
    SELECT c.conname INTO cname
      FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
     WHERE t.relname = 'supplier_payables' AND c.conname = 'uq_supplier_payable_no';
    IF cname IS NOT NULL THEN
        EXECUTE format('ALTER TABLE supplier_payables DROP CONSTRAINT %I', cname);
    END IF;
END $$;
DROP INDEX IF EXISTS uq_supplier_payable_no;
ALTER INDEX uq_supplier_payable_no__tnt RENAME TO uq_supplier_payable_no;

-- customer_receipts.uq_receipt_no → (tenant_id, receipt_no)
DROP INDEX IF EXISTS uq_receipt_no__tnt;
CREATE UNIQUE INDEX uq_receipt_no__tnt ON customer_receipts (tenant_id, receipt_no);
DO $$
DECLARE cname text;
BEGIN
    SELECT c.conname INTO cname
      FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
     WHERE t.relname = 'customer_receipts' AND c.conname = 'uq_receipt_no';
    IF cname IS NOT NULL THEN
        EXECUTE format('ALTER TABLE customer_receipts DROP CONSTRAINT %I', cname);
    END IF;
END $$;
DROP INDEX IF EXISTS uq_receipt_no;
ALTER INDEX uq_receipt_no__tnt RENAME TO uq_receipt_no;

-- payable_allocations.uq_payable_alloc_payment → (tenant_id, payment_no, payable_id)
DROP INDEX IF EXISTS uq_payable_alloc_payment__tnt;
CREATE UNIQUE INDEX uq_payable_alloc_payment__tnt ON payable_allocations (tenant_id, payment_no, payable_id);
DO $$
DECLARE cname text;
BEGIN
    SELECT c.conname INTO cname
      FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
     WHERE t.relname = 'payable_allocations' AND c.conname = 'uq_payable_alloc_payment';
    IF cname IS NOT NULL THEN
        EXECUTE format('ALTER TABLE payable_allocations DROP CONSTRAINT %I', cname);
    END IF;
END $$;
DROP INDEX IF EXISTS uq_payable_alloc_payment;
ALTER INDEX uq_payable_alloc_payment__tnt RENAME TO uq_payable_alloc_payment;

-- inventory_reservations.uq_reservation_request → (tenant_id, request_id)
DROP INDEX IF EXISTS uq_reservation_request__tnt;
CREATE UNIQUE INDEX uq_reservation_request__tnt ON inventory_reservations (tenant_id, request_id);
DO $$
DECLARE cname text;
BEGIN
    SELECT c.conname INTO cname
      FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
     WHERE t.relname = 'inventory_reservations' AND c.conname = 'uq_reservation_request';
    IF cname IS NOT NULL THEN
        EXECUTE format('ALTER TABLE inventory_reservations DROP CONSTRAINT %I', cname);
    END IF;
END $$;
DROP INDEX IF EXISTS uq_reservation_request;
ALTER INDEX uq_reservation_request__tnt RENAME TO uq_reservation_request;

-- ─────────────────────────── ⑤ 幂等键与审批流唯一（按租户） ────────────────
-- ★「同键」的作用域在这里被重新定义：全局一个键 → 每租户一个键。

-- inventory_ledger.uq_ledger_request → (tenant_id, request_id, biz_type, batch_no)
DROP INDEX IF EXISTS uq_ledger_request__tnt;
CREATE UNIQUE INDEX uq_ledger_request__tnt ON inventory_ledger (tenant_id, request_id, biz_type, batch_no) WHERE request_id IS NOT NULL;
DO $$
DECLARE cname text;
BEGIN
    SELECT c.conname INTO cname
      FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
     WHERE t.relname = 'inventory_ledger' AND c.conname = 'uq_ledger_request';
    IF cname IS NOT NULL THEN
        EXECUTE format('ALTER TABLE inventory_ledger DROP CONSTRAINT %I', cname);
    END IF;
END $$;
DROP INDEX IF EXISTS uq_ledger_request;
ALTER INDEX uq_ledger_request__tnt RENAME TO uq_ledger_request;

-- purchase_orders.uq_po_request → (tenant_id, request_id)
DROP INDEX IF EXISTS uq_po_request__tnt;
CREATE UNIQUE INDEX uq_po_request__tnt ON purchase_orders (tenant_id, request_id) WHERE request_id IS NOT NULL;
DO $$
DECLARE cname text;
BEGIN
    SELECT c.conname INTO cname
      FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
     WHERE t.relname = 'purchase_orders' AND c.conname = 'uq_po_request';
    IF cname IS NOT NULL THEN
        EXECUTE format('ALTER TABLE purchase_orders DROP CONSTRAINT %I', cname);
    END IF;
END $$;
DROP INDEX IF EXISTS uq_po_request;
ALTER INDEX uq_po_request__tnt RENAME TO uq_po_request;

-- purchase_orders.uq_po_conversion_request → (tenant_id, conversion_request_id)
DROP INDEX IF EXISTS uq_po_conversion_request__tnt;
CREATE UNIQUE INDEX uq_po_conversion_request__tnt ON purchase_orders (tenant_id, conversion_request_id) WHERE conversion_request_id IS NOT NULL;
DO $$
DECLARE cname text;
BEGIN
    SELECT c.conname INTO cname
      FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
     WHERE t.relname = 'purchase_orders' AND c.conname = 'uq_po_conversion_request';
    IF cname IS NOT NULL THEN
        EXECUTE format('ALTER TABLE purchase_orders DROP CONSTRAINT %I', cname);
    END IF;
END $$;
DROP INDEX IF EXISTS uq_po_conversion_request;
ALTER INDEX uq_po_conversion_request__tnt RENAME TO uq_po_conversion_request;

-- purchase_orders.uq_po_void_request → (tenant_id, void_request_id)
DROP INDEX IF EXISTS uq_po_void_request__tnt;
CREATE UNIQUE INDEX uq_po_void_request__tnt ON purchase_orders (tenant_id, void_request_id) WHERE void_request_id IS NOT NULL;
DO $$
DECLARE cname text;
BEGIN
    SELECT c.conname INTO cname
      FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
     WHERE t.relname = 'purchase_orders' AND c.conname = 'uq_po_void_request';
    IF cname IS NOT NULL THEN
        EXECUTE format('ALTER TABLE purchase_orders DROP CONSTRAINT %I', cname);
    END IF;
END $$;
DROP INDEX IF EXISTS uq_po_void_request;
ALTER INDEX uq_po_void_request__tnt RENAME TO uq_po_void_request;

-- sales_orders.uq_so_request → (tenant_id, request_id)
DROP INDEX IF EXISTS uq_so_request__tnt;
CREATE UNIQUE INDEX uq_so_request__tnt ON sales_orders (tenant_id, request_id) WHERE request_id IS NOT NULL;
DO $$
DECLARE cname text;
BEGIN
    SELECT c.conname INTO cname
      FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
     WHERE t.relname = 'sales_orders' AND c.conname = 'uq_so_request';
    IF cname IS NOT NULL THEN
        EXECUTE format('ALTER TABLE sales_orders DROP CONSTRAINT %I', cname);
    END IF;
END $$;
DROP INDEX IF EXISTS uq_so_request;
ALTER INDEX uq_so_request__tnt RENAME TO uq_so_request;

-- sales_orders.uq_so_void_request → (tenant_id, void_request_id)
DROP INDEX IF EXISTS uq_so_void_request__tnt;
CREATE UNIQUE INDEX uq_so_void_request__tnt ON sales_orders (tenant_id, void_request_id) WHERE void_request_id IS NOT NULL;
DO $$
DECLARE cname text;
BEGIN
    SELECT c.conname INTO cname
      FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
     WHERE t.relname = 'sales_orders' AND c.conname = 'uq_so_void_request';
    IF cname IS NOT NULL THEN
        EXECUTE format('ALTER TABLE sales_orders DROP CONSTRAINT %I', cname);
    END IF;
END $$;
DROP INDEX IF EXISTS uq_so_void_request;
ALTER INDEX uq_so_void_request__tnt RENAME TO uq_so_void_request;

-- return_orders.uq_ro_request → (tenant_id, request_id)
DROP INDEX IF EXISTS uq_ro_request__tnt;
CREATE UNIQUE INDEX uq_ro_request__tnt ON return_orders (tenant_id, request_id) WHERE request_id IS NOT NULL;
DO $$
DECLARE cname text;
BEGIN
    SELECT c.conname INTO cname
      FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
     WHERE t.relname = 'return_orders' AND c.conname = 'uq_ro_request';
    IF cname IS NOT NULL THEN
        EXECUTE format('ALTER TABLE return_orders DROP CONSTRAINT %I', cname);
    END IF;
END $$;
DROP INDEX IF EXISTS uq_ro_request;
ALTER INDEX uq_ro_request__tnt RENAME TO uq_ro_request;

-- return_orders.uq_ro_receive_request → (tenant_id, receive_request_id)
DROP INDEX IF EXISTS uq_ro_receive_request__tnt;
CREATE UNIQUE INDEX uq_ro_receive_request__tnt ON return_orders (tenant_id, receive_request_id) WHERE receive_request_id IS NOT NULL;
DO $$
DECLARE cname text;
BEGIN
    SELECT c.conname INTO cname
      FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
     WHERE t.relname = 'return_orders' AND c.conname = 'uq_ro_receive_request';
    IF cname IS NOT NULL THEN
        EXECUTE format('ALTER TABLE return_orders DROP CONSTRAINT %I', cname);
    END IF;
END $$;
DROP INDEX IF EXISTS uq_ro_receive_request;
ALTER INDEX uq_ro_receive_request__tnt RENAME TO uq_ro_receive_request;

-- inventory_transfers.uq_transfer_request → (tenant_id, request_id)
DROP INDEX IF EXISTS uq_transfer_request__tnt;
CREATE UNIQUE INDEX uq_transfer_request__tnt ON inventory_transfers (tenant_id, request_id) WHERE request_id IS NOT NULL;
DO $$
DECLARE cname text;
BEGIN
    SELECT c.conname INTO cname
      FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
     WHERE t.relname = 'inventory_transfers' AND c.conname = 'uq_transfer_request';
    IF cname IS NOT NULL THEN
        EXECUTE format('ALTER TABLE inventory_transfers DROP CONSTRAINT %I', cname);
    END IF;
END $$;
DROP INDEX IF EXISTS uq_transfer_request;
ALTER INDEX uq_transfer_request__tnt RENAME TO uq_transfer_request;

-- inventory_transfers.uq_transfer_receive_request → (tenant_id, receive_request_id)
DROP INDEX IF EXISTS uq_transfer_receive_request__tnt;
CREATE UNIQUE INDEX uq_transfer_receive_request__tnt ON inventory_transfers (tenant_id, receive_request_id) WHERE receive_request_id IS NOT NULL;
DO $$
DECLARE cname text;
BEGIN
    SELECT c.conname INTO cname
      FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
     WHERE t.relname = 'inventory_transfers' AND c.conname = 'uq_transfer_receive_request';
    IF cname IS NOT NULL THEN
        EXECUTE format('ALTER TABLE inventory_transfers DROP CONSTRAINT %I', cname);
    END IF;
END $$;
DROP INDEX IF EXISTS uq_transfer_receive_request;
ALTER INDEX uq_transfer_receive_request__tnt RENAME TO uq_transfer_receive_request;

-- stocktakes.uq_stocktake_request → (tenant_id, request_id)
DROP INDEX IF EXISTS uq_stocktake_request__tnt;
CREATE UNIQUE INDEX uq_stocktake_request__tnt ON stocktakes (tenant_id, request_id) WHERE request_id IS NOT NULL;
DO $$
DECLARE cname text;
BEGIN
    SELECT c.conname INTO cname
      FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
     WHERE t.relname = 'stocktakes' AND c.conname = 'uq_stocktake_request';
    IF cname IS NOT NULL THEN
        EXECUTE format('ALTER TABLE stocktakes DROP CONSTRAINT %I', cname);
    END IF;
END $$;
DROP INDEX IF EXISTS uq_stocktake_request;
ALTER INDEX uq_stocktake_request__tnt RENAME TO uq_stocktake_request;

-- stocktakes.uq_stocktake_post_request → (tenant_id, post_request_id)
DROP INDEX IF EXISTS uq_stocktake_post_request__tnt;
CREATE UNIQUE INDEX uq_stocktake_post_request__tnt ON stocktakes (tenant_id, post_request_id) WHERE post_request_id IS NOT NULL;
DO $$
DECLARE cname text;
BEGIN
    SELECT c.conname INTO cname
      FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
     WHERE t.relname = 'stocktakes' AND c.conname = 'uq_stocktake_post_request';
    IF cname IS NOT NULL THEN
        EXECUTE format('ALTER TABLE stocktakes DROP CONSTRAINT %I', cname);
    END IF;
END $$;
DROP INDEX IF EXISTS uq_stocktake_post_request;
ALTER INDEX uq_stocktake_post_request__tnt RENAME TO uq_stocktake_post_request;

-- inventory_adjustments.uq_adjust_request → (tenant_id, request_id)
DROP INDEX IF EXISTS uq_adjust_request__tnt;
CREATE UNIQUE INDEX uq_adjust_request__tnt ON inventory_adjustments (tenant_id, request_id) WHERE request_id IS NOT NULL;
DO $$
DECLARE cname text;
BEGIN
    SELECT c.conname INTO cname
      FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
     WHERE t.relname = 'inventory_adjustments' AND c.conname = 'uq_adjust_request';
    IF cname IS NOT NULL THEN
        EXECUTE format('ALTER TABLE inventory_adjustments DROP CONSTRAINT %I', cname);
    END IF;
END $$;
DROP INDEX IF EXISTS uq_adjust_request;
ALTER INDEX uq_adjust_request__tnt RENAME TO uq_adjust_request;

-- inventory_freezes.uq_freeze_request → (tenant_id, request_id)
DROP INDEX IF EXISTS uq_freeze_request__tnt;
CREATE UNIQUE INDEX uq_freeze_request__tnt ON inventory_freezes (tenant_id, request_id) WHERE request_id IS NOT NULL;
DO $$
DECLARE cname text;
BEGIN
    SELECT c.conname INTO cname
      FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
     WHERE t.relname = 'inventory_freezes' AND c.conname = 'uq_freeze_request';
    IF cname IS NOT NULL THEN
        EXECUTE format('ALTER TABLE inventory_freezes DROP CONSTRAINT %I', cname);
    END IF;
END $$;
DROP INDEX IF EXISTS uq_freeze_request;
ALTER INDEX uq_freeze_request__tnt RENAME TO uq_freeze_request;

-- inventory_freezes.uq_freeze_release_request → (tenant_id, release_request_id)
DROP INDEX IF EXISTS uq_freeze_release_request__tnt;
CREATE UNIQUE INDEX uq_freeze_release_request__tnt ON inventory_freezes (tenant_id, release_request_id) WHERE release_request_id IS NOT NULL;
DO $$
DECLARE cname text;
BEGIN
    SELECT c.conname INTO cname
      FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
     WHERE t.relname = 'inventory_freezes' AND c.conname = 'uq_freeze_release_request';
    IF cname IS NOT NULL THEN
        EXECUTE format('ALTER TABLE inventory_freezes DROP CONSTRAINT %I', cname);
    END IF;
END $$;
DROP INDEX IF EXISTS uq_freeze_release_request;
ALTER INDEX uq_freeze_release_request__tnt RENAME TO uq_freeze_release_request;

-- payable_allocations.uq_payable_alloc_request → (tenant_id, request_id, payable_id)
DROP INDEX IF EXISTS uq_payable_alloc_request__tnt;
CREATE UNIQUE INDEX uq_payable_alloc_request__tnt ON payable_allocations (tenant_id, request_id, payable_id) WHERE request_id IS NOT NULL;
DO $$
DECLARE cname text;
BEGIN
    SELECT c.conname INTO cname
      FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
     WHERE t.relname = 'payable_allocations' AND c.conname = 'uq_payable_alloc_request';
    IF cname IS NOT NULL THEN
        EXECUTE format('ALTER TABLE payable_allocations DROP CONSTRAINT %I', cname);
    END IF;
END $$;
DROP INDEX IF EXISTS uq_payable_alloc_request;
ALTER INDEX uq_payable_alloc_request__tnt RENAME TO uq_payable_alloc_request;

-- sales_quotes.uq_quote_request → (tenant_id, request_id)
DROP INDEX IF EXISTS uq_quote_request__tnt;
CREATE UNIQUE INDEX uq_quote_request__tnt ON sales_quotes (tenant_id, request_id) WHERE request_id IS NOT NULL;
DO $$
DECLARE cname text;
BEGIN
    SELECT c.conname INTO cname
      FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
     WHERE t.relname = 'sales_quotes' AND c.conname = 'uq_quote_request';
    IF cname IS NOT NULL THEN
        EXECUTE format('ALTER TABLE sales_quotes DROP CONSTRAINT %I', cname);
    END IF;
END $$;
DROP INDEX IF EXISTS uq_quote_request;
ALTER INDEX uq_quote_request__tnt RENAME TO uq_quote_request;

-- sales_quotes.uq_quote_convert_request → (tenant_id, convert_request_id)
DROP INDEX IF EXISTS uq_quote_convert_request__tnt;
CREATE UNIQUE INDEX uq_quote_convert_request__tnt ON sales_quotes (tenant_id, convert_request_id) WHERE convert_request_id IS NOT NULL;
DO $$
DECLARE cname text;
BEGIN
    SELECT c.conname INTO cname
      FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
     WHERE t.relname = 'sales_quotes' AND c.conname = 'uq_quote_convert_request';
    IF cname IS NOT NULL THEN
        EXECUTE format('ALTER TABLE sales_quotes DROP CONSTRAINT %I', cname);
    END IF;
END $$;
DROP INDEX IF EXISTS uq_quote_convert_request;
ALTER INDEX uq_quote_convert_request__tnt RENAME TO uq_quote_convert_request;

-- so_shipments.uq_shipment_confirm_request → (tenant_id, confirm_request_id)
DROP INDEX IF EXISTS uq_shipment_confirm_request__tnt;
CREATE UNIQUE INDEX uq_shipment_confirm_request__tnt ON so_shipments (tenant_id, confirm_request_id) WHERE confirm_request_id IS NOT NULL;
DO $$
DECLARE cname text;
BEGIN
    SELECT c.conname INTO cname
      FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
     WHERE t.relname = 'so_shipments' AND c.conname = 'uq_shipment_confirm_request';
    IF cname IS NOT NULL THEN
        EXECUTE format('ALTER TABLE so_shipments DROP CONSTRAINT %I', cname);
    END IF;
END $$;
DROP INDEX IF EXISTS uq_shipment_confirm_request;
ALTER INDEX uq_shipment_confirm_request__tnt RENAME TO uq_shipment_confirm_request;

-- approval_flows.uq_approval_flow_enabled_biz → (tenant_id, biz_type)
DROP INDEX IF EXISTS uq_approval_flow_enabled_biz__tnt;
CREATE UNIQUE INDEX uq_approval_flow_enabled_biz__tnt ON approval_flows (tenant_id, biz_type) WHERE enabled;
DO $$
DECLARE cname text;
BEGIN
    SELECT c.conname INTO cname
      FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
     WHERE t.relname = 'approval_flows' AND c.conname = 'uq_approval_flow_enabled_biz';
    IF cname IS NOT NULL THEN
        EXECUTE format('ALTER TABLE approval_flows DROP CONSTRAINT %I', cname);
    END IF;
END $$;
DROP INDEX IF EXISTS uq_approval_flow_enabled_biz;
ALTER INDEX uq_approval_flow_enabled_biz__tnt RENAME TO uq_approval_flow_enabled_biz;

-- approval_instances.uq_approval_instance_request → (tenant_id, request_id)
DROP INDEX IF EXISTS uq_approval_instance_request__tnt;
CREATE UNIQUE INDEX uq_approval_instance_request__tnt ON approval_instances (tenant_id, request_id) WHERE request_id IS NOT NULL;
DO $$
DECLARE cname text;
BEGIN
    SELECT c.conname INTO cname
      FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
     WHERE t.relname = 'approval_instances' AND c.conname = 'uq_approval_instance_request';
    IF cname IS NOT NULL THEN
        EXECUTE format('ALTER TABLE approval_instances DROP CONSTRAINT %I', cname);
    END IF;
END $$;
DROP INDEX IF EXISTS uq_approval_instance_request;
ALTER INDEX uq_approval_instance_request__tnt RENAME TO uq_approval_instance_request;

-- warehouses.uq_warehouse_create_request → (tenant_id, create_request_id)
DROP INDEX IF EXISTS uq_warehouse_create_request__tnt;
CREATE UNIQUE INDEX uq_warehouse_create_request__tnt ON warehouses (tenant_id, create_request_id) WHERE create_request_id IS NOT NULL;
DO $$
DECLARE cname text;
BEGIN
    SELECT c.conname INTO cname
      FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
     WHERE t.relname = 'warehouses' AND c.conname = 'uq_warehouse_create_request';
    IF cname IS NOT NULL THEN
        EXECUTE format('ALTER TABLE warehouses DROP CONSTRAINT %I', cname);
    END IF;
END $$;
DROP INDEX IF EXISTS uq_warehouse_create_request;
ALTER INDEX uq_warehouse_create_request__tnt RENAME TO uq_warehouse_create_request;

-- warehouses.uq_warehouse_disable_request → (tenant_id, disable_request_id)
DROP INDEX IF EXISTS uq_warehouse_disable_request__tnt;
CREATE UNIQUE INDEX uq_warehouse_disable_request__tnt ON warehouses (tenant_id, disable_request_id) WHERE disable_request_id IS NOT NULL;
DO $$
DECLARE cname text;
BEGIN
    SELECT c.conname INTO cname
      FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
     WHERE t.relname = 'warehouses' AND c.conname = 'uq_warehouse_disable_request';
    IF cname IS NOT NULL THEN
        EXECUTE format('ALTER TABLE warehouses DROP CONSTRAINT %I', cname);
    END IF;
END $$;
DROP INDEX IF EXISTS uq_warehouse_disable_request;
ALTER INDEX uq_warehouse_disable_request__tnt RENAME TO uq_warehouse_disable_request;

-- suppliers.uq_supplier_create_request → (tenant_id, create_request_id)
DROP INDEX IF EXISTS uq_supplier_create_request__tnt;
CREATE UNIQUE INDEX uq_supplier_create_request__tnt ON suppliers (tenant_id, create_request_id) WHERE create_request_id IS NOT NULL;
DO $$
DECLARE cname text;
BEGIN
    SELECT c.conname INTO cname
      FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
     WHERE t.relname = 'suppliers' AND c.conname = 'uq_supplier_create_request';
    IF cname IS NOT NULL THEN
        EXECUTE format('ALTER TABLE suppliers DROP CONSTRAINT %I', cname);
    END IF;
END $$;
DROP INDEX IF EXISTS uq_supplier_create_request;
ALTER INDEX uq_supplier_create_request__tnt RENAME TO uq_supplier_create_request;

-- suppliers.uq_supplier_disable_request → (tenant_id, disable_request_id)
DROP INDEX IF EXISTS uq_supplier_disable_request__tnt;
CREATE UNIQUE INDEX uq_supplier_disable_request__tnt ON suppliers (tenant_id, disable_request_id) WHERE disable_request_id IS NOT NULL;
DO $$
DECLARE cname text;
BEGIN
    SELECT c.conname INTO cname
      FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
     WHERE t.relname = 'suppliers' AND c.conname = 'uq_supplier_disable_request';
    IF cname IS NOT NULL THEN
        EXECUTE format('ALTER TABLE suppliers DROP CONSTRAINT %I', cname);
    END IF;
END $$;
DROP INDEX IF EXISTS uq_supplier_disable_request;
ALTER INDEX uq_supplier_disable_request__tnt RENAME TO uq_supplier_disable_request;

-- customers.uq_customer_create_request → (tenant_id, create_request_id)
DROP INDEX IF EXISTS uq_customer_create_request__tnt;
CREATE UNIQUE INDEX uq_customer_create_request__tnt ON customers (tenant_id, create_request_id) WHERE create_request_id IS NOT NULL;
DO $$
DECLARE cname text;
BEGIN
    SELECT c.conname INTO cname
      FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
     WHERE t.relname = 'customers' AND c.conname = 'uq_customer_create_request';
    IF cname IS NOT NULL THEN
        EXECUTE format('ALTER TABLE customers DROP CONSTRAINT %I', cname);
    END IF;
END $$;
DROP INDEX IF EXISTS uq_customer_create_request;
ALTER INDEX uq_customer_create_request__tnt RENAME TO uq_customer_create_request;

-- customers.uq_customer_disable_request → (tenant_id, disable_request_id)
DROP INDEX IF EXISTS uq_customer_disable_request__tnt;
CREATE UNIQUE INDEX uq_customer_disable_request__tnt ON customers (tenant_id, disable_request_id) WHERE disable_request_id IS NOT NULL;
DO $$
DECLARE cname text;
BEGIN
    SELECT c.conname INTO cname
      FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
     WHERE t.relname = 'customers' AND c.conname = 'uq_customer_disable_request';
    IF cname IS NOT NULL THEN
        EXECUTE format('ALTER TABLE customers DROP CONSTRAINT %I', cname);
    END IF;
END $$;
DROP INDEX IF EXISTS uq_customer_disable_request;
ALTER INDEX uq_customer_disable_request__tnt RENAME TO uq_customer_disable_request;

-- products.uq_product_create_request → (tenant_id, create_request_id)
DROP INDEX IF EXISTS uq_product_create_request__tnt;
CREATE UNIQUE INDEX uq_product_create_request__tnt ON products (tenant_id, create_request_id) WHERE create_request_id IS NOT NULL;
DO $$
DECLARE cname text;
BEGIN
    SELECT c.conname INTO cname
      FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
     WHERE t.relname = 'products' AND c.conname = 'uq_product_create_request';
    IF cname IS NOT NULL THEN
        EXECUTE format('ALTER TABLE products DROP CONSTRAINT %I', cname);
    END IF;
END $$;
DROP INDEX IF EXISTS uq_product_create_request;
ALTER INDEX uq_product_create_request__tnt RENAME TO uq_product_create_request;

-- skus.uq_sku_create_request → (tenant_id, create_request_id)
DROP INDEX IF EXISTS uq_sku_create_request__tnt;
CREATE UNIQUE INDEX uq_sku_create_request__tnt ON skus (tenant_id, create_request_id) WHERE create_request_id IS NOT NULL;
DO $$
DECLARE cname text;
BEGIN
    SELECT c.conname INTO cname
      FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
     WHERE t.relname = 'skus' AND c.conname = 'uq_sku_create_request';
    IF cname IS NOT NULL THEN
        EXECUTE format('ALTER TABLE skus DROP CONSTRAINT %I', cname);
    END IF;
END $$;
DROP INDEX IF EXISTS uq_sku_create_request;
ALTER INDEX uq_sku_create_request__tnt RENAME TO uq_sku_create_request;

-- po_receipts.uq_po_receipt_request → (tenant_id, request_id)
DROP INDEX IF EXISTS uq_po_receipt_request__tnt;
CREATE UNIQUE INDEX uq_po_receipt_request__tnt ON po_receipts (tenant_id, request_id);
DO $$
DECLARE cname text;
BEGIN
    SELECT c.conname INTO cname
      FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
     WHERE t.relname = 'po_receipts' AND c.conname = 'uq_po_receipt_request';
    IF cname IS NOT NULL THEN
        EXECUTE format('ALTER TABLE po_receipts DROP CONSTRAINT %I', cname);
    END IF;
END $$;
DROP INDEX IF EXISTS uq_po_receipt_request;
ALTER INDEX uq_po_receipt_request__tnt RENAME TO uq_po_receipt_request;

-- purchase_returns.uq_purchase_return_request → (tenant_id, request_id)
DROP INDEX IF EXISTS uq_purchase_return_request__tnt;
CREATE UNIQUE INDEX uq_purchase_return_request__tnt ON purchase_returns (tenant_id, request_id);
DO $$
DECLARE cname text;
BEGIN
    SELECT c.conname INTO cname
      FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
     WHERE t.relname = 'purchase_returns' AND c.conname = 'uq_purchase_return_request';
    IF cname IS NOT NULL THEN
        EXECUTE format('ALTER TABLE purchase_returns DROP CONSTRAINT %I', cname);
    END IF;
END $$;
DROP INDEX IF EXISTS uq_purchase_return_request;
ALTER INDEX uq_purchase_return_request__tnt RENAME TO uq_purchase_return_request;

-- purchase_returns.uq_purchase_return_approve_request → (tenant_id, approve_request_id)
DROP INDEX IF EXISTS uq_purchase_return_approve_request__tnt;
CREATE UNIQUE INDEX uq_purchase_return_approve_request__tnt ON purchase_returns (tenant_id, approve_request_id);
DO $$
DECLARE cname text;
BEGIN
    SELECT c.conname INTO cname
      FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
     WHERE t.relname = 'purchase_returns' AND c.conname = 'uq_purchase_return_approve_request';
    IF cname IS NOT NULL THEN
        EXECUTE format('ALTER TABLE purchase_returns DROP CONSTRAINT %I', cname);
    END IF;
END $$;
DROP INDEX IF EXISTS uq_purchase_return_approve_request;
ALTER INDEX uq_purchase_return_approve_request__tnt RENAME TO uq_purchase_return_approve_request;

-- purchase_returns.uq_purchase_return_void_request → (tenant_id, void_request_id)
DROP INDEX IF EXISTS uq_purchase_return_void_request__tnt;
CREATE UNIQUE INDEX uq_purchase_return_void_request__tnt ON purchase_returns (tenant_id, void_request_id);
DO $$
DECLARE cname text;
BEGIN
    SELECT c.conname INTO cname
      FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
     WHERE t.relname = 'purchase_returns' AND c.conname = 'uq_purchase_return_void_request';
    IF cname IS NOT NULL THEN
        EXECUTE format('ALTER TABLE purchase_returns DROP CONSTRAINT %I', cname);
    END IF;
END $$;
DROP INDEX IF EXISTS uq_purchase_return_void_request;
ALTER INDEX uq_purchase_return_void_request__tnt RENAME TO uq_purchase_return_void_request;

-- so_shipment_plans.uq_shipment_plan_request → (tenant_id, request_id)
DROP INDEX IF EXISTS uq_shipment_plan_request__tnt;
CREATE UNIQUE INDEX uq_shipment_plan_request__tnt ON so_shipment_plans (tenant_id, request_id);
DO $$
DECLARE cname text;
BEGIN
    SELECT c.conname INTO cname
      FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
     WHERE t.relname = 'so_shipment_plans' AND c.conname = 'uq_shipment_plan_request';
    IF cname IS NOT NULL THEN
        EXECUTE format('ALTER TABLE so_shipment_plans DROP CONSTRAINT %I', cname);
    END IF;
END $$;
DROP INDEX IF EXISTS uq_shipment_plan_request;
ALTER INDEX uq_shipment_plan_request__tnt RENAME TO uq_shipment_plan_request;

-- customer_receipts.uq_receipt_request → (tenant_id, request_id)
DROP INDEX IF EXISTS uq_receipt_request__tnt;
CREATE UNIQUE INDEX uq_receipt_request__tnt ON customer_receipts (tenant_id, request_id);
DO $$
DECLARE cname text;
BEGIN
    SELECT c.conname INTO cname
      FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
     WHERE t.relname = 'customer_receipts' AND c.conname = 'uq_receipt_request';
    IF cname IS NOT NULL THEN
        EXECUTE format('ALTER TABLE customer_receipts DROP CONSTRAINT %I', cname);
    END IF;
END $$;
DROP INDEX IF EXISTS uq_receipt_request;
ALTER INDEX uq_receipt_request__tnt RENAME TO uq_receipt_request;

-- supplier_payables.uq_supplier_payable_receipt → (tenant_id, receipt_id)
DROP INDEX IF EXISTS uq_supplier_payable_receipt__tnt;
CREATE UNIQUE INDEX uq_supplier_payable_receipt__tnt ON supplier_payables (tenant_id, receipt_id);
DO $$
DECLARE cname text;
BEGIN
    SELECT c.conname INTO cname
      FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
     WHERE t.relname = 'supplier_payables' AND c.conname = 'uq_supplier_payable_receipt';
    IF cname IS NOT NULL THEN
        EXECUTE format('ALTER TABLE supplier_payables DROP CONSTRAINT %I', cname);
    END IF;
END $$;
DROP INDEX IF EXISTS uq_supplier_payable_receipt;
ALTER INDEX uq_supplier_payable_receipt__tnt RENAME TO uq_supplier_payable_receipt;

-- bundle_items.uq_bundle_item → (tenant_id, bundle_id, component_sku_id)
DROP INDEX IF EXISTS uq_bundle_item__tnt;
CREATE UNIQUE INDEX uq_bundle_item__tnt ON bundle_items (tenant_id, bundle_id, component_sku_id);
DO $$
DECLARE cname text;
BEGIN
    SELECT c.conname INTO cname
      FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
     WHERE t.relname = 'bundle_items' AND c.conname = 'uq_bundle_item';
    IF cname IS NOT NULL THEN
        EXECUTE format('ALTER TABLE bundle_items DROP CONSTRAINT %I', cname);
    END IF;
END $$;
DROP INDEX IF EXISTS uq_bundle_item;
ALTER INDEX uq_bundle_item__tnt RENAME TO uq_bundle_item;

-- ─────────────────────────── ⑥ 热路径复合索引 ────────────────────────────
CREATE INDEX IF NOT EXISTS idx_balance_tenant_sku_wh ON inventory_balances (tenant_id, sku_id, warehouse_id);
CREATE INDEX IF NOT EXISTS idx_ledger_tenant_wh_time ON inventory_ledger (tenant_id, warehouse_id, occurred_at);
CREATE INDEX IF NOT EXISTS idx_ledger_tenant_bizref ON inventory_ledger (tenant_id, biz_ref_no);
CREATE INDEX IF NOT EXISTS idx_ledger_tenant_sku_wh ON inventory_ledger (tenant_id, sku_id, warehouse_id);
CREATE INDEX IF NOT EXISTS idx_po_tenant_status ON purchase_orders (tenant_id, status);
CREATE INDEX IF NOT EXISTS idx_po_tenant_wh_date ON purchase_orders (tenant_id, warehouse_id, biz_date);
CREATE INDEX IF NOT EXISTS idx_so_tenant_status ON sales_orders (tenant_id, status);
CREATE INDEX IF NOT EXISTS idx_so_tenant_wh_date ON sales_orders (tenant_id, warehouse_id, biz_date);
CREATE INDEX IF NOT EXISTS idx_ro_tenant_wh_date ON return_orders (tenant_id, warehouse_id, biz_date);
CREATE INDEX IF NOT EXISTS idx_reservation_tenant_wh ON inventory_reservations (tenant_id, sku_id, warehouse_id, status);
CREATE INDEX IF NOT EXISTS idx_po_line_tenant ON purchase_order_lines (tenant_id, order_id);
CREATE INDEX IF NOT EXISTS idx_so_line_tenant ON sales_order_lines (tenant_id, order_id);
CREATE INDEX IF NOT EXISTS idx_ro_line_tenant ON return_order_lines (tenant_id, order_id);
CREATE INDEX IF NOT EXISTS idx_cost_layer_tenant_pool ON cost_layers (tenant_id, sku_id, warehouse_id, occurred_at);
CREATE INDEX IF NOT EXISTS idx_daily_settlement_tenant ON daily_settlements (tenant_id, biz_date, warehouse_id);
CREATE INDEX IF NOT EXISTS idx_app_users_tenant ON app_users (tenant_id, username);
CREATE INDEX IF NOT EXISTS idx_audit_logs_tenant_time ON audit_logs (tenant_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_logs_tenant_actor ON audit_logs (tenant_id, actor_id, occurred_at);


-- ─────────────────────────── ⑦ 演示租户的第二套种子（FR-TNT-01/06） ──────────────
-- 目的：让"隔离"在**两套都非空**的数据上被验收。只有一套数据时，
-- "租户 B 看不到 A 的数据"与"租户 B 什么都没有"在响应上无法区分 —— 那是假绿。
-- 全部显式给 tenant_id（此刻还没有 DEFAULT，填充触发器也尚未安装），幂等可重跑。
-- 这一节在装 RLS 讨论之前执行；复合唯一已在 ④/⑤ 建好，
-- 所以下面的 ON CONFLICT (tenant_id, code) 能命中索引。
INSERT INTO warehouses (tenant_id, code, name, address) VALUES
    (2, 'DEMO-WH01', '演示仓-上海', '演示数据'),
    (2, 'DEMO-WH02', '演示仓-广州', '演示数据')
ON CONFLICT (tenant_id, code) DO NOTHING;

INSERT INTO products (tenant_id, code, name, category, season) VALUES
    (2, 'DEMO-P-001', '演示商品-圆领T恤', '上装', '2026春')
ON CONFLICT (tenant_id, code) DO NOTHING;

INSERT INTO skus (tenant_id, product_id, color, size, barcode, retail_price_amount)
SELECT 2, p.id, v.color, v.size, v.barcode, v.price
  FROM products p
  JOIN (VALUES ('白色', 'L', '6902000001', 12900),
               ('黑色', 'M', '6902000002', 12900)) AS v(color, size, barcode, price) ON TRUE
 WHERE p.tenant_id = 2 AND p.code = 'DEMO-P-001'
   AND NOT EXISTS (
       SELECT 1 FROM skus k
        WHERE k.product_id = p.id AND k.color = v.color AND k.size = v.size);

INSERT INTO suppliers (tenant_id, code, name) VALUES
    (2, 'DEMO-SUP-001', '演示供应商')
ON CONFLICT (tenant_id, code) DO NOTHING;

INSERT INTO customers (tenant_id, code, name, discount_rate) VALUES
    (2, 'DEMO-CUS-001', '演示客户', 10000)
ON CONFLICT (tenant_id, code) DO NOTHING;

-- 演示租户的库存：用来验证「Σ(各租户库存) == 全局库存」这条守恒不变量
INSERT INTO inventory_balances
    (tenant_id, sku_id, warehouse_id, batch_no, on_hand_qty, reserved_qty)
SELECT 2, k.id, w.id, 'DEMO-B1', 40, 0
  FROM skus k
  JOIN products p ON p.id = k.product_id
  JOIN warehouses w ON w.tenant_id = 2 AND w.code = 'DEMO-WH01'
 WHERE p.tenant_id = 2 AND p.code = 'DEMO-P-001'
   AND k.color = '白色' AND k.size = 'L'
   AND NOT EXISTS (
       SELECT 1 FROM inventory_balances b
        WHERE b.sku_id = k.id AND b.warehouse_id = w.id AND b.batch_no = 'DEMO-B1');

-- 演示租户的角色（与默认租户同构：否则演示租户登录后处处 403，
-- 矩阵用例会把"没权限"误判成"租户隔离生效"）
INSERT INTO app_roles (tenant_id, code, name, description)
SELECT 2, r.code, r.name, r.description
  FROM app_roles r WHERE r.tenant_id = 1
ON CONFLICT (tenant_id, code) DO NOTHING;

-- 演示租户的角色权限：从默认租户的同一角色复制（映射表也带租户）
INSERT INTO app_role_permissions (tenant_id, role_id, permission)
SELECT 2, demo.id, rp.permission
  FROM app_roles demo
  JOIN app_roles src ON src.tenant_id = 1 AND src.code = demo.code
  JOIN app_role_permissions rp ON rp.role_id = src.id
 WHERE demo.tenant_id = 2
   AND NOT EXISTS (
       SELECT 1 FROM app_role_permissions x
        WHERE x.role_id = demo.id AND x.permission = rp.permission);

-- 演示租户的字典
INSERT INTO dict_types (tenant_id, code, name, description)
SELECT 2, d.code, d.name, d.description
  FROM dict_types d WHERE d.tenant_id = 1
ON CONFLICT (tenant_id, code) DO NOTHING;

INSERT INTO dict_items (tenant_id, type_id, code, label, sort_no)
SELECT 2, demo.id, i.code, i.label, i.sort_no
  FROM dict_types demo
  JOIN dict_types src ON src.tenant_id = 1 AND src.code = demo.code
  JOIN dict_items i ON i.type_id = src.id
 WHERE demo.tenant_id = 2
   AND NOT EXISTS (
       SELECT 1 FROM dict_items x WHERE x.type_id = demo.id AND x.code = i.code);

-- 演示租户的审批流定义（与默认租户同构，默认全部停用 —— 与 V14 种子一致）
INSERT INTO approval_flows (tenant_id, code, name, biz_type, description, enabled)
SELECT 2, f.code, f.name, f.biz_type, f.description, FALSE
  FROM approval_flows f WHERE f.tenant_id = 1
ON CONFLICT (tenant_id, code) DO NOTHING;

-- ─────────────────────────── ⑧ NOT NULL（回填已消掉所有 NULL） ─────────────
-- 刻意**不设静态 DEFAULT**（见文件头 ①）：漏写租户由下面的触发器按会话租户填充。

ALTER TABLE warehouses ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE products ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE skus ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE suppliers ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE customers ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE inventory_balances ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE inventory_ledger ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE inventory_reservations ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE inventory_transfers ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE transfer_lines ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE stocktakes ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE stocktake_lines ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE inventory_adjustments ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE inventory_freezes ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE idempotency_keys ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE document_sequences ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE purchase_orders ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE purchase_order_lines ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE po_receipts ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE po_receipt_lines ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE supplier_payables ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE payable_allocations ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE purchase_returns ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE sales_orders ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE sales_order_lines ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE sales_order_line_reservations ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE product_bundles ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE bundle_items ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE sales_quotes ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE sales_quote_lines ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE so_shipment_plans ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE so_shipments ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE shipment_lines ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE customer_receivables ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE customer_receipts ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE receivable_allocations ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE customer_credit_snapshots ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE return_orders ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE return_order_lines ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE settlement_periods ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE daily_settlements ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE cost_layers ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE cost_monthly ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE margin_records ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE dict_types ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE dict_items ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE data_scopes ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE app_users ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE app_roles ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE app_user_roles ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE app_role_permissions ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE approval_flows ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE approval_instances ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE approval_steps ALTER COLUMN tenant_id SET NOT NULL;

-- ─────────────────────────── ⑨ INSERT 时按会话租户填充 ──────────────────
-- 为什么用触发器而不是给 53 个 INSERT 语句逐个补列：后者必然漏（而且以后新写的
-- INSERT 又会漏），漏掉的表现是"数据落进别的租户"——静默且致命。
-- 语义：显式给了非 NULL 的 tenant_id 就用它（迁移 / 运维 / 种子）；否则取会话租户。
-- 它只挂 BEFORE INSERT：audit_logs 上那个禁止 UPDATE/DELETE 的触发器作用于别的
-- 时机，两者不冲突。
CREATE OR REPLACE FUNCTION erp_tenant_fill() RETURNS trigger AS $$
BEGIN
    IF NEW.tenant_id IS NULL THEN
        NEW.tenant_id := erp_tenant_current();
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_warehouses_tenant_fill ON warehouses;
CREATE TRIGGER trg_warehouses_tenant_fill BEFORE INSERT ON warehouses
    FOR EACH ROW EXECUTE FUNCTION erp_tenant_fill();
DROP TRIGGER IF EXISTS trg_products_tenant_fill ON products;
CREATE TRIGGER trg_products_tenant_fill BEFORE INSERT ON products
    FOR EACH ROW EXECUTE FUNCTION erp_tenant_fill();
DROP TRIGGER IF EXISTS trg_skus_tenant_fill ON skus;
CREATE TRIGGER trg_skus_tenant_fill BEFORE INSERT ON skus
    FOR EACH ROW EXECUTE FUNCTION erp_tenant_fill();
DROP TRIGGER IF EXISTS trg_suppliers_tenant_fill ON suppliers;
CREATE TRIGGER trg_suppliers_tenant_fill BEFORE INSERT ON suppliers
    FOR EACH ROW EXECUTE FUNCTION erp_tenant_fill();
DROP TRIGGER IF EXISTS trg_customers_tenant_fill ON customers;
CREATE TRIGGER trg_customers_tenant_fill BEFORE INSERT ON customers
    FOR EACH ROW EXECUTE FUNCTION erp_tenant_fill();
DROP TRIGGER IF EXISTS trg_inventory_balances_tenant_fill ON inventory_balances;
CREATE TRIGGER trg_inventory_balances_tenant_fill BEFORE INSERT ON inventory_balances
    FOR EACH ROW EXECUTE FUNCTION erp_tenant_fill();
DROP TRIGGER IF EXISTS trg_inventory_ledger_tenant_fill ON inventory_ledger;
CREATE TRIGGER trg_inventory_ledger_tenant_fill BEFORE INSERT ON inventory_ledger
    FOR EACH ROW EXECUTE FUNCTION erp_tenant_fill();
DROP TRIGGER IF EXISTS trg_inventory_reservations_tenant_fill ON inventory_reservations;
CREATE TRIGGER trg_inventory_reservations_tenant_fill BEFORE INSERT ON inventory_reservations
    FOR EACH ROW EXECUTE FUNCTION erp_tenant_fill();
DROP TRIGGER IF EXISTS trg_inventory_transfers_tenant_fill ON inventory_transfers;
CREATE TRIGGER trg_inventory_transfers_tenant_fill BEFORE INSERT ON inventory_transfers
    FOR EACH ROW EXECUTE FUNCTION erp_tenant_fill();
DROP TRIGGER IF EXISTS trg_transfer_lines_tenant_fill ON transfer_lines;
CREATE TRIGGER trg_transfer_lines_tenant_fill BEFORE INSERT ON transfer_lines
    FOR EACH ROW EXECUTE FUNCTION erp_tenant_fill();
DROP TRIGGER IF EXISTS trg_stocktakes_tenant_fill ON stocktakes;
CREATE TRIGGER trg_stocktakes_tenant_fill BEFORE INSERT ON stocktakes
    FOR EACH ROW EXECUTE FUNCTION erp_tenant_fill();
DROP TRIGGER IF EXISTS trg_stocktake_lines_tenant_fill ON stocktake_lines;
CREATE TRIGGER trg_stocktake_lines_tenant_fill BEFORE INSERT ON stocktake_lines
    FOR EACH ROW EXECUTE FUNCTION erp_tenant_fill();
DROP TRIGGER IF EXISTS trg_inventory_adjustments_tenant_fill ON inventory_adjustments;
CREATE TRIGGER trg_inventory_adjustments_tenant_fill BEFORE INSERT ON inventory_adjustments
    FOR EACH ROW EXECUTE FUNCTION erp_tenant_fill();
DROP TRIGGER IF EXISTS trg_inventory_freezes_tenant_fill ON inventory_freezes;
CREATE TRIGGER trg_inventory_freezes_tenant_fill BEFORE INSERT ON inventory_freezes
    FOR EACH ROW EXECUTE FUNCTION erp_tenant_fill();
DROP TRIGGER IF EXISTS trg_idempotency_keys_tenant_fill ON idempotency_keys;
CREATE TRIGGER trg_idempotency_keys_tenant_fill BEFORE INSERT ON idempotency_keys
    FOR EACH ROW EXECUTE FUNCTION erp_tenant_fill();
DROP TRIGGER IF EXISTS trg_document_sequences_tenant_fill ON document_sequences;
CREATE TRIGGER trg_document_sequences_tenant_fill BEFORE INSERT ON document_sequences
    FOR EACH ROW EXECUTE FUNCTION erp_tenant_fill();
DROP TRIGGER IF EXISTS trg_purchase_orders_tenant_fill ON purchase_orders;
CREATE TRIGGER trg_purchase_orders_tenant_fill BEFORE INSERT ON purchase_orders
    FOR EACH ROW EXECUTE FUNCTION erp_tenant_fill();
DROP TRIGGER IF EXISTS trg_purchase_order_lines_tenant_fill ON purchase_order_lines;
CREATE TRIGGER trg_purchase_order_lines_tenant_fill BEFORE INSERT ON purchase_order_lines
    FOR EACH ROW EXECUTE FUNCTION erp_tenant_fill();
DROP TRIGGER IF EXISTS trg_po_receipts_tenant_fill ON po_receipts;
CREATE TRIGGER trg_po_receipts_tenant_fill BEFORE INSERT ON po_receipts
    FOR EACH ROW EXECUTE FUNCTION erp_tenant_fill();
DROP TRIGGER IF EXISTS trg_po_receipt_lines_tenant_fill ON po_receipt_lines;
CREATE TRIGGER trg_po_receipt_lines_tenant_fill BEFORE INSERT ON po_receipt_lines
    FOR EACH ROW EXECUTE FUNCTION erp_tenant_fill();
DROP TRIGGER IF EXISTS trg_supplier_payables_tenant_fill ON supplier_payables;
CREATE TRIGGER trg_supplier_payables_tenant_fill BEFORE INSERT ON supplier_payables
    FOR EACH ROW EXECUTE FUNCTION erp_tenant_fill();
DROP TRIGGER IF EXISTS trg_payable_allocations_tenant_fill ON payable_allocations;
CREATE TRIGGER trg_payable_allocations_tenant_fill BEFORE INSERT ON payable_allocations
    FOR EACH ROW EXECUTE FUNCTION erp_tenant_fill();
DROP TRIGGER IF EXISTS trg_purchase_returns_tenant_fill ON purchase_returns;
CREATE TRIGGER trg_purchase_returns_tenant_fill BEFORE INSERT ON purchase_returns
    FOR EACH ROW EXECUTE FUNCTION erp_tenant_fill();
DROP TRIGGER IF EXISTS trg_sales_orders_tenant_fill ON sales_orders;
CREATE TRIGGER trg_sales_orders_tenant_fill BEFORE INSERT ON sales_orders
    FOR EACH ROW EXECUTE FUNCTION erp_tenant_fill();
DROP TRIGGER IF EXISTS trg_sales_order_lines_tenant_fill ON sales_order_lines;
CREATE TRIGGER trg_sales_order_lines_tenant_fill BEFORE INSERT ON sales_order_lines
    FOR EACH ROW EXECUTE FUNCTION erp_tenant_fill();
DROP TRIGGER IF EXISTS trg_sales_order_line_reservations_tenant_fill ON sales_order_line_reservations;
CREATE TRIGGER trg_sales_order_line_reservations_tenant_fill BEFORE INSERT ON sales_order_line_reservations
    FOR EACH ROW EXECUTE FUNCTION erp_tenant_fill();
DROP TRIGGER IF EXISTS trg_product_bundles_tenant_fill ON product_bundles;
CREATE TRIGGER trg_product_bundles_tenant_fill BEFORE INSERT ON product_bundles
    FOR EACH ROW EXECUTE FUNCTION erp_tenant_fill();
DROP TRIGGER IF EXISTS trg_bundle_items_tenant_fill ON bundle_items;
CREATE TRIGGER trg_bundle_items_tenant_fill BEFORE INSERT ON bundle_items
    FOR EACH ROW EXECUTE FUNCTION erp_tenant_fill();
DROP TRIGGER IF EXISTS trg_sales_quotes_tenant_fill ON sales_quotes;
CREATE TRIGGER trg_sales_quotes_tenant_fill BEFORE INSERT ON sales_quotes
    FOR EACH ROW EXECUTE FUNCTION erp_tenant_fill();
DROP TRIGGER IF EXISTS trg_sales_quote_lines_tenant_fill ON sales_quote_lines;
CREATE TRIGGER trg_sales_quote_lines_tenant_fill BEFORE INSERT ON sales_quote_lines
    FOR EACH ROW EXECUTE FUNCTION erp_tenant_fill();
DROP TRIGGER IF EXISTS trg_so_shipment_plans_tenant_fill ON so_shipment_plans;
CREATE TRIGGER trg_so_shipment_plans_tenant_fill BEFORE INSERT ON so_shipment_plans
    FOR EACH ROW EXECUTE FUNCTION erp_tenant_fill();
DROP TRIGGER IF EXISTS trg_so_shipments_tenant_fill ON so_shipments;
CREATE TRIGGER trg_so_shipments_tenant_fill BEFORE INSERT ON so_shipments
    FOR EACH ROW EXECUTE FUNCTION erp_tenant_fill();
DROP TRIGGER IF EXISTS trg_shipment_lines_tenant_fill ON shipment_lines;
CREATE TRIGGER trg_shipment_lines_tenant_fill BEFORE INSERT ON shipment_lines
    FOR EACH ROW EXECUTE FUNCTION erp_tenant_fill();
DROP TRIGGER IF EXISTS trg_customer_receivables_tenant_fill ON customer_receivables;
CREATE TRIGGER trg_customer_receivables_tenant_fill BEFORE INSERT ON customer_receivables
    FOR EACH ROW EXECUTE FUNCTION erp_tenant_fill();
DROP TRIGGER IF EXISTS trg_customer_receipts_tenant_fill ON customer_receipts;
CREATE TRIGGER trg_customer_receipts_tenant_fill BEFORE INSERT ON customer_receipts
    FOR EACH ROW EXECUTE FUNCTION erp_tenant_fill();
DROP TRIGGER IF EXISTS trg_receivable_allocations_tenant_fill ON receivable_allocations;
CREATE TRIGGER trg_receivable_allocations_tenant_fill BEFORE INSERT ON receivable_allocations
    FOR EACH ROW EXECUTE FUNCTION erp_tenant_fill();
DROP TRIGGER IF EXISTS trg_customer_credit_snapshots_tenant_fill ON customer_credit_snapshots;
CREATE TRIGGER trg_customer_credit_snapshots_tenant_fill BEFORE INSERT ON customer_credit_snapshots
    FOR EACH ROW EXECUTE FUNCTION erp_tenant_fill();
DROP TRIGGER IF EXISTS trg_return_orders_tenant_fill ON return_orders;
CREATE TRIGGER trg_return_orders_tenant_fill BEFORE INSERT ON return_orders
    FOR EACH ROW EXECUTE FUNCTION erp_tenant_fill();
DROP TRIGGER IF EXISTS trg_return_order_lines_tenant_fill ON return_order_lines;
CREATE TRIGGER trg_return_order_lines_tenant_fill BEFORE INSERT ON return_order_lines
    FOR EACH ROW EXECUTE FUNCTION erp_tenant_fill();
DROP TRIGGER IF EXISTS trg_settlement_periods_tenant_fill ON settlement_periods;
CREATE TRIGGER trg_settlement_periods_tenant_fill BEFORE INSERT ON settlement_periods
    FOR EACH ROW EXECUTE FUNCTION erp_tenant_fill();
DROP TRIGGER IF EXISTS trg_daily_settlements_tenant_fill ON daily_settlements;
CREATE TRIGGER trg_daily_settlements_tenant_fill BEFORE INSERT ON daily_settlements
    FOR EACH ROW EXECUTE FUNCTION erp_tenant_fill();
DROP TRIGGER IF EXISTS trg_cost_layers_tenant_fill ON cost_layers;
CREATE TRIGGER trg_cost_layers_tenant_fill BEFORE INSERT ON cost_layers
    FOR EACH ROW EXECUTE FUNCTION erp_tenant_fill();
DROP TRIGGER IF EXISTS trg_cost_monthly_tenant_fill ON cost_monthly;
CREATE TRIGGER trg_cost_monthly_tenant_fill BEFORE INSERT ON cost_monthly
    FOR EACH ROW EXECUTE FUNCTION erp_tenant_fill();
DROP TRIGGER IF EXISTS trg_margin_records_tenant_fill ON margin_records;
CREATE TRIGGER trg_margin_records_tenant_fill BEFORE INSERT ON margin_records
    FOR EACH ROW EXECUTE FUNCTION erp_tenant_fill();
DROP TRIGGER IF EXISTS trg_dict_types_tenant_fill ON dict_types;
CREATE TRIGGER trg_dict_types_tenant_fill BEFORE INSERT ON dict_types
    FOR EACH ROW EXECUTE FUNCTION erp_tenant_fill();
DROP TRIGGER IF EXISTS trg_dict_items_tenant_fill ON dict_items;
CREATE TRIGGER trg_dict_items_tenant_fill BEFORE INSERT ON dict_items
    FOR EACH ROW EXECUTE FUNCTION erp_tenant_fill();
DROP TRIGGER IF EXISTS trg_data_scopes_tenant_fill ON data_scopes;
CREATE TRIGGER trg_data_scopes_tenant_fill BEFORE INSERT ON data_scopes
    FOR EACH ROW EXECUTE FUNCTION erp_tenant_fill();
DROP TRIGGER IF EXISTS trg_app_users_tenant_fill ON app_users;
CREATE TRIGGER trg_app_users_tenant_fill BEFORE INSERT ON app_users
    FOR EACH ROW EXECUTE FUNCTION erp_tenant_fill();
DROP TRIGGER IF EXISTS trg_app_roles_tenant_fill ON app_roles;
CREATE TRIGGER trg_app_roles_tenant_fill BEFORE INSERT ON app_roles
    FOR EACH ROW EXECUTE FUNCTION erp_tenant_fill();
DROP TRIGGER IF EXISTS trg_app_user_roles_tenant_fill ON app_user_roles;
CREATE TRIGGER trg_app_user_roles_tenant_fill BEFORE INSERT ON app_user_roles
    FOR EACH ROW EXECUTE FUNCTION erp_tenant_fill();
DROP TRIGGER IF EXISTS trg_app_role_permissions_tenant_fill ON app_role_permissions;
CREATE TRIGGER trg_app_role_permissions_tenant_fill BEFORE INSERT ON app_role_permissions
    FOR EACH ROW EXECUTE FUNCTION erp_tenant_fill();
DROP TRIGGER IF EXISTS trg_approval_flows_tenant_fill ON approval_flows;
CREATE TRIGGER trg_approval_flows_tenant_fill BEFORE INSERT ON approval_flows
    FOR EACH ROW EXECUTE FUNCTION erp_tenant_fill();
DROP TRIGGER IF EXISTS trg_approval_instances_tenant_fill ON approval_instances;
CREATE TRIGGER trg_approval_instances_tenant_fill BEFORE INSERT ON approval_instances
    FOR EACH ROW EXECUTE FUNCTION erp_tenant_fill();
DROP TRIGGER IF EXISTS trg_approval_steps_tenant_fill ON approval_steps;
CREATE TRIGGER trg_approval_steps_tenant_fill BEFORE INSERT ON approval_steps
    FOR EACH ROW EXECUTE FUNCTION erp_tenant_fill();
-- audit_logs 也装同一个填充触发器（V13 的 append-only 触发器原样保留）
DROP TRIGGER IF EXISTS trg_audit_logs_tenant_fill ON audit_logs;
CREATE TRIGGER trg_audit_logs_tenant_fill BEFORE INSERT ON audit_logs
    FOR EACH ROW EXECUTE FUNCTION erp_tenant_fill();

-- ─────────────────────────── ⑩ 迁移自检（在装 RLS 之前） ────────────────
-- 不变量：跑完回填，库里不存在「没有租户的行」。这条断言让「回填漏了某张表」
-- 变成启动期硬失败，而不是几个月后才在某个查询里显形。
-- ★ 必须在装 RLS **之前**跑：装了策略之后，NULL 行会被策略过滤掉，
--   count(*) 恒为 0 —— 那样的自检是恒真的假绿。
DO $$
DECLARE n bigint;
BEGIN
    n := (SELECT count(*) FROM warehouses WHERE tenant_id IS NULL) + (SELECT count(*) FROM products WHERE tenant_id IS NULL) + (SELECT count(*) FROM skus WHERE tenant_id IS NULL) + (SELECT count(*) FROM suppliers WHERE tenant_id IS NULL) + (SELECT count(*) FROM customers WHERE tenant_id IS NULL) + (SELECT count(*) FROM inventory_balances WHERE tenant_id IS NULL) + (SELECT count(*) FROM inventory_ledger WHERE tenant_id IS NULL) + (SELECT count(*) FROM inventory_reservations WHERE tenant_id IS NULL) + (SELECT count(*) FROM inventory_transfers WHERE tenant_id IS NULL) + (SELECT count(*) FROM transfer_lines WHERE tenant_id IS NULL) + (SELECT count(*) FROM stocktakes WHERE tenant_id IS NULL) + (SELECT count(*) FROM stocktake_lines WHERE tenant_id IS NULL) + (SELECT count(*) FROM inventory_adjustments WHERE tenant_id IS NULL) + (SELECT count(*) FROM inventory_freezes WHERE tenant_id IS NULL) + (SELECT count(*) FROM idempotency_keys WHERE tenant_id IS NULL) + (SELECT count(*) FROM document_sequences WHERE tenant_id IS NULL) + (SELECT count(*) FROM purchase_orders WHERE tenant_id IS NULL) + (SELECT count(*) FROM purchase_order_lines WHERE tenant_id IS NULL) + (SELECT count(*) FROM po_receipts WHERE tenant_id IS NULL) + (SELECT count(*) FROM po_receipt_lines WHERE tenant_id IS NULL) + (SELECT count(*) FROM supplier_payables WHERE tenant_id IS NULL) + (SELECT count(*) FROM payable_allocations WHERE tenant_id IS NULL) + (SELECT count(*) FROM purchase_returns WHERE tenant_id IS NULL) + (SELECT count(*) FROM sales_orders WHERE tenant_id IS NULL) + (SELECT count(*) FROM sales_order_lines WHERE tenant_id IS NULL) + (SELECT count(*) FROM sales_order_line_reservations WHERE tenant_id IS NULL) + (SELECT count(*) FROM product_bundles WHERE tenant_id IS NULL) + (SELECT count(*) FROM bundle_items WHERE tenant_id IS NULL) + (SELECT count(*) FROM sales_quotes WHERE tenant_id IS NULL) + (SELECT count(*) FROM sales_quote_lines WHERE tenant_id IS NULL) + (SELECT count(*) FROM so_shipment_plans WHERE tenant_id IS NULL) + (SELECT count(*) FROM so_shipments WHERE tenant_id IS NULL) + (SELECT count(*) FROM shipment_lines WHERE tenant_id IS NULL) + (SELECT count(*) FROM customer_receivables WHERE tenant_id IS NULL) + (SELECT count(*) FROM customer_receipts WHERE tenant_id IS NULL) + (SELECT count(*) FROM receivable_allocations WHERE tenant_id IS NULL) + (SELECT count(*) FROM customer_credit_snapshots WHERE tenant_id IS NULL) + (SELECT count(*) FROM return_orders WHERE tenant_id IS NULL) + (SELECT count(*) FROM return_order_lines WHERE tenant_id IS NULL) + (SELECT count(*) FROM settlement_periods WHERE tenant_id IS NULL) + (SELECT count(*) FROM daily_settlements WHERE tenant_id IS NULL) + (SELECT count(*) FROM cost_layers WHERE tenant_id IS NULL) + (SELECT count(*) FROM cost_monthly WHERE tenant_id IS NULL) + (SELECT count(*) FROM margin_records WHERE tenant_id IS NULL) + (SELECT count(*) FROM dict_types WHERE tenant_id IS NULL) + (SELECT count(*) FROM dict_items WHERE tenant_id IS NULL) + (SELECT count(*) FROM data_scopes WHERE tenant_id IS NULL) + (SELECT count(*) FROM app_users WHERE tenant_id IS NULL) + (SELECT count(*) FROM app_roles WHERE tenant_id IS NULL) + (SELECT count(*) FROM app_user_roles WHERE tenant_id IS NULL) + (SELECT count(*) FROM app_role_permissions WHERE tenant_id IS NULL) + (SELECT count(*) FROM approval_flows WHERE tenant_id IS NULL) + (SELECT count(*) FROM approval_instances WHERE tenant_id IS NULL) + (SELECT count(*) FROM approval_steps WHERE tenant_id IS NULL);
    IF n > 0 THEN
        RAISE EXCEPTION 'V16 自检失败：仍有 % 行没有 tenant_id', n;
    END IF;
END $$;


-- ─────────────────────────── ⑪ RLS 评估结论（不启用，理由见下） ────────────────
-- 本泳道**刻意不启用** PostgreSQL RLS，并把评估过程写在这里，避免下一个读到
-- 这份迁移的人以为"漏了 RLS"或"RLS 已经保护了数据"。
--
-- 为什么评估过 RLS：它是最省事的一层（对子查询 / JOIN / 聚合 / EXISTS 全部生效，
-- 新写的 SQL 自动安全），理论上比在 Java 里拼 SQL 更强。
--
-- 为什么最终没启用：
--   ① **超级用户永远绕过 RLS**，连 FORCE ROW LEVEL SECURITY 都拦不住（实测：
--      POSTGRES_USER=erp 的容器里，装了策略、订了 FORCE，跨租户 select 依旧全表）。
--      本项目 application.yml 与 compose 都用 erp 连接，因此"策略已装、用例全绿、
--      隔离零生效"会同时成立 —— 那是本泳道最不能接受的一类假绿。
--   ② 要让 RLS 真正生效，必须让应用以非超级用户连接，而 RLS 读的是**会话变量**；
--      会话变量在连接池里必须逐次借用绑定，绑错一次就是跨租户泄露或误过滤。
--      这条链路上每一环都比"在 SQL 文本里注入谓词"更难验证，也更容易静默出错。
--   ③ 半开 RLS 比不开更糟：策略恒解析成默认租户时，租户 2 的请求会被自己的策略
--      挡住（表现为"登录成功但什么都查不到"）。
--
-- 因此本泳道的强制过滤落在 **Java 的 SQL 层**（com.erp.shared.tenant.TenantSql，
-- 唯一出口是 TenantAwareJdbcTemplate），并由三道门禁守着"没有语句能漏":
--   ① TenantSqlGuardTest：扫描整轮测试执行的**每一条** SQL，凡引用了业务表却
--      没有租户谓词（或不在显式豁免清单里）即失败；
--   ② TenantIsolationMatrixIT：135 条跨租户组合，断言 404 / 空结果 + 同租户正对照；
--   ③ 复合唯一索引 + INSERT 填充触发器：数据库层挡住"撞号"与"漏写租户"。
-- 生产若要再加 RLS，前提是先把连接串换成非超级用户（DB_USER=erp_app）并补会话
-- 变量绑定 —— 这条已登记为遗留项（.plan/V2-7-报告.md §遗留）。
