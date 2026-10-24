import http, { type PageData, type SalesOrder } from './client'

/**
 * V2-3 销售闭环 API（FR-SAL-05 ~ 11 / FR-MDM-08）。
 *
 * 单独成文件而不是继续堆在 client.ts：本泳道只追加自己的端点与类型，
 * 减少与其它泳道在 client.ts 同一区域的合并冲突。
 */

export const quoteApi = {
  list: (params: { status?: string; customerId?: number; page?: number; size?: number }) =>
    http.get<never, PageData<SalesQuote>>('/sales-quotes', { params }),
  get: (id: number) => http.get<never, SalesQuote>(`/sales-quotes/${id}`),
  create: (body: SalesQuoteCreate) => http.post<never, SalesQuote>('/sales-quotes', body),
  submit: (id: number) => http.post<never, SalesQuote>(`/sales-quotes/${id}/submit`),
  approve: (id: number) => http.post<never, SalesQuote>(`/sales-quotes/${id}/approve`),
  reject: (id: number) => http.post<never, SalesQuote>(`/sales-quotes/${id}/reject`),
  voidOrder: (id: number) => http.post<never, SalesQuote>(`/sales-quotes/${id}/void`),
  /** 省略 warehouseId 时按仓库 priority 自动选仓/拆单。 */
  convert: (id: number, body: { warehouseId?: number; remark?: string }) =>
    http.post<never, SalesOrder>(`/sales-quotes/${id}/convert`, body),
}

export const bundleApi = {
  list: (params: { keyword?: string; onlyEnabled?: boolean; page?: number; size?: number }) =>
    http.get<never, PageData<ProductBundle>>('/product-bundles', { params }),
  get: (id: number) => http.get<never, ProductBundle>(`/product-bundles/${id}`),
  create: (body: ProductBundleCreate) =>
    http.post<never, ProductBundle>('/product-bundles', body),
  update: (id: number, body: ProductBundleUpdate) =>
    http.put<never, ProductBundle>(`/product-bundles/${id}`, body),
  disable: (id: number) => http.post<never, ProductBundle>(`/product-bundles/${id}/disable`),
  availability: (id: number, warehouseId?: number) =>
    http.get<never, BundleAvailability>(`/product-bundles/${id}/availability`, {
      params: { warehouseId },
    }),
}

export const shipmentApi = {
  createPlan: (salesOrderId: number) =>
    http.post<never, ShipmentPlanResult>(`/sales-orders/${salesOrderId}/shipments`),
  list: (params: { status?: string; salesOrderId?: number; page?: number; size?: number }) =>
    http.get<never, PageData<Shipment>>('/shipments', { params }),
  get: (id: number) => http.get<never, Shipment>(`/shipments/${id}`),
  scan: (id: number, body: { shipmentLineId: number; qty: number; barcode?: string }) =>
    http.post<never, Shipment>(`/shipments/${id}/scan`, body),
  confirm: (id: number) => http.post<never, Shipment>(`/shipments/${id}/confirm`),
}

export const arApi = {
  receivables: (params: {
    customerId?: number
    status?: string
    overdue?: boolean
    page?: number
    size?: number
  }) => http.get<never, PageData<CustomerReceivable>>('/receivables', { params }),
  receivable: (id: number) => http.get<never, CustomerReceivable>(`/receivables/${id}`),
  summary: (customerId: number) =>
    http.get<never, ReceivableSummary>('/receivables/summary', { params: { customerId } }),
  createReceipt: (body: CustomerReceiptCreate) =>
    http.post<never, CustomerReceipt>('/customer-receipts', body),
  receipts: (params: { customerId?: number; page?: number; size?: number }) =>
    http.get<never, PageData<CustomerReceipt>>('/customer-receipts', { params }),
  receipt: (id: number) => http.get<never, CustomerReceipt>(`/customer-receipts/${id}`),
  credit: (customerId: number) =>
    http.get<never, CustomerCredit>(`/customers/${customerId}/credit`),
  updateCredit: (customerId: number, body: { creditLimit: number; creditDays: number }) =>
    http.put<never, CustomerCredit>(`/customers/${customerId}/credit`, body),
}

export interface SalesQuoteLine {
  id: number
  lineNo: number
  skuId: number
  qty: number
  unitPriceAmount: number
  discountRate: number
  amount: number
}

export interface SalesQuote {
  id: number
  quoteNo: string
  status: string
  customerId: number
  bizDate: string
  validUntil?: string | null
  totalAmount: number
  totalQty: number
  remark?: string | null
  convertedOrderId?: number | null
  lines: SalesQuoteLine[]
}

export interface SalesQuoteCreate {
  customerId: number
  bizDate?: string
  validUntil?: string
  remark?: string
  lines: { skuId: number; qty: number; unitPriceAmount: number; discountRate?: number }[]
}

export interface ProductBundleItem {
  id: number
  componentSkuId: number
  ratioQty: number
}

export interface ProductBundle {
  id: number
  code: string
  name: string
  priceAmount: number
  enabled: boolean
  items: ProductBundleItem[]
}

export interface ProductBundleCreate {
  code: string
  name: string
  priceAmount?: number
  enabled?: boolean
  items: { componentSkuId: number; ratioQty: number }[]
}

export interface ProductBundleUpdate {
  name: string
  priceAmount?: number
  enabled?: boolean
  items: { componentSkuId: number; ratioQty: number }[]
}

export interface BundleAvailability {
  bundleId: number
  warehouseId: number | null
  availableQty: number
  components: {
    componentSkuId: number
    ratioQty: number
    availableQty: number
    maxBundles: number
  }[]
}

export interface ShipmentLine {
  id: number
  shipmentLineId: number
  lineNo: number
  salesOrderLineId: number
  skuId: number
  reservationId: number
  batchNo: string
  plannedQty: number
  pickedQty: number
}

export interface Shipment {
  id: number
  shipmentNo: string
  planId: number
  salesOrderId: number
  warehouseId: number
  status: string
  lines: ShipmentLine[]
}

export interface ShipmentPlanResult {
  planId: number
  planNo: string
  salesOrderId: number
  shipments: Shipment[]
}

export interface CustomerReceivable {
  id: number
  salesOrderId: number
  customerId: number
  bizDate: string
  dueDate?: string | null
  shippedAmount: number
  returnedAmount: number
  receivedAmount: number
  balance: number
  status: string
  overdue: boolean
}

export interface ReceivableSummary {
  customerId: number
  shippedAmount: number
  returnedAmount: number
  receivedAmount: number
  balance: number
  overdueAmount: number
}

export interface CustomerReceiptAllocation {
  id: number
  receivableId: number
  amount: number
}

export interface CustomerReceipt {
  id: number
  receiptNo: string
  customerId: number
  amount: number
  bizDate: string
  remark?: string | null
  allocations: CustomerReceiptAllocation[]
}

export interface CustomerReceiptCreate {
  customerId: number
  amount: number
  bizDate?: string
  remark?: string
  allocations: { receivableId: number; amount: number }[]
}

export interface CustomerCredit {
  customerId: number
  creditLimit: number
  creditDays: number
  exposureAmount: number
  /** -1 = 不设限（creditLimit = 0） */
  availableCredit: number
  overdueAmount: number
  snapshotDate?: string | null
}
