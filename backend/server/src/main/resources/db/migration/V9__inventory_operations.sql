-- ============================================================================
-- V9 · V2-1 库存作业（FR-INV-07 ~ FR-INV-13）
-- 范围：
--   ① 主数据加列：skus.min_level / max_level（补货阈值）、warehouses.priority（预警排序）
--   ② 余额加列：inventory_balances.frozen_qty，并重建 available_qty 生成列
--   ③ 六张作业表：调拨 / 盘点 / 调整 / 冻结
-- 依据：.plan/V2-1-计划书.md、docs/02-领域模型 §4（不变量）、docs/04-数据模型
--
-- 设计原则（延续 V1~V8）：**追加式迁移**。V1~V8 一行不改；
--   V1 的 available_qty 是内联生成列，改名/改定义只能重建，见 ②。
-- ============================================================================

-- ─────────────────────────── ① 主数据：补货阈值与仓库优先级 ───────────────────────────

ALTER TABLE skus
    ADD COLUMN min_level INTEGER NOT NULL DEFAULT 0 CHECK (min_level >= 0);
ALTER TABLE skus
    ADD COLUMN max_level INTEGER NOT NULL DEFAULT 0 CHECK (max_level >= 0);

COMMENT ON COLUMN skus.min_level IS '安全库存下限（FR-INV-12/13）：可用量低于此值触发预警与补货建议；0 = 不设阈值';
COMMENT ON COLUMN skus.max_level IS '补货上限（FR-INV-13）：建议补到该值；0 = 不参与补货建议';

-- max_level = 0 表示"不参与补货"，此时不要求 max >= min；
-- 一旦启用补货，max 必须不小于 min，否则建议量永远为负。
ALTER TABLE skus
    ADD CONSTRAINT ck_sku_level_order CHECK (max_level = 0 OR max_level >= min_level);

ALTER TABLE warehouses
    ADD COLUMN priority INTEGER NOT NULL DEFAULT 100;
COMMENT ON COLUMN warehouses.priority IS '仓库优先级（FR-INV-12）：数值越大越优先，预警/补货建议按此排序';

-- ─────────────────────────── ② 库存余额：冻结量 ───────────────────────────

ALTER TABLE inventory_balances
    ADD COLUMN frozen_qty INTEGER NOT NULL DEFAULT 0 CHECK (frozen_qty >= 0);
COMMENT ON COLUMN inventory_balances.frozen_qty IS '冻结量（FR-INV-10）：不参与可用量，也不可被预占/出库/调拨扣减';

-- V1 的 available_qty 是内联生成列，PostgreSQL 不允许直接改生成表达式，
-- 只能删列重建。先删后加在同一事务里完成，窗口期内没有并发读写。
ALTER TABLE inventory_balances DROP COLUMN available_qty;
ALTER TABLE inventory_balances
    ADD COLUMN available_qty INTEGER
        GENERATED ALWAYS AS (on_hand_qty - reserved_qty - frozen_qty) STORED;
COMMENT ON COLUMN inventory_balances.available_qty IS
    '可用量 = on_hand - reserved - frozen，生成列（FR-INV-10：冻结量不进可用量）';

-- 冻结与预占之和不得超过实存，否则可用量为负（不变量 INV-1 的数据库兜底）
ALTER TABLE inventory_balances
    ADD CONSTRAINT ck_frozen_reserved_le_onhand
        CHECK (reserved_qty + frozen_qty <= on_hand_qty);

-- ─────────────────────────── ③ 仓间调拨（FR-INV-07） ───────────────────────────
--
-- 两段式：建单即调出（源仓实存减少、批次转为在途），到货再调入（目的仓实存增加）。
-- 守恒（INV-7）：源仓流水 + 目的仓流水 + 在途量 == 0。
-- 在途量落在明细行的 in_transit_qty 上，是"货在哪里"的唯一事实。

