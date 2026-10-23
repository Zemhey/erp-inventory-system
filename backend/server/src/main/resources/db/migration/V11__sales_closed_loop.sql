-- ============================================================================
-- V11 · V2-3 销售闭环（FR-SAL-05 ~ 11 / FR-MDM-08）
-- 范围：
--   ① 主数据加列：customers 信用/账期、skus 季节/年份/波段、warehouses.priority
--   ② 报价单：sales_quotes / sales_quote_lines
--   ③ 套装：product_bundles / bundle_items；出货明细记录套装分组与配比
--   ④ 发货作业：so_shipment_plans / so_shipments / shipment_lines
--   ⑤ 应收/收款：customer_receivables / customer_receipts / receivable_allocations
--   ⑥ 信用快照：customer_credit_snapshots
--   ⑦ 权限种子：sal:quote / sal:pick / sal:receipt / sal:bundle
-- 依据：.plan/V2-3-计划书.md、.plan/V2-3-契约申请.md
--
-- 设计原则（延续 V1~V8）：**追加式迁移**。V1~V8 一行不改；
--   所有约束/索引命名清晰，便于测试与排障定位。
--
-- ★ 与 V2-1 的 V9 双写：warehouses.priority 两个泳道都会加，
--   谁先合谁建。这里用 ADD COLUMN IF NOT EXISTS 保证后合并方不报错。
-- ============================================================================

-- ─────────────────────────── ① 主数据加列 ───────────────────────────

-- ★ 与 V9 双保险，谁先合谁建：两列都由本迁移用 IF NOT EXISTS 兜底。
ALTER TABLE warehouses
    ADD COLUMN IF NOT EXISTS priority INTEGER NOT NULL DEFAULT 100;
COMMENT ON COLUMN warehouses.priority IS
    '发货选仓优先级：数值越小越优先（默认 100）。与 V9 双保险，谁先合谁建';
CREATE INDEX IF NOT EXISTS idx_warehouse_priority ON warehouses (priority, id);

ALTER TABLE customers
    ADD COLUMN credit_limit BIGINT NOT NULL DEFAULT 0
        CHECK (credit_limit >= 0),
    ADD COLUMN credit_days INTEGER NOT NULL DEFAULT 0
        CHECK (credit_days >= 0);
COMMENT ON COLUMN customers.credit_limit IS
    '信用额度（分）。0 = 不设限；大于 0 时建单前校验敞口，超额拒单且不产生预占';
COMMENT ON COLUMN customers.credit_days IS
    '账期天数。应收到期日 = 出货时的 ClockProvider.today() + credit_days';

ALTER TABLE skus
    ADD COLUMN season_code VARCHAR(32),
    ADD COLUMN band_code VARCHAR(32),
    ADD COLUMN launch_date DATE;
COMMENT ON COLUMN skus.season_code IS '季节编码（如 2026SS），FR-MDM-08';
COMMENT ON COLUMN skus.band_code IS '波段编码（如 BAND-01），FR-MDM-08';
COMMENT ON COLUMN skus.launch_date IS '上市日期；年份维度由该列派生，不另存冗余列';
CREATE INDEX idx_sku_season_band ON skus (season_code, band_code, launch_date);

-- ─────────────────────────── ② 套装 ───────────────────────────

CREATE TABLE product_bundles (
    id            BIGSERIAL PRIMARY KEY,
    code          VARCHAR(32)  NOT NULL,
    name          VARCHAR(128) NOT NULL,
    price_amount  BIGINT       NOT NULL DEFAULT 0 CHECK (price_amount >= 0),
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_bundle_code UNIQUE (code)
);
COMMENT ON TABLE product_bundles IS
    '组合商品/套装（FR-SAL-10）。套装不持有库存，可用量由组件可用量按配比派生（INV-16）';
COMMENT ON COLUMN product_bundles.price_amount IS
    '套装展示价（分）。仅报价展示；出库/结算按组件行金额，保证与日清重算同口径';

