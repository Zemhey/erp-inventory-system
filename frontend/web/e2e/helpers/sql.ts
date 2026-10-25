import type { RunInfo } from './run'
import { sqlString } from './db'

/**
 * E2E 清理 SQL。
 *
 * 所有条件都只认本次 runId 的唯一前缀（主数据）或由这些主数据派生出的单据/库存/结算，
 * 不按时间窗口、不按 id 范围删除 —— 既有预占 e2e-reserve-001 与开发基线不会被碰到。
 *
 * ★ 唯一的例外是**保留测试账期**（§6 坑 9）：日清行按 (业务日期, 仓库)、账期行按 period，
 *   两者都不带 runId 前缀，也无法挂回本 run 的主数据（账期是跨仓库的全月汇总）。
 *   所以结算类用例一律只用 `RESERVED_YEAR` 里的账期，运行前与清理时按整年清空 ——
 *   否则上一轮留下的 CLOSED 账期会让下一轮"在该期间建单"直接被 PeriodGuard 拒绝。
 */

/** 保留测试年份：只有这一年的账期属于 E2E，绝不碰真实当前月（计划书 §4.6 / §6 坑 9）。 */
export const RESERVED_YEAR = '2031'

function assertSafeRun(run: RunInfo): void {
  if (!/^E2E-[A-Z0-9]+-[A-Z0-9]+$/.test(run.runId)) {
    throw new Error(`runId 格式异常，拒绝执行清理 SQL：${run.runId}`)
  }
  for (const value of [run.viewerUsername, run.financeUsername]) {
    // 空串表示"这一轮没有该账号"（例如旧的 run.json 里没有 financeUsername），
    // 由 tempUsernames 过滤掉；但任何非空的非法名字都要拒绝。
    if (value !== '' && !/^[a-z0-9-]+$/.test(value)) {
      throw new Error(`临时账号名格式异常，拒绝执行清理 SQL：${value}`)
    }
  }
}

/** 本轮创建过的临时账号（SQL 里要删的就是这几个）。 */
function tempUsernames(run: RunInfo): string[] {
  return [run.viewerUsername, run.financeUsername].filter((name) => name !== '')
}

/** `IN (…)` 片段：一个账号都没有时用 NULL（IN () 不是合法 SQL，且匹配不到任何行）。 */
function userList(run: RunInfo): string {
  const names = tempUsernames(run).map(sqlString)
  return names.length ? names.join(', ') : 'NULL'
}

/**
 * 运行前后的业务表计数。
 *
 * ★ 每列都 `::text`：最后一行是 `..._reservation_state` 的文本指纹，
 *   而 `count(*)` 是 bigint —— UNION 里两种类型不统一会直接报
 *   `UNION types bigint and text cannot be matched`（本机第一次全量就跑红了）。
 */
export function baselineSql(): string {
  return `
SELECT 'products', count(*)::text FROM products
UNION ALL SELECT 'skus', count(*)::text FROM skus
UNION ALL SELECT 'suppliers', count(*)::text FROM suppliers
UNION ALL SELECT 'customers', count(*)::text FROM customers
UNION ALL SELECT 'warehouses', count(*)::text FROM warehouses
UNION ALL SELECT 'balances', count(*)::text FROM inventory_balances
UNION ALL SELECT 'ledger', count(*)::text FROM inventory_ledger
UNION ALL SELECT 'reservations', count(*)::text FROM inventory_reservations
UNION ALL SELECT 'purchase_orders', count(*)::text FROM purchase_orders
UNION ALL SELECT 'sales_orders', count(*)::text FROM sales_orders
UNION ALL SELECT 'return_orders', count(*)::text FROM return_orders
UNION ALL SELECT 'return_order_lines', count(*)::text FROM return_order_lines
UNION ALL SELECT 'daily_settlements', count(*)::text FROM daily_settlements
UNION ALL SELECT 'settlement_periods', count(*)::text FROM settlement_periods
UNION ALL SELECT 'line_reservations', count(*)::text FROM sales_order_line_reservations
UNION ALL SELECT 'sequences', count(*)::text FROM document_sequences
UNION ALL SELECT 'users', count(*)::text FROM app_users
UNION ALL SELECT 'user_roles', count(*)::text FROM app_user_roles
UNION ALL SELECT 'baseline_reservation', count(*)::text FROM inventory_reservations WHERE biz_ref_no = 'e2e-reserve-001'
UNION ALL SELECT 'baseline_reservation_state', COALESCE(string_agg(status || ':' || reserved_qty::text, ',' ORDER BY id), '') FROM inventory_reservations WHERE biz_ref_no = 'e2e-reserve-001'
ORDER BY 1;
`.trim()
}

