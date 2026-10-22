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

export interface InventoryBalanceDto {
  skuId: number
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

type Query = Record<string, string | number | boolean | undefined>

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
    method: 'GET' | 'POST',
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

  // ── 进货单 ──
  createPurchaseOrder(body: {
    supplierId: number
    warehouseId: number
    remark?: string
    lines: { skuId: number; qty: number; unitPriceAmount: number }[]
  }): Promise<PurchaseOrderDto> {
    return this.call('POST', '/purchase-orders', {
      body: { ...body, bizDate: today() },
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
    lines: {
      skuId: number
      qty: number
      unitPriceAmount: number
      discountRate: number
    }[]
  }): Promise<SalesOrderDto> {
    return this.call('POST', '/sales-orders', {
      body: { ...body, bizDate: today() },
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