CREATE TABLE bundle_items (
    id                BIGSERIAL PRIMARY KEY,
    bundle_id         BIGINT  NOT NULL REFERENCES product_bundles (id) ON DELETE CASCADE,
    component_sku_id  BIGINT  NOT NULL REFERENCES skus (id),
    ratio_qty         INTEGER NOT NULL CHECK (ratio_qty > 0),
    CONSTRAINT uq_bundle_item UNIQUE (bundle_id, component_sku_id)
);
COMMENT ON TABLE bundle_items IS
    '套装组件配比：1 个套装 = ratio_qty 个组件 SKU（INV-16 的守恒基准）';

CREATE INDEX idx_bundle_item_sku ON bundle_items (component_sku_id);

-- 出货明细的套装分组：套装订单落**组件行**，sku_id 仍是组件 SKU；
-- 同一 bundle_group_no 的组件行共享套装数量与配比。
ALTER TABLE sales_order_lines
    ADD COLUMN bundle_id BIGINT REFERENCES product_bundles (id),
    ADD COLUMN bundle_group_no INTEGER,
    ADD COLUMN bundle_ratio INTEGER;
ALTER TABLE sales_order_lines
    ADD CONSTRAINT ck_so_line_bundle_group CHECK (
        (bundle_id IS NULL AND bundle_group_no IS NULL AND bundle_ratio IS NULL)
        OR (bundle_id IS NOT NULL AND bundle_group_no > 0 AND bundle_ratio > 0)
    );
COMMENT ON COLUMN sales_order_lines.bundle_id IS
    '套装来源；NULL = 普通 SKU 行。套装行按组件展开，套装本身不建库存行（INV-16）';
COMMENT ON COLUMN sales_order_lines.bundle_group_no IS
    '同一订单内的套装分组号（一张订单可有多组套装）';
COMMENT ON COLUMN sales_order_lines.bundle_ratio IS
    '组件配比：本行数量 = 套装数量 × bundle_ratio';

-- 跨仓自动选仓后，一行订单可有多仓预占；发货计划按仓拆单依赖这两列。
ALTER TABLE sales_order_line_reservations
    ADD COLUMN warehouse_id BIGINT REFERENCES warehouses (id),
    ADD COLUMN batch_no VARCHAR(64);
COMMENT ON COLUMN sales_order_line_reservations.warehouse_id IS
    '预占所在仓库。自动选仓/拆单后同一订单行可跨多个仓预占（FR-SAL-11）';
COMMENT ON COLUMN sales_order_line_reservations.batch_no IS
    '预占批次快照，用于发货拣货行展示；实际扣减批次仍由 FEFO 决定';

CREATE INDEX idx_so_line_res_wh ON sales_order_line_reservations (line_id, warehouse_id);

-- ─────────────────────────── ③ 报价单 ───────────────────────────

CREATE TABLE sales_quotes (
    id                   BIGSERIAL PRIMARY KEY,
    quote_no             VARCHAR(64) NOT NULL,
    status               VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    customer_id          BIGINT      NOT NULL REFERENCES customers (id),
    biz_date             DATE        NOT NULL,
    valid_until          DATE,
    total_amount         BIGINT      NOT NULL DEFAULT 0 CHECK (total_amount >= 0),
    total_qty            INTEGER     NOT NULL DEFAULT 0 CHECK (total_qty >= 0),
    remark               VARCHAR(500),
    request_id           VARCHAR(64),
    convert_request_id   VARCHAR(64),
    converted_order_id   BIGINT REFERENCES sales_orders (id),
    version              INTEGER     NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_quote_no UNIQUE (quote_no),
    CONSTRAINT ck_quote_status CHECK (status IN
        ('DRAFT','SUBMITTED','APPROVED','PARTIAL','COMPLETED','VOIDED','REJECTED')),
    CONSTRAINT ck_quote_valid_until CHECK (valid_until IS NULL OR valid_until >= biz_date)
);
COMMENT ON TABLE sales_quotes IS
    '报价单（FR-SAL-05）。COMPLETED = 已转化为出货单；converted_order_id 记录转化结果';
COMMENT ON COLUMN sales_quotes.convert_request_id IS
    '转化幂等键；同键重放返回 converted_order_id 指向的订单';
