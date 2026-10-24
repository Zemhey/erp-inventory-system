import { spawnSync } from 'node:child_process'
import path from 'node:path'
import { repoRoot } from './paths'
import type { RunInfo } from './run'

/**
 * E2E 自有数据库：容器与端口默认 `erp-m4b-pg` / 15441，可用环境变量覆盖。
 * 所有清理只按本轮前缀 E2B-<runId> 及其派生数据，不按时间窗口、不碰共享库 15432。
 *
 * ★ 覆盖是并行泳道的硬要求：两条 E2E 同时打 15441 会互删对方的前缀数据。
 *   每条泳道用自己的容器（E2E_DB_CONTAINER）+ 自己的宿主端口（E2E_DB_PORT），
 *   端口还要和后端连接的 DB_URL 一致，否则「库是空的」这种假故障最难查。
 */

export const DB_CONTAINER = process.env.E2E_DB_CONTAINER ?? 'erp-m4b-pg'
export const DB_PORT = Number(process.env.E2E_DB_PORT ?? 15441)
export const DB_NAME = 'erp'
export const DB_USER = 'erp'
export const DB_PASSWORD = 'erp'

const DOCKER_TIMEOUT_MS = 120_000

function docker(args: string[], input?: string) {
  const result = spawnSync('docker', args, {
    encoding: 'utf8',
    timeout: DOCKER_TIMEOUT_MS,
    input,
    maxBuffer: 16 * 1024 * 1024,
  })
  if (result.error) {
    throw new Error(`docker ${args.join(' ')} 失败：${result.error.message}`)
  }
  return result
}

/** 执行 SQL 并把 stdout 原样返回（psql -At：无表头、行内字段用 tab 分隔）。 */
export function psql(sql: string): string {
  const result = docker([
    'exec',
    DB_CONTAINER,
    'psql',
    '-U',
    DB_USER,
    '-d',
    DB_NAME,
    '-v',
    'ON_ERROR_STOP=1',
    '-A',
    '-t',
    '-F',
    '\t',
    '-c',
    sql,
  ])
  if (result.status !== 0) {
    throw new Error(
      `psql 执行失败（exit ${result.status}）：\n${result.stderr || result.stdout || sql}`,
    )
  }
  return result.stdout
}

function scalar(sql: string): string {
  return psql(sql).trim()
}

/** 生成与后端同一个 BCryptPasswordEncoder(10) 的哈希（不加盐、不手写哈希）。 */
function bcryptHash(password: string): string {
  const script = path.join(repoRoot, 'scripts', 'bcrypt-hash.ps1')
  const result = spawnSync(
    'powershell.exe',
    ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', script, '-Password', password],
    { encoding: 'utf8', timeout: 180_000, maxBuffer: 4 * 1024 * 1024 },
  )
  if (result.error) {
    throw new Error(`生成 BCrypt 哈希失败：${result.error.message}`)
  }
  if (result.status !== 0) {
    throw new Error(`生成 BCrypt 哈希失败：${result.stderr || result.stdout}`)
  }
  const hash = (result.stdout || '')
    .split(/\r?\n/)
    .map((line) => line.trim())
    .find((line) => /^\$2[aby]\$/.test(line))
  if (!hash) {
    throw new Error(`bcrypt-hash.ps1 未输出合法哈希：${result.stdout}`)
  }
  return hash
}

/**
 * 确保自有库里有一个可登录的 admin（V3 迁移刻意不预置账号）。
 * 每轮开始重置失败计数，避免上一轮"错误密码"用例把账号锁住。
 */
export function ensureAdmin(): void {
  const existing = Number(scalar("SELECT count(*) FROM app_users WHERE username = 'admin'"))
  if (existing === 0) {
    const hash = bcryptHash('Admin@12345')
    psql(`
INSERT INTO app_users (username, password_hash, display_name, enabled)
VALUES ('admin', '${hash}', 'Admin', TRUE)
ON CONFLICT (username) DO NOTHING;
`)
  }

  psql(`
INSERT INTO app_user_roles (user_id, role_id)
SELECT u.id, r.id FROM app_users u, app_roles r
 WHERE u.username = 'admin' AND r.code = 'ADMIN'
ON CONFLICT DO NOTHING;

UPDATE app_users
   SET failed_attempts = 0, locked_until = NULL, updated_at = now()
 WHERE username = 'admin';
`)
}

