-- ════════════════════════════════════════════════════════════════════════════
-- V28：成本层的业务类型白名单补上「采购退货」
--
-- 背景：采购退货（质检不合格退回供应商）在库存侧是**合法出库**，
-- inventory_ledger 会写 biz_type = 'PURCHASE_RETURN'、change_qty 为负。
-- 但 V12 建 cost_layers 时，ck_cost_layer_direction 的 OUT 分支只列了
--   SALES_OUT / PURCHASE_REVERSAL / ADJUST_OUT / TRANSFER_OUT，
-- 漏了 PURCHASE_RETURN。
--
-- 后果不是"退货记不上成本"，而是**整个成本重算事务回滚**：
--   POST /cost/recalculate → 写第一条 PURCHASE_RETURN/OUT 成本层时
--   撞 ck_cost_layer_direction → 500。
-- 于是 cost_layers / cost_monthly 全空，毛利分析与库存金额两张报表整页无数据，
-- 而单据、库存、应收应付一切正常 —— 症状指向报表，根因在约束。
--
-- 只放宽 OUT 分支，IN 分支保持原样；不改已应用的 V12（迁移只追加、不改写）。
-- ════════════════════════════════════════════════════════════════════════════

ALTER TABLE cost_layers DROP CONSTRAINT IF EXISTS ck_cost_layer_direction;

ALTER TABLE cost_layers
    ADD CONSTRAINT ck_cost_layer_direction CHECK (
        (direction = 'IN' AND biz_type IN
            ('PURCHASE_IN', 'RETURN_IN', 'SALES_REVERSAL', 'ADJUST_IN', 'TRANSFER_IN'))
     OR (direction = 'OUT' AND biz_type IN
            ('SALES_OUT', 'PURCHASE_REVERSAL', 'ADJUST_OUT', 'TRANSFER_OUT', 'PURCHASE_RETURN'))
    );

COMMENT ON CONSTRAINT ck_cost_layer_direction ON cost_layers IS
    '成本层的方向与业务类型必须匹配；PURCHASE_RETURN 是采购退货出库（V28 补入 OUT 分支）';
