-- ============================================================================
-- V1 · M0 初始 schema
-- 范围：库存纵向切片（仓库 / 商品 / SKU / 库存余额 / 库存流水 / 预占 / 幂等键）
-- 依据：docs/04-数据模型.md、ADR-0005（金额用分）、ADR-0007（流水为事实源）
--
-- 设计原则：**不变量写在数据库层**，而不只是写在代码里。
--   这样即使应用层有 bug，并发与脏数据也会被数据库挡住，
--   集成测试也就有了干脆的断言对象。
-- ============================================================================

-- ─────────────────────────── 主数据 ───────────────────────────

CREATE TABLE warehouses (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(32)  NOT NULL,
    name        VARCHAR(64)  NOT NULL,
    address     VARCHAR(200),
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_warehouse_code UNIQUE (code)
);
COMMENT ON TABLE warehouses IS '仓库。数据权限按此隔离（FR-IAM-03）';

CREATE TABLE products (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(32)  NOT NULL,
    name        VARCHAR(128) NOT NULL,
    category    VARCHAR(64),
    season      VARCHAR(32),
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_product_code UNIQUE (code)
);
COMMENT ON TABLE products IS '商品（款式级）。不参与库存，库存挂在 SKU 上';

CREATE TABLE skus (
    id                  BIGSERIAL PRIMARY KEY,
    product_id          BIGINT       NOT NULL REFERENCES products (id),
    color               VARCHAR(32)  NOT NULL,
    size                VARCHAR(16)  NOT NULL,
    barcode             VARCHAR(64),
    -- ★ 金额一律整数「分」（ADR-0005），禁止浮点
    retail_price_amount BIGINT       NOT NULL DEFAULT 0 CHECK (retail_price_amount >= 0),
    enabled             BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- ★ 同一商品下 (颜色, 尺码) 唯一 → 对应错误码 MDM_SKU_DUPLICATED
    CONSTRAINT uq_sku_variant UNIQUE (product_id, color, size)
);
COMMENT ON TABLE skus IS '最小库存单元 = 商品 + 颜色 + 尺码（+季节/批次维度在库存层）';

-- ─────────────────────────── 库存余额 ───────────────────────────

