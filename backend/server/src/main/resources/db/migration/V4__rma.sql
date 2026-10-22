-- ============================================================================
-- V4 · M2-S1 退货（RMA）
-- 范围：
--   ① sales_order_lines.returned_qty —— 累计退货量（原子守卫的数据基础）
--   ② return_orders / return_order_lines —— 退货单主表与明细
--   ③ rma:* 权限种子
-- 依据：.plan/M2-计划书.md §3.2 / §3.3、docs/02-领域模型 §3（七态状态机）
--
-- 设计原则（延续 V1/V2）：**不变量写在数据库层**。
--   "累计退货不得超过已出货"是退货域最容易被并发击穿的一条，
--   因此它同时是 CHECK 约束和应用层的条件 UPDATE —— 两层任一失守都不会写脏。
-- ============================================================================

-- ─────────────────────────── 出货明细：累计退货量 ───────────────────────────

ALTER TABLE sales_order_lines
    ADD COLUMN returned_qty INTEGER NOT NULL DEFAULT 0 CHECK (returned_qty >= 0);
ALTER TABLE sales_order_lines
    ADD CONSTRAINT ck_so_line_return_le_shipped CHECK (returned_qty <= shipped_qty);

COMMENT ON COLUMN sales_order_lines.returned_qty IS
    '累计已退货数量。上限是 shipped_qty（不是 qty）：没出库的货无从退回';
COMMENT ON CONSTRAINT ck_so_line_return_le_shipped ON sales_order_lines IS
    'BR-RMA-01：累计退货不得超过已出货数量。应用层的条件 UPDATE 与本约束同一语义';

-- ─────────────────────────── 退货单主表 ───────────────────────────

CREATE TABLE return_orders (
    id                    BIGSERIAL PRIMARY KEY,
    order_no              VARCHAR(64)  NOT NULL,
    status                VARCHAR(32)  NOT NULL DEFAULT 'DRAFT',
    origin_sales_order_id BIGINT       NOT NULL REFERENCES sales_orders (id),
    customer_id           BIGINT       NOT NULL REFERENCES customers (id),
    warehouse_id          BIGINT       NOT NULL REFERENCES warehouses (id),
    biz_date              DATE         NOT NULL,
    total_amount          BIGINT       NOT NULL DEFAULT 0 CHECK (total_amount >= 0),
    total_qty             INTEGER      NOT NULL DEFAULT 0 CHECK (total_qty >= 0),
    period                VARCHAR(7),
    remark                VARCHAR(500),
    request_id            VARCHAR(64),
    -- 入库幂等键与建单幂等键**分开**：一张退货单有两个写动作（建单、入库），
    -- 共用一列会让"建单后立刻入库"被误判为重复提交。
    receive_request_id    VARCHAR(64),
    created_by            BIGINT,
    version               INTEGER      NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_return_order_no UNIQUE (order_no),
    -- 退货是整单一次性回补，没有"部分入库"，因此状态集合刻意不含 PARTIAL。
    -- 数据库层写死这一步，应用层就无法偷偷造出 PARTIAL 的退货单。
    CONSTRAINT ck_return_order_status CHECK (status IN
        ('DRAFT','SUBMITTED','APPROVED','COMPLETED','VOIDED','REJECTED'))
);
COMMENT ON TABLE return_orders IS
    '退货单主表。客户/仓库/单价/折扣全部由原出货单派生，不接受前端传入';
COMMENT ON COLUMN return_orders.origin_sales_order_id IS '原出货单；退货数量的上限来自它的 shipped_qty';
COMMENT ON COLUMN return_orders.version IS '乐观锁：并发入库/作废时防丢更新';

CREATE INDEX idx_ro_status ON return_orders (status);
CREATE INDEX idx_ro_origin ON return_orders (origin_sales_order_id, biz_date DESC);
CREATE INDEX idx_ro_customer ON return_orders (customer_id, biz_date DESC);
CREATE UNIQUE INDEX uq_ro_request ON return_orders (request_id) WHERE request_id IS NOT NULL;
CREATE UNIQUE INDEX uq_ro_receive_request ON return_orders (receive_request_id)
    WHERE receive_request_id IS NOT NULL;

-- ─────────────────────────── 退货单明细 ───────────────────────────

CREATE TABLE return_order_lines (
    id                BIGSERIAL PRIMARY KEY,
    order_id          BIGINT      NOT NULL REFERENCES return_orders (id) ON DELETE CASCADE,
    line_no           INTEGER     NOT NULL CHECK (line_no > 0),
    origin_line_id    BIGINT      NOT NULL REFERENCES sales_order_lines (id),
    sku_id            BIGINT      NOT NULL REFERENCES skus (id),
    qty               INTEGER     NOT NULL CHECK (qty > 0),
    unit_price_amount BIGINT      NOT NULL CHECK (unit_price_amount >= 0),
    discount_rate     INTEGER     NOT NULL CHECK (discount_rate > 0 AND discount_rate <= 10000),
    amount            BIGINT      NOT NULL CHECK (amount >= 0),
    batch_no          VARCHAR(64) NOT NULL DEFAULT '',
    production_date   DATE,
    expiry_date       DATE,
    CONSTRAINT uq_return_line_no UNIQUE (order_id, line_no),
    -- 同一张退货单里，同一条原出货行只能出现一次。
    -- 否则两行各退 3 件会绕过"单次预检"，把累计退货量的判断压力全压到入库那一刻。
    CONSTRAINT uq_return_origin_line UNIQUE (order_id, origin_line_id)
);
COMMENT ON TABLE return_order_lines IS
    '退货单明细。unit_price_amount / discount_rate 从原出货行复制，金额按出货同公式重算';
COMMENT ON COLUMN return_order_lines.amount IS
    '行退货金额 = round(qty × unit_price_amount × discount_rate / 10000)（分）';

CREATE INDEX idx_ro_line_order ON return_order_lines (order_id);
CREATE INDEX idx_ro_line_origin ON return_order_lines (origin_line_id);

-- ─────────────────────────── 权限种子（FR-IAM-02） ───────────────────────────
-- 沿用 M1 规则：迁移里不创建任何账号，只种角色→权限。
-- ADMIN 的 rma:* 只是数据完备；真正的放行由 PermissionAspect 的 ROLE_ADMIN 短路决定。

INSERT INTO app_role_permissions (role_id, permission)
SELECT r.id, p.permission FROM app_roles r
JOIN (VALUES
    ('ADMIN', 'rma:*'),
    ('SALESPERSON', 'rma:read'), ('SALESPERSON', 'rma:create'), ('SALESPERSON', 'rma:submit'),
    ('VIEWER', 'rma:read')
) AS p(role_code, permission) ON r.code = p.role_code;