CREATE UNIQUE INDEX uq_quote_request ON sales_quotes (request_id) WHERE request_id IS NOT NULL;
CREATE UNIQUE INDEX uq_quote_convert_request ON sales_quotes (convert_request_id)
    WHERE convert_request_id IS NOT NULL;
CREATE UNIQUE INDEX uq_quote_converted_order ON sales_quotes (converted_order_id)
    WHERE converted_order_id IS NOT NULL;
CREATE INDEX idx_quote_customer ON sales_quotes (customer_id, biz_date DESC);

CREATE TABLE sales_quote_lines (
    id                BIGSERIAL PRIMARY KEY,
    quote_id          BIGINT  NOT NULL REFERENCES sales_quotes (id) ON DELETE CASCADE,
    line_no           INTEGER NOT NULL CHECK (line_no > 0),
    sku_id            BIGINT  NOT NULL REFERENCES skus (id),
    qty               INTEGER NOT NULL CHECK (qty > 0),
    unit_price_amount BIGINT  NOT NULL CHECK (unit_price_amount >= 0),
    discount_rate     INTEGER NOT NULL DEFAULT 10000
                          CHECK (discount_rate > 0 AND discount_rate <= 10000),
    amount            BIGINT  NOT NULL CHECK (amount >= 0),
    CONSTRAINT uq_quote_line_no UNIQUE (quote_id, line_no)
);
COMMENT ON TABLE sales_quote_lines IS
    '报价明细。金额 = round(qty × unitPrice × discountRate / 10000)，与出货同一公式';

CREATE INDEX idx_quote_line_quote ON sales_quote_lines (quote_id);

-- ─────────────────────────── ④ 发货作业 ───────────────────────────

CREATE TABLE so_shipment_plans (
    id             BIGSERIAL PRIMARY KEY,
    plan_no        VARCHAR(64) NOT NULL,
    sales_order_id BIGINT      NOT NULL REFERENCES sales_orders (id),
    request_id     VARCHAR(64) NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_shipment_plan_no UNIQUE (plan_no),
    CONSTRAINT uq_shipment_plan_request UNIQUE (request_id)
);
COMMENT ON TABLE so_shipment_plans IS
    '发货计划（FR-SAL-06）：一次计划可拆成多张按仓发货单；request_id 保证计划幂等';

CREATE TABLE so_shipments (
    id                 BIGSERIAL PRIMARY KEY,
    plan_id            BIGINT      NOT NULL REFERENCES so_shipment_plans (id),
    shipment_no        VARCHAR(64) NOT NULL,
    sales_order_id     BIGINT      NOT NULL REFERENCES sales_orders (id),
    warehouse_id       BIGINT      NOT NULL REFERENCES warehouses (id),
    status             VARCHAR(16) NOT NULL DEFAULT 'DRAFT'
                           CHECK (status IN ('DRAFT','PICKING','PICKED','COMPLETED','VOIDED')),
    confirm_request_id VARCHAR(64),
    version            INTEGER     NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_shipment_no UNIQUE (shipment_no)
);
COMMENT ON TABLE so_shipments IS
    '按仓拆分的发货单（FR-SAL-06/11）。单仓足够时只有一张；跨仓时每个仓一张';
CREATE UNIQUE INDEX uq_shipment_confirm_request ON so_shipments (confirm_request_id)
    WHERE confirm_request_id IS NOT NULL;
CREATE INDEX idx_shipment_plan ON so_shipments (plan_id);
CREATE INDEX idx_shipment_order ON so_shipments (sales_order_id, status);

CREATE TABLE shipment_lines (
    id                   BIGSERIAL PRIMARY KEY,
    shipment_id          BIGINT      NOT NULL REFERENCES so_shipments (id) ON DELETE CASCADE,
    line_no              INTEGER     NOT NULL CHECK (line_no > 0),
    sales_order_line_id  BIGINT      NOT NULL REFERENCES sales_order_lines (id),
    sku_id               BIGINT      NOT NULL REFERENCES skus (id),
    reservation_id       BIGINT      NOT NULL,
    batch_no             VARCHAR(64) NOT NULL DEFAULT '',
    planned_qty          INTEGER     NOT NULL CHECK (planned_qty > 0),
    picked_qty           INTEGER     NOT NULL DEFAULT 0
                             CHECK (picked_qty >= 0 AND picked_qty <= planned_qty),
    CONSTRAINT uq_shipment_line_no UNIQUE (shipment_id, line_no),
    -- 一条预占只能被一个拣货行占用：并发重复计划在数据库层被拦住
    CONSTRAINT uq_shipment_line_reservation UNIQUE (reservation_id)
);
COMMENT ON TABLE shipment_lines IS
    '拣货行：来自一条 ACTIVE 预占分段。planned_qty = 该段预占量，扫码累计不得超计划';
