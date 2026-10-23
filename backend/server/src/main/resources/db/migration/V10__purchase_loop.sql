-- ============================================================================
-- V10 · V2-2 采购闭环（FR-PUR-04 ~ FR-PUR-08）
-- 范围：
--   ① 请购单：purchase_orders 增加 doc_type 与转化关联列（复用现有单据表，
--      不新增第 6 张表；请购明细仍存 purchase_order_lines）
--   ② 到货质检：purchase_order_lines 增加 qc_status / qc_qty / qc_note；
--      po_receipts / po_receipt_lines 记录每次到货与逐行质检结果
--   ③ 供应商应付：supplier_payables（一收货单一张，余额为生成列）
--   ④ 付款核销：payable_allocations（逐核销明细，payment_no 分组守恒）
--   ⑤ 采购退货：purchase_returns（退货单 + JSONB 明细）
--   ⑥ inventory_ledger 业务类型追加 PURCHASE_RETURN
--   ⑦ pur:payable / pur:pay 权限种子
-- 依据：.plan/V2-2-计划书.md §2 / §3
--
-- 设计原则（延续 V1~V8）：**追加式迁移**。V1~V8 一行不改；
--   不变量（余额非负、质检数量守恒、应付余额公式）尽量写在数据库层。
-- ============================================================================

-- ─────────────────────────── ① 请购单（复用 purchase_orders） ───────────────────────────

ALTER TABLE purchase_orders
    ADD COLUMN doc_type              VARCHAR(16) NOT NULL DEFAULT 'PURCHASE',
    ADD COLUMN source_requisition_id BIGINT REFERENCES purchase_orders (id),
    ADD COLUMN converted_order_id    BIGINT REFERENCES purchase_orders (id),
    ADD COLUMN conversion_request_id VARCHAR(64),
    ADD COLUMN converted_at          TIMESTAMPTZ;

ALTER TABLE purchase_orders
    ADD CONSTRAINT ck_po_doc_type CHECK (doc_type IN ('PURCHASE', 'REQUISITION'));

COMMENT ON COLUMN purchase_orders.doc_type IS
    'PURCHASE=进货单；REQUISITION=请购单。现有 /purchase-orders 接口一律只读写 PURCHASE';
COMMENT ON COLUMN purchase_orders.source_requisition_id IS
    '转化生成的订单指向来源请购单（一张请购单最多转出一张订单）';
COMMENT ON COLUMN purchase_orders.converted_order_id IS
    '请购单转化后指向生成的进货单；非空即表示已转化、不可再改';
COMMENT ON COLUMN purchase_orders.conversion_request_id IS
    '请购转订单的幂等键（X-Request-Id）。同键重放返回首次生成的订单';

-- 一张请购单最多一张订单：数据库层兜底，避免并发转化各自建单
CREATE UNIQUE INDEX uq_po_source_requisition
    ON purchase_orders (source_requisition_id)
    WHERE source_requisition_id IS NOT NULL;
CREATE UNIQUE INDEX uq_po_converted_order
    ON purchase_orders (converted_order_id)
    WHERE converted_order_id IS NOT NULL;
CREATE UNIQUE INDEX uq_po_conversion_request
    ON purchase_orders (conversion_request_id)
    WHERE conversion_request_id IS NOT NULL;

CREATE INDEX idx_po_doc_type ON purchase_orders (doc_type, status, biz_date DESC);

-- ─────────────────────────── ② 到货质检 ───────────────────────────

ALTER TABLE purchase_order_lines
    ADD COLUMN qc_status VARCHAR(16) NOT NULL DEFAULT 'NONE',
    ADD COLUMN qc_qty    INTEGER     NOT NULL DEFAULT 0,
    ADD COLUMN qc_note   VARCHAR(500);

-- 存量已入库行视为全部合格：否则上线当天历史进货会从"可用"变成"未质检"
UPDATE purchase_order_lines
   SET qc_qty = received_qty,
       qc_status = CASE WHEN received_qty > 0 THEN 'PASSED' ELSE 'NONE' END;

ALTER TABLE purchase_order_lines
    ADD CONSTRAINT ck_po_line_qc_status CHECK (qc_status IN
        ('NONE', 'PASSED', 'PARTIAL', 'FAILED')),
    ADD CONSTRAINT ck_po_line_qc_qty CHECK (qc_qty >= 0 AND qc_qty <= received_qty);

