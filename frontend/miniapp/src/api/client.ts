import { API_BASE_HINT, API_BASE_IS_ABSOLUTE, API_BASE_URL, IS_H5 } from '@/config'

/**
 * 移动端 HTTP 客户端。
 *
 * 与 Web 端 frontend/web/src/api/client.ts 保持同一套语义：
 * 1. 自动注入 token；
 * 2. 写操作自动生成 X-Request-Id（GET 不加）；
 * 3. 错误消息优先取后端 message（后端要求消息含定位信息）；
 * 4. 401 清登录态并回登录页，但登录页自身的 401 必须原样展示
 *    （M3-S8 E2E-02 踩过的"点登录毫无反馈"缺陷）。
 *
 * 不用 axios：小程序端没有 XHR，axios 需要额外适配器，直接用 uni.request 更稳。
 */

export interface ApiResponse<T = unknown> {
  code: string
  message: string
  data: T
  traceId?: string
}

export interface PageData<T> {
  items: T[]
  total: number
  page: number
  size: number
}

export interface ApiErrorPayload {
  code: string
  message: string
  details?: Record<string, unknown>
  traceId?: string
}

export class ApiRequestError extends Error {
  readonly code: string
  readonly status: number
  readonly details?: Record<string, unknown>
  readonly traceId?: string

  constructor(payload: {
    code: string
    message: string
    status: number
    details?: Record<string, unknown>
    traceId?: string
  }) {
    super(payload.message)
    this.name = 'ApiRequestError'
    this.code = payload.code
    this.status = payload.status
    this.details = payload.details
    this.traceId = payload.traceId
  }
}

const TOKEN_KEY = 'erp.token'
const PERMISSIONS_KEY = 'erp.permissions'
const USER_KEY = 'erp.user'

export function getToken(): string {
  try {
    const raw = uni.getStorageSync(TOKEN_KEY)
    return typeof raw === 'string' ? raw : ''
  } catch {
    return ''
  }
}

export function setToken(token: string): void {
  uni.setStorageSync(TOKEN_KEY, token)
}

/** 清掉全部登录相关缓存（不只是 token，避免 401 后仍残留旧权限）。 */
export function clearToken(): void {
  uni.removeStorageSync(TOKEN_KEY)
  uni.removeStorageSync(PERMISSIONS_KEY)
  uni.removeStorageSync(USER_KEY)
}