CREATE INDEX idx_shipment_line_shipment ON shipment_lines (shipment_id);
CREATE INDEX idx_shipment_line_order_line ON shipment_lines (sales_order_line_id);

-- ─────────────────────────── ⑤ 应收 / 收款 ───────────────────────────

CREATE TABLE customer_receivables (
    id              BIGSERIAL PRIMARY KEY,
    sales_order_id  BIGINT      NOT NULL REFERENCES sales_orders (id),
    customer_id     BIGINT      NOT NULL REFERENCES customers (id),
    biz_date        DATE        NOT NULL,
    due_date        DATE,
    shipped_amount  BIGINT      NOT NULL DEFAULT 0 CHECK (shipped_amount >= 0),
    returned_amount BIGINT      NOT NULL DEFAULT 0 CHECK (returned_amount >= 0),
    received_amount BIGINT      NOT NULL DEFAULT 0 CHECK (received_amount >= 0),
    balance         BIGINT      NOT NULL DEFAULT 0,
    status          VARCHAR(16) NOT NULL DEFAULT 'OPEN'
                        CHECK (status IN ('OPEN','SETTLED','VOIDED')),
    version         INTEGER     NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_receivable_order UNIQUE (sales_order_id),
    -- ★ 硬验收 1：应收余额 == 出货 − 退货 − 回款（数据库层恒等式）
    CONSTRAINT ck_receivable_balance CHECK
        (balance = shipped_amount - returned_amount - received_amount),
    -- 退货不能超过已出货金额（退货数量上限在 RMA 已守，这里守金额）
    CONSTRAINT ck_receivable_return_le_shipped CHECK (returned_amount <= shipped_amount)
);
COMMENT ON TABLE customer_receivables IS
    '客户应收（FR-SAL-07）：一单一应收；balance 恒等于 shipped - returned - received';
COMMENT ON COLUMN customer_receivables.due_date IS
    '应收到期日 = 出货时 ClockProvider.today() + customers.credit_days（禁止系统时间）';

CREATE INDEX idx_receivable_customer ON customer_receivables (customer_id, status);
CREATE INDEX idx_receivable_due ON customer_receivables (due_date) WHERE status <> 'VOIDED';

CREATE TABLE customer_receipts (
    id          BIGSERIAL PRIMARY KEY,
    receipt_no  VARCHAR(64) NOT NULL,
    customer_id BIGINT      NOT NULL REFERENCES customers (id),
    amount      BIGINT      NOT NULL CHECK (amount > 0),
    biz_date    DATE        NOT NULL,
    remark      VARCHAR(500),
    request_id  VARCHAR(64) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_receipt_no UNIQUE (receipt_no),
    CONSTRAINT uq_receipt_request UNIQUE (request_id)
);
COMMENT ON TABLE customer_receipts IS
    '收款单（FR-SAL-08）。部分收款 = amount 小于应收余额；核销明细合计必须精确等于 amount';
CREATE INDEX idx_receipt_customer ON customer_receipts (customer_id, biz_date DESC);

CREATE TABLE receivable_allocations (
    id            BIGSERIAL PRIMARY KEY,
    receipt_id    BIGINT      NOT NULL REFERENCES customer_receipts (id) ON DELETE CASCADE,
    receivable_id BIGINT      NOT NULL REFERENCES customer_receivables (id),
    amount        BIGINT      NOT NULL CHECK (amount > 0),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_allocation_receipt_receivable UNIQUE (receipt_id, receivable_id)
);
COMMENT ON TABLE receivable_allocations IS
    '收款核销明细（FR-SAL-08）。Σ amount 必须等于收款单 amount；单笔不得超过应收余额';