COMMENT ON COLUMN purchase_order_lines.qc_status IS
    '累计质检结果：NONE=未到货；PASSED=全部合格；PARTIAL=部分不合格；FAILED=全部不合格';
COMMENT ON COLUMN purchase_order_lines.qc_qty IS
    '累计合格数量（<= received_qty）。只有合格量会入库存、生成应付';
COMMENT ON COLUMN purchase_order_lines.qc_note IS '最近一次非空质检备注';

CREATE TABLE po_receipts (
    id                BIGSERIAL PRIMARY KEY,
    receipt_no        VARCHAR(64) NOT NULL,
    purchase_order_id BIGINT      NOT NULL REFERENCES purchase_orders (id),
    supplier_id       BIGINT      NOT NULL REFERENCES suppliers (id),
    warehouse_id      BIGINT      NOT NULL REFERENCES warehouses (id),
    biz_date          DATE        NOT NULL,
    period            VARCHAR(7),
    total_qty         INTEGER     NOT NULL CHECK (total_qty > 0),
    accepted_qty      INTEGER     NOT NULL CHECK (accepted_qty >= 0),
    rejected_qty      INTEGER     NOT NULL CHECK (rejected_qty >= 0),
    accepted_amount   BIGINT      NOT NULL CHECK (accepted_amount >= 0),
    request_id        VARCHAR(64),
    received_at       TIMESTAMPTZ NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_po_receipt_no UNIQUE (receipt_no),
    CONSTRAINT uq_po_receipt_request UNIQUE (request_id),
    -- 到货量 = 合格量 + 不合格量：不允许有"去向不明"的数量
    CONSTRAINT ck_po_receipt_qty CHECK (accepted_qty + rejected_qty = total_qty)
);
COMMENT ON TABLE po_receipts IS
    '到货单：一次入库确认一张。不合格数量只记在这里，不进入库存与应付';

CREATE INDEX idx_po_receipt_order ON po_receipts (purchase_order_id, id);
CREATE INDEX idx_po_receipt_supplier ON po_receipts (supplier_id, biz_date DESC);

CREATE TABLE po_receipt_lines (
    id                     BIGSERIAL PRIMARY KEY,
    receipt_id             BIGINT      NOT NULL REFERENCES po_receipts (id) ON DELETE CASCADE,
    line_no                INTEGER     NOT NULL CHECK (line_no > 0),
    purchase_order_line_id BIGINT      NOT NULL REFERENCES purchase_order_lines (id),
    sku_id                 BIGINT      NOT NULL REFERENCES skus (id),
    batch_no               VARCHAR(64) NOT NULL DEFAULT '',
    production_date        DATE,
    expiry_date            DATE,
    received_qty           INTEGER     NOT NULL CHECK (received_qty > 0),
    qc_qty                 INTEGER     NOT NULL CHECK (qc_qty >= 0),
    rejected_qty           INTEGER     NOT NULL CHECK (rejected_qty >= 0),
    unit_price_amount      BIGINT      NOT NULL CHECK (unit_price_amount >= 0),
    accepted_amount        BIGINT      NOT NULL CHECK (accepted_amount >= 0),
    qc_status              VARCHAR(16) NOT NULL
                               CHECK (qc_status IN ('PASSED', 'PARTIAL', 'FAILED')),
    qc_note                VARCHAR(500),
    CONSTRAINT uq_po_receipt_line_no UNIQUE (receipt_id, line_no),
    CONSTRAINT ck_po_receipt_line_qty CHECK (qc_qty + rejected_qty = received_qty),
    -- 应付金额不接受前端传值：必须等于合格量 × 单价
    CONSTRAINT ck_po_receipt_line_amount CHECK (accepted_amount = qc_qty * unit_price_amount)
);
COMMENT ON TABLE po_receipt_lines IS
    '到货行（含质检与批次）。qc_qty=合格入库量，rejected_qty 不产生库存流水';

CREATE INDEX idx_po_receipt_line_order_line
    ON po_receipt_lines (purchase_order_line_id);
CREATE INDEX idx_po_receipt_line_sku ON po_receipt_lines (sku_id, batch_no);

-- 历史数据回填：V10 之前的已入库进货生成一张 -LEGACY 收货单，保证应付恒等式
-- 从升级后的第一刻起就成立。回填不写库存流水、不改余额。
INSERT INTO po_receipts (receipt_no, purchase_order_id, supplier_id, warehouse_id,
                         biz_date, period, total_qty, accepted_qty, rejected_qty,
                         accepted_amount, request_id, received_at)
