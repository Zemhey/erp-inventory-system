import { api, login } from './api'
import type { RunInfo, SeedActor, SeedData, SeedOrder, SeedQuote } from './run'

interface Warehouse {
  id: number
  code: string
  name: string
}

interface Supplier {
  id: number
  code: string
  name: string
}

interface Customer {
  id: number
  code: string
  name: string
}

interface Product {
  id: number
  code: string
  name: string
}

interface Sku {
  id: number
  productId: number
  color: string
  size: string
  barcode?: string | null
}

interface OrderLine {
  id: number
  lineNo: number
  skuId: number
  qty: number
  receivedQty?: number
  shippedQty?: number
  openQty?: number
}

interface Order {
  id: number
  orderNo: string
  status: string
  totalQty: number
  lines: OrderLine[]
}

const COLORS = ['暖白', '墨黑', '赤红', '藏蓝', '烟灰']
const SIZES = ['S', 'M', 'L', 'XL', 'XXL']
const RETAIL_PRICE = 19900
const STOCK_UNIT_PRICE = 1000
const ACTION_UNIT_PRICE = 1200
const RECEIVE_UNIT_PRICE = 1100

function toSeedOrder(order: Order): SeedOrder {
  return {
    id: order.id,
    orderNo: order.orderNo,
    status: order.status,
    totalQty: order.totalQty,
    lineCount: order.lines.length,
  }
}

function toSeedQuote(quote: Quote): SeedQuote {
  return {
    id: quote.id,
    quoteNo: quote.quoteNo,
    status: quote.status,
    totalAmount: quote.totalAmount,
    totalQty: quote.totalQty,
    lineCount: quote.lines.length,
  }
}

function expectEqual(actual: unknown, expected: unknown, label: string): void {
  if (actual !== expected) {
    throw new Error(`[seed] ${label} 期望 ${String(expected)}，实际 ${String(actual)}`)
  }
}

async function createPurchaseOrder(
  token: string,
  body: {
    supplierId: number
    warehouseId: number
    remark: string
    lines: Array<{ skuId: number; qty: number; unitPriceAmount: number }>
  },
): Promise<Order> {
  return api<Order>('POST', '/purchase-orders', { token, body })
}

async function createSalesOrder(
  token: string,
  body: {
    customerId: number
    warehouseId: number
    remark: string
    lines: Array<{ skuId: number; qty: number; unitPriceAmount: number; discountRate: number }>
  },
): Promise<Order> {
  return api<Order>('POST', '/sales-orders', { token, body })
}

async function act(token: string, path: string): Promise<Order> {
  return api<Order>('POST', path, { token })
}

async function getOrder(token: string, path: string): Promise<Order> {
  return api<Order>('GET', path, { token })
}

interface QuoteLine {
  id: number
  lineNo: number
  skuId: number
  qty: number
  amount: number
}

interface Quote {
  id: number
  quoteNo: string
  status: string
  totalAmount: number
  totalQty: number
  lines: QuoteLine[]
}

interface Page<T> {
  items: T[]
  total: number
  page: number
  size: number
}

interface ReceivableRow {
  id: number
  salesOrderId: number
  customerId: number
  shippedAmount: number
  returnedAmount: number
  receivedAmount: number
  balance: number
  status: string
}

interface CreatedUser {
  id: number
  username: string
  displayName: string
  enabled: boolean
  roles: string[]
  permissions: string[]
}

/** 建号时的初始密码；随后必须改一次密才能用（见 createActor 的注释）。 */
const SEED_PASSWORD = 'Seed@12345-init'
const SEED_PASSWORD_FINAL = 'Seed@12345-live'

async function createQuote(
  token: string,
  body: {
    customerId: number
    validUntil: string
    remark: string
    lines: Array<{ skuId: number; qty: number; unitPriceAmount: number; discountRate: number }>
  },
): Promise<Quote> {
  return api<Quote>('POST', '/sales-quotes', { token, body })
}

/** 报价单的有效期必须落在未来：转化会拒过期单（SalesQuoteApplicationService:201）。 */
function futureDate(days: number): string {
  const date = new Date(Date.now() + days * 86_400_000)
  return date.toISOString().slice(0, 10)
}

