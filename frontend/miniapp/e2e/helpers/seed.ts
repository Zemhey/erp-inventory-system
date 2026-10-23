import { api, login } from './api'
import type { RunInfo, SeedData, SeedOrder } from './run'

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
    expected: {
      stockBalanceRows: 25,
      stockOnHand: 50,
      stockAvailable: 50,
      stockLedgerRows: 25,
      productSkuCount: 25,
      poReceiveOpenQty: 10,
      soActionQty: 4,
    },
  }
}
