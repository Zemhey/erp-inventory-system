import { randomUUID } from 'node:crypto'
import type { APIRequestContext } from '@playwright/test'

/**
 * E2E 的 API 客户端：只用于“安排数据”和“读取断言值”，
 * 所有被验收的动作仍然走浏览器 UI。token 来自 UI 登录后的 storageState。
 */

interface Envelope<T> {
  code: string
  message: string
  data: T
}

export interface PageData<T> {
  items: T[]
  total: number
  page: number
  size: number
}

export interface ProductDto {
  id: number
  code: string
  name: string
  enabled: boolean
}

export interface SkuDto {
  id: number
  productId: number
  color: string
  size: string
  retailPriceAmount: number
  enabled: boolean
}

export interface SupplierDto {
  id: number
  code: string
  name: string
  enabled: boolean
}

export interface CustomerDto {
  id: number
  code: string
  name: string
  discountRate: number
  enabled: boolean
}

export interface WarehouseDto {
  id: number
  code: string
  name: string
  enabled: boolean
  capacityQty?: number
}

/** 客户收货地址（V18 · B2）。 */
export interface CustomerAddressDto {
  id: number
  customerId: number
  addressKey: string
  recipientName: string
  phone: string
  province: string
  city: string
  district?: string | null
  detailAddress: string
  isDefault: boolean
  enabled: boolean
}

/** 仓库库位（V18 · B2）。 */
export interface WarehouseLocationDto {
  id: number
  warehouseId: number
  locationCode: string
  name?: string | null
  capacityQty: number
  enabled: boolean
}

export interface WarehouseLocationReconcileDto {
  warehouseId: number
  warehouseCapacityQty: number
  locationCapacityQty: number
  difference: number
  enabledLocationCount: number
  totalLocationCount: number
  consistent: boolean
}

export interface ProductAttributeDto {
  key: string
  value: string
}

export interface OrderLineDto {
  id: number
  lineNo: number
  skuId: number
  qty: number
  unitPriceAmount: number
  amount: number
  receivedQty?: number
  shippedQty?: number
  /** 累计已退货数量：超退上限的数据基础（V4 加的列） */
  returnedQty?: number
  openQty?: number
  discountRate?: number
  reservationIds?: number[]
}

export interface PurchaseOrderDto {
  id: number
  orderNo: string
  status: string
  supplierId: number
  warehouseId: number
  totalQty: number
  totalAmount: number
  lines: OrderLineDto[]
}

export interface SalesOrderDto {
  id: number
  orderNo: string
  status: string
  customerId: number
  warehouseId: number
  totalQty: number
  totalAmount: number
  lines: OrderLineDto[]
}

export interface InventoryBalanceDto {  skuId: number
  warehouseId: number
  batchNo: string
  onHandQty: number
  reservedQty: number
  availableQty: number
}

export interface LedgerEntryDto {
  id: number
  skuId: number
  warehouseId: number
  batchNo: string
  changeQty: number
  bizType: string
  bizRefNo: string
  unitCostAmount?: number | null
  occurredAt: string
}

export interface ReturnOrderLineDto {
  id: number
  lineNo: number
  originLineId: number
  skuId: number
  qty: number
  unitPriceAmount: number
  discountRate: number
  amount: number
  batchNo?: string | null
}

export interface ReturnOrderDto {
  id: number
  orderNo: string
  status: string
  originSalesOrderId: number
  customerId: number
  warehouseId: number
  bizDate: string
  totalAmount: number
  totalQty: number
  period?: string | null
  remark?: string | null
  lines: ReturnOrderLineDto[]
}

/** 契约 SettlementTotals：金额一律整数分，netAmount 由后端派生（页面不重算）。 */
export interface SettlementTotalsDto {
  purchaseQty: number
  purchaseAmount: number
  salesQty: number
  salesAmount: number
  returnQty: number
  returnAmount: number
  netAmount: number
}

