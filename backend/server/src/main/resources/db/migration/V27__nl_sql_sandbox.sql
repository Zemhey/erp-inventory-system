-- ════════════════════════════════════════════════════════════════════════════
-- V27：自然语言"自定义查询"的只读沙箱视图（FR-SYS-04 · text-to-SQL）
--
-- 背景：固定工具只能覆盖"事先想到的问法"。用户要"让 AI 自己决定查什么"，
-- 就必须让它写 SELECT。但直接把业务表开放给它有两个致命问题：
--   ① 租户隔离靠应用层拼 SQL 实现（TenantAwareJdbcTemplate 字面量注入），
--      任意 SQL 绕不过改写就一定会漏条件 → 跨租户泄露；
--   ② sys_config / app_users 这类表一旦可读，等于把 API Key 密文与口令哈希
--      暴露给模型（以及任何能问一句话的人）。
--
-- 解法：建一个独立的 schema `ai`，里面只有这 12 个**已按租户过滤的视图**。
--   沙箱执行时 SET LOCAL search_path = ai，模型写 `sales_orders` 命中的是视图，
--   而不是 public 里的真表。于是：
--     · 租户条件由视图强制施加，不依赖模型是否写对；
--     · 敏感表根本不在 search_path 里，写了也找不到；
--     · 现有业务代码完全不受影响（public 表一行没动）。
--
-- ★ 为什么用 current_setting('app.tenant_id', true)：
--   第二个参数 missing_ok = true，未设置时返回 NULL 而不是抛错。
--   `tenant_id = NULL` 恒为 UNKNOWN → 查不到任何行。
--   即"忘记设置租户"的结果是**空结果**，不是全量数据 —— 失败必须朝安全的那一侧倒。
-- ════════════════════════════════════════════════════════════════════════════

CREATE SCHEMA IF NOT EXISTS ai;

COMMENT ON SCHEMA ai IS
    '自然语言自定义查询的只读沙箱：仅含按租户过滤的视图，不存放任何数据';

CREATE OR REPLACE VIEW ai.sales_orders AS
SELECT id, order_no, status, customer_id, warehouse_id, biz_date,
       total_amount, total_qty, period, remark
  FROM public.sales_orders
 WHERE tenant_id = current_setting('app.tenant_id', true)::bigint;

CREATE OR REPLACE VIEW ai.sales_order_lines AS
SELECT id, order_id, line_no, sku_id, qty, shipped_qty, returned_qty,
       unit_price_amount, amount, discount_rate
  FROM public.sales_order_lines
 WHERE tenant_id = current_setting('app.tenant_id', true)::bigint;

CREATE OR REPLACE VIEW ai.purchase_orders AS
SELECT id, order_no, status, supplier_id, warehouse_id, biz_date,
       total_amount, total_qty, period, remark
  FROM public.purchase_orders
 WHERE tenant_id = current_setting('app.tenant_id', true)::bigint;

CREATE OR REPLACE VIEW ai.purchase_order_lines AS
SELECT id, order_id, line_no, sku_id, qty, received_qty, qc_status,
       unit_price_amount, amount
  FROM public.purchase_order_lines
 WHERE tenant_id = current_setting('app.tenant_id', true)::bigint;

CREATE OR REPLACE VIEW ai.return_orders AS
SELECT id, order_no, status, origin_sales_order_id, customer_id, warehouse_id,
       biz_date, total_amount, total_qty, period
  FROM public.return_orders
 WHERE tenant_id = current_setting('app.tenant_id', true)::bigint;

CREATE OR REPLACE VIEW ai.return_order_lines AS
SELECT id, order_id, line_no, sku_id, qty, unit_price_amount, amount, batch_no
  FROM public.return_order_lines
 WHERE tenant_id = current_setting('app.tenant_id', true)::bigint;

CREATE OR REPLACE VIEW ai.inventory_balances AS
SELECT id, sku_id, warehouse_id, batch_no, expiry_date,
       on_hand_qty, reserved_qty, frozen_qty, available_qty
  FROM public.inventory_balances
 WHERE tenant_id = current_setting('app.tenant_id', true)::bigint;

CREATE OR REPLACE VIEW ai.products AS
SELECT id, code, name, category, season, enabled
  FROM public.products
 WHERE tenant_id = current_setting('app.tenant_id', true)::bigint;

CREATE OR REPLACE VIEW ai.skus AS
SELECT id, product_id, color, size, barcode, retail_price_amount,
       enabled, min_level, max_level
  FROM public.skus
 WHERE tenant_id = current_setting('app.tenant_id', true)::bigint;

CREATE OR REPLACE VIEW ai.suppliers AS
SELECT id, code, name, contact_name, contact_phone, address, enabled
  FROM public.suppliers
 WHERE tenant_id = current_setting('app.tenant_id', true)::bigint;

CREATE OR REPLACE VIEW ai.customers AS
SELECT id, code, name, phone, discount_rate, level, enabled, credit_limit, credit_days
  FROM public.customers
 WHERE tenant_id = current_setting('app.tenant_id', true)::bigint;

CREATE OR REPLACE VIEW ai.warehouses AS
SELECT id, code, name, address, enabled, priority
  FROM public.warehouses
 WHERE tenant_id = current_setting('app.tenant_id', true)::bigint;
