-- ════════════════════════════════════════════════════════════════════════════
-- 演示库「业务数据」清空脚本（保留主数据 / IAM / 科目 / 审批流配置）
--
-- ★ 这不是"造数"脚本。造数一律走 scripts/seed-demo.mjs 的真实业务接口，
--   因为库存余额、成本层、应收应付、凭证都是业务钩子的产物；
--   直接 INSERT 只会得到"有表无账"的假数据，一问就穿。
--   本脚本只负责把上一批（时间轴不全、模块为空）的残留清干净，
--   让 seeder 从一张白纸开始，且**不碰任何需要保持一致性的派生表**。
--
-- 保留：tenants / app_users / app_roles / 权限 / data_scopes /
--       warehouses / products / skus / customers / suppliers /
--       fin_account（科目表）/ fin_bank_account（账户，仅把余额复位到期初）/
--       approval_flows（流程定义）/ dict_* / sys_* / number rules /
--       audit_logs（审计是只读事实，且带 append-only 触发器，不该被清）
--
-- 清空：所有业务单据、库存、成本、应收应付、凭证、促销价格方案、审批实例、
--       NL 执行记录、幂等键、单据流水号（保证重灌后单号从 001 开始）。
--
-- 用法（Windows / Docker）：
--   docker exec -i erp-postgres psql -U erp -d erp -f - < scripts/reset-demo-data.sql
-- 或：
--   Get-Content scripts/reset-demo-data.sql -Raw | docker exec -i erp-postgres psql -U erp -d erp -f -
--
-- 只清默认租户（tenant_id = 1）的业务数据；租户 2 的演示行原样保留
-- （租户隔离矩阵要拿它做"异租户看不到"的负样本）。
-- ════════════════════════════════════════════════════════════════════════════

BEGIN;

-- ─────────────────────────── ① 销售与履约 ───────────────────────────
TRUNCATE TABLE shipment_lines, so_shipments, so_shipment_plans,
               sales_order_line_reservations, sales_order_lines, sales_orders,
               return_order_lines, return_orders,
               sales_quote_lines, sales_quotes,
               receivable_allocations, customer_receipts,
               customer_receivables, customer_credit_snapshots
        RESTART IDENTITY CASCADE;

-- ─────────────────────────── ② 采购与应付 ───────────────────────────
-- 注意：请购单与采购单同表（purchase_orders.doc_type 区分），没有独立的请购表。
TRUNCATE TABLE payable_allocations, supplier_payables,
               po_receipt_lines, po_receipts,
               purchase_returns, purchase_order_lines, purchase_orders
        RESTART IDENTITY CASCADE;

-- ─────────────────────────── ③ 库存与成本 ───────────────────────────
-- inventory_balances 是派生结果，必须与 inventory_ledger 一起清；
-- 只清一边会让"实存 == 流水净和"（不变量 INV-2）当场失衡。
TRUNCATE TABLE stocktake_lines, stocktakes,
               transfer_lines, inventory_transfers,
               inventory_adjustments, inventory_freezes,
               inventory_reservations, inventory_ledger, inventory_balances,
               cost_layers, cost_monthly, margin_records,
               daily_settlements, settlement_periods
        RESTART IDENTITY CASCADE;

-- ─────────────────────────── ④ 财务 ───────────────────────────
TRUNCATE TABLE fin_voucher_line, fin_voucher,
               fin_expense_line, fin_expense,
               fin_invoice, fin_cash_journal
        RESTART IDENTITY CASCADE;

-- 银行账户余额复位到期初：日记账被清空后，账户余额若留着旧值，
-- 「资金余额」卡片会与日记账链尾对不上，财务首页与资产负债表立刻打架。
UPDATE fin_bank_account
   SET balance = COALESCE(init_balance, 0)
 WHERE tenant_id = 1;

-- 科目期初归零：seeder 末尾的期初建账会按"银行合计 − 总账净额"重新倒挤，
-- 不先归零就会在旧期初上再加一遍，资产负债表直接被撑爆。
UPDATE fin_account
   SET init_balance = 0
 WHERE tenant_id = 1;

-- ─────────────────────────── ⑤ 营销 ───────────────────────────
TRUNCATE TABLE prm_promotion_rule, prm_promotion,
               prm_price_plan_item, prm_price_plan
        RESTART IDENTITY CASCADE;

