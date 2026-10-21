import axios, { type AxiosInstance, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'

/**
 * HTTP 客户端封装。
 *
 * ★ 三个关键设计，都是照着后端契约来的（docs/05-接口契约规范）：
 *
 * 1. **写操作自动生成 X-Request-Id**
 *    后端所有写接口都要求这个头（缺失返回 400）。
 *    这里自动生成，调用方无需关心 —— 因为让每个页面手写幂等键，
 *    迟早有人漏写，并且漏了只在生产才被发现。
 *
 * 2. **401 统一跳登录**
 *    后端业务码 IAM_UNAUTHENTICATED 表示 token 失效。
 *    在这里集中处理，而不是每个页面各自判断。
 *
 * 3. **错误消息优先取后端 message**
 *    后端契约要求错误消息必须含定位信息（哪个 SKU、哪个仓、差多少）。
 *    前端直接展示它，比前端自己编"操作失败"有用得多。
 */

/** 后端统一响应包络（ApiResponse）。 */
export interface ApiResponse<T = unknown> {
  code: string
  message: string
  data: T
  traceId?: string
}

/** 后端分页结包络（ApiPageResponse.data）。 */
export interface PageData<T> {
  items: T[]
  total: number
  page: number
  size: number
}

/** 后端错误响应（ApiError）。 */
export interface ApiError {
  code: string
  message: string
  details?: Record<string, unknown>
  traceId?: string
}

const TOKEN_KEY = 'erp.token'

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

export function setToken(token: string): void {
  localStorage.setItem(TOKEN_KEY, token)
}

export function clearToken(): void {
  localStorage.removeItem(TOKEN_KEY)
}

/**
 * 生成幂等键。
 *
 * 用 crypto.randomUUID 而不是时间戳：并发提交时时间戳可能相同，
 * 而幂等键一旦重复，后端会把两个不同的请求当成同一次重复提交 ——
 * 第二个请求会静默返回第一个的结果，用户以为成功了但操作没执行。
 */
function newRequestId(): string {
  if (crypto?.randomUUID) {
    return crypto.randomUUID()
  }
  return `req-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
}

const http: AxiosInstance = axios.create({
  baseURL: '/api/v1',
  timeout: 15000,
})

// ── 请求拦截：注入 token 与幂等键 ──
http.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const token = getToken()
  if (token) {
    config.headers.set('Authorization', `Bearer ${token}`)
  }
  // 只给写操作加幂等键：GET 是幂等的，加了没意义还污染日志
  const method = (config.method ?? 'get').toLowerCase()
  if (['post', 'put', 'patch', 'delete'].includes(method)) {
    config.headers.set('X-Request-Id', newRequestId())
  }
  return config
})

// ── 响应拦截：拆包 + 统一错误处理 ──
http.interceptors.response.use(
  (response: AxiosResponse<ApiResponse>) => {
    // 后端把业务数据放在 data 里，这里直接剥出来
    return response.data?.data as never
  },
  (error) => {
    const status = error.response?.status
    const payload = error.response?.data as ApiError | undefined
    const message = payload?.message || error.message || '请求失败'

    if (status === 401) {
      // token 无效 / 过期：清掉本地 token 并回登录页
      clearToken()
      if (window.location.pathname !== '/login') {
        ElMessage.warning('登录已过期，请重新登录')
        window.location.href = '/login'
      }
      return Promise.reject(new Error(message))
    }

    if (status === 403) {
      ElMessage.error(message || '当前账号无权执行该操作')
    } else if (status === 423) {
      // 账号锁定
      ElMessage.error(message)
    } else if (status >= 500) {
      ElMessage.error(`服务器错误：${message}（traceId: ${payload?.traceId ?? '无'}）`)
    } else {
      ElMessage.warning(message)
    }

    return Promise.reject(error)
  },
)

export default http

// ═══════════════════════ 业务 API（按模块分组） ═══════════════════════

/** 认证（FR-IAM-01）。 */
export const authApi = {
  login: (username: string, password: string) =>
    http.post<never, { token: string; username: string; displayName: string; roles: string[]; permissions: string[] }>(
      '/auth/login',
      { username, password },
    ),
  me: () => http.get<never, { username: string; roles: string[]; permissions: string[] }>('/auth/me'),
}

/** 主数据：商品 / SKU / 供应商 / 客户 / 仓库（FR-MDM-01~04）。 */
export const mdmApi = {
  // 商品
  listProducts: (params: { keyword?: string; page?: number; size?: number }) =>
    http.get<never, PageData<Product>>('/products', { params }),
  getProduct: (id: number) => http.get<never, Product>(`/products/${id}`),
  createProduct: (body: ProductCreate) => http.post<never, Product>('/products', body),
  updateProduct: (id: number, body: ProductUpdate) => http.put<never, Product>(`/products/${id}`, body),
  disableProduct: (id: number) => http.post<never, Product>(`/products/${id}/disable`),

  // SKU
  /**
   * @param params.skuSize 尺码筛选（如 "L"）
   * @param params.pageSize 分页条数（发给后端的参数名是 size）
   *
   * ★ 尺码筛选必须叫 skuSize：`size` 已归分页专用。
   *   两者若同名，后端只会绑定其中一个，另一个被静默忽略 ——
   *   M3-S1 实测发现原实现正是如此（分页永远 20 条且不报错）。
   */
  listSkus: (params: {
    productId?: number
    color?: string
    skuSize?: string
    page?: number
    pageSize?: number
  }) => {
    const { pageSize, ...rest } = params
    return http.get<never, PageData<Sku>>('/skus', { params: { ...rest, size: pageSize } })
  },
  createSku: (body: SkuCreate) => http.post<never, Sku>('/skus', body),
  updateSku: (id: number, body: SkuUpdate) => http.put<never, Sku>(`/skus/${id}`, body),
  /** 颜色 × 尺码矩阵批量生成（本项目服装行业的特色能力） */
  createSkuMatrix: (body: SkuMatrixCreate) =>
    http.post<never, { created: Sku[]; createdCount: number; skipped: number }>('/skus/matrix', body),
  disableSku: (id: number) => http.post<never, Sku>(`/skus/${id}/disable`),

  // 供应商
  listSuppliers: (params: { keyword?: string; page?: number; size?: number }) =>
    http.get<never, PageData<Supplier>>('/suppliers', { params }),
  createSupplier: (body: SupplierCreate) => http.post<never, Supplier>('/suppliers', body),

  // 客户
  listCustomers: (params: { keyword?: string; page?: number; size?: number }) =>
    http.get<never, PageData<Customer>>('/customers', { params }),
  createCustomer: (body: CustomerCreate) => http.post<never, Customer>('/customers', body),

  // 仓库（不分页，返回数组）
  listWarehouses: (onlyEnabled = false) =>
    http.get<never, Warehouse[]>('/warehouses', { params: { onlyEnabled } }),
  createWarehouse: (body: WarehouseCreate) => http.post<never, Warehouse>('/warehouses', body),
}

/** 库存（FR-INV-01/02/03）。 */
export const invApi = {
  query: (params: { skuId?: number; warehouseId?: number; batchNo?: string; onlyAvailable?: boolean }) =>
    http.get<never, InventoryBalance[]>('/inventory', { params }),
  ledger: (params: {
    skuId?: number
    warehouseId?: number
    bizRefNo?: string
    /** ISO-8601 带时区，如 2026-10-01T00:00:00+08:00 */
    from?: string
    to?: string
    page?: number
    size?: number
  }) => http.get<never, PageData<LedgerEntry>>('/inventory/ledger', { params }),
  consistencyCheck: () => http.get<never, { consistent: boolean }>('/inventory/consistency-check'),
}

/** 进货（FR-PUR-01/02）。 */
export const purApi = {
  list: (params: { status?: string; supplierId?: number; page?: number; size?: number }) =>
    http.get<never, PageData<PurchaseOrder>>('/purchase-orders', { params }),
  get: (id: number) => http.get<never, PurchaseOrder>(`/purchase-orders/${id}`),
  create: (body: PurchaseOrderCreate) => http.post<never, PurchaseOrder>('/purchase-orders', body),
  submit: (id: number) => http.post<never, PurchaseOrder>(`/purchase-orders/${id}/submit`),
  approve: (id: number) => http.post<never, PurchaseOrder>(`/purchase-orders/${id}/approve`),
  reject: (id: number) => http.post<never, PurchaseOrder>(`/purchase-orders/${id}/reject`),
  receive: (id: number, body: { lines: ReceiveLine[] }) =>
    http.post<never, PurchaseOrder>(`/purchase-orders/${id}/receive`, body),
}

/** 出货（FR-SAL-01/02）。 */
export const salApi = {
  list: (params: { status?: string; customerId?: number; page?: number; size?: number }) =>
    http.get<never, PageData<SalesOrder>>('/sales-orders', { params }),
  get: (id: number) => http.get<never, SalesOrder>(`/sales-orders/${id}`),
  create: (body: SalesOrderCreate) => http.post<never, SalesOrder>('/sales-orders', body),
  submit: (id: number) => http.post<never, SalesOrder>(`/sales-orders/${id}/submit`),
  approve: (id: number) => http.post<never, SalesOrder>(`/sales-orders/${id}/approve`),
  ship: (id: number) => http.post<never, SalesOrder>(`/sales-orders/${id}/ship`),
  voidOrder: (id: number) => http.post<never, SalesOrder>(`/sales-orders/${id}/void`),
}

// ═══════════════════════ 类型定义（与后端 DTO 对齐） ═══════════════════════

export interface Product {
  id: number
  code: string
  name: string
  category?: string | null
  season?: string | null
  enabled: boolean
}

export interface ProductCreate {
  code: string
  name: string
  category?: string
  season?: string
  enabled?: boolean
}

/** 编码创建后不可改，因此更新体不含 code。 */
export interface ProductUpdate {
  name: string
  category?: string
  season?: string
  enabled?: boolean
}

export interface Sku {
  id: number
  productId: number
  color: string
  size: string
  barcode?: string | null
  retailPriceAmount: number
  enabled: boolean
}

export interface SkuCreate {
  productId: number
  color: string
  size: string
  barcode?: string
  retailPriceAmount?: number
}

export interface SkuUpdate {
  barcode?: string
  retailPriceAmount?: number
  enabled?: boolean
}

export interface SkuMatrixCreate {
  productId: number
  colors: string[]
  sizes: string[]
  retailPriceAmount?: number
}

export interface Supplier {
  id: number
  code: string
  name: string
  contactName?: string | null
  contactPhone?: string | null
  address?: string | null
  enabled: boolean
}

export interface SupplierCreate {
  code: string
  name: string
  contactName?: string
  contactPhone?: string
  address?: string
}

export interface Customer {
  id: number
  code: string
  name: string
  phone?: string | null
  /** 整数万分比：10000 = 不打折，9000 = 9 折 */
  discountRate: number
  level: string
  enabled: boolean
}

export interface CustomerCreate {
  code: string
  name: string
  phone?: string
  discountRate?: number
  level?: string
}

export interface Warehouse {
  id: number
  code: string
  name: string
  address?: string | null
  enabled: boolean
}

export interface WarehouseCreate {
  code: string
  name: string
  address?: string
}

export interface InventoryBalance {
  skuId: number
  warehouseId: number
  batchNo: string
  expiryDate?: string | null
  onHandQty: number
  reservedQty: number
  availableQty: number
}

export interface LedgerEntry {
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

export interface OrderLine {
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
}

export interface PurchaseOrder {
  id: number
  orderNo: string
  status: string
  supplierId: number
  warehouseId: number
  bizDate: string
  totalAmount: number
  totalQty: number
  period?: string | null
  remark?: string | null
  lines: OrderLine[]
}

export interface PurchaseOrderCreate {
  supplierId: number
  warehouseId: number
  bizDate?: string
  remark?: string
  lines: { skuId: number; qty: number; unitPriceAmount: number }[]
}

export interface ReceiveLine {
  lineId: number
  qty: number
  batchNo?: string
  productionDate?: string
  expiryDate?: string
}

export interface SalesOrder {
  id: number
  orderNo: string
  status: string
  customerId: number
  warehouseId: number
  bizDate: string
  totalAmount: number
  totalQty: number
  period?: string | null
  remark?: string | null
  lines: OrderLine[]
}

export interface SalesOrderCreate {
  customerId: number
  warehouseId: number
  bizDate?: string
  remark?: string
  lines: { skuId: number; qty: number; unitPriceAmount: number; discountRate?: number }[]
}

// ═══════════════════════ 金额工具（ADR-0005） ═══════════════════════

/**
 * 分 → 元显示。
 *
 * ★ 全系统金额以「整数分」传输与存储（ADR-0005）。
 * 前端**只在展示层**转成元，绝不把元回传后端 ——
 * 否则会出现 0.1 + 0.2 = 0.30000000000000004 这类浮点误差，
 * 在财务系统里是无法接受的。
 */
export function fenToYuan(fen: number | null | undefined): string {
  if (fen == null) return '-'
  return (fen / 100).toFixed(2)
}

/** 元 → 分（用户输入后转成整数分再提交）。 */
export function yuanToFen(yuan: number | string): number {
  const n = typeof yuan === 'string' ? Number.parseFloat(yuan) : yuan
  if (Number.isNaN(n)) return 0
  return Math.round(n * 100)
}

/** 折扣率（万分比）→ 显示文本，如 9000 → "9 折"。 */
export function discountLabel(rate: number | null | undefined): string {
  if (rate == null || rate === 10000) return '不打折'
  return `${(rate / 1000).toFixed(rate % 1000 === 0 ? 0 : 1)} 折`
}
