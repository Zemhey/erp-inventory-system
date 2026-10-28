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
  /**
   * 额外请求头（当前只用于自然语言写意图的 `X-Nl-Plan-Token` 回执）。
   * 不在这里放鉴权/幂等键：token 与 X-Request-Id 由上面统一处理，
   * 给调用方留口子只是为了"写意图二次确认"这种服务端强制带凭据的场景。
   */
  headers?: Record<string, string>
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

    // 调用方自定义的请求头最后覆盖：目前仅自然语言回执用到，不影响鉴权/幂等键的优先级
    if (options.headers) {
      Object.assign(header, options.headers)
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

/** 本人人事档案：V29 之后 /auth/me 会带；没录人事信息的账号为 null，页面显示"未填写"。 */
export interface EmployeeProfile {
  employeeNo: string | null
  phone: string | null
  email: string | null
  hireDate: string | null
  remark: string | null
  departmentId: number | null
  departmentName: string | null
  positionId: number | null
  positionName: string | null
}

export interface MeResult {
  username: string
  displayName: string
  roles: string[]
  permissions: string[]
  tenantId?: number
  tenantCode?: string
  tenantName?: string
  employee?: EmployeeProfile | null
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
  /**
   * 改自己的密码。
   *
   * silent：改密失败的提示必须显示在表单旁边（"原密码错误"要人对着输入框看），
   * 弹一个转瞬即逝的 toast 等于让他重输一遍才发现。
   */
  changePassword: (oldPassword: string, newPassword: string) =>
    request<{ changed: boolean }>('/auth/change-password', {
      method: 'POST',
      data: { oldPassword, newPassword },
      silent: true,
    }),
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

/**
 * 库存余额分页行（GET /inventory/page）。
 *
 * ★ 行里自带 `skuName / warehouseName`：后端按主数据拼好直接返回，
 *   移动端不再依赖本地 200 条 SKU 字典翻译（字典外的行原来只能显示 `SKU#123`）。
 *   字段名与全量接口保持对齐，多出来的可读名称只是"加分项"，前端用哪个都行。
 */
export interface InventoryPageRow {
  skuId: number
  productCode: string
  productName: string
  category?: string | null
  color?: string | null
  size?: string | null
  barcode?: string | null
  skuName: string
  warehouseId: number
  warehouseName: string
  batchNo: string
  expiryDate?: string | null
  onHandQty: number
  reservedQty: number
  frozenQty: number
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
  /**
   * 服务端分页查询（GET /inventory/page）。
   *
   * 为什么不全量拉：库存行有上千条，首屏一次性返回会让前端渲染与逐行翻译一起卡住；
   * 分页后一页 50 行 + 关键字过滤，首屏立刻出数。size 上限 200，移动端用 20 翻页即可。
   */
  page: (params: {
    skuId?: number
    warehouseId?: number
    batchNo?: string
    onlyAvailable?: boolean
    keyword?: string
    page?: number
    size?: number
  } = {}) => request<PageData<InventoryPageRow>>('/inventory/page', { query: params }),
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
  receipts: (id: number) => request<PurchaseReceipt[]>(`/purchase-orders/${id}/receipts`),
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

// ─────────────────────────── V2-D1：销售闭环与审批搬到移动端 ───────────────────────────
//
// ★ 类型的事实源是 Controller 的 toMap 与领域 record，不是 openapi.yaml
//   （本批并行泳道正在改契约）。列表接口多数直接把领域记录交给 Jackson 序列化，
//   详情接口才走 toMap —— 两者字段集合不同，下面的可选字段就是这个分歧，
//   不是「后端可能返回」的猜测。页面不得假设列表里有只在详情出现的派生值。

/** 报价单明细行（SalesQuoteController:143 与 SalesQuoteLine 字段一致）。 */
export interface QuoteLine {
  id: number
  lineNo: number
  skuId: number
  qty: number
  /** 整数分 */
  unitPriceAmount: number
  /** 整数万分比：10000 = 不打折 */
  discountRate: number
  /** 整数分 */
  amount: number
}

/**
 * 报价单头。状态是共享单据七态（DocumentStatus），转化后变 COMPLETED。
 *
 * - `convertedOrderId`：详情 toMap 给出；
 * - `convertRequestId` / `version`：只有列表（领域记录）给出；
 * - 转化成功的判据是 `status === 'COMPLETED' && convertedOrderId != null`
 *   （后端 converted() 就是这个式子，SaleQuote:22）。
 */
export interface SalesQuote {
  id: number
  quoteNo: string
  status: string
  customerId: number
  bizDate: string | null
  validUntil: string | null
  totalAmount: number
  totalQty: number
  remark: string | null
  convertedOrderId?: number | null
  convertRequestId?: string | null
  version?: number
  lines: QuoteLine[]
}

export interface QuoteCreateLine {
  skuId: number
  qty: number
  unitPriceAmount: number
  discountRate?: number
}

/**
 * 报价单（FR-SAL-05）：列表 / 详情 / 提交 / 审核 / 驳回 / 作废 / 转出货单。
 *
 * 建单留在 Web（多行 + SKU 选择 + 折扣是桌面表单）。
 * `convert` 只有 APPROVED 且未过期可转；warehouseId 省略时后端按仓库 priority 自动拆单。
 */
export const quoteApi = {
  list: (params: {
    status?: string
    customerId?: number
    page?: number
    size?: number
  } = {}) => request<PageData<SalesQuote>>('/sales-quotes', { query: params }),
  get: (id: number) => request<SalesQuote>(`/sales-quotes/${id}`),
  submit: (id: number) => request<SalesQuote>(`/sales-quotes/${id}/submit`, { method: 'POST' }),
  approve: (id: number) => request<SalesQuote>(`/sales-quotes/${id}/approve`, { method: 'POST' }),
  reject: (id: number) => request<SalesQuote>(`/sales-quotes/${id}/reject`, { method: 'POST' }),
  voidQuote: (id: number) => request<SalesQuote>(`/sales-quotes/${id}/void`, { method: 'POST' }),
  convert: (id: number, body: { warehouseId?: number; remark?: string } = {}) =>
    request<SalesOrder>(`/sales-quotes/${id}/convert`, { method: 'POST', data: body }),
}

/**
 * 发货拣货行。
 * 详情 toMap 额外给了 `shipmentLineId` 作为 `id` 的别名（给扫码回传用的），
 * 列表是领域记录、没有这个别名 —— 页面取值要用 `line.shipmentLineId ?? line.id`。
 */
export interface ShipmentLine {
  id: number
  shipmentLineId?: number
  lineNo: number
  salesOrderLineId: number
  skuId: number
  reservationId: number
  batchNo: string | null
  plannedQty: number
  pickedQty: number
}

/** 发货单：状态是 ShipmentStatus 五态（不是单据七态），不参与收付款/账期统计。 */
export interface Shipment {
  id: number
  shipmentNo: string
  planId: number
  salesOrderId: number
  warehouseId: number
  status: string
  lines: ShipmentLine[]
  confirmRequestId?: string | null
  version?: number
}

/** 发货计划头：一张出货单一个计划，跨仓时 plan.shipments 会有多张。 */
export interface ShipmentPlan {
  planId: number
  planNo: string
  salesOrderId: number
  shipments: Shipment[]
}

/**
 * 发货作业（FR-SAL-06）：为出货单生成计划 → 逐行扫码 → 整单确认。
 *
 * `scan` 不带幂等键要求，但仍由拦截器统一注入；条码非空时后端逐字比对 SKU 条码，
 * 不匹配即报错（扫码枪与手录走的是同一个接口，降级不放松校验）。
 * `confirm` 要求全部行扫满（状态 PICKED），一次出库并登记应收。
 */
export const shipmentApi = {
  createPlan: (salesOrderId: number) =>
    request<ShipmentPlan>(`/sales-orders/${salesOrderId}/shipments`, { method: 'POST' }),
  list: (params: {
    status?: string
    salesOrderId?: number
    page?: number
    size?: number
  } = {}) => request<PageData<Shipment>>('/shipments', { query: params }),
  get: (id: number) => request<Shipment>(`/shipments/${id}`),
  scan: (id: number, body: { shipmentLineId: number; qty: number; barcode?: string }) =>
    request<Shipment>(`/shipments/${id}/scan`, { method: 'POST', data: body }),
  confirm: (id: number) => request<Shipment>(`/shipments/${id}/confirm`, { method: 'POST' }),
}

/**
 * 客户应收。恒等式 `balance = shipped − returned − received` 由数据库 CHECK 兜底。
 *
 * `overdue` 只在详情给出（后端按当天判定）；列表是领域记录，只有 `version`。
 * status 取值 OPEN / SETTLED / VOIDED（ReceivableRepository 的 CASE 表达式）。
 */
export interface Receivable {
  id: number
  salesOrderId: number
  customerId: number
  bizDate: string | null
  dueDate: string | null
  shippedAmount: number
  returnedAmount: number
  receivedAmount: number
  balance: number
  status: string
  overdue?: boolean
  version?: number
}

/** 客户应收汇总（/receivables/summary）：balance 由后端算，页面只复述不自算。 */
export interface ReceivableSummary {
  customerId: number
  shippedAmount: number
  returnedAmount: number
  receivedAmount: number
  balance: number
  overdueAmount: number
}

export interface ReceiptAllocation {
  id: number
  receivableId: number
  amount: number
}

/** 收款单。核销守恒：Σ allocations == amount（精确相等，少一分多一分都拒）。 */
export interface CustomerReceipt {
  id: number
  receiptNo: string
  customerId: number
  amount: number
  bizDate: string | null
  remark: string | null
  allocations: ReceiptAllocation[]
}

/** 应收（FR-SAL-07）：只读列表 / 汇总 / 详情。 */
export const receivableApi = {
  list: (params: {
    customerId?: number
    status?: string
    overdue?: boolean
    page?: number
    size?: number
  } = {}) => request<PageData<Receivable>>('/receivables', { query: params }),
  summary: (customerId: number) =>
    request<ReceivableSummary>('/receivables/summary', { query: { customerId } }),
  get: (id: number) => request<Receivable>(`/receivables/${id}`),
}

/**
 * 收款登记（FR-SAL-08）。
 *
 * 超额核销会被后端拒成 `SAL_RECEIPT_OVER_ALLOCATED`，消息里带应收 id、余额与本次核销额 ——
 * 页面必须**逐字**显示，不能自己拼一句"金额太大"（那是第二套口径）。
 */
export const receiptApi = {
  create: (body: {
    customerId: number
    amount: number
    bizDate?: string
    remark?: string
    allocations: Array<{ receivableId: number; amount: number }>
  }) => request<CustomerReceipt>('/customer-receipts', { method: 'POST', data: body }),
  list: (params: { customerId?: number; page?: number; size?: number } = {}) =>
    request<PageData<CustomerReceipt>>('/customer-receipts', { query: params }),
  get: (id: number) => request<CustomerReceipt>(`/customer-receipts/${id}`),
}

/**
 * 审批节点（实例快照态）。
 * `approverPermission` 是这个节点要求的权限码 —— 「待我审批」就是按它判定的。
 */
export interface ApprovalStepInfo {
  stepNo: number
  name: string
  approverPermission: string
  approveMode: string
  requiredApprovals: number
  thresholdAmount: number
  status: string
  approvedBy: number[]
  decidedAt: string | null
  decisionBy: number | null
  remark: string | null
}

/**
 * 审批实例。★ 状态是 ApprovalStatus 七态，与单据七态是**两套状态机**，
 * 同名不同义（APPROVED 在这里是「审批通过」、在单据侧是「已审核」）。
 */
export interface ApprovalInstance {
  id: number
  flowId: number
  bizType: string
  bizId: number
  bizRefNo: string
  amount: number
  currentStep: number
  status: string
  initiatorId: number
  steps: ApprovalStepInfo[]
}

/**
 * 审批流（FR-APS-02）：列表 / 详情 / 提交 / 同意 / 驳回 / 撤回 / 转办 / 接办。
 *
 * ★ 后端 `GET /approvals` **没有**「按当前人」的过滤参数（只有 status / bizType），
 *   逐节点资格在应用层按 approverPermission 判 —— 所以「待我审批」是客户端按
 *   「实例在飞 且 我有当前节点的 approverPermission」筛出来的，最终能否执行仍由后端 403 兜底。
 * 动作权限码各不相同：approve / reject / transfer 要 aps:approve，
 * withdraw 要 aps:withdraw 且只有发起人本人才走得通。
 */
export const approvalApi = {
  list: (params: {
    status?: string
    bizType?: string
    page?: number
    size?: number
  } = {}) => request<PageData<ApprovalInstance>>('/approvals', { query: params }),
  get: (id: number) => request<ApprovalInstance>(`/approvals/${id}`),
  submit: (body: { bizType: string; bizId: number; bizRefNo: string; amount: number }) =>
    request<ApprovalInstance>('/approvals', { method: 'POST', data: body }),
  approve: (id: number, remark?: string) =>
    request<ApprovalInstance>(`/approvals/${id}/approve`, {
      method: 'POST',
      data: remark ? { remark } : undefined,
    }),
  reject: (id: number, remark?: string) =>
    request<ApprovalInstance>(`/approvals/${id}/reject`, {
      method: 'POST',
      data: remark ? { remark } : undefined,
    }),
  withdraw: (id: number, remark?: string) =>
    request<ApprovalInstance>(`/approvals/${id}/withdraw`, {
      method: 'POST',
      data: remark ? { remark } : undefined,
    }),
  transfer: (id: number, body: { targetUserId: number; remark?: string }) =>
    request<ApprovalInstance>(`/approvals/${id}/transfer`, { method: 'POST', data: body }),
  acceptTransfer: (id: number) =>
    request<ApprovalInstance>(`/approvals/${id}/accept-transfer`, { method: 'POST' }),
}

/** 账号（只为转办选人存在；iam:read 才拿得到，页面按权限降级为手输 ID）。 */
export interface IamUser {
  id: number
  username: string
  displayName: string
  enabled: boolean
  roles: string[]
  permissions: string[]
}

export const iamApi = {
  listUsers: (params: { keyword?: string; page?: number; size?: number } = {}) =>
    request<PageData<IamUser>>('/iam/users', { query: params }),
}

// ─────────── PDA 作业（扫码收货 / 盘点）───────────
// 从 erp-v29-mobile 泳道捞回的三个页所用；那批页面从未进过版本控制，
// 四个端点已逐个对现在的后端验过（/inventory/stocktakes、
// /inventory/stocktakes/{id}/post、/purchase-orders/{id}/receipts）。

/** 到货记录行（GET /purchase-orders/{id}/receipts，列级快照）。 */
export interface PurchaseReceiptLine {
  id: number
  lineNo: number
  purchaseOrderLineId: number
  skuId: number
  batchNo: string | null
  receivedQty: number
  qcQty: number
}

export interface PurchaseReceipt {
  id: number
  receiptNo: string
  purchaseOrderId: number
  supplierId: number
  warehouseId: number
  bizDate: string | null
  totalQty: number
  acceptedQty: number
  rejectedQty: number
  acceptedAmount: number
  lines: PurchaseReceiptLine[]
}

export interface StocktakeLine {
  lineNo: number
  skuId: number
  batchNo: string
  bookQty: number
  countedQty: number
  diffQty: number
}

export interface Stocktake {
  id: number
  stocktakeNo: string
  warehouseId: number
  status: string
  bizDate: string | null
  remark: string | null
  totalDiffQty: number
  postedAt: string | null
  createdAt: string | null
  lines: StocktakeLine[]
}

export const stocktakeApi = {
  list: (params: { status?: string; warehouseId?: number; page?: number; size?: number } = {}) =>
    request<PageData<Stocktake>>('/inventory/stocktakes', { query: params }),
  create: (body: {
    warehouseId: number
    bizDate?: string
    remark?: string
    lines: Array<{ skuId: number; batchNo?: string; countedQty: number }>
  }) => request<Stocktake>('/inventory/stocktakes', { method: 'POST', data: body }),
  post: (id: number) =>
    request<Stocktake>(`/inventory/stocktakes/${id}/post`, { method: 'POST' }),
}

// ─────────────────────────── 工作台聚合（FR-V3-02） ───────────────────────────

export interface DashboardKpis {
  productTotal: number
  skuTotal: number
  customerTotal: number
  supplierTotal: number
  purchaseOrderTotal: number
  salesOrderTotal: number
  inventoryRowTotal: number
  lowStockTotal: number
}

export interface DashboardTodos {
  approvalPending: number
  purchaseReceipt: number
  salesShip: number
  lowStock: number
  items: unknown[]
}

export interface DashboardSummary {
  kpis: DashboardKpis
  /** 图表数据移动端不渲染，留作占位避免类型收窄报错 */
  charts: Record<string, unknown>
  todos: DashboardTodos
  recentDocuments: unknown[]
}

// ─────────────────────────── 待办中心（FR-V3-03） ───────────────────────────

/** 四种待办的字段并集，按 type 决定渲染哪一列，不每个 Tab 各定义一套。 */
export interface TodoItem {
  type?: string
  id: number
  no?: string
  status?: string
  bizDate?: string
  partyName?: string
  warehouseName?: string
  totalQty?: number
  totalAmount?: number
  skuId?: number
  warehouseId?: number
  productCode?: string
  productName?: string
  variant?: string
  minLevel?: number
  onHandQty?: number
  availableQty?: number
  bizType?: string
  bizId?: number
  bizRefNo?: string
  amount?: number
  currentStep?: number
  createdAt?: string
}

export type TodoType = 'approval' | 'purchase-receipt' | 'sales-ship' | 'low-stock'

export const skeletonApi = {
  /** 工作台聚合：KPI 与四类待办计数都来自后端，前端只复述不自算。 */
  dashboardSummary: () => request<DashboardSummary>('/dashboard/summary'),
  /** 待办中心：按 type 拉一类，limit 默认 50（与 Web 端一致）。 */
  todos: (type: TodoType, limit = 50) =>
    request<{ items: TodoItem[] }>('/todos', { query: { type, limit } }),
}

// ─────────────────────────── 自然语言操作（FR-SYS-04） ───────────────────────────

export interface NlCommandResult {
  intent: string
  description: string
  write: boolean
  data: unknown
}

export interface NlPlanResult {
  intent: string
  write: boolean
  /** 写意图必须为 true：执行前必须有人点确认，只读恒为 false。 */
  requiresReview: boolean
  reviewReason: string
  executable: boolean
  summary: string
  steps: string[]
  impact: { label: string; value: string }[]
  warnings: string[]
  blockers: string[]
  args: Record<string, unknown>
}

export interface NlReview {
  verdict: string
  blocking: boolean
  source: string
  summary: string
  findings: { level: string; source: string; message: string }[]
}

export interface NlConfirmation {
  required: boolean
  token: string | null
  expiresAt: string | null
  reason: string
}

export interface NlPlanResponse {
  plan: NlPlanResult
  review: NlReview
  confirmation: NlConfirmation
}

export const nlApi = {
  /**
   * 只生成计划、跑复核、签发确认凭据；不执行任何写操作，可安全重复调用。
   * 写意图会返回 `confirmation.token`，执行时随 `/nl/command` 带回服务端做二次校验。
   * `silent` 让自然语言页自己展示内联错误，而不是只弹一个 toast code。
   */
  plan: (command: string, silent = false) =>
    request<NlPlanResponse>('/nl/plan', { method: 'POST', data: { command }, silent }),
  /**
   * 执行。`planToken` 仅写意图需要：缺了服务端会 409 拒绝（闸门在服务端，绕过页面没用）。
   * 只读意图传不传都一样 —— 读不审。同样支持 `silent` 以走内联报错。
   */
  command: (command: string, planToken?: string, silent = false) =>
    request<NlCommandResult>('/nl/command', {
      method: 'POST',
      data: { command },
      headers: planToken ? { 'X-Nl-Plan-Token': planToken } : undefined,
      silent,
    }),
}