/**
 * 运行前先把保留账期清空（幂等）。
 *
 * 顺序与 teardown 一致：日清行在账期行之前，两者都在主数据之前 —— 这里它们本来就在
 * 一个独立事务里，不依赖别的删除顺序。返回删掉的行数，供 setup 打印。
 */
export function reservedPeriodResetSql(): string {
  return `
BEGIN;
CREATE TEMP TABLE e2e_reset_daily ON COMMIT DROP AS
  SELECT id FROM daily_settlements WHERE EXTRACT(YEAR FROM biz_date) = ${RESERVED_YEAR};
CREATE TEMP TABLE e2e_reset_periods ON COMMIT DROP AS
  SELECT period FROM settlement_periods WHERE split_part(period, '-', 1) = '${RESERVED_YEAR}';
DELETE FROM daily_settlements WHERE id IN (SELECT id FROM e2e_reset_daily);
DELETE FROM settlement_periods WHERE period IN (SELECT period FROM e2e_reset_periods);
SELECT 'daily', count(*) FROM e2e_reset_daily
UNION ALL SELECT 'periods', count(*) FROM e2e_reset_periods
ORDER BY 1;
COMMIT;
`.trim()
}

/**
 * 创建临时账号：密码哈希复制自管理员（§6 坑 17 —— seed-dev-user.ps1 在 PS 5.1 下因 BOM 失败），
 * 角色只从 app_roles 里取已种好的（VIEWER / FINANCE），不为了测试改任何权限规则。
 */
export function createUserSql(username: string, roleCode: string, adminUsername: string): string {
  const user = sqlString(username)
  const admin = sqlString(adminUsername)
  const role = sqlString(roleCode)
  return `
INSERT INTO app_users (tenant_id, username, password_hash, display_name)
SELECT a.tenant_id, ${user}, a.password_hash, 'E2E ' || ${role}
  FROM app_users a WHERE a.username = ${admin}
ON CONFLICT (username) DO NOTHING;

INSERT INTO app_user_roles (tenant_id, user_id, role_id)
SELECT u.tenant_id, u.id, r.id
  FROM app_users u
  JOIN app_roles r ON r.tenant_id = u.tenant_id
 WHERE u.username = ${user} AND r.code = ${role}
ON CONFLICT DO NOTHING;

SELECT (SELECT count(*) FROM app_users WHERE username = ${user}),
       (SELECT count(*) FROM app_user_roles ur
          JOIN app_users u ON u.id = ur.user_id
          JOIN app_roles r ON r.id = ur.role_id
         WHERE u.username = ${user} AND r.code = ${role});
`.trim()
}

/**
 * 给临时账号追加**第二个租户**的成员关系与角色（B1 多租户切换 E2E 用）。
 *
 * ★ 为什么不能复用 createUserSql：那条 SQL 只能把账号建在管理员的主租户里，
 *   而"切换器只在真的多租户成员时出现"必须有一个跨两个租户的账号来证明。
 *   这里直接用租户编码定位（E2E 库里固定有 default / demo 两个租户）。
 *
 * 返回两列：成员关系行数、目标租户角色行数；调用方必须都为 1，否则说明种子缺失。
 */