/**
 * 造一个可登录的临时身份。
 *
 * ★ 建号必须紧跟着改一次密：`POST /iam/users` 会把 mustChangePassword 置真
 *   （UserAdminService:101），而 PasswordExpiryFilter 在该标记下把**除改密与 /auth/me
 *   之外的所有业务接口**拦成 403。不改密的账号登录后任何页面都是错的，
 *   症状会被误判成「权限没配对」。改密同时把标记清成 false，旧 token 继续可用。
 *   新密码不能与旧密码相同（AuthenticationService:174），所以用两个常量。
 */
async function createActor(
  adminToken: string,
  username: string,
  displayName: string,
  roles: string[],
): Promise<SeedActor> {
  const user = await api<CreatedUser>('POST', '/iam/users', {
    token: adminToken,
    body: { username, password: SEED_PASSWORD, displayName, enabled: true, roles },
  })

  const auth = await login(username, SEED_PASSWORD)
  await api('POST', '/auth/change-password', {
    token: auth.token,
    body: { oldPassword: SEED_PASSWORD, newPassword: SEED_PASSWORD_FINAL },
  })

  // 改密后重新登录拿一份干净 token（权限从库里回读，与 UI 登录同路径）
  const refreshed = await login(username, SEED_PASSWORD_FINAL)
  return {
    id: user.id,
    username,
    password: SEED_PASSWORD_FINAL,
    roles,
    permissions: refreshed.permissions,
    token: refreshed.token,
  }
}

/**
 * 一轮唯一前缀造数（全部走真实接口，不手改库）：
 * - 25 SKU 的收货（25 条流水 / 25 行余额）→ 库存余额与流水分页用例
 * - 动作仓 2 SKU 先入库 10，再建并审核一张 2×2 的出货单 → 出货用例
 * - 一张 APPROVED 的 2×5 进货单 → 入库用例
 *
 * 清理只按 run.prefix（主数据 code）+ 派生的单据/流水/预占/余额。
 */
