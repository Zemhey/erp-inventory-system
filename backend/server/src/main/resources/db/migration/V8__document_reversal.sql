-- ============================================================================
-- V8 · M2.5 红冲（FR-SAL-04 / FR-PUR-03）
-- 范围：
--   ① inventory_ledger.biz_type 扩展两个红冲类型（追加，不改 V1~V7）
--   ② sales_orders / purchase_orders 增加作废幂等键 void_request_id
-- 依据：.plan/M2.5-计划书.md §2.2 / §2.3 / §2.5
--
-- 设计原则（延续 V1~V7）：**追加式迁移**。已合并的 V1~V7 一行不改；
--   CHECK 约束的扩建通过先删旧约束、再建命名约束完成。
-- ============================================================================

-- ─────────────────────────── ① 流水类型：红冲 ───────────────────────────
--
-- V1 把 biz_type 的内联 CHECK 写在列上，PostgreSQL 自动生成了形如
-- inventory_ledger_biz_type_check 的约束名。这里不写死那个名字（不同版本/手工
-- 重建可能不同），而是按「表 + CHECK + 定义里含 biz_type」找到它再删除。
DO $$
DECLARE
    old_constraint TEXT;
BEGIN
    SELECT conname INTO old_constraint
      FROM pg_constraint
     WHERE conrelid = 'inventory_ledger'::regclass
       AND contype = 'c'
       AND pg_get_constraintdef(oid) LIKE '%biz_type%'
     LIMIT 1;

    IF old_constraint IS NOT NULL THEN
        EXECUTE format('ALTER TABLE inventory_ledger DROP CONSTRAINT %I', old_constraint);
    END IF;
END $$;

ALTER TABLE inventory_ledger
    ADD CONSTRAINT ck_ledger_biz_type CHECK (biz_type IN (
        'PURCHASE_IN','SALES_OUT','RETURN_IN',
        'TRANSFER_IN','TRANSFER_OUT','ADJUST_IN','ADJUST_OUT',
        -- M2.5 红冲：方向明确的补偿流水，不与盘点/调账的 ADJUST_* 混用
        'SALES_REVERSAL','PURCHASE_REVERSAL'
    ));

COMMENT ON CONSTRAINT ck_ledger_biz_type ON inventory_ledger IS
    '流水业务类型：正向出入库 + 退货 + 调拨/调整 + M2.5 两类红冲（SALES_REVERSAL 为正、PURCHASE_REVERSAL 为负）';

-- ─────────────────────────── ② 作废幂等键 ───────────────────────────
--
-- 与退货单的 receive_request_id 同一做法：作废动作单独一列，
-- 同键重放返回首次结果；异键重复作废由业务层报状态非法。
-- 唯一索引是「同一幂等键只能产生一次副作用」的数据库级保证；
-- 跨单据复用同一个键会被拦住，由应用翻译成 SYS_DUPLICATE_REQUEST。

ALTER TABLE sales_orders
    ADD COLUMN void_request_id VARCHAR(64);
COMMENT ON COLUMN sales_orders.void_request_id IS
    '作废/红冲的幂等键（X-Request-Id）。同键重放返回首次结果，不重复回补库存';

CREATE UNIQUE INDEX uq_so_void_request ON sales_orders (void_request_id)
    WHERE void_request_id IS NOT NULL;

ALTER TABLE purchase_orders
    ADD COLUMN void_request_id VARCHAR(64);
COMMENT ON COLUMN purchase_orders.void_request_id IS
    '作废/红冲的幂等键（X-Request-Id）。同键重放返回首次结果，不重复回退库存';

CREATE UNIQUE INDEX uq_po_void_request ON purchase_orders (void_request_id)
    WHERE void_request_id IS NOT NULL;

-- 红冲要按「原单号 + 原业务类型」精确取回原始流水；
-- 已有 idx_ledger_bizref 只覆盖 biz_ref_no，这里补一个复合索引让红冲查询可走索引。
CREATE INDEX idx_ledger_bizref_type ON inventory_ledger (biz_ref_no, biz_type);
