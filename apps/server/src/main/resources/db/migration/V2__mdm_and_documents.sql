-- ============================================================================
-- V2 · M1 主数据 + 单据骨架
-- 范围：
--   ① 主数据补强：suppliers（供应商）、customers（客户/会员）+ 折扣等级
--   ② 单据号序列表：document_sequences（原子取号，见 DocumentNumberGenerator）
--   ③ 单据表：purchase_orders / purchase_order_lines / sales_orders / sales_order_lines
--   ④ 幂等键补强：idempotency_keys 增加 result_ref（首次结果定位）
-- 依据：docs/04-数据模型.md、docs/02-领域模型 §3（七态状态机）、ADR-0005（金额用分）
--
-- 设计原则（延续 V1）：**不变量写在数据库层**。
--   状态取值、数量正负、金额非负、累计入库不超量，全部由 CHECK / 约束兜底，
--   应用层 bug 只会变成一条干脆的约束违例，而不是脏数据。
-- ============================================================================

-- ─────────────────────────── 主数据：供应商 ───────────────────────────

CREATE TABLE suppliers (
    id            BIGSERIAL PRIMARY KEY,
    code          VARCHAR(32)  NOT NULL,
    name          VARCHAR(128) NOT NULL,
    contact_name  VARCHAR(64),
    contact_phone VARCHAR(32),
    address       VARCHAR(200),
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_supplier_code UNIQUE (code)
);
COMMENT ON TABLE suppliers IS '供应商。编码唯一，停用不删（enabled），保证历史单据仍可追溯';

-- ─────────────────────────── 主数据：客户/会员 ───────────────────────────

CREATE TABLE customers (
    id            BIGSERIAL PRIMARY KEY,
    code          VARCHAR(32)  NOT NULL,
    name          VARCHAR(128) NOT NULL,
    phone         VARCHAR(32),
    -- ★ 折扣等级：整数万分比（10000 = 不打折，9000 = 9 折），禁止浮点（ADR-0005）
    --   M1 只建字段与校验，真正参与售价计算在 M2 促销（见计划书 §7 决策 3）
    discount_rate INTEGER      NOT NULL DEFAULT 10000
                      CHECK (discount_rate > 0 AND discount_rate <= 10000),
    level         VARCHAR(16)  NOT NULL DEFAULT 'NORMAL',
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_customer_code UNIQUE (code)
);
COMMENT ON TABLE customers IS '客户/会员。discount_rate 为整数万分比，10000=不打折';
COMMENT ON COLUMN customers.discount_rate IS '折扣率（万分比）。9000 表示 9 折；与 SAL 明细 discountRate 同口径';

CREATE INDEX idx_customer_phone ON customers (phone) WHERE phone IS NOT NULL;

-- ─────────────────────────── 单据号序列（原子取号） ───────────────────────────

CREATE TABLE document_sequences (
    doc_prefix  VARCHAR(8)  NOT NULL,
    day_key     VARCHAR(8)  NOT NULL,          -- yyyyMMdd
    current_no  BIGINT      NOT NULL CHECK (current_no > 0),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (doc_prefix, day_key)
);
COMMENT ON TABLE document_sequences IS
    '单据号序列：按 (前缀, 日期) 计数。用 INSERT..ON CONFLICT..RETURNING 原子取号，'
    '杜绝 SELECT max(no)+1 在并发下重号（计划书 §3.2 坑点 1）';

-- ─────────────────────────── 进货单 ───────────────────────────

CREATE TABLE purchase_orders (
    id             BIGSERIAL PRIMARY KEY,
    order_no       VARCHAR(64) NOT NULL,
    status         VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    supplier_id    BIGINT      NOT NULL REFERENCES suppliers (id),
    warehouse_id   BIGINT      NOT NULL REFERENCES warehouses (id),
    biz_date       DATE        NOT NULL,        -- 业务日期，由 ClockProvider 注入
    total_amount   BIGINT      NOT NULL DEFAULT 0 CHECK (total_amount >= 0),
    total_qty      INTEGER     NOT NULL DEFAULT 0 CHECK (total_qty >= 0),
    period         VARCHAR(7),                  -- yyyy-MM，M2 月结锁账用
    remark         VARCHAR(500),
    request_id     VARCHAR(64),                 -- 建单幂等键
    created_by     BIGINT,
    version        INTEGER     NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_purchase_order_no UNIQUE (order_no),
    CONSTRAINT ck_purchase_order_status CHECK (status IN
        ('DRAFT','SUBMITTED','APPROVED','PARTIAL','COMPLETED','VOIDED','REJECTED'))
);
COMMENT ON TABLE purchase_orders IS '进货单主表。状态机见 DocumentStatus（七态）';
COMMENT ON COLUMN purchase_orders.period IS 'yyyy-MM，预留月结锁账（BR-SET-02）';
COMMENT ON COLUMN purchase_orders.version IS '乐观锁：并发审核/入库时防丢更新';

CREATE INDEX idx_po_status  ON purchase_orders (status);
CREATE INDEX idx_po_supplier ON purchase_orders (supplier_id, biz_date DESC);
-- 建单幂等：同一 request_id 只能建成一张单（不变量 INV-9）
CREATE UNIQUE INDEX uq_po_request ON purchase_orders (request_id) WHERE request_id IS NOT NULL;

