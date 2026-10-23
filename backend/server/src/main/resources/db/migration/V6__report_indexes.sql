-- ============================================================================
-- V6 · M2-S3 报表查询索引
-- 范围：**只加索引，不动任何表结构**（计划书 §3.2 / §5.4 对 S3 的约束）
-- 依据：.plan/M2-计划书.md §3.5（三张报表口径）、§6 坑 8（报表 N+1）
--
-- 加索引的规矩：一个索引必须对应一条真实存在的谓词，写不出对应查询的就不建。
--   多余的索引会拖慢每一次入库/出货写入，而报表是只读的 —— 收益在读侧、
--   成本在写侧，所以这里刻意保持"最小集合"，并且每条都注明它服务哪个接口。
--
-- 刻意**没有**建的索引：
--   · return_orders (biz_date, status)：三张报表都不查退货单（出货统计按 §3.5
--     明确"不含退货"，进销存汇总走流水）。它是日清/月结的谓词，属 S2；
--     在这里建一个本泳道无人使用的索引，等于把成本塞给别人还看不出收益。
--   · inventory_ledger (sku_id, warehouse_id, occurred_at)：V1 的
--     idx_ledger_sku_wh 已经是这个前缀，重复建只会让规划器多一个候选。
-- ============================================================================

-- ── 进货统计 GET /reports/purchase ──────────────────────────────────────────
-- 谓词：biz_date BETWEEN :from AND :to（闭区间）+ status IN ('PARTIAL','COMPLETED')
--       （+ 可选 warehouse_id = :wh，由 biz_date 选出后回表过滤，不再单建索引）
-- 分组键（supplier / product / sku / day / month）不参与过滤，不需要索引。
CREATE INDEX idx_rpt_po_bizdate_status
    ON purchase_orders (biz_date, status);

COMMENT ON INDEX idx_rpt_po_bizdate_status IS
    '报表：进货统计按业务日期闭区间 + 状态过滤（GET /reports/purchase）';

-- ── 出货统计 GET /reports/sales ─────────────────────────────────────────────
CREATE INDEX idx_rpt_so_bizdate_status
    ON sales_orders (biz_date, status);

COMMENT ON INDEX idx_rpt_so_bizdate_status IS
    '报表：出货统计按业务日期闭区间 + 状态过滤（GET /reports/sales）';

-- ── 进销存汇总 GET /reports/inventory-summary ───────────────────────────────
-- 谓词：occurred_at < :期末（+ 可选 warehouse_id = :wh）。
-- 不带仓库过滤时期间通常覆盖大半个表，顺序扫描才是最优解，故只建"仓库在前"的这一条：
-- (warehouse_id, occurred_at) 让按仓过滤能一次定位到区间，
-- 现有的 idx_ledger_sku_wh 以 sku_id 打头，无法服务"只按仓库 + 时间"的查询。
CREATE INDEX idx_rpt_ledger_wh_occurred
    ON inventory_ledger (warehouse_id, occurred_at);

COMMENT ON INDEX idx_rpt_ledger_wh_occurred IS
    '报表：进销存汇总按仓库过滤 + 截至期末的流水区间（GET /reports/inventory-summary）';