const BASELINE_TABLES = [
  'products',
  'skus',
  'suppliers',
  'customers',
  'warehouses',
  'inventory_balances',
  'inventory_ledger',
  'inventory_reservations',
  'purchase_orders',
  'purchase_order_lines',
  'sales_orders',
  'sales_order_lines',
  'sales_order_line_reservations',
  'app_users',
  'app_user_roles',
  // ★ V2-D1：报价 / 发货 / 应收 / 收款 / 审批都新增行，全部纳入基线回归。
  //   只把「删除语句写了但没进基线」的表当透明，等于给泄漏留口子 ——
  //   计数回到基线这条硬校验必须覆盖本轮真正会写的所有业务表。
  'sales_quotes',
  'sales_quote_lines',
  'so_shipment_plans',
  'so_shipments',
  'shipment_lines',
  'customer_receivables',
  'customer_receipts',
  'receivable_allocations',
  'customer_credit_snapshots',
  'approval_instances',
  'approval_steps',
] as const

export function readBaseline(): Record<string, number> {
  const sql = BASELINE_TABLES.map(
    (table) => `SELECT '${table}', count(*)::text FROM ${table}`,
  ).join('\nUNION ALL\n')
  const output = psql(`${sql}\nORDER BY 1;`)
  const baseline: Record<string, number> = {}
  for (const line of output.split(/\r?\n/)) {
    if (!line.trim()) continue
    const [key, value] = line.split('\t')
    if (key) baseline[key] = Number(value)
  }
  for (const table of BASELINE_TABLES) {
    if (!(table in baseline)) throw new Error(`基线缺少表 ${table} 的计数`)
  }
  return baseline
}

function assertSafeRun(run: RunInfo): void {
  if (!/^M4B[A-Z0-9]+$/.test(run.runId)) {
    throw new Error(`runId 格式异常，拒绝执行清理 SQL：${run.runId}`)
  }
  if (run.prefix !== `E2B-${run.runId}`) {
    throw new Error(`前缀与 runId 不一致，拒绝执行清理 SQL：${run.prefix}`)
  }
}

/**
 * 清理前的行数快照（用于报告与"前缀残留必须为 0"）。
 *
 * ★ 这些子查询**不能**复用 cleanupRun 里的 e2e_* 临时表：temp 表是 ON COMMIT DROP，
 *   而计数是另一次 psql 调用（独立事务），那时它们已经不存在了。
 *   所以每一项都从主数据 code 前缀现推一遍 —— 看着重复，但重复的是同一套口径。
 */
