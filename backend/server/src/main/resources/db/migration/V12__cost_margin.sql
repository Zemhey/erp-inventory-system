-- ============================================================================
-- V12 · V2-4 成本毛利（FR-CST-01 / FR-CST-02 / FR-CST-03）
-- 范围：
--   ① cost_layers   —— 每条库存流水对应的成本层（移动加权，派生自 inventory_ledger）
--   ② cost_monthly  —— 按 (账期, SKU, 仓库) 的成本月结转
--   ③ margin_records —— 按 (出货单, SKU) 的毛利快照
--   ④ cst:* 权限种子
-- 依据：.plan/V2-4-计划书.md §3、ADR-0005（金额整数分）、ADR-0007（流水为事实源）
--
-- 设计原则（延续 V1~V8）：**追加式迁移**。已合并的 V1~V8 一行不改。
-- 成本表全部是**派生数据**：任何时刻都可以从 inventory_ledger 重算覆盖，
-- 因此不建 inventory_ledger 外键，也不回写流水的 unit_cost_amount。
-- ============================================================================

-- ─────────────────────────── ① 成本层 ───────────────────────────
--
-- 一条流水 = 一条成本层。unit_cost/total_cost 是**该条流水**结转的成本，
-- pool_qty_after / pool_cost_after 是该 (SKU, 仓库) 成本池回放后的状态。
-- 有了池状态，"当前结存成本"不用每次全量回放；有了 unique(ledger_id)，
-- 重算时"一条流水最多一个成本层"由数据库兜底。
--
-- 金额允许为负只可能出现在「买退跨批次与销售交错」的极端场景；
-- 正常业务路径（进货 >= 0、销售按均价）金额恒为非负。
CREATE TABLE cost_layers (
    id                BIGSERIAL PRIMARY KEY,
    ledger_id         BIGINT      NOT NULL,
    sku_id            BIGINT      NOT NULL REFERENCES skus (id),
    warehouse_id      BIGINT      NOT NULL REFERENCES warehouses (id),
    batch_no          VARCHAR(64) NOT NULL DEFAULT '',
    biz_type          VARCHAR(32) NOT NULL,
    biz_ref_no        VARCHAR(64) NOT NULL,
    direction         VARCHAR(8)  NOT NULL CHECK (direction IN ('IN','OUT')),
    qty               INTEGER     NOT NULL CHECK (qty > 0),
    unit_cost_amount  BIGINT      NOT NULL,
    total_cost_amount BIGINT      NOT NULL,
    pool_qty_after    BIGINT      NOT NULL,
    pool_cost_after   BIGINT      NOT NULL,
    occurred_at       TIMESTAMPTZ NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_cost_layer_ledger UNIQUE (ledger_id),
    -- 方向必须与 biz_type 对应：漏掉这条，把 SALES_OUT 记成 IN 也能进表，
    -- 期末成本与毛利会静默偏一个数量级（不变量 INV-11 的结构性防线）。
    CONSTRAINT ck_cost_layer_direction CHECK (
        (direction = 'IN'  AND biz_type IN
            ('PURCHASE_IN','RETURN_IN','SALES_REVERSAL','ADJUST_IN','TRANSFER_IN'))
     OR (direction = 'OUT' AND biz_type IN
            ('SALES_OUT','PURCHASE_REVERSAL','ADJUST_OUT','TRANSFER_OUT'))
    )
);
COMMENT ON TABLE cost_layers IS
    '成本层：每条库存流水的移动加权结转成本（派生数据，可从 inventory_ledger 全量重算）';
COMMENT ON COLUMN cost_layers.ledger_id IS
    'inventory_ledger.id；不建外键，因为流水是不可变事实源、成本表是派生缓存';
COMMENT ON COLUMN cost_layers.pool_qty_after IS
    '该流水回放后 (sku_id, warehouse_id) 成本池数量；期末成本直接取最后一条';
COMMENT ON COLUMN cost_layers.pool_cost_after IS
    '该流水回放后成本池金额（分）';

CREATE INDEX idx_cost_layer_pool ON cost_layers (sku_id, warehouse_id, occurred_at, id);
CREATE INDEX idx_cost_layer_period ON cost_layers (occurred_at);
CREATE INDEX idx_cost_layer_bizref_type ON cost_layers (biz_ref_no, biz_type);