CREATE TABLE purchase_order_lines (
    id                BIGSERIAL PRIMARY KEY,
    order_id          BIGINT      NOT NULL REFERENCES purchase_orders (id) ON DELETE CASCADE,
    line_no           INTEGER     NOT NULL CHECK (line_no > 0),
    sku_id            BIGINT      NOT NULL REFERENCES skus (id),
    qty               INTEGER     NOT NULL CHECK (qty > 0),
    -- ★ 累计入库量：分批入库的护栏（BR-DOC-04）
    received_qty      INTEGER     NOT NULL DEFAULT 0 CHECK (received_qty >= 0),
    unit_price_amount BIGINT      NOT NULL CHECK (unit_price_amount >= 0),
    -- amount = qty * unit_price_amount，由应用层写入，DB 只保证非负
    amount            BIGINT      NOT NULL CHECK (amount >= 0),
    CONSTRAINT uq_po_line_no UNIQUE (order_id, line_no),
    -- ★ BR-DOC-04：累计入库不得超过单据数量 —— 数据库层兜底超量入库
    CONSTRAINT ck_po_line_over_receive CHECK (received_qty <= qty)
);
COMMENT ON TABLE purchase_order_lines IS '进货单明细。received_qty 为累计已入库量，不得超 qty（PUR_OVER_RECEIVE）';

CREATE INDEX idx_po_line_order ON purchase_order_lines (order_id);

-- ─────────────────────────── 出货单 ───────────────────────────

CREATE TABLE sales_orders (
    id             BIGSERIAL PRIMARY KEY,
    order_no       VARCHAR(64) NOT NULL,
    status         VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    customer_id    BIGINT      NOT NULL REFERENCES customers (id),
    warehouse_id   BIGINT      NOT NULL REFERENCES warehouses (id),
    biz_date       DATE        NOT NULL,
    total_amount   BIGINT      NOT NULL DEFAULT 0 CHECK (total_amount >= 0),
    total_qty      INTEGER     NOT NULL DEFAULT 0 CHECK (total_qty >= 0),
    period         VARCHAR(7),
    remark         VARCHAR(500),
    request_id     VARCHAR(64),
    created_by     BIGINT,
    version        INTEGER     NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_sales_order_no UNIQUE (order_no),
    CONSTRAINT ck_sales_order_status CHECK (status IN
        ('DRAFT','SUBMITTED','APPROVED','PARTIAL','COMPLETED','VOIDED','REJECTED'))
);
COMMENT ON TABLE sales_orders IS '出货单主表。建单即预占库存（FR-SAL-01）';

CREATE INDEX idx_so_status   ON sales_orders (status);
CREATE INDEX idx_so_customer ON sales_orders (customer_id, biz_date DESC);
CREATE UNIQUE INDEX uq_so_request ON sales_orders (request_id) WHERE request_id IS NOT NULL;

CREATE TABLE sales_order_lines (
    id                BIGSERIAL PRIMARY KEY,
    order_id          BIGINT      NOT NULL REFERENCES sales_orders (id) ON DELETE CASCADE,
    line_no           INTEGER     NOT NULL CHECK (line_no > 0),
    sku_id            BIGINT      NOT NULL REFERENCES skus (id),
    qty               INTEGER     NOT NULL CHECK (qty > 0),
    shipped_qty       INTEGER     NOT NULL DEFAULT 0 CHECK (shipped_qty >= 0),
    unit_price_amount BIGINT      NOT NULL CHECK (unit_price_amount >= 0),
    -- ★ 折扣率整数万分比（9000 = 9 折），禁止浮点（ADR-0005 / 契约 discountRate）
    discount_rate     INTEGER     NOT NULL DEFAULT 10000
                          CHECK (discount_rate > 0 AND discount_rate <= 10000),
    amount            BIGINT      NOT NULL CHECK (amount >= 0),
    -- 预占记录 id：建单时逐行预占，出货确认时据此定位（FEFO 扣减在 INV 内部完成）
    reservation_id    BIGINT,
    CONSTRAINT uq_so_line_no UNIQUE (order_id, line_no),
    CONSTRAINT ck_so_line_over_ship CHECK (shipped_qty <= qty)
);
COMMENT ON TABLE sales_order_lines IS '出货单明细。discount_rate 为整数万分比；reservation_id 指向建单时的预占';
COMMENT ON COLUMN sales_order_lines.amount IS '行金额 = qty × unitPrice × discountRate / 10000，四舍五入到分';

CREATE INDEX idx_so_line_order ON sales_order_lines (order_id);

-- ─────────────────────────── 幂等键补强 ───────────────────────────

ALTER TABLE idempotency_keys
    ADD COLUMN result_ref VARCHAR(128);
COMMENT ON COLUMN idempotency_keys.result_ref IS
    '首次结果的定位（如 PO id），重复提交时据此直接返回首次结果而不重复执行';

-- ─────────────────────────── 种子数据（本地开发用） ───────────────────────────
-- 测试环境的数据由 TestDataFactory 构造，不依赖这里的种子

INSERT INTO suppliers (code, name, contact_name, contact_phone) VALUES
    ('SUP-001', '杭州面料供应商', '张伟', '13800000001'),
    ('SUP-002', '广州辅料供应商', '李娜', '13800000002');

INSERT INTO customers (code, name, phone, discount_rate, level) VALUES
    ('CUS-001', '零售散客', NULL, 10000, 'NORMAL'),
    ('CUS-002', '杭州旗舰店', '13900000001', 9500, 'VIP');