CREATE INDEX idx_allocation_receivable ON receivable_allocations (receivable_id);

-- ─────────────────────────── ⑥ 信用快照 ───────────────────────────

CREATE TABLE customer_credit_snapshots (
    id               BIGSERIAL PRIMARY KEY,
    customer_id      BIGINT      NOT NULL REFERENCES customers (id),
    snapshot_date    DATE        NOT NULL,
    credit_limit     BIGINT      NOT NULL CHECK (credit_limit >= 0),
    credit_days      INTEGER     NOT NULL CHECK (credit_days >= 0),
    exposure_amount  BIGINT      NOT NULL,
    order_amount     BIGINT      NOT NULL CHECK (order_amount >= 0),
    sales_order_id   BIGINT REFERENCES sales_orders (id),
    request_id       VARCHAR(64),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE customer_credit_snapshots IS
    '信用快照（FR-SAL-09）：每次授信通过的建单记一条，snapshot_date 来自 ClockProvider';
CREATE INDEX idx_credit_snapshot_customer ON customer_credit_snapshots (customer_id, snapshot_date DESC);

-- ─────────────────────────── ⑦ 存量数据回填 ───────────────────────────
--
-- 已出货的历史订单在 V11 之前没有应收行。若不回填，"应收余额 == 出货 − 退货 − 回款"
-- 对存量数据不成立，退货入库也会因找不到应收而失败。这里按已出货/已退货数量
-- 用与 Java lineAmount 相同的口径（round(qty × price × rate / 10000)）回填。
WITH line_amounts AS (
    SELECT l.order_id,
           SUM(ROUND(l.shipped_qty::numeric * l.unit_price_amount * l.discount_rate / 10000.0))::bigint
               AS shipped_amount,
           SUM(ROUND(l.returned_qty::numeric * l.unit_price_amount * l.discount_rate / 10000.0))::bigint
               AS returned_amount
      FROM sales_order_lines l
     GROUP BY l.order_id
)
INSERT INTO customer_receivables
    (sales_order_id, customer_id, biz_date, due_date,
     shipped_amount, returned_amount, received_amount, balance, status)
SELECT o.id, o.customer_id, o.biz_date, NULL,
       COALESCE(a.shipped_amount, 0), COALESCE(a.returned_amount, 0), 0,
       COALESCE(a.shipped_amount, 0) - COALESCE(a.returned_amount, 0),
       CASE WHEN COALESCE(a.shipped_amount, 0) - COALESCE(a.returned_amount, 0) > 0
            THEN 'OPEN' ELSE 'SETTLED' END
  FROM sales_orders o
  JOIN line_amounts a ON a.order_id = o.id
 WHERE o.status IN ('PARTIAL', 'COMPLETED')
ON CONFLICT (sales_order_id) DO NOTHING;
COMMENT ON TABLE customer_receivables IS
    '客户应收（FR-SAL-07）：一单一应收；balance 恒等于 shipped - returned - received（V11 已回填存量已出货订单）';

-- 历史预占段补齐仓库/批次，让存量订单也能生成发货计划并按仓拆单。
UPDATE sales_order_line_reservations r
   SET warehouse_id = ir.warehouse_id,
       batch_no = ir.batch_no
  FROM inventory_reservations ir
 WHERE ir.id = r.reservation_id
   AND r.warehouse_id IS NULL;

-- ─────────────────────────── ⑧ 权限种子 ───────────────────────────

INSERT INTO app_role_permissions (role_id, permission)
SELECT r.id, p.permission FROM app_roles r
JOIN (VALUES
    ('ADMIN', 'sal:quote'), ('ADMIN', 'sal:pick'), ('ADMIN', 'sal:receipt'), ('ADMIN', 'sal:bundle'),
    ('SALESPERSON', 'sal:quote'), ('SALESPERSON', 'sal:pick'),
    ('SALESPERSON', 'sal:receipt'), ('SALESPERSON', 'sal:bundle'),
    ('FINANCE', 'sal:read'), ('FINANCE', 'sal:receipt'),
    ('VIEWER', 'sal:read')
) AS p(role_code, permission) ON r.code = p.role_code
ON CONFLICT DO NOTHING;