export function addTenantMembershipSql(
  username: string,
  tenantCode: string,
  roleCode: string,
): string {
  const user = sqlString(username)
  const tenant = sqlString(tenantCode)
  const role = sqlString(roleCode)
  return `
INSERT INTO app_user_tenants (user_id, tenant_id)
SELECT u.id, t.id
  FROM app_users u
  JOIN tenants t ON t.code = ${tenant}
 WHERE u.username = ${user}
ON CONFLICT DO NOTHING;

INSERT INTO app_user_roles (tenant_id, user_id, role_id)
SELECT t.id, u.id, r.id
  FROM app_users u
  JOIN tenants t ON t.code = ${tenant}
  JOIN app_roles r ON r.tenant_id = t.id
 WHERE u.username = ${user} AND r.code = ${role}
ON CONFLICT DO NOTHING;

SELECT (SELECT count(*) FROM app_user_tenants ut
          JOIN app_users u ON u.id = ut.user_id
          JOIN tenants t ON t.id = ut.tenant_id
         WHERE u.username = ${user} AND t.code = ${tenant}),
       (SELECT count(*) FROM app_user_roles ur
          JOIN app_users u ON u.id = ur.user_id
          JOIN tenants t ON t.id = ur.tenant_id
          JOIN app_roles r ON r.id = ur.role_id
         WHERE u.username = ${user} AND t.code = ${tenant} AND r.code = ${role});
`.trim()
}