SELECT po.order_no || '-LEGACY', po.id, po.supplier_id, po.warehouse_id,
       po.biz_date, po.period,
       SUM(l.received_qty), SUM(l.received_qty), 0,
       SUM(l.received_qty::BIGINT * l.unit_price_amount),
       NULL, now()
  FROM purchase_orders po
  JOIN purchase_order_lines l ON l.order_id = po.id
 WHERE po.doc_type = 'PURCHASE' AND l.received_qty > 0
 GROUP BY po.id, po.order_no, po.supplier_id, po.warehouse_id, po.biz_date, po.period;

INSERT INTO po_receipt_lines (receipt_id, line_no, purchase_order_line_id, sku_id,
                              batch_no, received_qty, qc_qty, rejected_qty,
                              unit_price_amount, accepted_amount, qc_status, qc_note)
SELECT r.id,
       ROW_NUMBER() OVER (PARTITION BY r.purchase_order_id ORDER BY l.line_no),
       l.id, l.sku_id, '', l.received_qty, l.received_qty, 0,
       l.unit_price_amount, l.received_qty::BIGINT * l.unit_price_amount,
       'PASSED', 'V10 历史回填'
  FROM po_receipts r
  JOIN purchase_order_lines l ON l.order_id = r.purchase_order_id
 WHERE r.receipt_no LIKE '%-LEGACY' AND l.received_qty > 0;

-- ─────────────────────────── ③ 供应商应付 ───────────────────────────

CREATE TABLE supplier_payables (
    id               BIGSERIAL PRIMARY KEY,
    payable_no       VARCHAR(64) NOT NULL,
    supplier_id      BIGINT      NOT NULL REFERENCES suppliers (id),
    purchase_order_id BIGINT     NOT NULL REFERENCES purchase_orders (id),
    receipt_id       BIGINT      NOT NULL REFERENCES po_receipts (id),
    biz_date         DATE        NOT NULL,
    period           VARCHAR(7),
    original_amount  BIGINT      NOT NULL CHECK (original_amount > 0),
    returned_amount  BIGINT      NOT NULL DEFAULT 0 CHECK (returned_amount >= 0),
    paid_amount      BIGINT      NOT NULL DEFAULT 0 CHECK (paid_amount >= 0),
    -- ★ 应付余额由数据库生成：应用层无法写错，恒等式在行级永远成立
    balance_amount   BIGINT      GENERATED ALWAYS AS
                         (original_amount - returned_amount - paid_amount) STORED,
    version          INTEGER     NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_supplier_payable_no UNIQUE (payable_no),
    -- 一收货单最多一张应付单（重复收货幂等由收货单 request_id 先挡住）
    CONSTRAINT uq_supplier_payable_receipt UNIQUE (receipt_id),
    CONSTRAINT ck_supplier_payable_amount CHECK
        (returned_amount + paid_amount <= original_amount)
);
COMMENT ON TABLE supplier_payables IS
    '供应商应付单（按收货单）。balance_amount = original - returned - paid（生成列）';
COMMENT ON COLUMN supplier_payables.balance_amount IS
    '应付余额（分）。不变量：供应商维度 Σbalance = Σ进货 − Σ退货 − Σ付款';

CREATE INDEX idx_supplier_payable_supplier
    ON supplier_payables (supplier_id, biz_date DESC, id DESC);
CREATE INDEX idx_supplier_payable_order ON supplier_payables (purchase_order_id);

INSERT INTO supplier_payables (payable_no, supplier_id, purchase_order_id, receipt_id,
                               biz_date, period, original_amount)
SELECT 'AP-LEGACY-' || r.id, r.supplier_id, r.purchase_order_id, r.id,
       r.biz_date, r.period, r.accepted_amount
  FROM po_receipts r
 WHERE r.accepted_amount > 0;

-- ─────────────────────────── ④ 付款核销 ───────────────────────────

CREATE TABLE payable_allocations (
    id             BIGSERIAL PRIMARY KEY,
    payment_no     VARCHAR(64) NOT NULL,
    supplier_id    BIGINT      NOT NULL REFERENCES suppliers (id),
    payable_id     BIGINT      NOT NULL REFERENCES supplier_payables (id),
    amount         BIGINT      NOT NULL CHECK (amount > 0),
    payment_amount BIGINT      NOT NULL CHECK (payment_amount > 0),
    biz_date       DATE        NOT NULL,
    period         VARCHAR(7),
    remark         VARCHAR(500),
    request_id     VARCHAR(64),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_payable_alloc_payment UNIQUE (payment_no, payable_id),
    CONSTRAINT ck_payable_alloc_amount CHECK (amount <= payment_amount)
);
COMMENT ON TABLE payable_allocations IS
    '付款核销明细（一行 = 一张付款单对一张应付单的核销）。'
    '守恒：同一 payment_no 的 Σamount = payment_amount；由应用校验 + 核对查询守住';