-- ─────────────────────────── ⑥ 流程 / 幂等 / NL ───────────────────────────
-- ★ approval_steps 一张表存两种东西：
--     instance_id IS NULL     → **流程节点定义**（属于配置，必须保留）
--     instance_id IS NOT NULL → 某张单据的审批实例节点（属于业务数据，要清）
-- 两个坑都在这里踩过：
--   ① 直接 TRUNCATE approval_steps → 把三条流的节点定义一并删了，
--      审批流变成"已启用却没有节点"，所有单据 approve 报 APS_APPROVAL_REQUIRED，
--      而单据表看起来完全正常，非常难查；
--   ② 用 TRUNCATE approval_instances ... CASCADE → CASCADE 会顺着外键把
--      approval_steps **整表**清掉（连 instance_id IS NULL 的定义一起），
--      等于绕了一圈又把 ① 复现一遍。
-- 因此这里一律用 DELETE（不是 TRUNCATE），按"先删引用方、再删被引用方"的顺序。
DELETE FROM approval_steps WHERE instance_id IS NOT NULL;
DELETE FROM approval_instances;
TRUNCATE TABLE idempotency_keys, nl_execution RESTART IDENTITY;

-- ─────────────────────────── ⑦ 主数据残渣 ───────────────────────────
-- 主数据是「保留」的，但保留的前提是它本身干净。历史上踩过两次坑：
--   ① 商品/客户编号格式改过一次（DM-P-1 → DM-P-01、DM-CUS-1 → DM-CUS-01），
--      findOrCreate 按编号去重，换格式后旧行不会被复用，而是**又建一份同名主数据**；
--      结果客户从 40 变成 51 个重名的"优品服饰店"，排行榜上同一家店出现两行。
--   ② 联调测试跑出来的商品（端到端验证商品 / 幂等测试商品 / S2 验收T恤）留在目录里，
--      商品列表第一页就能看到"测试"，答辩时比空表格更扎眼。
-- 因此每次重置都把这些残渣清掉，只留规范编号的演示主数据与迁移自带的主数据。
-- ★ 只清 tenant_id = 1；租户 2 的演示行是"异租户看不到"的负样本，必须保留。
DELETE FROM skus WHERE product_id IN (
    SELECT id FROM products
     WHERE tenant_id = 1
       AND (code ~ '^DM-P-[0-9]$' OR code LIKE 'P-E2E%'
            OR code LIKE 'P-IDEM%' OR code LIKE 'P-M3%'));
DELETE FROM products
 WHERE tenant_id = 1
   AND (code ~ '^DM-P-[0-9]$' OR code LIKE 'P-E2E%'
        OR code LIKE 'P-IDEM%' OR code LIKE 'P-M3%');
DELETE FROM customers
 WHERE tenant_id = 1
   AND code ~ '^DM-CUS-[0-9]$';

-- 单据流水号：不清会让重灌后的单号从 137 开始，看起来像"接着旧数据"。
DELETE FROM document_sequences WHERE tenant_id = 1;

COMMIT;

-- ─────────────────────────── 自检 ───────────────────────────
-- 清完立刻报一次数：如果还有业务行残留，说明有表漏在 TRUNCATE 列表外，
-- 那时 seeder 会撞上"旧应收/旧余额"，且症状极难定位。
SELECT 'sales_orders' AS t, COUNT(*) FROM sales_orders WHERE tenant_id = 1
UNION ALL SELECT 'purchase_orders', COUNT(*) FROM purchase_orders WHERE tenant_id = 1
UNION ALL SELECT 'inventory_ledger', COUNT(*) FROM inventory_ledger WHERE tenant_id = 1
UNION ALL SELECT 'inventory_balances', COUNT(*) FROM inventory_balances WHERE tenant_id = 1
UNION ALL SELECT 'customer_receivables', COUNT(*) FROM customer_receivables WHERE tenant_id = 1
UNION ALL SELECT 'fin_voucher', COUNT(*) FROM fin_voucher WHERE tenant_id = 1
UNION ALL SELECT 'products(保留)', COUNT(*) FROM products WHERE tenant_id = 1
UNION ALL SELECT 'customers(保留)', COUNT(*) FROM customers WHERE tenant_id = 1;