export function cleanupSql(run: RunInfo): string {
  assertSafeRun(run)
  const prefix = sqlString(`${run.runId}-%`)
  // IN () 不是合法 SQL，所以一个账号都没有时用 NULL（匹配不到任何行）。
  const users = userList(run)

  return `
BEGIN;

CREATE TEMP TABLE e2e_products ON COMMIT DROP AS
  SELECT id FROM products WHERE code LIKE ${prefix};
CREATE TEMP TABLE e2e_skus ON COMMIT DROP AS
  SELECT id FROM skus WHERE product_id IN (SELECT id FROM e2e_products);
CREATE TEMP TABLE e2e_suppliers ON COMMIT DROP AS
  SELECT id FROM suppliers WHERE code LIKE ${prefix};
CREATE TEMP TABLE e2e_customers ON COMMIT DROP AS
  SELECT id FROM customers WHERE code LIKE ${prefix};
CREATE TEMP TABLE e2e_warehouses ON COMMIT DROP AS
  SELECT id FROM warehouses WHERE code LIKE ${prefix};

CREATE TEMP TABLE e2e_po ON COMMIT DROP AS
  SELECT o.id, o.order_no, o.request_id, o.biz_date
    FROM purchase_orders o
   WHERE o.supplier_id IN (SELECT id FROM e2e_suppliers)
      OR o.warehouse_id IN (SELECT id FROM e2e_warehouses)
      OR EXISTS (SELECT 1 FROM purchase_order_lines l
                  WHERE l.order_id = o.id AND l.sku_id IN (SELECT id FROM e2e_skus));
CREATE TEMP TABLE e2e_so ON COMMIT DROP AS
  SELECT o.id, o.order_no, o.request_id, o.biz_date
    FROM sales_orders o
   WHERE o.customer_id IN (SELECT id FROM e2e_customers)
      OR o.warehouse_id IN (SELECT id FROM e2e_warehouses)
      OR EXISTS (SELECT 1 FROM sales_order_lines l
                  WHERE l.order_id = o.id AND l.sku_id IN (SELECT id FROM e2e_skus));

-- 退货单：可能由本 run 的客户 / 仓库 / SKU 派生，也可能只是"原单是本 run 的出货单"
CREATE TEMP TABLE e2e_ro ON COMMIT DROP AS
  SELECT o.id, o.order_no, o.request_id, o.receive_request_id, o.biz_date
    FROM return_orders o
   WHERE o.customer_id IN (SELECT id FROM e2e_customers)
      OR o.warehouse_id IN (SELECT id FROM e2e_warehouses)
      OR o.origin_sales_order_id IN (SELECT id FROM e2e_so)
      OR EXISTS (SELECT 1 FROM return_order_lines l
                  WHERE l.order_id = o.id AND l.sku_id IN (SELECT id FROM e2e_skus));

CREATE TEMP TABLE e2e_order_nos ON COMMIT DROP AS
  SELECT order_no FROM e2e_po UNION SELECT order_no FROM e2e_so UNION SELECT order_no FROM e2e_ro;

-- ★ 保留账期（日清行按仓库、账期行按 period，都挂不上 runId 前缀）
CREATE TEMP TABLE e2e_daily ON COMMIT DROP AS
  SELECT id, request_id, biz_date FROM daily_settlements
   WHERE warehouse_id IN (SELECT id FROM e2e_warehouses)
      OR EXTRACT(YEAR FROM biz_date) = ${RESERVED_YEAR};
-- 只删保留年的账期行：这是 E2E 独占的范围。**不要**按"本 run 日清行所在期间"反推删除范围，
-- 那会在真实当前月被别的泳道锁过时把别人的锁账记录一起删掉。
CREATE TEMP TABLE e2e_periods ON COMMIT DROP AS
  SELECT period FROM settlement_periods
   WHERE split_part(period, '-', 1) = '${RESERVED_YEAR}';

CREATE TEMP TABLE e2e_request_ids ON COMMIT DROP AS
  SELECT request_id FROM e2e_po WHERE request_id IS NOT NULL
  UNION SELECT request_id FROM e2e_so WHERE request_id IS NOT NULL
  UNION SELECT request_id FROM e2e_ro WHERE request_id IS NOT NULL
  UNION SELECT receive_request_id FROM e2e_ro WHERE receive_request_id IS NOT NULL
  UNION SELECT request_id FROM e2e_daily WHERE request_id IS NOT NULL
  UNION SELECT request_id FROM inventory_ledger
         WHERE request_id IS NOT NULL
           AND (sku_id IN (SELECT id FROM e2e_skus)
                OR warehouse_id IN (SELECT id FROM e2e_warehouses)
                OR biz_ref_no IN (SELECT order_no FROM e2e_order_nos))
  UNION SELECT request_id FROM inventory_reservations
         WHERE sku_id IN (SELECT id FROM e2e_skus)
            OR warehouse_id IN (SELECT id FROM e2e_warehouses)
            OR biz_ref_no IN (SELECT order_no FROM e2e_order_nos);

SELECT 'po', count(*) FROM e2e_po
UNION ALL SELECT 'so', count(*) FROM e2e_so
UNION ALL SELECT 'ro', count(*) FROM e2e_ro
UNION ALL SELECT 'daily', count(*) FROM e2e_daily
UNION ALL SELECT 'periods', count(*) FROM e2e_periods
UNION ALL SELECT 'products', count(*) FROM e2e_products
UNION ALL SELECT 'skus', count(*) FROM e2e_skus
UNION ALL SELECT 'suppliers', count(*) FROM e2e_suppliers
UNION ALL SELECT 'customers', count(*) FROM e2e_customers
UNION ALL SELECT 'warehouses', count(*) FROM e2e_warehouses
ORDER BY 1;

-- ★ 删除顺序（§6 坑 10）：return_order_lines → return_orders → 出货 → 进货 → 结算。
--   return_order_lines.origin_line_id 与 daily_settlements.warehouse_id 都不是级联外键，
--   先删被引用方就会以 foreign_key_violation 失败。
DELETE FROM return_order_lines WHERE order_id IN (SELECT id FROM e2e_ro);
DELETE FROM return_orders WHERE id IN (SELECT id FROM e2e_ro);

-- V2-3 销售闭环：发货/应收/报价/套装都引用 sales_orders 与其行，必须先于出货/进货删除。
DELETE FROM shipment_lines
 WHERE sales_order_line_id IN (SELECT id FROM sales_order_lines WHERE order_id IN (SELECT id FROM e2e_so))
    OR sku_id IN (SELECT id FROM e2e_skus);
DELETE FROM so_shipments
 WHERE sales_order_id IN (SELECT id FROM e2e_so)
    OR warehouse_id IN (SELECT id FROM e2e_warehouses)
    OR plan_id IN (SELECT id FROM so_shipment_plans WHERE sales_order_id IN (SELECT id FROM e2e_so));
DELETE FROM so_shipment_plans WHERE sales_order_id IN (SELECT id FROM e2e_so);
DELETE FROM receivable_allocations
 WHERE receipt_id IN (SELECT id FROM customer_receipts WHERE customer_id IN (SELECT id FROM e2e_customers))
    OR receivable_id IN (SELECT id FROM customer_receivables
                          WHERE customer_id IN (SELECT id FROM e2e_customers)
                             OR sales_order_id IN (SELECT id FROM e2e_so));
DELETE FROM customer_receivables
 WHERE customer_id IN (SELECT id FROM e2e_customers) OR sales_order_id IN (SELECT id FROM e2e_so);
DELETE FROM customer_receipts WHERE customer_id IN (SELECT id FROM e2e_customers);
DELETE FROM customer_credit_snapshots
 WHERE customer_id IN (SELECT id FROM e2e_customers) OR sales_order_id IN (SELECT id FROM e2e_so);
DELETE FROM sales_quote_lines
 WHERE quote_id IN (SELECT id FROM sales_quotes WHERE customer_id IN (SELECT id FROM e2e_customers))
    OR sku_id IN (SELECT id FROM e2e_skus);
DELETE FROM sales_quotes
 WHERE customer_id IN (SELECT id FROM e2e_customers)
    OR converted_order_id IN (SELECT id FROM e2e_so);
DELETE FROM bundle_items
 WHERE component_sku_id IN (SELECT id FROM e2e_skus)
    OR bundle_id IN (SELECT i.bundle_id FROM bundle_items i
                      WHERE i.component_sku_id IN (SELECT id FROM e2e_skus));
DELETE FROM product_bundles
 WHERE id IN (SELECT bundle_id FROM bundle_items WHERE component_sku_id IN (SELECT id FROM e2e_skus));

DELETE FROM sales_order_line_reservations
 WHERE line_id IN (SELECT id FROM sales_order_lines WHERE order_id IN (SELECT id FROM e2e_so));
DELETE FROM sales_order_lines WHERE order_id IN (SELECT id FROM e2e_so);
DELETE FROM sales_orders WHERE id IN (SELECT id FROM e2e_so);

-- V2-2 采购闭环：先清收货/应付/退货，再删进货行（po_receipt_lines 引用 purchase_order_lines）
DELETE FROM payable_allocations
 WHERE supplier_id IN (SELECT id FROM e2e_suppliers)
    OR payable_id IN (SELECT id FROM supplier_payables WHERE purchase_order_id IN (SELECT id FROM e2e_po));
DELETE FROM supplier_payables
 WHERE purchase_order_id IN (SELECT id FROM e2e_po)
    OR receipt_id IN (SELECT id FROM po_receipts WHERE purchase_order_id IN (SELECT id FROM e2e_po));
DELETE FROM purchase_returns WHERE purchase_order_id IN (SELECT id FROM e2e_po);
DELETE FROM po_receipt_lines
 WHERE purchase_order_line_id IN (SELECT id FROM purchase_order_lines WHERE order_id IN (SELECT id FROM e2e_po))
    OR receipt_id IN (SELECT id FROM po_receipts WHERE purchase_order_id IN (SELECT id FROM e2e_po));
DELETE FROM po_receipts WHERE purchase_order_id IN (SELECT id FROM e2e_po);

DELETE FROM purchase_order_lines WHERE order_id IN (SELECT id FROM e2e_po);
DELETE FROM purchase_orders WHERE id IN (SELECT id FROM e2e_po);

DELETE FROM daily_settlements WHERE id IN (SELECT id FROM e2e_daily);
DELETE FROM settlement_periods WHERE period IN (SELECT period FROM e2e_periods);

-- 取号行不是业务数据，但会计入基线核对。波 A 之后前缀远不止 PO/SO/RO
-- （TO/ST/AJ/PR/GR/AP/PY/RT/QT/SH/RC…），逐个列举一定会漏；改为
-- "本轮触碰过的日期 + 今天，且当天已无任何单据" 时通配删除。
DELETE FROM document_sequences ds
 WHERE ds.day_key IN (
     SELECT to_char(biz_date, 'YYYYMMDD') FROM e2e_po
     UNION SELECT to_char(biz_date, 'YYYYMMDD') FROM e2e_so
     UNION SELECT to_char(biz_date, 'YYYYMMDD') FROM e2e_ro
     UNION SELECT to_char(CURRENT_DATE, 'YYYYMMDD')
   )
   AND NOT EXISTS (SELECT 1 FROM purchase_orders po WHERE to_char(po.biz_date, 'YYYYMMDD') = ds.day_key)
   AND NOT EXISTS (SELECT 1 FROM sales_orders so WHERE to_char(so.biz_date, 'YYYYMMDD') = ds.day_key)
   AND NOT EXISTS (SELECT 1 FROM return_orders ro WHERE to_char(ro.biz_date, 'YYYYMMDD') = ds.day_key);

DELETE FROM inventory_ledger
 WHERE sku_id IN (SELECT id FROM e2e_skus)
    OR warehouse_id IN (SELECT id FROM e2e_warehouses)
    OR biz_ref_no IN (SELECT order_no FROM e2e_order_nos);
DELETE FROM inventory_reservations
 WHERE sku_id IN (SELECT id FROM e2e_skus)
    OR warehouse_id IN (SELECT id FROM e2e_warehouses)
    OR biz_ref_no IN (SELECT order_no FROM e2e_order_nos);
DELETE FROM inventory_balances
 WHERE sku_id IN (SELECT id FROM e2e_skus)
    OR warehouse_id IN (SELECT id FROM e2e_warehouses);

DELETE FROM idempotency_keys WHERE request_id IN (SELECT request_id FROM e2e_request_ids);

DELETE FROM app_user_roles
 WHERE user_id IN (SELECT id FROM app_users WHERE username IN (${users}));
DELETE FROM app_users WHERE username IN (${users});

DELETE FROM skus WHERE product_id IN (SELECT id FROM e2e_products);
DELETE FROM products WHERE id IN (SELECT id FROM e2e_products);
DELETE FROM suppliers WHERE id IN (SELECT id FROM e2e_suppliers);
DELETE FROM customers WHERE id IN (SELECT id FROM e2e_customers);
DELETE FROM warehouses WHERE id IN (SELECT id FROM e2e_warehouses);

DO $$
DECLARE n bigint;
BEGIN
  SELECT count(*) INTO n FROM inventory_ledger
   WHERE biz_ref_no IN (SELECT order_no FROM e2e_order_nos);
  IF n <> 0 THEN RAISE EXCEPTION 'E2E 清理后仍有 % 条流水', n; END IF;

  SELECT count(*) INTO n FROM inventory_reservations
   WHERE biz_ref_no IN (SELECT order_no FROM e2e_order_nos);
  IF n <> 0 THEN RAISE EXCEPTION 'E2E 清理后仍有 % 条预占', n; END IF;

  SELECT count(*) INTO n FROM inventory_balances
   WHERE sku_id IN (SELECT id FROM e2e_skus);
  IF n <> 0 THEN RAISE EXCEPTION 'E2E 清理后仍有 % 条库存', n; END IF;

  SELECT count(*) INTO n FROM purchase_orders WHERE id IN (SELECT id FROM e2e_po);
  IF n <> 0 THEN RAISE EXCEPTION 'E2E 清理后仍有 % 张进货单', n; END IF;

  SELECT count(*) INTO n FROM sales_orders WHERE id IN (SELECT id FROM e2e_so);
  IF n <> 0 THEN RAISE EXCEPTION 'E2E 清理后仍有 % 张出货单', n; END IF;

  SELECT count(*) INTO n FROM return_order_lines WHERE order_id IN (SELECT id FROM e2e_ro);
  IF n <> 0 THEN RAISE EXCEPTION 'E2E 清理后仍有 % 条退货明细', n; END IF;

  SELECT count(*) INTO n FROM return_orders WHERE id IN (SELECT id FROM e2e_ro);
  IF n <> 0 THEN RAISE EXCEPTION 'E2E 清理后仍有 % 张退货单', n; END IF;

  SELECT count(*) INTO n FROM daily_settlements WHERE id IN (SELECT id FROM e2e_daily);
  IF n <> 0 THEN RAISE EXCEPTION 'E2E 清理后仍有 % 条日清行', n; END IF;

  SELECT count(*) INTO n FROM settlement_periods
   WHERE period IN (SELECT period FROM e2e_periods);
  IF n <> 0 THEN RAISE EXCEPTION 'E2E 清理后仍有 % 个账期（锁账残留会让下一轮建单被拒）', n; END IF;

  SELECT count(*) INTO n FROM skus WHERE product_id IN (SELECT id FROM e2e_products);
  IF n <> 0 THEN RAISE EXCEPTION 'E2E 清理后仍有 % 个 SKU', n; END IF;

  SELECT count(*) INTO n FROM products WHERE code LIKE ${prefix};
  IF n <> 0 THEN RAISE EXCEPTION 'E2E 清理后仍有 % 个商品', n; END IF;

  SELECT count(*) INTO n FROM suppliers WHERE code LIKE ${prefix};
  IF n <> 0 THEN RAISE EXCEPTION 'E2E 清理后仍有 % 个供应商', n; END IF;

  SELECT count(*) INTO n FROM customers WHERE code LIKE ${prefix};
  IF n <> 0 THEN RAISE EXCEPTION 'E2E 清理后仍有 % 个客户', n; END IF;

  SELECT count(*) INTO n FROM warehouses WHERE code LIKE ${prefix};
  IF n <> 0 THEN RAISE EXCEPTION 'E2E 清理后仍有 % 个仓库', n; END IF;

  SELECT count(*) INTO n FROM app_users WHERE username IN (${users});
  IF n <> 0 THEN RAISE EXCEPTION 'E2E 清理后仍残留临时账号'; END IF;

  -- 反向校验：既有基线数据必须原样还在。
  -- ★ **这里不断言 e2e-reserve-001 的绝对条数**：它是共享开发库里手工留下的夹具
  --   （M3 记的"10-19 基线"），不在任何迁移或脚本里，全新数据库里就是 0 条。
  --   可移植的不变量是"本轮没动过它"，由 baseline_reservation 与
  --   baseline_reservation_state 两个基线键在 global-teardown 里逐字对比来保证。
  SELECT count(*) INTO n FROM settlement_periods
   WHERE split_part(period, '-', 1) = '${RESERVED_YEAR}';
  IF n <> 0 THEN RAISE EXCEPTION '保留账期 ${RESERVED_YEAR} 应清空，实际仍锁着 % 个期间', n; END IF;
END $$;

COMMIT;
`.trim()
}