CREATE UNIQUE INDEX uq_payable_alloc_request
    ON payable_allocations (request_id, payable_id)
    WHERE request_id IS NOT NULL;
CREATE INDEX idx_payable_alloc_payable ON payable_allocations (payable_id, id);
CREATE INDEX idx_payable_alloc_payment ON payable_allocations (payment_no);

-- ─────────────────────────── ⑤ 采购退货 ───────────────────────────

CREATE TABLE purchase_returns (
    id                  BIGSERIAL PRIMARY KEY,
    return_no           VARCHAR(64) NOT NULL,
    purchase_order_id   BIGINT      NOT NULL REFERENCES purchase_orders (id),
    supplier_id         BIGINT      NOT NULL REFERENCES suppliers (id),
    warehouse_id        BIGINT      NOT NULL REFERENCES warehouses (id),
    biz_date            DATE        NOT NULL,
    period              VARCHAR(7),
    status              VARCHAR(16) NOT NULL DEFAULT 'DRAFT'
                            CHECK (status IN ('DRAFT', 'COMPLETED', 'VOIDED')),
    total_qty           INTEGER     NOT NULL CHECK (total_qty > 0),
    total_amount        BIGINT      NOT NULL CHECK (total_amount > 0),
    remark              VARCHAR(500),
    -- 明细引用收货行（原批次）；JSONB 避免第 6 张表，单行结构由应用层校验
    lines               JSONB       NOT NULL,
    request_id          VARCHAR(64),
    approve_request_id  VARCHAR(64),
    void_request_id     VARCHAR(64),
    version             INTEGER     NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_purchase_return_no UNIQUE (return_no),
    CONSTRAINT uq_purchase_return_request UNIQUE (request_id),
    CONSTRAINT uq_purchase_return_approve_request UNIQUE (approve_request_id),
    CONSTRAINT uq_purchase_return_void_request UNIQUE (void_request_id),
    CONSTRAINT ck_purchase_return_lines CHECK (jsonb_array_length(lines) > 0)
);
COMMENT ON TABLE purchase_returns IS
    '采购退货单：DRAFT 不动库存/应付；COMPLETED 时扣库存并冲减收货单应付';
COMMENT ON COLUMN purchase_returns.lines IS
    '[{receiptLineId,purchaseOrderLineId,skuId,qty,unitPriceAmount,amount,batchNo}]';

CREATE INDEX idx_purchase_return_order ON purchase_returns (purchase_order_id, id DESC);
CREATE INDEX idx_purchase_return_status ON purchase_returns (status, biz_date DESC);

-- ─────────────────────────── ⑥ 库存流水类型：采购退货 ───────────────────────────

ALTER TABLE inventory_ledger DROP CONSTRAINT ck_ledger_biz_type;

ALTER TABLE inventory_ledger
    ADD CONSTRAINT ck_ledger_biz_type CHECK (biz_type IN (
        'PURCHASE_IN','SALES_OUT','RETURN_IN',
        'TRANSFER_IN','TRANSFER_OUT','ADJUST_IN','ADJUST_OUT',
        'SALES_REVERSAL','PURCHASE_REVERSAL',
        -- V2-2：采购退货，负向流水（冲减实存）
        'PURCHASE_RETURN'
    ));

COMMENT ON CONSTRAINT ck_ledger_biz_type ON inventory_ledger IS
    '流水业务类型：正向出入库 + 退货 + 调拨/调整 + 红冲 + V2-2 采购退货（PURCHASE_RETURN 为负）';

-- ─────────────────────────── ⑦ 权限种子 ───────────────────────────

INSERT INTO app_role_permissions (role_id, permission)
SELECT r.id, p.permission
  FROM app_roles r
  JOIN (VALUES
      ('FINANCE',    'pur:payable'),
      ('FINANCE',    'pur:pay'),
      ('PURCHASER',  'pur:payable'),
      ('VIEWER',     'pur:payable')
  ) AS p(role_code, permission) ON r.code = p.role_code
ON CONFLICT DO NOTHING;