function newRequestId(): string {
  const cryptoApi = (globalThis as { crypto?: { randomUUID?: () => string } }).crypto
  if (cryptoApi?.randomUUID) {
    return cryptoApi.randomUUID()
  }
  return `mp-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
}

type HttpMethod = 'GET' | 'POST' | 'PUT' | 'DELETE'

interface RequestOptions {
  method?: HttpMethod
  data?: unknown
  query?: Record<string, string | number | boolean | null | undefined>
  /** 不弹全局 toast（登录页自己展示内联错误时用）。 */
  silent?: boolean
  /** 默认 true；登录请求不需要携带旧 token。 */
  auth?: boolean
}

const WRITE_METHODS: HttpMethod[] = ['POST', 'PUT', 'DELETE']

let configWarned = false

function assertConfigured(): void {
  if (IS_H5 || API_BASE_IS_ABSOLUTE) return
  if (!configWarned) {
    configWarned = true
    uni.showModal({
      title: '接口地址未配置',
      content: API_BASE_HINT,
      showCancel: false,
    })
  }
  throw new Error(API_BASE_HINT)
}

function buildQuery(query?: RequestOptions['query']): string {
  if (!query) return ''
  const parts: string[] = []
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined || value === null || value === '') continue
    parts.push(`${encodeURIComponent(key)}=${encodeURIComponent(String(value))}`)
  }
  return parts.length ? `?${parts.join('&')}` : ''
}

function currentRoute(): string {
  try {
    const pages = getCurrentPages()
    const current = pages[pages.length - 1] as { route?: string } | undefined
    return current?.route ? `/${current.route}` : ''
  } catch {
    return ''
  }
}

function isLoginPage(): boolean {
  return currentRoute().indexOf('/pages/login/login') >= 0
}

let redirecting = false

function redirectToLogin(): void {
  if (redirecting || isLoginPage()) return
  redirecting = true
  uni.reLaunch({
    url: '/pages/login/login',
    complete: () => {
      setTimeout(() => {
        redirecting = false
      }, 600)
    },
  })
}

function toast(message: string): void {
  uni.showToast({ title: message, icon: 'none', duration: 2600 })
}

function defaultMessage(status: number): string {
  if (status === 403) return '当前账号无权执行该操作'
  if (status === 404) return '请求的资源不存在'
  if (status >= 500) return '服务器内部错误'
  return '请求失败'
}

function handleHttpError(
  status: number,
  payload: ApiErrorPayload | undefined,
  options: RequestOptions,
  reject: (reason?: unknown) => void,
): void {
  const message = payload?.message || defaultMessage(status)

  if (status === 401) {
    clearToken()
    if (isLoginPage()) {
      // 登录页的 401 是"账号或密码错误"的业务结果，必须原样展示
      if (!options.silent) toast(message)
    } else {
      if (!options.silent) toast('登录已过期，请重新登录')
      redirectToLogin()
    }
  } else if (!options.silent) {
    if (status >= 500) {
      toast(`服务器错误：${message}`)
    } else {
      toast(message)
    }
  }

  reject(
    new ApiRequestError({
      code: payload?.code || `HTTP_${status}`,
      message,
      status,
      details: payload?.details,
      traceId: payload?.traceId,
    }),
  )
}

export function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    try {
      assertConfigured()
    } catch (error) {
      reject(error)
      return
    }

    const method = options.method ?? 'GET'
    const header: Record<string, string> = {
      'Content-Type': 'application/json',
    }

    if (options.auth !== false) {
      const token = getToken()
      if (token) header.Authorization = `Bearer ${token}`
    }

    // 幂等键只给写操作：GET 加了没意义，还会污染日志
    if (WRITE_METHODS.includes(method)) {
      header['X-Request-Id'] = newRequestId()
    }

    const url = `${API_BASE_URL}${path.startsWith('/') ? path : `/${path}`}${buildQuery(options.query)}`

    uni.request({
      url,
      method,
      data: options.data as string | Record<string, unknown> | ArrayBuffer | undefined,
      header,
      timeout: 15000,
      success: (response) => {
        const status = response.statusCode
        const payload = response.data as ApiResponse<T> | ApiErrorPayload | undefined

        if (status >= 200 && status < 300) {
          const body = payload as ApiResponse<T>
          if (body?.code === 'OK') {
            resolve(body.data)
            return
          }
          const message = body?.message || '接口返回格式异常'
          if (!options.silent) toast(message)
          reject(
            new ApiRequestError({
              code: body?.code || 'SYS_UNEXPECTED_RESPONSE',
              message,
              status,
              traceId: body?.traceId,
            }),
          )
          return
        }

        handleHttpError(status, payload as ApiErrorPayload | undefined, options, reject)
      },
      fail: (error) => {
        const message = '网络连接失败，请确认后端已启动且当前网络可达'
        if (!options.silent) toast(message)
        reject(new ApiRequestError({ code: 'NETWORK_ERROR', message, status: 0 }))
        // error.errMsg 留在控制台，便于排查是超时还是域名校验
        console.warn('[miniapp] request failed:', error.errMsg)
      },
    })
  })
}

// ═══════════════════════ 业务 API（按模块分组） ═══════════════════════

export interface LoginResult {
  token: string
  tokenType: string
  username: string
  displayName: string
  roles: string[]
  permissions: string[]
}

export interface MeResult {
  username: string
  displayName: string
  roles: string[]
  permissions: string[]
}

export const authApi = {
  login: (username: string, password: string) =>
    request<LoginResult>('/auth/login', {
      method: 'POST',
      data: { username, password },
      auth: false,
      silent: true,
    }),
  me: () => request<MeResult>('/auth/me'),
}

export interface Warehouse {
  id: number
  code: string
  name: string
  address?: string | null
  enabled: boolean
}

export interface Product {
  id: number
  code: string
  name: string
  category?: string | null
  season?: string | null
  enabled: boolean
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

export interface Supplier {
  id: number
  code: string
  name: string
  contactName?: string | null
  contactPhone?: string | null
  address?: string | null
  enabled: boolean
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

export const mdmApi = {
  listWarehouses: (onlyEnabled = false) =>
    request<Warehouse[]>('/warehouses', { query: { onlyEnabled } }),
  listProducts: (params: { keyword?: string; page?: number; size?: number } = {}) =>
    request<PageData<Product>>('/products', {
      query: {
        keyword: params.keyword,
        page: params.page ?? 1,
        size: params.size ?? 200,
      },
    }),
  getProduct: (id: number) => request<Product>(`/products/${id}`),
  listSkus: (params: {
    productId?: number
    color?: string
    /** 尺码筛选必须叫 skuSize：size 是分页条数（与 Web 同一条约定）。 */
    skuSize?: string
    page?: number
    size?: number
  } = {}) =>
    request<PageData<Sku>>('/skus', {
      query: {
        productId: params.productId,
        color: params.color,
        skuSize: params.skuSize,
        page: params.page ?? 1,
        size: params.size ?? 200,
      },
    }),
  getSku: (id: number) => request<Sku>(`/skus/${id}`),
  listSuppliers: (params: { keyword?: string; page?: number; size?: number } = {}) =>
    request<PageData<Supplier>>('/suppliers', {
      query: {
        keyword: params.keyword,
        page: params.page ?? 1,
        size: params.size ?? 200,
      },
    }),
  listCustomers: (params: { keyword?: string; page?: number; size?: number } = {}) =>
    request<PageData<Customer>>('/customers', {
      query: {
        keyword: params.keyword,
        page: params.page ?? 1,
        size: params.size ?? 200,
      },
    }),
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

export const invApi = {
  query: (params: {
    skuId?: number
    warehouseId?: number
    batchNo?: string
    onlyAvailable?: boolean
  } = {}) => request<InventoryBalance[]>('/inventory', { query: params }),
  ledger: (params: {
    skuId?: number
    warehouseId?: number
    bizRefNo?: string
    from?: string
    to?: string
    page?: number
    size?: number
  } = {}) => request<PageData<LedgerEntry>>('/inventory/ledger', { query: params }),
  consistencyCheck: () =>
    request<{ consistent: boolean; checked?: boolean }>('/inventory/consistency-check'),
}

// ─────────────────────────── 单据（移动端只读 + 单动作） ───────────────────────────

export interface OrderLine {
  id: number
  lineNo: number
  skuId: number
  qty: number
  unitPriceAmount: number
  amount: number
  /** 进货单：累计已入库量（列表序列化自带；详情由后端映射补齐 openQty） */
  receivedQty?: number
  /** 出货单：累计已出货 / 已退货量 */
  shippedQty?: number
  returnedQty?: number
  /** 详情接口给出；列表接口的领域记录不一定序列化该派生值 */
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

export interface ReceiveLine {
  lineId: number
  qty: number
  batchNo?: string
  productionDate?: string
  expiryDate?: string
}

/**
 * 进货单：列表 / 详情 / 入库。
 *
 * 建单、审核、作废仍留在 Web；移动端只暴露查看与 `receive`。
 * 写操作的 `X-Request-Id` 由 request() 统一注入，页面不手写幂等键。
 */
export const purApi = {
  list: (params: {
    status?: string
    supplierId?: number
    page?: number
    size?: number
  } = {}) => request<PageData<PurchaseOrder>>('/purchase-orders', { query: params }),
  get: (id: number) => request<PurchaseOrder>(`/purchase-orders/${id}`),
  receive: (id: number, body: { lines: ReceiveLine[] }) =>
    request<PurchaseOrder>(`/purchase-orders/${id}/receive`, {
      method: 'POST',
      data: body,
    }),
}

/**
 * 出货单：列表 / 详情 / 出货。
 *
 * `ship` 没有请求体：后端按各行未出量整单出货，批次由 FEFO 决定（FR-SAL-02）。
 * 建单（多行 + 折扣）留在 Web；移动端只做查看与 `ship`。
 */
export const salApi = {
  list: (params: {
    status?: string
    customerId?: number
    page?: number
    size?: number
  } = {}) => request<PageData<SalesOrder>>('/sales-orders', { query: params }),
  get: (id: number) => request<SalesOrder>(`/sales-orders/${id}`),
  ship: (id: number) =>
    request<SalesOrder>(`/sales-orders/${id}/ship`, { method: 'POST' }),
}