CREATE TABLE inventory_balances (
    id              BIGSERIAL PRIMARY KEY,
    sku_id          BIGINT       NOT NULL REFERENCES skus (id),
    warehouse_id    BIGINT       NOT NULL REFERENCES warehouses (id),
    batch_no        VARCHAR(64)  NOT NULL DEFAULT '',
    production_date DATE,
    expiry_date     DATE,
    -- ★ 不变量 INV-1：实存与预占都不允许为负 —— 超卖的最后一道防线
    on_hand_qty     INTEGER      NOT NULL DEFAULT 0 CHECK (on_hand_qty >= 0),
    reserved_qty    INTEGER      NOT NULL DEFAULT 0 CHECK (reserved_qty >= 0),
    -- ★ 不变量 INV-3：可用量由数据库保证，应用层不可能算错
    available_qty   INTEGER      GENERATED ALWAYS AS (on_hand_qty - reserved_qty) STORED,
    version         INTEGER      NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_balance UNIQUE (sku_id, warehouse_id, batch_no),
    -- ★ 预占不得超过实存（否则可用量为负）
    CONSTRAINT ck_reserved_le_onhand CHECK (reserved_qty <= on_hand_qty)
);
COMMENT ON TABLE inventory_balances IS '库存余额快照（派生结果，ADR-0007）。无批次时 batch_no = '''' ';
COMMENT ON COLUMN inventory_balances.available_qty IS '可用量 = on_hand - reserved，生成列（不变量 INV-3）';
COMMENT ON COLUMN inventory_balances.expiry_date IS 'FEFO 扣减依据（BR-INV-06）';

CREATE INDEX idx_balance_sku_wh ON inventory_balances (sku_id, warehouse_id);
CREATE INDEX idx_balance_fefo   ON inventory_balances (sku_id, warehouse_id, expiry_date, batch_no);

-- ─────────────────────────── 库存流水（★ 不可变事实源） ───────────────────────────

CREATE TABLE inventory_ledger (
    id              BIGSERIAL PRIMARY KEY,
    sku_id          BIGINT       NOT NULL,
    warehouse_id    BIGINT       NOT NULL,
    batch_no        VARCHAR(64)  NOT NULL DEFAULT '',
    -- 正=入库，负=出库；★ 不允许为 0（零变动流水没有意义，属程序设计错误）
    change_qty      INTEGER      NOT NULL CHECK (change_qty <> 0),
    -- 注意：仅记录**实存（on_hand）**的变动。
    -- 预占/释放不改变实存，因此不产生流水 —— 这是不变量 INV-2 成立的前提。
    biz_type        VARCHAR(32)  NOT NULL CHECK (biz_type IN (
                        'PURCHASE_IN','SALES_OUT','RETURN_IN',
                        'TRANSFER_IN','TRANSFER_OUT','ADJUST_IN','ADJUST_OUT')),
    biz_ref_no      VARCHAR(64)  NOT NULL,
    biz_ref_id      BIGINT,
    unit_cost_amount BIGINT,
    request_id      VARCHAR(64),
    -- ★ 业务时间（由 ClockProvider 注入），不是 now()。
    --   这让跨月/跨年/闰月结算与效期归属成为可测命题。
    occurred_at     TIMESTAMPTZ  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
    -- 注意：本表刻意没有 updated_at / deleted 字段 —— 结构上宣告"不可变"（不变量 INV-3 的基础）
);
COMMENT ON TABLE inventory_ledger IS '库存流水：不可变事实源（ADR-0007）。余额与流水不一致时以流水为准重算';
COMMENT ON COLUMN inventory_ledger.biz_ref_no IS '来源单据号，可反查（FR-INV-02）';
COMMENT ON COLUMN inventory_ledger.occurred_at IS '业务时间，由 ClockProvider 注入，绝非 now()';

CREATE INDEX idx_ledger_sku_wh    ON inventory_ledger (sku_id, warehouse_id, occurred_at DESC);
CREATE INDEX idx_ledger_bizref    ON inventory_ledger (biz_ref_no);
CREATE INDEX idx_ledger_occurred  ON inventory_ledger (occurred_at);
-- ★ 幂等护栏：同一 request_id + 业务类型 + 批次 只允许一条流水。
--   这是"重复提交不产生重复库存变动"（不变量 INV-9）的数据库级保证。
--
--   为什么必须带 batch_no：一笔出货按 FEFO 拆多批次扣减时，
--   同一个 request_id 会合法地产生多条流水（每批次一条）。
--   若只按 (request_id, biz_type) 唯一，正常的多批次出货会被误判为重复提交而失败
--   —— 该缺陷由 TC-INV-009（FEFO 拆批次出库）在 M0 阶段真实捕获。
--   带上 batch_no 后：合法拆批次各自成立，而重复提交同一批次仍被拦截。
CREATE UNIQUE INDEX uq_ledger_request ON inventory_ledger (request_id, biz_type, batch_no)
    WHERE request_id IS NOT NULL;

-- ─────────────────────────── 库存预占 ───────────────────────────

CREATE TABLE inventory_reservations (
    id              BIGSERIAL PRIMARY KEY,
    sku_id          BIGINT       NOT NULL,
    warehouse_id    BIGINT       NOT NULL,
    batch_no        VARCHAR(64)  NOT NULL DEFAULT '',
    reserved_qty    INTEGER      NOT NULL CHECK (reserved_qty > 0),
    status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE'
                        CHECK (status IN ('ACTIVE','RELEASED','CONSUMED','EXPIRED')),
    biz_ref_no      VARCHAR(64)  NOT NULL,
    request_id      VARCHAR(64)  NOT NULL,
    expires_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- ★ 同一 request_id 只能有一条预占记录 → 预占的幂等由数据库保证（不变量 INV-9）
    CONSTRAINT uq_reservation_request UNIQUE (request_id)
);
COMMENT ON TABLE inventory_reservations IS '库存预占记录。超时自动释放（BR-INV-05）';

CREATE INDEX idx_reservation_active ON inventory_reservations (sku_id, warehouse_id, status)
    WHERE status = 'ACTIVE';

-- ─────────────────────────── 幂等键 ───────────────────────────

CREATE TABLE idempotency_keys (
    request_id    VARCHAR(64) PRIMARY KEY,
    endpoint      VARCHAR(128) NOT NULL,
    response_body TEXT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
COMMENT ON TABLE idempotency_keys IS '幂等键记录：相同 X-Request-Id 重复提交只生效一次（不变量 INV-9）';

-- ─────────────────────────── 种子数据（本地开发用） ───────────────────────────
-- 注：测试环境的数据由 test-kit 数据工厂构造，不依赖这里的种子

INSERT INTO warehouses (code, name, address) VALUES
    ('SH01', '上海仓', '上海市青浦区'),
    ('GZ01', '广州仓', '广州市白云区');

INSERT INTO products (code, name, category, season) VALUES
    ('P-TS-001', '圆领T恤', '上装', '2025夏');

INSERT INTO skus (product_id, color, size, barcode, retail_price_amount)
SELECT p.id, v.color, v.size, v.barcode, v.price
FROM products p,
     (VALUES ('白色', 'L', '6901000001', 12900),
             ('白色', 'M', '6901000002', 12900),
             ('黑色', 'L', '6901000003', 12900)) AS v(color, size, barcode, price)
WHERE p.code = 'P-TS-001';
