import type {
  ApiClient,
  CustomerDto,
  ProductDto,
  PurchaseOrderDto,
  ReturnOrderDto,
  SalesOrderDto,
  SkuDto,
  SupplierDto,
  WarehouseDto,
} from './api'

export interface SeedBase {
  supplier: SupplierDto
  customer: CustomerDto
  warehouse: WarehouseDto
  product: ProductDto
  skus: SkuDto[]
  stockOrder?: PurchaseOrderDto
}

export interface SeedOptions {
  /** 2 个 SKU 用于“整单回滚”用例；默认 1 个 */
  skuCount?: 1 | 2
  /** >0 时会通过“建进货单 → 提交 → 审核 → 入库”造真实库存 */
  stockQty?: number
  priceFen?: number
  discountRate?: number
  /** 备货进货单的业务日期（yyyy-MM-DD）；省略则用当天。结算用例要落在保留账期内 */
  stockBizDate?: string
  /** 备货单价（分），默认 8800 */
  stockPriceFen?: number
}

/**
 * 每个用例用自己的前缀造一套主数据，互不共享。
 * 库存只能由进货入库产生，所以这里也走真实单据，不直接改余额。
 */
export async function seedBase(
  api: ApiClient,
  tag: string,
  options: SeedOptions = {},
): Promise<SeedBase> {
  const priceFen = options.priceFen ?? 12900
  const supplier = await api.createSupplier({ code: `${tag}-SUP`, name: `${tag} 供应商` })
  const warehouse = await api.createWarehouse({ code: `${tag}-WH`, name: `${tag} 仓库` })
  const customer = await api.createCustomer({
    code: `${tag}-CUS`,
    name: `${tag} 客户`,
    discountRate: options.discountRate ?? 9000,
  })
  const product = await api.createProduct({ code: `${tag}-P`, name: `${tag} 商品` })

  const variants: [string, string][] =
    options.skuCount === 2
      ? [
          ['白色', 'L'],
          ['黑色', 'L'],
        ]
      : [['白色', 'L']]
  const skus: SkuDto[] = []
  for (const [color, size] of variants) {
    skus.push(
      await api.createSku({
        productId: product.id,
        color,
        size,
        retailPriceAmount: priceFen,
      }),
    )
  }

  const seed: SeedBase = { supplier, customer, warehouse, product, skus }
  if (options.stockQty && options.stockQty > 0) {
    seed.stockOrder = await stockIn(
      api,
      seed,
      options.stockQty,
      options.stockPriceFen ?? 8800,
      options.stockBizDate,
    )
  }
  return seed
}

/** 用真实进货流程给第一个 SKU 备货。 */
export async function stockIn(
  api: ApiClient,
  seed: SeedBase,
  qty: number,
  unitPriceFen = 8800,
  bizDate?: string,
): Promise<PurchaseOrderDto> {
  let order = await api.createPurchaseOrder({
    supplierId: seed.supplier.id,
    warehouseId: seed.warehouse.id,
    remark: `E2E seed ${qty}`,
    ...(bizDate ? { bizDate } : {}),
    lines: [{ skuId: seed.skus[0].id, qty, unitPriceAmount: unitPriceFen }],
  })
  order = await api.submitPurchase(order.id)
  order = await api.approvePurchase(order.id)
  order = await api.receivePurchase(order.id, [{ lineId: order.lines[0].id, qty }])
  return order
}

/**
 * 出货并整单发出（PARTIAL → COMPLETED 由后端定），结算与退货都要求“已出货”这一事实。
 *
 * 单价/折扣必须显式传：日清的销售金额按 round(qty × 单价 × 折扣 / 10000) 重算，
 * 用例要断的是这个整数公式的结果，不是页面上四舍五入后的字符串。
 */
export async function shipOut(
  api: ApiClient,
  seed: SeedBase,
  options: { qty: number; unitPriceFen?: number; discountRate?: number; bizDate?: string },
): Promise<SalesOrderDto> {
  const line = {
    skuId: seed.skus[0].id,
    qty: options.qty,
    unitPriceAmount: options.unitPriceFen ?? 12900,
    discountRate: options.discountRate ?? 9000,
  }
  let order = await api.createSalesOrder({
    customerId: seed.customer.id,
    warehouseId: seed.warehouse.id,
    remark: `E2E ship ${options.qty}`,
    ...(options.bizDate ? { bizDate: options.bizDate } : {}),
    lines: [line],
  })
  order = await api.submitSales(order.id)
  order = await api.approveSales(order.id)
  order = await api.shipSales(order.id)
  return order
}

/** 建退货单并走到 COMPLETED（提交 → 审核 → 退货入库）。 */
export async function completeReturn(
  api: ApiClient,
  origin: SalesOrderDto,
  options: { originLineId: number; qty: number; bizDate?: string; batchNo?: string },
): Promise<ReturnOrderDto> {
  let order = await api.createReturnOrder({
    originSalesOrderId: origin.id,
    ...(options.bizDate ? { bizDate: options.bizDate } : {}),
    lines: [
      {
        originLineId: options.originLineId,
        qty: options.qty,
        ...(options.batchNo ? { batchNo: options.batchNo } : {}),
      },
    ],
  })
  order = await api.submitReturn(order.id)
  order = await api.approveReturn(order.id)
  order = await api.receiveReturn(order.id)
  return order
}