export async function seedRun(run: RunInfo): Promise<SeedData> {
  const auth = await login()
  const token = auth.token

  const suffix = run.runId
  const [stockWarehouse, actionWarehouse] = await Promise.all([
    api<Warehouse>('POST', '/warehouses', {
      token,
      body: { code: `${run.prefix}-S`, name: `E2E 备货仓 ${suffix}`, address: 'E2E' },
    }),
    api<Warehouse>('POST', '/warehouses', {
      token,
      body: { code: `${run.prefix}-A`, name: `E2E 动作仓 ${suffix}`, address: 'E2E' },
    }),
  ])

  const [supplier, customer, product] = await Promise.all([
    api<Supplier>('POST', '/suppliers', {
      token,
      body: { code: `${run.prefix}-SUP`, name: `E2E 供应商 ${suffix}` },
    }),
    api<Customer>('POST', '/customers', {
      token,
      body: {
        code: `${run.prefix}-CUS`,
        name: `E2E 客户 ${suffix}`,
        discountRate: 10000,
        level: 'NORMAL',
      },
    }),
    api<Product>('POST', '/products', {
      token,
      body: {
        code: run.prefix,
        name: `E2E 测试商品 ${suffix}`,
        category: 'E2E',
        season: '2026',
      },
    }),
  ])

  const matrix = await api<{ created: Sku[]; createdCount: number; skipped: number }>(
    'POST',
    '/skus/matrix',
    {
      token,
      body: {
        productId: product.id,
        colors: COLORS,
        sizes: SIZES,
        retailPriceAmount: RETAIL_PRICE,
      },
    },
  )
  expectEqual(matrix.createdCount, COLORS.length * SIZES.length, '矩阵生成 SKU 数')

  const skus: SeedData['skus'] = []
  for (let index = 0; index < matrix.created.length; index += 1) {
    const sku = matrix.created[index]
    const barcode = `${run.runId}-${String(index + 1).padStart(2, '0')}`
    await api<Sku>('PUT', `/skus/${sku.id}`, {
      token,
      body: { barcode, retailPriceAmount: RETAIL_PRICE, enabled: true },
    })
    skus.push({ id: sku.id, color: sku.color, size: sku.size, barcode })
  }

  // ── 1) 备货仓：25 行 × 2 件，一次入库 → 25 条流水 ──
  const batchStock = `B-${run.runId}`
  const poStockCreated = await createPurchaseOrder(token, {
    supplierId: supplier.id,
    warehouseId: stockWarehouse.id,
    remark: `E2E stock ${suffix}`,
    lines: skus.map((sku) => ({
      skuId: sku.id,
      qty: 2,
      unitPriceAmount: STOCK_UNIT_PRICE,
    })),
  })
  expectEqual(poStockCreated.lines.length, 25, '备货进货单行数')
  await act(token, `/purchase-orders/${poStockCreated.id}/submit`)
  await act(token, `/purchase-orders/${poStockCreated.id}/approve`)
  const poStock = await api<Order>('POST', `/purchase-orders/${poStockCreated.id}/receive`, {
    token,
    body: {
      lines: poStockCreated.lines.map((line) => ({
        lineId: line.id,
        qty: line.qty,
        batchNo: batchStock,
        expiryDate: '2028-12-31',
      })),
    },
  })
  expectEqual(poStock.status, 'COMPLETED', '备货进货单最终状态')

  // ── 2) 动作仓：2 个 SKU 各入库 10 件，供出货用例扣减 ──
  const batchAction = `A-${run.runId}`
  const actionSkus = skus.slice(0, 2)
  const poActionCreated = await createPurchaseOrder(token, {
    supplierId: supplier.id,
    warehouseId: actionWarehouse.id,
    remark: `E2E action stock ${suffix}`,
    lines: actionSkus.map((sku) => ({
      skuId: sku.id,
      qty: 10,
      unitPriceAmount: ACTION_UNIT_PRICE,
    })),
  })
  await act(token, `/purchase-orders/${poActionCreated.id}/submit`)
  await act(token, `/purchase-orders/${poActionCreated.id}/approve`)
  const poAction = await api<Order>('POST', `/purchase-orders/${poActionCreated.id}/receive`, {
    token,
    body: {
      lines: poActionCreated.lines.map((line) => ({
        lineId: line.id,
        qty: line.qty,
        batchNo: batchAction,
        expiryDate: '2029-06-30',
      })),
    },
  })
  expectEqual(poAction.status, 'COMPLETED', '动作仓进货单最终状态')

  // ── 3) 出货单：2 行 × 2 件，审核后产生 4 件预占，等 UI 执行 ship ──
  const soCreated = await createSalesOrder(token, {
    customerId: customer.id,
    warehouseId: actionWarehouse.id,
    remark: `E2E ship ${suffix}`,
    lines: actionSkus.map((sku) => ({
      skuId: sku.id,
      qty: 2,
      unitPriceAmount: RETAIL_PRICE,
      discountRate: 10000,
    })),
  })
  await act(token, `/sales-orders/${soCreated.id}/submit`)
  const soAction = await act(token, `/sales-orders/${soCreated.id}/approve`)
  expectEqual(soAction.status, 'APPROVED', '出货单审核后状态')
  expectEqual(soAction.totalQty, 4, '出货单总数量')

  // ── 4) 待入库进货单：2 行 × 5 件，APPROVED，等 UI 执行 receive ──
  const poReceiveCreated = await createPurchaseOrder(token, {
    supplierId: supplier.id,
    warehouseId: actionWarehouse.id,
    remark: `E2E receive ${suffix}`,
    lines: actionSkus.map((sku) => ({
      skuId: sku.id,
      qty: 5,
      unitPriceAmount: RECEIVE_UNIT_PRICE,
    })),
  })
  await act(token, `/purchase-orders/${poReceiveCreated.id}/submit`)
  const poReceive = await act(token, `/purchase-orders/${poReceiveCreated.id}/approve`)
  expectEqual(poReceive.status, 'APPROVED', '待入库进货单状态')
  expectEqual(poReceive.totalQty, 10, '待入库进货单总数量')

  // ── 5) 报价单三张：分别给「提交→审核→转化」「驳回」「作废」三条 UI 路径 ──
  const quoteLines = [
    { skuId: actionSkus[0].id, qty: 1, unitPriceAmount: RETAIL_PRICE, discountRate: 10000 },
    { skuId: actionSkus[1].id, qty: 1, unitPriceAmount: RETAIL_PRICE, discountRate: 10000 },
  ]
  const quoteFlow = await createQuote(token, {
    customerId: customer.id,
    validUntil: futureDate(30),
    remark: `E2E quote flow ${suffix}`,
    lines: quoteLines,
  })
  expectEqual(quoteFlow.status, 'DRAFT', '流转报价单初始状态')
  expectEqual(quoteFlow.lines.length, 2, '流转报价单行数')

  const quoteRejectCreated = await createQuote(token, {
    customerId: customer.id,
    validUntil: futureDate(30),
    remark: `E2E quote reject ${suffix}`,
    lines: quoteLines.slice(0, 1),
  })
  const quoteReject = await api<Quote>('POST', `/sales-quotes/${quoteRejectCreated.id}/submit`, {
    token,
  })
  expectEqual(quoteReject.status, 'SUBMITTED', '待驳回报价单状态')

  const quoteVoid = await createQuote(token, {
    customerId: customer.id,
    validUntil: futureDate(30),
    remark: `E2E quote void ${suffix}`,
    lines: quoteLines.slice(0, 1),
  })

  // 这张草稿单**不被任何 admin 用例改动**：无权限身份的用例需要一个
  // 「状态本该给出写按钮」的单据，否则「看不到按钮」可能只是因为没按钮可言。
  const quoteLocked = await createQuote(token, {
    customerId: customer.id,
    validUntil: futureDate(30),
    remark: `E2E quote locked ${suffix}`,
    lines: quoteLines.slice(0, 1),
  })
  expectEqual(quoteLocked.status, 'DRAFT', '权限对照报价单初始状态')

  // ── 6) 待扫码的出货单：审核后产生预占，等 UI 生成发货计划并扫码 ──
  const soPickCreated = await createSalesOrder(token, {
    customerId: customer.id,
    warehouseId: actionWarehouse.id,
    remark: `E2E pick ${suffix}`,
    lines: actionSkus.map((sku) => ({
      skuId: sku.id,
      qty: 2,
      unitPriceAmount: RETAIL_PRICE,
      discountRate: 10000,
    })),
  })
  await act(token, `/sales-orders/${soPickCreated.id}/submit`)
  const soPick = await act(token, `/sales-orders/${soPickCreated.id}/approve`)
  expectEqual(soPick.status, 'APPROVED', '待拣货出货单状态')

  // ── 7) 已出货的出货单：seed 直接 ship，用来产生客户应收 ──
  const soDebtCreated = await createSalesOrder(token, {
    customerId: customer.id,
    warehouseId: actionWarehouse.id,
    remark: `E2E debt ${suffix}`,
    lines: actionSkus.map((sku) => ({
      skuId: sku.id,
      qty: 1,
      unitPriceAmount: RETAIL_PRICE,
      discountRate: 10000,
    })),
  })
  await act(token, `/sales-orders/${soDebtCreated.id}/submit`)
  await act(token, `/sales-orders/${soDebtCreated.id}/approve`)
  const soDebt = await act(token, `/sales-orders/${soDebtCreated.id}/ship`)
  expectEqual(soDebt.status, 'COMPLETED', '产生应收的出货单最终状态')

  const debtFound = await api<Page<ReceivableRow>>(
    'GET',
    `/receivables?customerId=${customer.id}&page=1&size=20`,
    { token },
  )
  const receivableRow = debtFound.items.find(
    (item) => item.salesOrderId === soDebt.id,
  )
  if (!receivableRow) {
    throw new Error(
      `[seed] 出货单 ${soDebt.orderNo}(#${soDebt.id}) 出货后没有产生应收；` +
        `现有 ${debtFound.items.map((item) => `${item.salesOrderId}#${item.status}`).join(',')}`,
    )
  }

  // ── 8) 四张审批用单据：一张单据只能挂一个未结实例，按用途分张（同意/驳回/撤回/转办） ──
  const approvalOrders: Order[] = []
  for (let index = 0; index < 4; index += 1) {
    const created = await createSalesOrder(token, {
      customerId: customer.id,
      warehouseId: actionWarehouse.id,
      remark: `E2E approval ${index + 1} ${suffix}`,
      lines: [{ skuId: actionSkus[0].id, qty: 1, unitPriceAmount: RETAIL_PRICE, discountRate: 10000 }],
    })
    await act(token, `/sales-orders/${created.id}/submit`)
    approvalOrders.push(await act(token, `/sales-orders/${created.id}/approve`))
  }

  // ── 9) 两个临时身份：只读（看不到写按钮）与可接办转办（有 aps:approve） ──
  const viewer = await createActor(
    token,
    `${run.prefix}-VIEW`,
    `E2E 只读 ${suffix}`,
    ['VIEWER'],
  )
  const approver = await createActor(
    token,
    `${run.prefix}-APR`,
    `E2E 接办人 ${suffix}`,
    ['FINANCE'],
  )
  if (!viewer.permissions.includes('sal:read')) {
    throw new Error(`[seed] 只读身份应能读应收，实际权限 ${viewer.permissions.join(',')}`)
  }
  if (!approver.permissions.includes('aps:approve')) {
    throw new Error(
      `[seed] 接办身份必须有 aps:approve（否则转办会被后端拒），实际 ${approver.permissions.join(',')}`,
    )
  }
  for (const permission of ['sal:quote', 'sal:pick', 'sal:receipt', 'aps:approve']) {
    if (viewer.permissions.includes(permission)) {
      throw new Error(`[seed] 只读身份不该拥有写权限 ${permission}，失败路径用例会假绿`)
    }
  }

  // 重新读详情，确保 openQty 等派生字段存在
  const poReceiveDetail = await getOrder(token, `/purchase-orders/${poReceive.id}`)

  return {
    runId: run.runId,
    prefix: run.prefix,
    admin: {
      username: auth.username,
      displayName: auth.displayName,
      roles: auth.roles,
      token: auth.token,
      permissions: auth.permissions,
    },
    stockWarehouse: {
      id: stockWarehouse.id,
      code: stockWarehouse.code,
      name: stockWarehouse.name,
    },
    actionWarehouse: {
      id: actionWarehouse.id,
      code: actionWarehouse.code,
      name: actionWarehouse.name,
    },
    supplier: { id: supplier.id, code: supplier.code, name: supplier.name },
    customer: { id: customer.id, code: customer.code, name: customer.name },
    product: { id: product.id, code: product.code, name: product.name },
    skus,
    batchStock,
    batchAction,
    poStock: toSeedOrder(poStock),
    poAction: toSeedOrder(poAction),
    poReceive: toSeedOrder(poReceiveDetail),
    soAction: toSeedOrder(soAction),
    quoteFlow: toSeedQuote(quoteFlow),
    quoteReject: toSeedQuote(quoteReject),
    quoteVoid: toSeedQuote(quoteVoid),
    quoteLocked: toSeedQuote(quoteLocked),
    convertedOrder: null,
    soPick: toSeedOrder(soPick),
    soDebt: toSeedOrder(soDebt),
    approvalOrders: approvalOrders.map(toSeedOrder),
    receivable: {
      id: receivableRow.id,
      salesOrderId: receivableRow.salesOrderId,
      customerId: receivableRow.customerId,
      shippedAmount: receivableRow.shippedAmount,
      returnedAmount: receivableRow.returnedAmount,
      receivedAmount: receivableRow.receivedAmount,
      balance: receivableRow.balance,
      status: receivableRow.status,
    },
    viewer,
    approver,
    expected: {
      stockBalanceRows: 25,
      stockOnHand: 50,
      stockAvailable: 50,
      stockLedgerRows: 25,
      productSkuCount: 25,
      poReceiveOpenQty: 10,
      soActionQty: 4,
      soPickQty: 4,
      soDebtQty: 2,
      soDebtAmount: 2 * RETAIL_PRICE,
      receivableBalance: 2 * RETAIL_PRICE,
      quoteFlowAmount: 2 * RETAIL_PRICE,
    },
  }
}
