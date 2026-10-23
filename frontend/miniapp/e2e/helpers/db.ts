import { spawnSync } from 'node:child_process'
import path from 'node:path'
import { repoRoot } from './paths'
import type { RunInfo } from './run'

/**
 * E2E 自有数据库：容器 `erp-m4b-pg`（宿主 15441）。
 * 所有清理只按本轮前缀 E2B-<runId> 及其派生数据，不按时间窗口、不碰共享库 15432。
 */

export const DB_CONTAINER = 'erp-m4b-pg'
export const DB_PORT = 15441
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

/** 清理前的行数快照（用于报告与"前缀残留必须为 0"）。 */
function countSql(run: RunInfo): string {
  const like = `E2B-${run.runId}%`
  return `
SELECT 'products', count(*)::text FROM products WHERE code LIKE '${like}'
UNION ALL SELECT 'skus', count(*)::text FROM skus WHERE product_id IN (SELECT id FROM products WHERE code LIKE '${like}')
UNION ALL SELECT 'suppliers', count(*)::text FROM suppliers WHERE code LIKE '${like}'
UNION ALL SELECT 'customers', count(*)::text FROM customers WHERE code LIKE '${like}'
UNION ALL SELECT 'warehouses', count(*)::text FROM warehouses WHERE code LIKE '${like}'
UNION ALL SELECT 'ledger', count(*)::text FROM inventory_ledger
  WHERE sku_id IN (SELECT id FROM skus WHERE product_id IN (SELECT id FROM products WHERE code LIKE '${like}'))
     OR warehouse_id IN (SELECT id FROM warehouses WHERE code LIKE '${like}')
UNION ALL SELECT 'reservations', count(*)::text FROM inventory_reservations
  WHERE sku_id IN (SELECT id FROM skus WHERE product_id IN (SELECT id FROM products WHERE code LIKE '${like}'))
     OR warehouse_id IN (SELECT id FROM warehouses WHERE code LIKE '${like}')
UNION ALL SELECT 'balances', count(*)::text FROM inventory_balances
  WHERE sku_id IN (SELECT id FROM skus WHERE product_id IN (SELECT id FROM products WHERE code LIKE '${like}'))
     OR warehouse_id IN (SELECT id FROM warehouses WHERE code LIKE '${like}')
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
ORDER BY 1;

DELETE FROM sales_order_line_reservations
 WHERE line_id IN (SELECT id FROM sales_order_lines WHERE order_id IN (SELECT id FROM e2e_so));
DELETE FROM sales_order_lines WHERE order_id IN (SELECT id FROM e2e_so);
DELETE FROM sales_orders WHERE id IN (SELECT id FROM e2e_so);

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