CREATE TABLE inventory_transfers (
    id                 BIGSERIAL PRIMARY KEY,
    transfer_no        VARCHAR(64) NOT NULL,
    from_warehouse_id  BIGINT      NOT NULL REFERENCES warehouses (id),
    to_warehouse_id    BIGINT      NOT NULL REFERENCES warehouses (id),
    status             VARCHAR(16) NOT NULL DEFAULT 'IN_TRANSIT'
                           CHECK (status IN ('IN_TRANSIT','COMPLETED')),
    biz_date           DATE        NOT NULL,
    remark             VARCHAR(500),
    request_id         VARCHAR(64),
    receive_request_id VARCHAR(64),
    received_at        TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_transfer_no UNIQUE (transfer_no),
    CONSTRAINT ck_transfer_distinct_warehouse CHECK (from_warehouse_id <> to_warehouse_id),
    -- COMPLETED 必须有到货时间；IN_TRANSIT 必须没有，避免"状态与事实各说各话"
    CONSTRAINT ck_transfer_received_at CHECK (
        (status = 'COMPLETED' AND received_at IS NOT NULL)
        OR (status = 'IN_TRANSIT' AND received_at IS NULL))
);
COMMENT ON TABLE inventory_transfers IS
    '仓间调拨单（FR-INV-07）：建单即调出、到货再调入；守恒见 INV-7';

CREATE UNIQUE INDEX uq_transfer_request ON inventory_transfers (request_id)
    WHERE request_id IS NOT NULL;
CREATE UNIQUE INDEX uq_transfer_receive_request ON inventory_transfers (receive_request_id)
    WHERE receive_request_id IS NOT NULL;
CREATE INDEX idx_transfer_status ON inventory_transfers (status, biz_date DESC);
CREATE INDEX idx_transfer_from ON inventory_transfers (from_warehouse_id, biz_date DESC);
CREATE INDEX idx_transfer_to ON inventory_transfers (to_warehouse_id, biz_date DESC);

CREATE TABLE transfer_lines (
    id               BIGSERIAL PRIMARY KEY,
    transfer_id      BIGINT      NOT NULL REFERENCES inventory_transfers (id) ON DELETE CASCADE,
    line_no          INTEGER     NOT NULL CHECK (line_no > 0),
    sku_id           BIGINT      NOT NULL REFERENCES skus (id),
    batch_no         VARCHAR(64) NOT NULL DEFAULT '',
    qty              INTEGER     NOT NULL CHECK (qty > 0),
    -- 0 = 已到货；qty = 尚未到货。禁止超过发运量。
    in_transit_qty   INTEGER     NOT NULL CHECK (in_transit_qty >= 0 AND in_transit_qty <= qty),
    -- 发运时从源仓余额快照的批次日期，到货时原样带到目的仓，避免 FEFO 丢效期
    production_date  DATE,
    expiry_date      DATE,
    unit_cost_amount BIGINT      CHECK (unit_cost_amount >= 0),
    CONSTRAINT uq_transfer_line_no UNIQUE (transfer_id, line_no),
    CONSTRAINT uq_transfer_line_sku_batch UNIQUE (transfer_id, sku_id, batch_no)
);
COMMENT ON TABLE transfer_lines IS
    '调拨明细：in_transit_qty 为仍在途的数量，到货后归零（INV-7 的在途项）';

-- ─────────────────────────── ④ 盘点与差异过账（FR-INV-08） ───────────────────────────

CREATE TABLE stocktakes (
    id              BIGSERIAL PRIMARY KEY,
    stocktake_no    VARCHAR(64) NOT NULL,
    warehouse_id    BIGINT      NOT NULL REFERENCES warehouses (id),
    status          VARCHAR(16) NOT NULL DEFAULT 'DRAFT'
                        CHECK (status IN ('DRAFT','POSTED')),
    biz_date        DATE        NOT NULL,
    remark          VARCHAR(500),
    request_id      VARCHAR(64),
    post_request_id VARCHAR(64),
    posted_at       TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_stocktake_no UNIQUE (stocktake_no),
    CONSTRAINT ck_stocktake_posted_at CHECK (
        (status = 'POSTED' AND posted_at IS NOT NULL)
        OR (status = 'DRAFT' AND posted_at IS NULL))
);
COMMENT ON TABLE stocktakes IS
    '盘点单（FR-INV-08）：建单时快照账面量，过账时按差异生成 ADJUST_IN/OUT 流水';

CREATE UNIQUE INDEX uq_stocktake_request ON stocktakes (request_id)
    WHERE request_id IS NOT NULL;
CREATE UNIQUE INDEX uq_stocktake_post_request ON stocktakes (post_request_id)
    WHERE post_request_id IS NOT NULL;