export interface DailySettlementResultDto {
  bizDate: string
  items: (SettlementTotalsDto & { warehouseId: number })[]
  totals: SettlementTotalsDto
}

export interface MonthlySettlementResultDto extends SettlementTotalsDto {
  period: string
  status: 'OPEN' | 'CLOSED'
  closedAt?: string | null
  duplicate: boolean
}

export interface ReconcileResultDto {
  period: string
  generalTotal: number
  detailTotal: number
  diff: number
  balanced: boolean
}

export interface ReportRowDto {
  key: string
  label: string
  qty: number
  amount: number
  orderCount: number
}

export interface InventorySummaryRowDto {
  skuId: number
  warehouseId: number
  openingQty: number
  inboundQty: number
  outboundQty: number
  closingQty: number
}

type Query = Record<string, string | number | boolean | undefined>

/**
 * `call` 抛出的错误对象：用于断言"这个写操作确实被后端以某个错误码拒绝"，
 * 而不是只断"页面出现了一行红字"。文案归页面断，错误码归这里断。
 */
export interface ApiFailure {
  status: number
  code: string
  message: string
}

export function asApiFailure(error: unknown): ApiFailure {
  const e = error as { status?: number; code?: string; message?: string }
  return { status: e?.status ?? 0, code: e?.code ?? '', message: e?.message ?? String(error) }
}