function countSql(run: RunInfo): string {
  const like = `E2B-${run.runId}%`
  const productIds = `(SELECT id FROM products WHERE code LIKE '${like}')`
  const skuIds = `(SELECT id FROM skus WHERE product_id IN ${productIds})`
  const customerIds = `(SELECT id FROM customers WHERE code LIKE '${like}')`
  const supplierIds = `(SELECT id FROM suppliers WHERE code LIKE '${like}')`
  const warehouseIds = `(SELECT id FROM warehouses WHERE code LIKE '${like}')`
  const soIds = `(SELECT id FROM sales_orders WHERE customer_id IN ${customerIds}
                    OR warehouse_id IN ${warehouseIds}
                    OR id IN (SELECT order_id FROM sales_order_lines WHERE sku_id IN ${skuIds}))`
  const poIds = `(SELECT id FROM purchase_orders WHERE supplier_id IN ${supplierIds}
                    OR warehouse_id IN ${warehouseIds}
                    OR id IN (SELECT order_id FROM purchase_order_lines WHERE sku_id IN ${skuIds}))`
  const orderNos = `(SELECT order_no FROM sales_orders WHERE id IN ${soIds}
                     UNION SELECT order_no FROM purchase_orders WHERE id IN ${poIds})`
  const quoteIds = `(SELECT id FROM sales_quotes WHERE customer_id IN ${customerIds}
                      OR converted_order_id IN ${soIds}
                      OR id IN (SELECT quote_id FROM sales_quote_lines WHERE sku_id IN ${skuIds}))`
  const planIds = `(SELECT id FROM so_shipment_plans WHERE sales_order_id IN ${soIds})`
  const shipmentIds = `(SELECT id FROM so_shipments WHERE sales_order_id IN ${soIds}
                        OR warehouse_id IN ${warehouseIds}
                        OR plan_id IN ${planIds})`
  const receivableIds = `(SELECT id FROM customer_receivables WHERE customer_id IN ${customerIds}
                          OR sales_order_id IN ${soIds})`
  const receiptIds = `(SELECT id FROM customer_receipts WHERE customer_id IN ${customerIds})`
  const approvalIds = `(SELECT id FROM approval_instances
                        WHERE initiator_id IN (SELECT id FROM app_users WHERE username LIKE '${like}')
                        OR request_id LIKE '${like}'
                        OR biz_ref_no IN ${orderNos})`

  return `
SELECT 'products', count(*)::text FROM products WHERE code LIKE '${like}'
UNION ALL SELECT 'skus', count(*)::text FROM skus WHERE product_id IN ${productIds}
UNION ALL SELECT 'suppliers', count(*)::text FROM suppliers WHERE code LIKE '${like}'
UNION ALL SELECT 'customers', count(*)::text FROM customers WHERE code LIKE '${like}'
UNION ALL SELECT 'warehouses', count(*)::text FROM warehouses WHERE code LIKE '${like}'
UNION ALL SELECT 'ledger', count(*)::text FROM inventory_ledger
  WHERE sku_id IN ${skuIds} OR warehouse_id IN ${warehouseIds}
UNION ALL SELECT 'reservations', count(*)::text FROM inventory_reservations
  WHERE sku_id IN ${skuIds} OR warehouse_id IN ${warehouseIds}
UNION ALL SELECT 'balances', count(*)::text FROM inventory_balances
  WHERE sku_id IN ${skuIds} OR warehouse_id IN ${warehouseIds}
UNION ALL SELECT 'quotes', count(*)::text FROM sales_quotes WHERE id IN ${quoteIds}
UNION ALL SELECT 'quoteLines', count(*)::text FROM sales_quote_lines
  WHERE quote_id IN ${quoteIds} OR sku_id IN ${skuIds}
UNION ALL SELECT 'plans', count(*)::text FROM so_shipment_plans WHERE id IN ${planIds}
UNION ALL SELECT 'shipments', count(*)::text FROM so_shipments WHERE id IN ${shipmentIds}
UNION ALL SELECT 'shipmentLines', count(*)::text FROM shipment_lines
  WHERE shipment_id IN ${shipmentIds} OR sku_id IN ${skuIds}
UNION ALL SELECT 'receivables', count(*)::text FROM customer_receivables WHERE id IN ${receivableIds}
UNION ALL SELECT 'receipts', count(*)::text FROM customer_receipts WHERE id IN ${receiptIds}
UNION ALL SELECT 'allocations', count(*)::text FROM receivable_allocations
  WHERE receipt_id IN ${receiptIds} OR receivable_id IN ${receivableIds}
UNION ALL SELECT 'creditSnapshots', count(*)::text FROM customer_credit_snapshots
  WHERE customer_id IN ${customerIds} OR sales_order_id IN ${soIds}
UNION ALL SELECT 'approvals', count(*)::text FROM approval_instances WHERE id IN ${approvalIds}
UNION ALL SELECT 'approvalSteps', count(*)::text FROM approval_steps
  WHERE instance_id IN ${approvalIds}
UNION ALL SELECT 'users', count(*)::text FROM app_users WHERE username LIKE '${like}'
ORDER BY 1;
`.trim()
}