CREATE INDEX idx_stocktake_wh ON stocktakes (warehouse_id, biz_date DESC);

CREATE TABLE stocktake_lines (
    id          BIGSERIAL PRIMARY KEY,
    stocktake_id BIGINT  NOT NULL REFERENCES stocktakes (id) ON DELETE CASCADE,
    line_no     INTEGER     NOT NULL CHECK (line_no > 0),
    sku_id      BIGINT      NOT NULL REFERENCES skus (id),
    batch_no    VARCHAR(64) NOT NULL DEFAULT '',
    book_qty    INTEGER     NOT NULL CHECK (book_qty >= 0),
    counted_qty INTEGER     NOT NULL CHECK (counted_qty >= 0),
    -- 差异由数据库计算，应用层不可能忘记或算错方向
    diff_qty    INTEGER     GENERATED ALWAYS AS (counted_qty - book_qty) STORED,
    CONSTRAINT uq_stocktake_line_no UNIQUE (stocktake_id, line_no),
    CONSTRAINT uq_stocktake_line_sku_batch UNIQUE (stocktake_id, sku_id, batch_no)
);
COMMENT ON TABLE stocktake_lines IS
    '盘点明细：diff_qty = counted - book（生成列）；diff=0 的行不得产生调整流水';

-- ─────────────────────────── ⑤ 库存调整（FR-INV-09） ───────────────────────────

CREATE TABLE inventory_adjustments (
    id            BIGSERIAL PRIMARY KEY,
    adjust_no     VARCHAR(64)  NOT NULL,
    sku_id        BIGINT       NOT NULL REFERENCES skus (id),
    warehouse_id  BIGINT       NOT NULL REFERENCES warehouses (id),
    batch_no      VARCHAR(64)  NOT NULL DEFAULT '',
    -- 正=盘盈/调增，负=盘亏/调减；0 没有意义，属程序错误
    change_qty    INTEGER      NOT NULL CHECK (change_qty <> 0),
    reason        VARCHAR(200) NOT NULL CHECK (length(btrim(reason)) > 0),
    request_id    VARCHAR(64)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_adjust_no UNIQUE (adjust_no),
    CONSTRAINT uq_adjust_request UNIQUE (request_id)
);
COMMENT ON TABLE inventory_adjustments IS
    '库存调整（FR-INV-09）：原因必填；每次调整对应一条 ADJUST_IN/ADJUST_OUT 流水';

CREATE INDEX idx_adjust_sku_wh ON inventory_adjustments (sku_id, warehouse_id, created_at DESC);

-- ─────────────────────────── ⑥ 库存冻结（FR-INV-10） ───────────────────────────

CREATE TABLE inventory_freezes (
    id                BIGSERIAL PRIMARY KEY,
    sku_id            BIGINT       NOT NULL REFERENCES skus (id),
    warehouse_id      BIGINT       NOT NULL REFERENCES warehouses (id),
    batch_no          VARCHAR(64)  NOT NULL DEFAULT '',
    qty               INTEGER      NOT NULL CHECK (qty > 0),
    reason            VARCHAR(200),
    status            VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE'
                          CHECK (status IN ('ACTIVE','RELEASED','EXPIRED')),
    expires_at        TIMESTAMPTZ,
    request_id        VARCHAR(64)  NOT NULL,
    release_request_id VARCHAR(64),
    released_at       TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_freeze_request UNIQUE (request_id),
    -- 解冻（人工/超时）必须留下时间；ACTIVE 不得有
    CONSTRAINT ck_freeze_released_at CHECK (
        (status = 'ACTIVE' AND released_at IS NULL)
        OR (status IN ('RELEASED','EXPIRED') AND released_at IS NOT NULL))
);
COMMENT ON TABLE inventory_freezes IS
    '库存冻结（FR-INV-10）：冻结量不进可用量；超时解冻走 ClockProvider（expires_at）';

CREATE UNIQUE INDEX uq_freeze_release_request ON inventory_freezes (release_request_id)
    WHERE release_request_id IS NOT NULL;
CREATE INDEX idx_freeze_active ON inventory_freezes (sku_id, warehouse_id, batch_no)
    WHERE status = 'ACTIVE';
CREATE INDEX idx_freeze_expiry ON inventory_freezes (expires_at)
    WHERE status = 'ACTIVE' AND expires_at IS NOT NULL;
