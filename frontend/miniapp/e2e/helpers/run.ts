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

export interface SeedQuote {
  id: number
  quoteNo: string
  status: string
  totalAmount: number
  totalQty: number
  lineCount: number
}

/** 造数造出来的应收（发货确认后由后端登记，移动端不手工建应收）。 */
export interface SeedReceivable {
  id: number
  salesOrderId: number
  customerId: number
  shippedAmount: number
  returnedAmount: number
  receivedAmount: number
  balance: number
  status: string
}

/** 临时身份：建号后必须自己改一次密，否则 mustChangePassword 会把业务接口全拦成 403。 */
export interface SeedActor {
  id: number
  username: string
  password: string
  roles: string[]
  permissions: string[]
  token: string
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
  /** V2-D1 新增 */
  /** 草稿报价单：UI 走 提交 → 审核 → 转出货单 */
  quoteFlow: SeedQuote
  /** 已提交报价单：UI 走 驳回 */
  quoteReject: SeedQuote
  /** 草稿报价单：UI 走 作废 */
  quoteVoid: SeedQuote
  /** 草稿报价单：不被任何有权限用例改动，专供「无权限看不到写按钮」的对照 */
  quoteLocked: SeedQuote
  /** 转化报价单后新建出来的出货单 */
  convertedOrder: SeedOrder | null
  /** 已审核、等待扫码的出货单（发货作业） */
  soPick: SeedOrder
  /** seed 内直接出货，用来产生应收 */
  soDebt: SeedOrder
  /**
   * 四张挂审批实例的出货单 —— 一张单据同时只能有一个未结实例，所以按用途分张：
   * 0=同意 1=驳回 2=撤回 3=转办
   */
  approvalOrders: SeedOrder[]
  receivable: SeedReceivable
  /** 只读身份（VIEWER）：sal:read / aps:read 有，写动作权限码一个都没有 */
  viewer: SeedActor
  /** 可接办转办的身份（FINANCE）：有 aps:approve */
  approver: SeedActor
  expected: {
    stockBalanceRows: number
    stockOnHand: number
    stockAvailable: number
    stockLedgerRows: number
    productSkuCount: number
    poReceiveOpenQty: number
    soActionQty: number
    soPickQty: number
    soDebtQty: number
    soDebtAmount: number
    receivableBalance: number
    quoteFlowAmount: number
  }
}

export function normalizeBaseline(raw: Record<string, string | number>): Record<string, number> {
  const result: Record<string, number> = {}
  for (const [key, value] of Object.entries(raw)) {
    result[key] = Number(value)
  }
  return result
}