function today(): string {
  const now = new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`
}

export class ApiClient {
  constructor(
    private readonly request: APIRequestContext,
    private readonly token: string,
  ) {}

  private async call<T>(
    method: 'GET' | 'POST' | 'PUT',
    path: string,
    options: { query?: Query; body?: unknown } = {},
  ): Promise<T> {
    const headers: Record<string, string> = { Authorization: `Bearer ${this.token}` }
    if (method !== 'GET') headers['X-Request-Id'] = randomUUID()

    const params: Record<string, string | number | boolean> = {}
    for (const [key, value] of Object.entries(options.query ?? {})) {
      if (value !== undefined) params[key] = value
    }

    const response = await this.request.fetch(`/api/v1${path}`, {
      method,
      headers,
      params,
      ...(options.body === undefined ? {} : { data: options.body }),
      failOnStatusCode: false,
    })
    const text = await response.text()
    let payload: (Envelope<T> & { details?: unknown }) | null = null
    try {
      payload = text ? JSON.parse(text) : null
    } catch {
      payload = null
    }

    if (!response.ok()) {
      const error = new Error(
        `${method} ${path} -> HTTP ${response.status()} ` +
          `${payload?.code ?? ''} ${payload?.message ?? text}`.trim(),
      )
      Object.assign(error, {
        status: response.status(),
        code: payload?.code,
        details: payload?.details,
      })
      throw error
    }
    return (payload as Envelope<T>).data
  }

  // ── 主数据 ──
  createProduct(body: { code: string; name: string }): Promise<ProductDto> {
    return this.call('POST', '/products', { body: { ...body, enabled: true } })
  }

  createSku(body: {
    productId: number
    color: string
    size: string
    retailPriceAmount: number
  }): Promise<SkuDto> {
    return this.call('POST', '/skus', { body })
  }

  createSupplier(body: { code: string; name: string }): Promise<SupplierDto> {
    return this.call('POST', '/suppliers', { body: { ...body, enabled: true } })
  }

  createCustomer(body: {
    code: string
    name: string
    discountRate: number
  }): Promise<CustomerDto> {
    return this.call('POST', '/customers', {
      body: { ...body, level: 'NORMAL', enabled: true },
    })
  }

  createWarehouse(body: { code: string; name: string }): Promise<WarehouseDto> {
    return this.call('POST', '/warehouses', { body: { ...body, enabled: true } })
  }

  // ── 主数据扩展（V18 · B2）──
  listCustomerAddresses(customerId: number): Promise<CustomerAddressDto[]> {
    return this.call('GET', `/customers/${customerId}/addresses`)
  }

  createCustomerAddress(
    customerId: number,
    body: {
      addressKey: string
      recipientName: string
      phone: string
      province: string
      city: string
      district?: string
      detailAddress: string
      isDefault?: boolean
      enabled?: boolean
    },
  ): Promise<CustomerAddressDto> {
    return this.call('POST', `/customers/${customerId}/addresses`, { body })
  }

  setDefaultCustomerAddress(
    customerId: number,
    addressId: number,
  ): Promise<CustomerAddressDto> {
    return this.call('POST', `/customers/${customerId}/addresses/${addressId}/default`)
  }

  listWarehouseLocations(warehouseId: number): Promise<WarehouseLocationDto[]> {
    return this.call('GET', `/warehouses/${warehouseId}/locations`)
  }

  createWarehouseLocation(
    warehouseId: number,
    body: { locationCode: string; name?: string; capacityQty: number; enabled?: boolean },
  ): Promise<WarehouseLocationDto> {
    return this.call('POST', `/warehouses/${warehouseId}/locations`, { body })
  }

  reconcileWarehouseLocations(warehouseId: number): Promise<WarehouseLocationReconcileDto> {
    return this.call('GET', `/warehouses/${warehouseId}/locations/reconcile`)
  }

  setWarehouseCapacity(warehouseId: number, capacityQty: number): Promise<WarehouseDto> {
    return this.call('PUT', `/warehouses/${warehouseId}/capacity`, { body: { capacityQty } })
  }

  listProductAttributes(productId: number): Promise<{ items: ProductAttributeDto[] }> {
    return this.call('GET', `/products/${productId}/attributes`)
  }

  replaceProductAttributes(
    productId: number,
    attributes: ProductAttributeDto[],
  ): Promise<{ items: ProductAttributeDto[] }> {
    return this.call('PUT', `/products/${productId}/attributes`, { body: { attributes } })
  }

  // ── 进货单 ──
  createPurchaseOrder(body: {
    supplierId: number
    warehouseId: number
    remark?: string
    bizDate?: string
    lines: { skuId: number; qty: number; unitPriceAmount: number }[]
  }): Promise<PurchaseOrderDto> {
    return this.call('POST', '/purchase-orders', {
      body: { ...body, bizDate: body.bizDate ?? today() },
    })
  }

  getPurchase(id: number): Promise<PurchaseOrderDto> {
    return this.call('GET', `/purchase-orders/${id}`)
  }

  listPurchases(query: { supplierId?: number; status?: string } = {}): Promise<
    PageData<PurchaseOrderDto>
  > {
    return this.call('GET', '/purchase-orders', { query: { ...query, page: 1, size: 100 } })
  }

  submitPurchase(id: number): Promise<PurchaseOrderDto> {
    return this.call('POST', `/purchase-orders/${id}/submit`)
  }

  approvePurchase(id: number): Promise<PurchaseOrderDto> {
    return this.call('POST', `/purchase-orders/${id}/approve`)
  }

  receivePurchase(
    id: number,
    lines: { lineId: number; qty: number; batchNo?: string; expiryDate?: string }[],
  ): Promise<PurchaseOrderDto> {
    return this.call('POST', `/purchase-orders/${id}/receive`, { body: { lines } })
  }

  // ── 出货单 ──
  createSalesOrder(body: {
    customerId: number
    warehouseId: number
    remark?: string
    bizDate?: string
    lines: {
      skuId: number
      qty: number
      unitPriceAmount: number
      discountRate: number
    }[]
  }): Promise<SalesOrderDto> {
    return this.call('POST', '/sales-orders', {
      body: { ...body, bizDate: body.bizDate ?? today() },
    })
  }

  getSales(id: number): Promise<SalesOrderDto> {
    return this.call('GET', `/sales-orders/${id}`)
  }

  listSales(query: { customerId?: number; status?: string } = {}): Promise<
    PageData<SalesOrderDto>
  > {
    return this.call('GET', '/sales-orders', { query: { ...query, page: 1, size: 100 } })
  }

  submitSales(id: number): Promise<SalesOrderDto> {
    return this.call('POST', `/sales-orders/${id}/submit`)
  }

  approveSales(id: number): Promise<SalesOrderDto> {
    return this.call('POST', `/sales-orders/${id}/approve`)
  }

  /** 整单按 FEFO 出货（与 UI 的「出货」按钮同一个入口）。 */
  shipSales(id: number): Promise<SalesOrderDto> {
    return this.call('POST', `/sales-orders/${id}/ship`)
  }

  // ── 退货单 ──
  createReturnOrder(body: {
    originSalesOrderId: number
    bizDate?: string
    remark?: string
    lines: {
      originLineId: number
      qty: number
      batchNo?: string
      productionDate?: string
      expiryDate?: string
    }[]
  }): Promise<ReturnOrderDto> {
    return this.call('POST', '/return-orders', { body })
  }

  getReturn(id: number): Promise<ReturnOrderDto> {
    return this.call('GET', `/return-orders/${id}`)
  }

  listReturns(query: {
    status?: string
    customerId?: number
    originSalesOrderId?: number
  } = {}): Promise<PageData<ReturnOrderDto>> {
    return this.call('GET', '/return-orders', { query: { page: 1, size: 100, ...query } })
  }

  submitReturn(id: number): Promise<ReturnOrderDto> {
    return this.call('POST', `/return-orders/${id}/submit`)
  }

  approveReturn(id: number): Promise<ReturnOrderDto> {
    return this.call('POST', `/return-orders/${id}/approve`)
  }

  receiveReturn(id: number): Promise<ReturnOrderDto> {
    return this.call('POST', `/return-orders/${id}/receive`)
  }

  // ── 结算（造数与断言用；被验收的动作走浏览器） ──
  settleDaily(body: { bizDate: string; warehouseId?: number }): Promise<DailySettlementResultDto> {
    return this.call('POST', '/settlements/daily', { body })
  }

  settleMonthly(period: string): Promise<MonthlySettlementResultDto> {
    return this.call('POST', '/settlements/monthly', { body: { period } })
  }

  reconcile(period: string): Promise<ReconcileResultDto> {
    return this.call('GET', '/settlements/reconcile', { query: { period } })
  }

  // ── 报表 ──
  reportPurchase(query: {
    from: string
    to: string
    groupBy?: string
    warehouseId?: number
  }): Promise<{ items: ReportRowDto[] }> {
    return this.call('GET', '/reports/purchase', { query })
  }

  reportSales(query: {
    from: string
    to: string
    groupBy?: string
    warehouseId?: number
  }): Promise<{ items: ReportRowDto[] }> {
    return this.call('GET', '/reports/sales', { query })
  }

  reportInventorySummary(query: {
    period: string
    warehouseId?: number
  }): Promise<{ items: InventorySummaryRowDto[] }> {
    return this.call('GET', '/reports/inventory-summary', { query })
  }

  // ── 库存 ──
  inventory(query: {
    skuId?: number
    warehouseId?: number
    batchNo?: string
    onlyAvailable?: boolean
  } = {}): Promise<InventoryBalanceDto[]> {
    return this.call('GET', '/inventory', { query })
  }

  ledger(query: {
    skuId?: number
    warehouseId?: number
    bizRefNo?: string
    page?: number
    size?: number
  } = {}): Promise<PageData<LedgerEntryDto>> {
    return this.call('GET', '/inventory/ledger', { query: { page: 1, size: 100, ...query } })
  }

  async balanceOf(skuId: number, warehouseId: number): Promise<InventoryBalanceDto> {
    const rows = await this.inventory({ skuId, warehouseId })
    return rows[0] ?? {
      skuId,
      warehouseId,
      batchNo: '',
      onHandQty: 0,
      reservedQty: 0,
      availableQty: 0,
    }
  }
}