/** 清理后的兜底核对（即使上面的 DO 块通过，也再由 JS 断一次）。 */
export function remainingSql(run: RunInfo): string {
  assertSafeRun(run)
  const prefix = sqlString(`${run.runId}-%`)
  const users = userList(run)
  return `
SELECT 'products', count(*) FROM products WHERE code LIKE ${prefix}
UNION ALL SELECT 'skus', count(*) FROM skus s
  JOIN products p ON p.id = s.product_id WHERE p.code LIKE ${prefix}
UNION ALL SELECT 'suppliers', count(*) FROM suppliers WHERE code LIKE ${prefix}
UNION ALL SELECT 'customers', count(*) FROM customers WHERE code LIKE ${prefix}
UNION ALL SELECT 'warehouses', count(*) FROM warehouses WHERE code LIKE ${prefix}
UNION ALL SELECT 'temp_users', count(*) FROM app_users WHERE username IN (${users})
UNION ALL SELECT 'reserved_periods', count(*) FROM settlement_periods
  WHERE split_part(period, '-', 1) = '${RESERVED_YEAR}'
UNION ALL SELECT 'reserved_daily', count(*) FROM daily_settlements
  WHERE EXTRACT(YEAR FROM biz_date) = ${RESERVED_YEAR}
ORDER BY 1;
`.trim()
}
