import type {
  ApiClient,
  CustomerDto,
  ProductDto,
  PurchaseOrderDto,
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
    seed.stockOrder = await stockIn(api, seed, options.stockQty)
  }
  return seed
}

/** 用真实进货流程给第一个 SKU 备货。 */
export async function stockIn(
  api: ApiClient,
  seed: SeedBase,
  qty: number,
  unitPriceFen = 8800,
): Promise<PurchaseOrderDto> {
  let order = await api.createPurchaseOrder({
    supplierId: seed.supplier.id,
    warehouseId: seed.warehouse.id,
    remark: `E2E seed ${qty}`,
    lines: [{ skuId: seed.skus[0].id, qty, unitPriceAmount: unitPriceFen }],
  })
  order = await api.submitPurchase(order.id)
  order = await api.approvePurchase(order.id)
  order = await api.receivePurchase(order.id, [{ lineId: order.lines[0].id, qty }])
  return order
}