-- ─────────────────────────── ② 成本月结转 ───────────────────────────
--
-- 粒度 (period, sku_id, warehouse_id)。期初取期间开始前最后一条成本层的池状态，
-- 期内按方向汇总，期末 = 期初 + 入 − 出。
-- 两条 CHECK 把恒等式写进数据库：应用层算错也进不了表。
CREATE TABLE cost_monthly (
    id                   BIGSERIAL PRIMARY KEY,
    period               VARCHAR(7) NOT NULL,
    sku_id               BIGINT     NOT NULL REFERENCES skus (id),
    warehouse_id         BIGINT     NOT NULL REFERENCES warehouses (id),
    opening_qty          BIGINT     NOT NULL DEFAULT 0,
    opening_cost_amount  BIGINT     NOT NULL DEFAULT 0,
    inbound_qty          BIGINT     NOT NULL DEFAULT 0,
    inbound_cost_amount  BIGINT     NOT NULL DEFAULT 0,
    outbound_qty         BIGINT     NOT NULL DEFAULT 0,
    outbound_cost_amount BIGINT     NOT NULL DEFAULT 0,
    closing_qty          BIGINT     NOT NULL DEFAULT 0,
    closing_cost_amount  BIGINT     NOT NULL DEFAULT 0,
    request_id           VARCHAR(64),
    computed_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    version              INTEGER     NOT NULL DEFAULT 0,
    CONSTRAINT uq_cost_monthly UNIQUE (period, sku_id, warehouse_id),
    CONSTRAINT ck_cost_monthly_period CHECK (period ~ '^[0-9]{4}-[0-9]{2}$'),
    CONSTRAINT ck_cost_monthly_qty CHECK (
        closing_qty = opening_qty + inbound_qty - outbound_qty),
    CONSTRAINT ck_cost_monthly_cost CHECK (
        closing_cost_amount = opening_cost_amount + inbound_cost_amount - outbound_cost_amount)
);
COMMENT ON TABLE cost_monthly IS
    '成本月结转：按账期/SKU/仓库的期初、入、出、期末；幂等键为 (period, sku_id, warehouse_id)';
COMMENT ON COLUMN cost_monthly.request_id IS
    '最近一次执行成本月结的幂等键，仅用于排障追踪；幂等由唯一键 + 先读后比保证';

CREATE INDEX idx_cost_monthly_period ON cost_monthly (period);

-- ─────────────────────────── ③ 毛利记录 ───────────────────────────
--
-- 粒度 (sales_order_id, sku_id)。同单同 SKU 多行合并，避免行间成本分摊误差。
-- 净额口径：已出货 − 已退货；完全退货（qty=0）不落行。
CREATE TABLE margin_records (
    id                   BIGSERIAL PRIMARY KEY,
    period               VARCHAR(7)  NOT NULL,
    sales_order_id       BIGINT      NOT NULL REFERENCES sales_orders (id),
    sales_order_no       VARCHAR(64) NOT NULL,
    customer_id          BIGINT      NOT NULL REFERENCES customers (id),
    sku_id               BIGINT      NOT NULL REFERENCES skus (id),
    warehouse_id         BIGINT      NOT NULL REFERENCES warehouses (id),
    biz_date             DATE        NOT NULL,
    qty                  INTEGER     NOT NULL CHECK (qty > 0),
    revenue_amount       BIGINT      NOT NULL CHECK (revenue_amount >= 0),
    cost_amount          BIGINT      NOT NULL,
    gross_profit_amount  BIGINT      NOT NULL,
    gross_margin_rate_bp BIGINT      NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_margin_order_sku UNIQUE (sales_order_id, sku_id),
    CONSTRAINT ck_margin_period CHECK (period ~ '^[0-9]{4}-[0-9]{2}$'),
    CONSTRAINT ck_margin_profit CHECK (gross_profit_amount = revenue_amount - cost_amount)
);
COMMENT ON TABLE margin_records IS
    '毛利快照：按出货单 × SKU，收入 − 移动加权出库成本；净额口径（已出货 − 已退货）';
COMMENT ON COLUMN margin_records.gross_margin_rate_bp IS
    '毛利率万分比（6000 = 60%）；收入为 0 时记 0';

CREATE INDEX idx_margin_period ON margin_records (period);
CREATE INDEX idx_margin_customer ON margin_records (customer_id, period);
CREATE INDEX idx_margin_sku ON margin_records (sku_id, period);

-- ─────────────────────────── ④ 权限种子 ───────────────────────────
-- 只种角色→权限，不创建账号（沿用 V3/V5 规则）。
-- ADMIN 另有 ROLE_ADMIN 短路，cst:* 只是数据完备。

INSERT INTO app_role_permissions (role_id, permission)
SELECT r.id, p.permission FROM app_roles r
JOIN (VALUES
    ('ADMIN',   'cst:*'),
    ('FINANCE', 'cst:read'), ('FINANCE', 'cst:recalc'), ('FINANCE', 'cst:monthly'),
    ('VIEWER',  'cst:read')
) AS p(role_code, permission) ON r.code = p.role_code
ON CONFLICT DO NOTHING;
