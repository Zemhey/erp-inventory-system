import type { RunInfo } from './run'
import { sqlString } from './db'

/**
 * E2E 清理 SQL。
 *
 * 所有条件都只认本次 runId 的唯一前缀（主数据）或由这些主数据派生出的单据/库存，
 * 不按时间窗口、不按 id 范围删除 —— 既有预占 e2e-reserve-001 与开发基线不会被碰到。
 */

function assertSafeRun(run: RunInfo): void {
  if (!/^E2E-[A-Z0-9]+-[A-Z0-9]+$/.test(run.runId)) {
    throw new Error(`runId 格式异常，拒绝执行清理 SQL：${run.runId}`)
  }
  if (!/^[a-z0-9-]+$/.test(run.viewerUsername)) {
    throw new Error(`VIEWER 用户名格式异常，拒绝执行清理 SQL：${run.viewerUsername}`)
  }
}

export function baselineSql(): string {
  return `
SELECT 'products', count(*) FROM products
UNION ALL SELECT 'skus', count(*) FROM skus
UNION ALL SELECT 'suppliers', count(*) FROM suppliers
UNION ALL SELECT 'customers', count(*) FROM customers
UNION ALL SELECT 'warehouses', count(*) FROM warehouses
UNION ALL SELECT 'balances', count(*) FROM inventory_balances
UNION ALL SELECT 'ledger', count(*) FROM inventory_ledger
UNION ALL SELECT 'reservations', count(*) FROM inventory_reservations
UNION ALL SELECT 'purchase_orders', count(*) FROM purchase_orders
UNION ALL SELECT 'sales_orders', count(*) FROM sales_orders
UNION ALL SELECT 'line_reservations', count(*) FROM sales_order_line_reservations
UNION ALL SELECT 'sequences', count(*) FROM document_sequences
UNION ALL SELECT 'users', count(*) FROM app_users
UNION ALL SELECT 'user_roles', count(*) FROM app_user_roles
UNION ALL SELECT 'baseline_reservation', count(*) FROM inventory_reservations WHERE biz_ref_no = 'e2e-reserve-001'
ORDER BY 1;
`.trim()
}

export function createViewerSql(viewerUsername: string, adminUsername: string): string {
  const viewer = sqlString(viewerUsername)
  const admin = sqlString(adminUsername)
  return `
INSERT INTO app_users (username, password_hash, display_name)
SELECT ${viewer}, password_hash, 'E2E Viewer'
  FROM app_users WHERE username = ${admin}
ON CONFLICT (username) DO NOTHING;

INSERT INTO app_user_roles (user_id, role_id)
SELECT u.id, r.id
  FROM app_users u, app_roles r
 WHERE u.username = ${viewer} AND r.code = 'VIEWER'
ON CONFLICT DO NOTHING;

SELECT (SELECT count(*) FROM app_users WHERE username = ${viewer}),
       (SELECT count(*) FROM app_user_roles ur
          JOIN app_users u ON u.id = ur.user_id
          JOIN app_roles r ON r.id = ur.role_id
         WHERE u.username = ${viewer} AND r.code = 'VIEWER');
`.trim()
}

export function cleanupSql(run: RunInfo): string {
  assertSafeRun(run)
  const prefix = sqlString(`${run.runId}-%`)
  const viewer = sqlString(run.viewerUsername)

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

CREATE TEMP TABLE e2e_order_nos ON COMMIT DROP AS
  SELECT order_no FROM e2e_po UNION SELECT order_no FROM e2e_so;

CREATE TEMP TABLE e2e_request_ids ON COMMIT DROP AS
  SELECT request_id FROM e2e_po WHERE request_id IS NOT NULL
  UNION SELECT request_id FROM e2e_so WHERE request_id IS NOT NULL
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
UNION ALL SELECT 'products', count(*) FROM e2e_products
UNION ALL SELECT 'skus', count(*) FROM e2e_skus
UNION ALL SELECT 'suppliers', count(*) FROM e2e_suppliers
UNION ALL SELECT 'customers', count(*) FROM e2e_customers
UNION ALL SELECT 'warehouses', count(*) FROM e2e_warehouses
ORDER BY 1;

DELETE FROM sales_order_line_reservations
 WHERE line_id IN (SELECT id FROM sales_order_lines WHERE order_id IN (SELECT id FROM e2e_so));
DELETE FROM sales_order_lines WHERE order_id IN (SELECT id FROM e2e_so);
DELETE FROM sales_orders WHERE id IN (SELECT id FROM e2e_so);

DELETE FROM purchase_order_lines WHERE order_id IN (SELECT id FROM e2e_po);
DELETE FROM purchase_orders WHERE id IN (SELECT id FROM e2e_po);

-- 取号行不是业务数据，但也会变化；只有当天已无任何单据时才删（避免重置后撞号）
DELETE FROM document_sequences ds
 WHERE ds.doc_prefix = 'PO'
   AND ds.day_key IN (SELECT to_char(biz_date, 'YYYYMMDD') FROM e2e_po)
   AND NOT EXISTS (
     SELECT 1 FROM purchase_orders po
      WHERE to_char(po.biz_date, 'YYYYMMDD') = ds.day_key
   );
DELETE FROM document_sequences ds
 WHERE ds.doc_prefix = 'SO'
   AND ds.day_key IN (SELECT to_char(biz_date, 'YYYYMMDD') FROM e2e_so)
   AND NOT EXISTS (
     SELECT 1 FROM sales_orders so
      WHERE to_char(so.biz_date, 'YYYYMMDD') = ds.day_key
   );

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
 WHERE user_id IN (SELECT id FROM app_users WHERE username = ${viewer});
DELETE FROM app_users WHERE username = ${viewer};

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

  SELECT count(*) INTO n FROM app_users WHERE username = ${viewer};
  IF n <> 0 THEN RAISE EXCEPTION 'E2E 清理后仍有 VIEWER 账号'; END IF;
END $$;

COMMIT;
`.trim()
}

/** 清理后的兜底核对（即使上面的 DO 块通过，也再由 JS 断一次）。 */
export function remainingSql(run: RunInfo): string {
  assertSafeRun(run)
  const prefix = sqlString(`${run.runId}-%`)
  const viewer = sqlString(run.viewerUsername)
  return `
SELECT 'products', count(*) FROM products WHERE code LIKE ${prefix}
UNION ALL SELECT 'skus', count(*) FROM skus s
  JOIN products p ON p.id = s.product_id WHERE p.code LIKE ${prefix}
UNION ALL SELECT 'suppliers', count(*) FROM suppliers WHERE code LIKE ${prefix}
UNION ALL SELECT 'customers', count(*) FROM customers WHERE code LIKE ${prefix}
UNION ALL SELECT 'warehouses', count(*) FROM warehouses WHERE code LIKE ${prefix}
UNION ALL SELECT 'viewer', count(*) FROM app_users WHERE username = ${viewer}
ORDER BY 1;
`.trim()
}
