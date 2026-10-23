/** 每轮 E2E 的唯一前缀与运行前基线，供 teardown 做硬校验。 */
export interface RunInfo {
  runId: string
  /** 所有可清理主数据的 code 前缀（E2B-<runId>）。 */
  prefix: string
  startedAt: string
  /** 业务表行数基线（不含会自然增长的日志表）。 */
  baseline: Record<string, number>
}

export interface SeedOrder {
  id: number
  orderNo: string
  status: string
  totalQty: number
  lineCount: number
}

export interface SeedData {
  runId: string
  prefix: string
  admin: {
    username: string
    displayName: string
    roles: string[]
    token: string
    permissions: string[]
  }
  stockWarehouse: { id: number; code: string; name: string }
  actionWarehouse: { id: number; code: string; name: string }
  supplier: { id: number; code: string; name: string }
  customer: { id: number; code: string; name: string }
  product: { id: number; code: string; name: string }
  skus: Array<{ id: number; color: string; size: string; barcode: string }>
  batchStock: string
  batchAction: string
  poStock: SeedOrder
  poAction: SeedOrder
  poReceive: SeedOrder
  soAction: SeedOrder
  expected: {
    stockBalanceRows: number
    stockOnHand: number
    stockAvailable: number
    stockLedgerRows: number
    productSkuCount: number
    poReceiveOpenQty: number
    soActionQty: number
  }
}

export function normalizeBaseline(raw: Record<string, string | number>): Record<string, number> {
  const result: Record<string, number> = {}
  for (const [key, value] of Object.entries(raw)) {
    result[key] = Number(value)
  }
  return result
}