export interface CleanupResult {
  summary: string
  remaining: Record<string, number>
}

/** 清理后必须逐项归零的表；少一项就说明计数查询本身没跑通，不能当作"没有残留"。 */
const RESIDUE_KEYS = [
  'products',
  'skus',
  'suppliers',
  'customers',
  'warehouses',
  'ledger',
  'reservations',
  'balances',
  'quotes',
  'quoteLines',
  'plans',
  'shipments',
  'shipmentLines',
  'receivables',
  'receipts',
  'allocations',
  'creditSnapshots',
  'approvals',
  'approvalSteps',
  'users',
] as const

export function cleanupRun(run: RunInfo): CleanupResult {
  assertSafeRun(run)
  const like = `E2B-${run.runId}%`
  const sql = `
BEGIN;

CREATE TEMP TABLE e2e_products ON COMMIT DROP AS
  SELECT id FROM products WHERE code LIKE '${like}';
CREATE TEMP TABLE e2e_skus ON COMMIT DROP AS
  SELECT id FROM skus WHERE product_id IN (SELECT id FROM e2e_products);
CREATE TEMP TABLE e2e_suppliers ON COMMIT DROP AS
  SELECT id FROM suppliers WHERE code LIKE '${like}';
CREATE TEMP TABLE e2e_customers ON COMMIT DROP AS
  SELECT id FROM customers WHERE code LIKE '${like}';
CREATE TEMP TABLE e2e_warehouses ON COMMIT DROP AS
  SELECT id FROM warehouses WHERE code LIKE '${like}';

CREATE TEMP TABLE e2e_po ON COMMIT DROP AS
  SELECT o.id, o.order_no
    FROM purchase_orders o
   WHERE o.supplier_id IN (SELECT id FROM e2e_suppliers)
      OR o.warehouse_id IN (SELECT id FROM e2e_warehouses)
      OR EXISTS (SELECT 1 FROM purchase_order_lines l
                  WHERE l.order_id = o.id AND l.sku_id IN (SELECT id FROM e2e_skus));
CREATE TEMP TABLE e2e_so ON COMMIT DROP AS
  SELECT o.id, o.order_no
    FROM sales_orders o
   WHERE o.customer_id IN (SELECT id FROM e2e_customers)
      OR o.warehouse_id IN (SELECT id FROM e2e_warehouses)
      OR EXISTS (SELECT 1 FROM sales_order_lines l
                  WHERE l.order_id = o.id AND l.sku_id IN (SELECT id FROM e2e_skus));

CREATE TEMP TABLE e2e_order_nos ON COMMIT DROP AS
  SELECT order_no FROM e2e_po UNION SELECT order_no FROM e2e_so;

-- V2-D1：造数会建两个临时身份（只读 / 接办），审批实例的发起人外键指向 app_users，
--   所以「审批」必须排在「删用户」之前；approval_steps 虽有 ON DELETE CASCADE，
--   这里仍显式删一遍 —— 删除计数要能如实报出本轮清掉了多少节点行。
CREATE TEMP TABLE e2e_users ON COMMIT DROP AS
  SELECT id FROM app_users WHERE username LIKE '${like}';
CREATE TEMP TABLE e2e_approvals ON COMMIT DROP AS
  SELECT id FROM approval_instances
   WHERE initiator_id IN (SELECT id FROM e2e_users)
      OR biz_ref_no IN (SELECT order_no FROM e2e_order_nos)
      OR request_id LIKE '${like}';

CREATE TEMP TABLE e2e_ledger ON COMMIT DROP AS
  SELECT id FROM inventory_ledger
   WHERE sku_id IN (SELECT id FROM e2e_skus)
      OR warehouse_id IN (SELECT id FROM e2e_warehouses)
      OR biz_ref_no IN (SELECT order_no FROM e2e_order_nos);
CREATE TEMP TABLE e2e_reservations ON COMMIT DROP AS
  SELECT id FROM inventory_reservations
   WHERE sku_id IN (SELECT id FROM e2e_skus)
      OR warehouse_id IN (SELECT id FROM e2e_warehouses)
      OR biz_ref_no IN (SELECT order_no FROM e2e_order_nos);
CREATE TEMP TABLE e2e_balances ON COMMIT DROP AS
  SELECT id FROM inventory_balances
   WHERE sku_id IN (SELECT id FROM e2e_skus)
      OR warehouse_id IN (SELECT id FROM e2e_warehouses);

SELECT 'po', count(*) FROM e2e_po
UNION ALL SELECT 'so', count(*) FROM e2e_so
UNION ALL SELECT 'products', count(*) FROM e2e_products
UNION ALL SELECT 'skus', count(*) FROM e2e_skus
UNION ALL SELECT 'suppliers', count(*) FROM e2e_suppliers
UNION ALL SELECT 'customers', count(*) FROM e2e_customers
UNION ALL SELECT 'warehouses', count(*) FROM e2e_warehouses
UNION ALL SELECT 'ledger', count(*) FROM e2e_ledger
UNION ALL SELECT 'reservations', count(*) FROM e2e_reservations
UNION ALL SELECT 'balances', count(*) FROM e2e_balances
UNION ALL SELECT 'approvals', count(*) FROM e2e_approvals
UNION ALL SELECT 'users', count(*) FROM e2e_users
ORDER BY 1;

-- V2-D1：审批实例与节点行先删（initiator_id 外键指向 app_users，删完才动账号）。
DELETE FROM approval_steps
 WHERE instance_id IN (SELECT id FROM e2e_approvals);
DELETE FROM approval_instances
 WHERE id IN (SELECT id FROM e2e_approvals);

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

DELETE FROM inventory_ledger WHERE id IN (SELECT id FROM e2e_ledger);
DELETE FROM inventory_reservations WHERE id IN (SELECT id FROM e2e_reservations);
DELETE FROM inventory_balances WHERE id IN (SELECT id FROM e2e_balances);

DELETE FROM skus WHERE id IN (SELECT id FROM e2e_skus);
DELETE FROM products WHERE id IN (SELECT id FROM e2e_products);
DELETE FROM suppliers WHERE id IN (SELECT id FROM e2e_suppliers);
DELETE FROM customers WHERE id IN (SELECT id FROM e2e_customers);
DELETE FROM warehouses WHERE id IN (SELECT id FROM e2e_warehouses);

-- 临时账号最后删：app_user_roles / data_scopes 是 ON DELETE CASCADE，
-- approval_instances 已在上面清掉，initiator_id 这条非级联外键才不会拦住删除。
-- ★ 只按 username 前缀删，admin 与其他泳道/保留账号一律不动。
DELETE FROM app_users WHERE id IN (SELECT id FROM e2e_users);

COMMIT;
`.trim()

  const output = psql(sql)
  const summary = output
    .split(/\r?\n/)
    .filter((line) => line.includes('\t'))
    .map((line) => {
      const [key, value] = line.split('\t')
      return `${key}=${value}`
    })
    .join(' ')

  // ★ 这里曾经把 SQL 文本本身喂给 parseCounts（少写一次 psql 调用），
  //   结果是 remaining 恒为 {}、"前缀残留必须为 0"变成一条永远不会红的门禁。
  //   现在既执行，又要求每一项计数都在，缺项直接报错而不是当作干净。
  const remaining = parseCounts(psql(countSql(run)))
  const missing = RESIDUE_KEYS.filter((key) => !(key in remaining))
  if (missing.length) {
    throw new Error(`[e2e] 残留计数查询缺少 ${missing.join('、')}，无法判定清理是否干净`)
  }
  return { summary, remaining }
}

function parseCounts(sqlOutput: string): Record<string, number> {
  const counts: Record<string, number> = {}
  for (const line of sqlOutput.split(/\r?\n/)) {
    if (!line.includes('\t')) continue
    const [key, value] = line.split('\t')
    if (key) counts[key] = Number(value)
  }
  return counts
}
