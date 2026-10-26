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

/**
 * 租户元数据（B1）。与契约 `TenantView` 同形。
 *
 * `enabled=false` 的租户仍会出现在成员列表里（前端置灰），但后端在登录/切换时
 * 仍会独立拒绝 —— 显示与授权是两件事。
 */
export interface TenantView {
  id: number
  code: string
  name: string
  enabled: boolean
}

/** 登录 / 切换租户 / 刷新会话共用的会话快照（契约 `LoginResult` 去掉 token）。 */
export interface SessionSnapshot {
  username: string
  displayName: string | null
  roles: string[]
  permissions: string[]
  mustChangePassword: boolean
  passwordExpiresAt: string | null
  passwordRules: string[]
  tenantId: number
  tenantCode: string
  tenantName: string
  tenants: TenantView[]
}

/** 登录与切换租户的响应（契约 `LoginResult`）。 */
export interface LoginPayload extends SessionSnapshot {
  token: string
  tokenType: string
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

/**
 * 极小的认证事件总线（目前只有一个事件：password-expired）。
 *
 * 为什么不用 pinia store：client.ts 被 store 引用，store 再反向引用 client 就是循环依赖，
 * 而 vite 下循环依赖的表现是"有时能用有时白屏"。这里用一个没有依赖的模块级 Set，
 * 订阅方（MainLayout）负责在卸载时退订。
 */
export type AuthEventName = 'password-expired'
const authListeners = new Set<() => void>()

export function onAuthEvent(name: AuthEventName, handler: () => void): () => void {
  void name // 目前只有一种事件，先不分支，避免为单个事件写一套分发
  authListeners.add(handler)
  return () => authListeners.delete(handler)
}

function emitAuthEvent(_name: AuthEventName): void {
  authListeners.forEach((handler) => handler())
}

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
      // token 无效 / 过期：清掉本地 token 并回登录页。
      // 但在登录页本身，401 是“用户名或密码错误”的业务结果，必须原样展示 ——
      // 否则用户点了登录毫无反馈（M3-S8 E2E-02 实测发现的缺陷）。
      clearToken()
      if (window.location.pathname === '/login') {
        ElMessage.error(message)
      } else {
        ElMessage.warning('登录已过期，请重新登录')
        window.location.href = '/login'
      }
      return Promise.reject(new Error(message))
    }

    if (status === 403) {
      // 密码过期/强制改密：不弹普通 toast（用户点哪儿都是同一条 403，弹十条没有意义），
      // 改为广播事件，由 MainLayout 打开"必须改密"对话框 —— 改密是此状态下唯一能做的事。
      if (payload?.code === 'IAM_PASSWORD_EXPIRED') {
        emitAuthEvent('password-expired')
        return Promise.reject(new Error(payload?.message ?? '必须先修改密码'))
      }
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

/**
 * 认证（FR-IAM-01 / FR-TNT-07）。
 *
 * ★ switchTenant 返回**新 token**（tid 已变）：调用方必须整体覆盖会话，
 *   绝不能用旧 token 继续请求 —— 那会让界面显示与实际数据边界不一致。
 */
export const authApi = {
  login: (username: string, password: string, tenantCode?: string) =>
    http.post<never, LoginPayload>('/auth/login', {
      username,
      password,
      ...(tenantCode && tenantCode.trim() ? { tenantCode: tenantCode.trim() } : {}),
    }),
  me: () => http.get<never, SessionSnapshot>('/auth/me'),
  switchTenant: (tenantCode: string) =>
    http.post<never, LoginPayload>('/auth/switch-tenant', { tenantCode }),
  /**
   * 自助修改密码（FR-IAM-06）。
   *
   * ★ 这是"必须改密"状态下后端放行的**唯一写接口**（见 PasswordExpiryFilter 白名单）：
   *   后端的做法是登录照发 token、但把其它业务接口拦成 403 IAM_PASSWORD_EXPIRED，
   *   这样过期用户还能自救，不必找管理员。所以前端在这个状态下**必须**只留改密入口，
   *   否则用户到处点都会撞 403，看起来像系统坏了。
   *   校验规则以后端 `passwordRules`（PasswordPolicy.describe()）为准，弱密码返回
   *   400 IAM_PASSWORD_WEAK，message 里带具体缺哪条。
   */
  changePassword: (oldPassword: string, newPassword: string) =>
    http.post<never, { changed: boolean; mustChangePassword: boolean }>('/auth/change-password', {
      oldPassword,
      newPassword,
    }),
}

/**
 * 平台租户管理（B1 · FR-TNT-08）。
 *
 * 只有平台租户管理员（`tenant:manage`，且**不享受 ADMIN 短路**）可调用；
 * 普通租户管理员在菜单上看不到入口，直接请求也是 403。
 */
export const tenantApi = {
  list: () => http.get<never, { items: TenantView[] }>('/tenants'),
  setEnabled: (id: number, enabled: boolean) =>
    http.post<never, TenantView>(`/tenants/${id}/enabled`, { enabled }),
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

  // 商品规格属性（V18 · B2）：全量替换，空数组=清空
  listProductAttributes: (productId: number) =>
    http.get<never, { items: ProductAttribute[] }>(`/products/${productId}/attributes`),
  replaceProductAttributes: (productId: number, attributes: ProductAttribute[]) =>
    http.put<never, { items: ProductAttribute[] }>(`/products/${productId}/attributes`, {
      attributes,
    }),

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
  updateSupplier: (id: number, body: SupplierUpdate) =>
    http.put<never, Supplier>(`/suppliers/${id}`, body),
  disableSupplier: (id: number) => http.post<never, Supplier>(`/suppliers/${id}/disable`),

  // 客户
  listCustomers: (params: { keyword?: string; page?: number; size?: number }) =>
    http.get<never, PageData<Customer>>('/customers', { params }),
  createCustomer: (body: CustomerCreate) => http.post<never, Customer>('/customers', body),
  updateCustomer: (id: number, body: CustomerUpdate) =>
    http.put<never, Customer>(`/customers/${id}`, body),
  disableCustomer: (id: number) => http.post<never, Customer>(`/customers/${id}/disable`),

  // 客户收货地址（V18 · B2）
  listCustomerAddresses: (customerId: number, onlyEnabled = false) =>
    http.get<never, CustomerAddress[]>(`/customers/${customerId}/addresses`, {
      params: { onlyEnabled },
    }),
  createCustomerAddress: (customerId: number, body: CustomerAddressCreate) =>
    http.post<never, CustomerAddress>(`/customers/${customerId}/addresses`, body),
  updateCustomerAddress: (
    customerId: number,
    addressId: number,
    body: CustomerAddressUpdate,
  ) => http.put<never, CustomerAddress>(`/customers/${customerId}/addresses/${addressId}`, body),
  setDefaultCustomerAddress: (customerId: number, addressId: number) =>
    http.post<never, CustomerAddress>(
      `/customers/${customerId}/addresses/${addressId}/default`,
    ),
  disableCustomerAddress: (customerId: number, addressId: number) =>
    http.post<never, CustomerAddress>(
      `/customers/${customerId}/addresses/${addressId}/disable`,
    ),
  enableCustomerAddress: (customerId: number, addressId: number) =>
    http.post<never, CustomerAddress>(
      `/customers/${customerId}/addresses/${addressId}/enable`,
    ),

  // 仓库（不分页，返回数组）
  listWarehouses: (onlyEnabled = false) =>
    http.get<never, Warehouse[]>('/warehouses', { params: { onlyEnabled } }),
  createWarehouse: (body: WarehouseCreate) => http.post<never, Warehouse>('/warehouses', body),
  updateWarehouse: (id: number, body: WarehouseUpdate) =>
    http.put<never, Warehouse>(`/warehouses/${id}`, body),
  disableWarehouse: (id: number) => http.post<never, Warehouse>(`/warehouses/${id}/disable`),

  // 仓库库位与容量（V18 · B2）
  listWarehouseLocations: (warehouseId: number, onlyEnabled = false) =>
    http.get<never, WarehouseLocation[]>(`/warehouses/${warehouseId}/locations`, {
      params: { onlyEnabled },
    }),
  createWarehouseLocation: (warehouseId: number, body: WarehouseLocationCreate) =>
    http.post<never, WarehouseLocation>(`/warehouses/${warehouseId}/locations`, body),
  updateWarehouseLocation: (
    warehouseId: number,
    locationId: number,
    body: WarehouseLocationUpdate,
  ) =>
    http.put<never, WarehouseLocation>(
      `/warehouses/${warehouseId}/locations/${locationId}`,
      body,
    ),
  disableWarehouseLocation: (warehouseId: number, locationId: number) =>
    http.post<never, WarehouseLocation>(
      `/warehouses/${warehouseId}/locations/${locationId}/disable`,
    ),
  enableWarehouseLocation: (warehouseId: number, locationId: number) =>
    http.post<never, WarehouseLocation>(
      `/warehouses/${warehouseId}/locations/${locationId}/enable`,
    ),
  reconcileWarehouseLocations: (warehouseId: number) =>
    http.get<never, WarehouseLocationReconcile>(
      `/warehouses/${warehouseId}/locations/reconcile`,
    ),
  setWarehouseCapacity: (warehouseId: number, capacityQty: number) =>
    http.put<never, Warehouse>(`/warehouses/${warehouseId}/capacity`, { capacityQty }),
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

/**
 * 库存作业（V2-1 FR-INV-07~13）：调拨 / 盘点 / 调整 / 冻结 / 洞察。
 *
 * 写操作由拦截器自动带 X-Request-Id；页面只负责收集参数。
 * 所有数量字段都是后端算好的结果，前端不做库存判断。
 */
export const invOpsApi = {
  // ── 调拨 ──
  listTransfers: (params: {
    status?: TransferStatus
    fromWarehouseId?: number
    toWarehouseId?: number
    page?: number
    size?: number
  }) => http.get<never, PageData<InventoryTransfer>>('/inventory/transfers', { params }),
  createTransfer: (body: {
    fromWarehouseId: number
    toWarehouseId: number
    bizDate?: string
    remark?: string
    lines: { skuId: number; batchNo?: string; qty: number; unitCostAmount?: number }[]
  }) => http.post<never, InventoryTransfer>('/inventory/transfers', body),
  receiveTransfer: (id: number) =>
    http.post<never, InventoryTransfer & { conservationDiff: number }>(
      `/inventory/transfers/${id}/receive`,
    ),

  // ── 盘点 ──
  listStocktakes: (params: { status?: StocktakeStatus; warehouseId?: number; page?: number; size?: number }) =>
    http.get<never, PageData<Stocktake>>('/inventory/stocktakes', { params }),
  createStocktake: (body: {
    warehouseId: number
    bizDate?: string
    remark?: string
    lines: { skuId: number; batchNo?: string; countedQty: number }[]
  }) => http.post<never, Stocktake>('/inventory/stocktakes', body),
  postStocktake: (id: number) => http.post<never, Stocktake>(`/inventory/stocktakes/${id}/post`),

  // ── 调整 ──
  listAdjustments: (params: { skuId?: number; warehouseId?: number; page?: number; size?: number }) =>
    http.get<never, PageData<InventoryAdjustment>>('/inventory/adjustments', { params }),
  createAdjustment: (body: {
    skuId: number
    warehouseId: number
    batchNo?: string
    changeQty: number
    reason: string
    unitCostAmount?: number
  }) => http.post<never, InventoryAdjustment>('/inventory/adjustments', body),

  // ── 冻结 ──
  listFreezes: (params: {
    status?: FreezeStatus
    skuId?: number
    warehouseId?: number
    page?: number
    size?: number
  }) => http.get<never, PageData<InventoryFreeze>>('/inventory/freezes', { params }),
  createFreeze: (body: {
    skuId: number
    warehouseId: number
    batchNo?: string
    qty: number
    reason?: string
    expiresAt?: string
  }) => http.post<never, InventoryFreeze>('/inventory/freezes', body),
  releaseFreeze: (id: number) =>
    http.post<never, InventoryFreeze>(`/inventory/freezes/${id}/release`),

  // ── 洞察 ──
  balanceDimensions: (params: {
    skuId?: number
    warehouseId?: number
    batchNo?: string
    status?: StockStatus
    ownership?: 'SELF'
  }) =>
    http.get<never, { items: BalanceDimensionRow[]; totals: BalanceDimensionTotals }>(
      '/inventory/balance-dimensions',
      { params },
    ),
  alerts: (params: {
    warehouseId?: number
    type?: AlertType
    expiringDays?: number
    slowMovingDays?: number
  }) => http.get<never, { items: InventoryAlertRow[]; total: number }>('/inventory/alerts', { params }),
  replenishment: (params: { warehouseId?: number }) =>
    http.get<never, { items: ReplenishmentSuggestion[]; total: number }>('/inventory/replenishment', {
      params,
    }),
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
  // 后端 PurchaseOrderController 有 @PostMapping("/{id}/void")；
  // 已入库的单据走红冲（FR-PUR-03，M2.5：回退实存 + PURCHASE_REVERSAL 流水），
  // 库存不足以回退时后端返回 INV_NEGATIVE_NOT_ALLOWED，错误消息原样透出。
  voidOrder: (id: number) => http.post<never, PurchaseOrder>(`/purchase-orders/${id}/void`),
  receive: (id: number, body: { lines: ReceiveLine[] }) =>
    http.post<never, PurchaseOrder>(`/purchase-orders/${id}/receive`, body),
}

/** 出货（FR-SAL-01/02）。 */
export const salApi = {
  list: (params: { status?: string; customerId?: number; page?: number; size?: number }) =>
    http.get<never, PageData<SalesOrder>>('/sales-orders', { params }),
  get: (id: number) => http.get<never, SalesOrder>(`/sales-orders/${id}`),
  create: (body: SalesOrderCreate) => http.post<never, SalesOrder>('/sales-orders', body),
  /** 自动选仓建单（FR-SAL-11）：priority 越大越优先，单仓不足才跨仓拆分配。 */
  autoSplit: (body: SalesOrderCreate) =>
    http.post<never, SalesOrder>('/sales-orders/auto-split', body),
  submit: (id: number) => http.post<never, SalesOrder>(`/sales-orders/${id}/submit`),
  approve: (id: number) => http.post<never, SalesOrder>(`/sales-orders/${id}/approve`),
  // 后端 SalesOrderController 有 @PostMapping("/{id}/reject")；REJECTED 只给作废（G2：无 reopen）
  reject: (id: number) => http.post<never, SalesOrder>(`/sales-orders/${id}/reject`),
  // 出货无参数：后端按各行未出量整行出货，批次由 FEFO 决定（FR-SAL-02）
  ship: (id: number) => http.post<never, SalesOrder>(`/sales-orders/${id}/ship`),
  // 未出货作废会释放预占；已出货走红冲（FR-SAL-04，M2.5：回补实存 + SALES_REVERSAL 流水）。
  // 有退货数量的单据后端拒绝红冲（SAL_STATUS_ILLEGAL），错误消息原样透出。
  voidOrder: (id: number) => http.post<never, SalesOrder>(`/sales-orders/${id}/void`),
}

/**
 * 退货（FR-RMA-01）。
 *
 * 入库（receive）**没有请求体**：数量、批次、效期在建单时就已确定，
 * 整单一次性回补，不支持分批（§7 决策 7）。
 * X-Request-Id 由请求拦截器统一注入，建单键与入库键在后端分列。
 */
export const rmaApi = {
  // 可退单据：rma:read 单码可读（M2 补口，替代"跨模块读 /sales-orders 要 sal:read"）
  returnableOrders: (params?: { page?: number; size?: number }) =>
    http.get<never, PageData<ReturnableOrder>>('/return-orders/returnable-orders', { params }),
  list: (params: {
    status?: string
    customerId?: number
    originSalesOrderId?: number
    page?: number
    size?: number
  }) => http.get<never, PageData<ReturnOrder>>('/return-orders', { params }),
  get: (id: number) => http.get<never, ReturnOrder>(`/return-orders/${id}`),
  create: (body: ReturnOrderCreate) => http.post<never, ReturnOrder>('/return-orders', body),
  submit: (id: number) => http.post<never, ReturnOrder>(`/return-orders/${id}/submit`),
  approve: (id: number) => http.post<never, ReturnOrder>(`/return-orders/${id}/approve`),
  reject: (id: number) => http.post<never, ReturnOrder>(`/return-orders/${id}/reject`),
  receive: (id: number) => http.post<never, ReturnOrder>(`/return-orders/${id}/receive`),
  // COMPLETED 的作废被业务层拒绝（需红冲，属 M2.5），错误消息原样透出
  voidOrder: (id: number) => http.post<never, ReturnOrder>(`/return-orders/${id}/void`),
}

/**
 * 结算（FR-SET-01/02/03）。
 *
 * ★ 三个动作是三个权限码（`set:daily` / `set:monthly` / `set:reconcile`），
 *   入口页权限只是 `set:read`。所以"能不能进这一页"和"能不能点执行"必须分开判断，
 *   按钮显隐问各自的动作码 —— FINANCE 能结算、VIEWER 只能看，靠的就是这个拆分。
 *
 * ★ 对账是 GET 但也要 `set:reconcile`：它不写数据，却把总账与明细都重算一遍，
 *   属于财务动作而不是查询。
 */
export const setApi = {
  // 账期只读列表：set:read 唯一可消费的读出口（M2 补口 2）
  periods: (params?: { status?: 'OPEN' | 'CLOSED'; page?: number; size?: number }) =>
    http.get<never, PageData<SettlementPeriodRow>>('/settlements/periods', { params }),
  daily: (body: DailySettlementRequest) => http.post<never, DailySettlementResult>('/settlements/daily', body),
  monthly: (body: MonthlySettlementRequest) =>
    http.post<never, MonthlySettlementResult>('/settlements/monthly', body),
  reconcile: (period: string) =>
    http.get<never, ReconcileResult>('/settlements/reconcile', { params: { period } }),
}

/**
 * 报表（FR-RPT-01/02/03）。整个模块只读，全 GET，权限只有一个 `rpt:read`。
 *
 * ★ 两组参数不能混用（计划书 §3.5 / §6 坑 4）：
 *   进货、出货统计收 `from`/`to` 闭区间 + `groupBy`；
 *   进销存汇总收 `period`（yyyy-MM），因为它要的是"期初/期末"，
 *   任意日期区间没有期初概念。
 */
export const rptApi = {
  purchase: (params: { from: string; to: string; groupBy?: ReportGroupBy; warehouseId?: number }) =>
    http.get<never, { items: ReportRow[] }>('/reports/purchase', { params }),
  sales: (params: { from: string; to: string; groupBy?: ReportGroupBy; warehouseId?: number }) =>
    http.get<never, { items: ReportRow[] }>('/reports/sales', { params }),
  inventorySummary: (params: { period: string; warehouseId?: number }) =>
    http.get<never, { items: InventorySummaryRow[] }>('/reports/inventory-summary', { params }),
}

/**
 * 成本毛利（V2-4，FR-CST-01/02/03）。
 *
 * 端点尚未落 openapi.yaml（本泳道只提契约申请），因此路径以申请单为准。
 * 写接口的 X-Request-Id 由请求拦截器统一注入。
 *
 * ★ 毛利数据来自「成本月结」生成的 margin_records：
 *   未执行月结的期间查询结果为空，页面必须把这一点说清楚，
 *   否则用户会以为"卖出去的东西没有毛利"。
 */
export const cstApi = {
  recalculate: () =>
    http.post<never, CostRecalculateResult>('/cost/recalculate'),
  closeMonthly: (body: CostMonthlyRequest) =>
    http.post<never, CostMonthlyResult>('/cost/monthly', body),
  monthly: (period: string) =>
    http.get<never, { items: CostMonthlyRow[] }>('/cost/monthly', { params: { period } }),
  profit: (params: { from: string; to: string; groupBy?: MarginGroupBy }) =>
    http.get<never, { items: MarginSummaryRow[] }>('/reports/profit', { params }),
}

/**
 * 自然语言操作（FR-SYS-04）。
 *
 * 后端只做"解析 + 调度既有 ApplicationService"，业务规则与权限都在服务端；
 * 前端不解析指令、不推断意图，只展示 `intent / description / data`。
 * `X-Request-Id` 由请求拦截器统一注入；写意图时它就是后端建单的幂等键。
 */
export const nlApi = {
  command: (command: string) => http.post<never, NlCommandResult>('/nl/command', { command }),
}

/**
 * 请购单（FR-PUR-04）。复用进货单的存储与状态机，但只有"转订单"一个出口。
 * 转单动作要 `pur:approve`：它把申请变成真实采购承诺。
 */
export const requisitionApi = {
  list: (params: { status?: string; supplierId?: number; page?: number; size?: number }) =>
    http.get<never, PageData<PurchaseRequisition>>('/purchase-requisitions', { params }),
  get: (id: number) => http.get<never, PurchaseRequisition>(`/purchase-requisitions/${id}`),
  create: (body: PurchaseRequisitionCreate) =>
    http.post<never, PurchaseRequisition>('/purchase-requisitions', body),
  submit: (id: number) =>
    http.post<never, PurchaseRequisition>(`/purchase-requisitions/${id}/submit`),
  approve: (id: number) =>
    http.post<never, PurchaseRequisition>(`/purchase-requisitions/${id}/approve`),
  convert: (id: number, body: RequisitionConvertRequest) =>
    http.post<never, PurchaseOrder>(`/purchase-requisitions/${id}/convert`, body),
}

/**
 * 到货/质检记录（FR-PUR-05）。
 * 不合格量只存在于这里，`acceptedQty` 才是进库存与应付的数量。
 */
export const purchaseReceiptApi = {
  listByOrder: (orderId: number) =>
    http.get<never, PurchaseReceipt[]>(`/purchase-orders/${orderId}/receipts`),
}

/** 采购退货（FR-PUR-06）：建单只收到货行与数量，金额由后端派生。 */
export const purchaseReturnApi = {
  list: (params: {
    status?: string
    supplierId?: number
    purchaseOrderId?: number
    page?: number
    size?: number
  }) => http.get<never, PageData<PurchaseReturn>>('/purchase-returns', { params }),
  get: (id: number) => http.get<never, PurchaseReturn>(`/purchase-returns/${id}`),
  create: (body: PurchaseReturnCreate) =>
    http.post<never, PurchaseReturn>('/purchase-returns', body),
  approve: (id: number) => http.post<never, PurchaseReturn>(`/purchase-returns/${id}/approve`),
  voidReturn: (id: number) => http.post<never, PurchaseReturn>(`/purchase-returns/${id}/void`),
}

/**
 * 供应商应付与付款核销（FR-PUR-07/08）。
 *
 * `reconcile` 是可重复的余额/守恒校验入口：每次实时聚合，不读缓存。
 * 付款请求的 `allocations` 合计必须等于 `amount`，否则后端 400 拒绝。
 */
export const payableApi = {
  list: (params: { supplierId?: number; status?: string; page?: number; size?: number }) =>
    http.get<never, PageData<SupplierPayable>>('/supplier-payables', { params }),
  get: (id: number) => http.get<never, SupplierPayable>(`/supplier-payables/${id}`),
  reconcile: (supplierId?: number) =>
    http.get<never, PayableReconcile>('/supplier-payables/reconcile', {
      params: { supplierId },
    }),
  pay: (body: SupplierPaymentRequest) =>
    http.post<never, SupplierPaymentResult>('/supplier-payments', body),
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

/** 商品级扩展规格属性（V18 · B2）：键值对，不参与 SKU 颜色×尺码唯一键。 */
export interface ProductAttribute {
  key: string
  value: string
}

export interface Sku {
  id: number
  productId: number
  color: string
  size: string
  barcode?: string | null
  retailPriceAmount: number
  enabled: boolean
  /** FR-MDM-08 季节/波段/上市日期；年份由上市日期派生 */
  seasonCode?: string | null
  bandCode?: string | null
  launchDate?: string | null
  seasonYear?: number | null
}

export interface SkuCreate {
  productId: number
  color: string
  size: string
  barcode?: string
  retailPriceAmount?: number
  seasonCode?: string
  bandCode?: string
  launchDate?: string
}

export interface SkuUpdate {
  barcode?: string
  retailPriceAmount?: number
  enabled?: boolean
  seasonCode?: string
  bandCode?: string
  launchDate?: string
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
  enabled?: boolean
}

/** 编码创建后不可改，因此更新体不含 code。 */
export interface SupplierUpdate {
  name: string
  contactName?: string
  contactPhone?: string
  address?: string
  enabled?: boolean
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
  /** 0 = 不设限（FR-SAL-09） */
  creditLimit: number
  creditDays: number
}

export interface CustomerCreate {
  code: string
  name: string
  phone?: string
  discountRate?: number
  level?: string
  enabled?: boolean
}

/** 编码创建后不可改，因此更新体不含 code。 */
export interface CustomerUpdate {
  name: string
  phone?: string
  discountRate?: number
  level?: string
  enabled?: boolean
}

/** 客户收货地址（V18 · B2）。同一客户最多一个 isDefault。 */
export interface CustomerAddress {
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

export interface CustomerAddressCreate {
  addressKey: string
  recipientName: string
  phone: string
  province: string
  city: string
  district?: string
  detailAddress: string
  isDefault?: boolean
  enabled?: boolean
}

/** addressKey 创建后不可改。 */
export interface CustomerAddressUpdate {
  recipientName: string
  phone: string
  province: string
  city: string
  district?: string
  detailAddress: string
  isDefault?: boolean
  enabled?: boolean
}

export interface Warehouse {
  id: number
  code: string
  name: string
  address?: string | null
  enabled: boolean
  /** 发货选仓优先级：与 V9 一致，越大越优先（FR-SAL-11） */
  priority: number
  /** 仓库容量（件）；0 = 未设定（V18） */
  capacityQty: number
}

export interface WarehouseCreate {
  code: string
  name: string
  address?: string
  enabled?: boolean
  priority?: number
}

/** 编码创建后不可改，因此更新体不含 code。 */
export interface WarehouseUpdate {
  name: string
  address?: string
  enabled?: boolean
  priority?: number
}

/** 仓库库位（V18 · B2）。库存维度不含库位，容量用于仓内分配核对。 */
export interface WarehouseLocation {
  id: number
  warehouseId: number
  locationCode: string
  name?: string | null
  capacityQty: number
  enabled: boolean
}

export interface WarehouseLocationCreate {
  locationCode: string
  name?: string
  capacityQty: number
  enabled?: boolean
}

/** locationCode 创建后不可改。 */
export interface WarehouseLocationUpdate {
  name?: string
  capacityQty: number
}

/** 守恒核对：SUM(启用库位容量) vs 仓库容量。 */
export interface WarehouseLocationReconcile {
  warehouseId: number
  warehouseCapacityQty: number
  locationCapacityQty: number
  difference: number
  enabledLocationCount: number
  totalLocationCount: number
  consistent: boolean
}

export interface InventoryBalance {
  skuId: number
  warehouseId: number
  batchNo: string
  expiryDate?: string | null
  onHandQty: number
  reservedQty: number
  /** 冻结量：不参与可用量（FR-INV-10） */
  frozenQty: number
  availableQty: number
}

// ═══════════════════════ 库存作业（V2-1） ═══════════════════════

export type TransferStatus = 'IN_TRANSIT' | 'COMPLETED'
export type StocktakeStatus = 'DRAFT' | 'POSTED'
export type FreezeStatus = 'ACTIVE' | 'RELEASED' | 'EXPIRED'
export type StockStatus = 'AVAILABLE' | 'RESERVED' | 'FROZEN'
export type AlertType = 'SAFETY' | 'SLOW_MOVING' | 'EXPIRING'

export interface TransferLine {
  lineNo: number
  skuId: number
  batchNo: string
  qty: number
  /** 仍在途的数量；到货后为 0 */
  inTransitQty: number
  productionDate?: string | null
  expiryDate?: string | null
  unitCostAmount?: number | null
}

export interface InventoryTransfer {
  id: number
  transferNo: string
  fromWarehouseId: number
  toWarehouseId: number
  status: TransferStatus
  bizDate: string
  remark?: string | null
  inTransitQty: number
  receivedAt?: string | null
  createdAt: string
  lines: TransferLine[]
  duplicate?: boolean
}

export interface StocktakeLine {
  lineNo: number
  skuId: number
  batchNo: string
  /** 建单时快照的账面量 */
  bookQty: number
  countedQty: number
  /** counted - book；0 表示不产生调整流水 */
  diffQty: number
}

export interface Stocktake {
  id: number
  stocktakeNo: string
  warehouseId: number
  status: StocktakeStatus
  bizDate: string
  remark?: string | null
  totalDiffQty: number
  postedAt?: string | null
  createdAt: string
  lines: StocktakeLine[]
  duplicate?: boolean
}

export interface InventoryAdjustment {
  id: number
  adjustNo: string
  skuId: number
  warehouseId: number
  batchNo: string
  /** 正=调增，负=调减 */
  changeQty: number
  reason: string
  createdAt: string
  duplicate?: boolean
}

export interface InventoryFreeze {
  id: number
  skuId: number
  warehouseId: number
  batchNo: string
  qty: number
  reason?: string | null
  status: FreezeStatus
  expiresAt?: string | null
  releasedAt?: string | null
  createdAt: string
  duplicate?: boolean
}

export interface BalanceDimensionRow {
  warehouseId: number
  skuId: number
  batchNo: string
  stockStatus: StockStatus
  ownership: 'SELF'
  qty: number
}

export interface BalanceDimensionTotals {
  onHandQty: number
  byStatus: Record<StockStatus, number>
}

export interface InventoryAlertRow {
  type: AlertType
  skuId: number
  warehouseId: number
  batchNo?: string | null
  onHandQty: number
  availableQty: number
  /** 安全库存下限 / 滞销天数 / 临期天数 */
  threshold: number
  expiryDate?: string | null
  lastMovementAt?: string | null
  warehousePriority: number
}

export interface ReplenishmentSuggestion {
  skuId: number
  warehouseId: number
  availableQty: number
  minLevel: number
  maxLevel: number
  suggestedQty: number
  warehousePriority: number
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
  /** 累计质检合格量（FR-PUR-05）：只有合格量入库存/应付 */
  qcQty?: number
  /** NONE / PASSED / PARTIAL / FAILED */
  qcStatus?: string
  qcNote?: string | null
  /** 累计到货但不合格的数量 */
  rejectedQty?: number
  shippedQty?: number
  /** 累计已退货数量；剩余可退 = shippedQty − returnedQty（退货页用） */
  returnedQty?: number
  openQty?: number
  discountRate?: number
  bundleId?: number | null
  bundleGroupNo?: number | null
  bundleRatio?: number | null
  bundleQty?: number | null
  /** 按仓拆分的预占分配（自动选仓时可见） */
  allocations?: { warehouseId: number; qty: number }[]
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
  /** 本次到货中的合格数量；缺省 = qty（兼容旧调用） */
  qcQty?: number
  qcNote?: string
}

/** 请购明细行（FR-PUR-04）。与进货行同构，但没有到货/质检字段。 */
export interface RequisitionLine {
  id: number
  lineNo: number
  skuId: number
  qty: number
  unitPriceAmount: number
  amount: number
}

export interface PurchaseRequisition {
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
  /** 非空表示已转单，不能再修改/转化 */
  convertedOrderId?: number | null
  lines: RequisitionLine[]
}

export interface PurchaseRequisitionCreate {
  supplierId: number
  warehouseId: number
  bizDate?: string
  remark?: string
  lines: { skuId: number; qty: number; unitPriceAmount: number }[]
}

/** 转单必须覆盖全部行；数量 ∈ [1, 申请数量] */
export interface RequisitionConvertRequest {
  bizDate?: string
  remark?: string
  lines: { lineId: number; qty: number }[]
}

/** 到货行（FR-PUR-05）：received = qc + rejected。 */
export interface PurchaseReceiptLine {
  id: number
  lineNo: number
  purchaseOrderLineId: number
  skuId: number
  batchNo: string
  receivedQty: number
  qcQty: number
  rejectedQty: number
  qcStatus: 'PASSED' | 'PARTIAL' | 'FAILED'
  qcNote?: string | null
  unitPriceAmount: number
  acceptedAmount: number
}

export interface PurchaseReceipt {
  id: number
  receiptNo: string
  purchaseOrderId: number
  supplierId: number
  warehouseId: number
  bizDate: string
  totalQty: number
  acceptedQty: number
  rejectedQty: number
  acceptedAmount: number
  lines: PurchaseReceiptLine[]
}

/** 采购退货单（FR-PUR-06）。 */
export interface PurchaseReturn {
  id: number
  returnNo: string
  purchaseOrderId: number
  supplierId: number
  warehouseId: number
  bizDate: string
  period?: string | null
  status: 'DRAFT' | 'COMPLETED' | 'VOIDED'
  totalQty: number
  totalAmount: number
  remark?: string | null
  lines: PurchaseReturnLine[]
}

export interface PurchaseReturnLine {
  receiptLineId: number
  purchaseOrderLineId: number
  skuId: number
  qty: number
  unitPriceAmount: number
  amount: number
  batchNo: string
}

export interface PurchaseReturnCreate {
  purchaseOrderId: number
  bizDate?: string
  remark?: string
  lines: { receiptLineId: number; qty: number }[]
}

/** 应付核销明细（一行 = 一张付款单对一张应付单）。 */
export interface PayableAllocation {
  id: number
  paymentNo: string
  amount: number
  paymentAmount: number
  bizDate: string
  remark?: string | null
}

/**
 * 供应商应付单（FR-PUR-07）。
 * `balance` 是后端生成列，前端只读、不自行计算。
 */
export interface SupplierPayable {
  id: number
  payableNo: string
  supplierId: number
  purchaseOrderId: number
  orderNo: string
  receiptId: number
  bizDate: string
  period?: string | null
  originalAmount: number
  returnedAmount: number
  paidAmount: number
  balance: number
  status: 'OPEN' | 'PARTIAL' | 'SETTLED'
  allocations: PayableAllocation[]
}

/** 余额/核销守恒核对结果（FR-PUR-07/08）。 */
export interface PayableReconcile {
  supplierId?: number | null
  purchaseAmount: number
  returnAmount: number
  paidAmount: number
  balance: number
  paymentTotal: number
  allocationTotal: number
  balanced: boolean
  conserved: boolean
}

export interface SupplierPaymentRequest {
  supplierId: number
  amount: number
  bizDate?: string
  remark?: string
  allocations: { payableId: number; amount: number }[]
}

export interface SupplierPaymentResult {
  paymentNo: string
  paymentAmount: number
  allocations: { payableId: number; amount: number }[]
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

/**
 * 退货单（契约 `ReturnOrder`）。
 *
 * 状态刻意不含 PARTIAL：退货整单一次回补，DB CHECK 与契约 enum 都写死了六态。
 * 客户 / 仓库 / 单价 / 折扣都是后端从原出货单派生的结果，前端只能读、不能传。
 */
export interface ReturnOrder {
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
  lines: ReturnOrderLine[]
}

export interface ReturnOrderLine {
  id: number
  lineNo: number
  /** 原出货单明细行 id；剩余可退的上限来自这一行 */
  originLineId: number
  skuId: number
  qty: number
  unitPriceAmount: number
  discountRate: number
  amount: number
  batchNo?: string | null
  productionDate?: string | null
  expiryDate?: string | null
}

/** 退货建单：只有原行、数量与批次/效期，没有任何金额字段。 */
export interface ReturnOrderCreate {
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
}

/**
 * 可退单据（契约 `ReturnableOrder`）。
 *
 * rma:read 单码即可读，不再跨模块要 sal:read；
 * `lines` 只含还有可退量的行（整行退完的不出现），
 * 客户名与 SKU 展示名由服务端批量补齐，前端不必再查主数据字典。
 */
export interface ReturnableOrderLine {
  originLineId: number
  lineNo: number
  skuId: number
  skuName: string
  shippedQty: number
  returnedQty: number
  returnableQty: number
  unitPriceAmount: number
  discountRate: number
}

export interface ReturnableOrder {
  orderId: number
  orderNo: string
  status: 'PARTIAL' | 'COMPLETED'
  customerId: number
  customerName: string
  warehouseId: number
  bizDate: string
  lines: ReturnableOrderLine[]
}

/**
 * 结算七项汇总（契约 `SettlementTotals`）。
 *
 * 金额一律整数分；`netAmount` 由后端算好返回（= salesAmount − returnAmount），
 * 前端不得自己再减一次 —— 两处算法迟早分叉，对账就永远调不平。
 */
export interface SettlementTotals {
  purchaseQty: number
  purchaseAmount: number
  salesQty: number
  salesAmount: number
  returnQty: number
  returnAmount: number
  netAmount: number
}

/**
 * 账期行（契约 `SettlementPeriodRow`）：只读。
 * `closedAt` 在 OPEN 行上是 null；没结过账的月份不会补 0 值行。
 */
export interface SettlementPeriodRow extends SettlementTotals {
  period: string
  status: 'OPEN' | 'CLOSED'
  closedAt: string | null
  closedBy: number | null
}

/** 日清：`warehouseId` 省略 = 全部仓库（后端按仓返回 items + 一份 totals）。 */
export interface DailySettlementRequest {
  bizDate: string
  warehouseId?: number
}

export interface DailySettlementItem extends SettlementTotals {
  warehouseId: number
}

export interface DailySettlementResult {
  bizDate: string
  items: DailySettlementItem[]
  totals: SettlementTotals
}

/** 月结请求：账期 `yyyy-MM`。 */
export interface MonthlySettlementRequest {
  period: string
}

/**
 * 月结结果 = 期间汇总 + 账期状态。
 *
 * `status` 只有 OPEN/CLOSED 两态，不是单据状态机（无 PARTIAL/VOIDED），
 * 因此不进 `documentStatus.ts`，页面内联判断即可。
 * `duplicate=true` 表示这次是幂等命中（账期早已关闭且重算一致），没有重复结算。
 */
export interface MonthlySettlementResult extends SettlementTotals {
  period: string
  status: 'OPEN' | 'CLOSED'
  closedAt?: string | null
  duplicate: boolean
}

/**
 * 对账结果（契约 `ReconcileResult`）。
 *
 * 差额不为 0 时后端不会返回这个对象，而是 409 `SET_RECONCILE_MISMATCH`
 * （details 带 period/generalTotal/detailTotal/diff），所以成功分支里 `balanced` 恒为 true；
 * 页面仍按字段渲染，不去猜后端什么时候报错。
 */
export interface ReconcileResult {
  period: string
  generalTotal: number
  detailTotal: number
  diff: number
  balanced: boolean
}

/** 契约 `groupBy` 取值：进货无 customer、出货无 supplier，各自见报表页候选表。 */
export type ReportGroupBy = 'supplier' | 'customer' | 'product' | 'sku' | 'day' | 'month'

/** 进货 / 出货统计行（契约 `ReportRow`）。`amount` 为分。 */
export interface ReportRow {
  key: string
  label: string
  qty: number
  amount: number
  orderCount: number
}

/**
 * 进销存汇总行（契约 `InventorySummaryRow`），粒度是 (SKU, 仓库)。
 *
 * 恒等式 `openingQty + inboundQty − outboundQty == closingQty` 由后端在一条聚合 SQL 里
 * 分别求和保证（TC-RPT-006）；`outboundQty` 已取正数，所以恒等式里是减号。
 */
export interface InventorySummaryRow {
  skuId: number
  warehouseId: number
  openingQty: number
  inboundQty: number
  outboundQty: number
  closingQty: number
}

/** 毛利分析维度（契约申请 `groupBy`）。 */
export type MarginGroupBy = 'product' | 'customer' | 'period'

/** 成本重算结果（POST /cost/recalculate）。金额单位：分。 */
export interface CostRecalculateResult {
  layerCount: number
  inboundCostAmount: number
  outboundCostAmount: number
  endingCostAmount: number
  balanced: boolean
}

export interface CostMonthlyRequest {
  period: string
}

/** 成本月结行（cost_monthly），金额单位：分。 */
export interface CostMonthlyRow {
  skuId: number
  warehouseId: number
  openingQty: number
  openingCostAmount: number
  inboundQty: number
  inboundCostAmount: number
  outboundQty: number
  outboundCostAmount: number
  closingQty: number
  closingCostAmount: number
}

/** 成本月结结果；`duplicate=true` 表示本次为幂等命中、未改动账目。 */
export interface CostMonthlyResult {
  period: string
  duplicate: boolean
  items: CostMonthlyRow[]
  marginRecordCount: number
}

/**
 * 毛利分析行（GET /reports/profit）。
 *
 * `grossMarginRateBp` 是万分比（6000 = 60%），不是百分比；
 * 展示时除以 100 并保留两位小数，不要回传后端。
 */
export interface MarginSummaryRow {
  key: string
  label: string
  qty: number
  revenueAmount: number
  costAmount: number
  grossProfitAmount: number
  grossMarginRateBp: number
  orderCount: number
}

/**
 * 自然语言调度结果（契约 `NlCommandResult`）。
 *
 * `data` 的形状由 `intent` 决定，与对应 REST 接口同源；
 * 前端不做类型收窄推断，展示层按 items / 原始 JSON 两种方式渲染。
 */
export interface NlCommandResult {
  intent:
    | 'help'
    | 'query_inventory'
    | 'list_documents'
    | 'get_document'
    | 'inventory_summary'
    | 'query_master_data'
    | 'create_purchase_order'
    | 'create_sales_order'
  description: string
  write: boolean
  data: unknown
}

// ═══════════════════════ IAM 深化 / 审批（V2-6） ═══════════════════════

/**
 * 账号视图（FR-IAM-05）。
 *
 * ★ 刻意没有 `passwordHash` 字段：后端从不下发它，前端也不该有地方装它。
 *   这类"类型上就装不下"的约束比任何注释都可靠。
 */
export interface UserView {
  id: number
  username: string
  displayName: string
  enabled: boolean
  roles: string[]
  permissions: string[]
  failedAttempts: number
  lockedUntil: string | null
  passwordExpiresAt: string | null
  mustChangePassword: boolean
}

/** 角色视图。 */
export interface RoleView {
  id: number
  code: string
  name: string
  description: string | null
}

/** 数据权限视图：读写同形（每项是 `ALL` / `SELF` / `WAREHOUSE:<id>`）。 */
export interface DataScopeView {
  direct: string[]
  inherited: string[]
}

/** 审批节点视图（FR-APS-01/02）。 */
export interface ApprovalStepView {
  stepNo: number
  name: string
  approverPermission: string
  approveMode: 'SINGLE' | 'ALL_SIGN' | 'ANY_SIGN'
  requiredApprovals: number
  thresholdAmount: number
  status: 'DEFINED' | 'PENDING' | 'APPROVED' | 'REJECTED' | 'SKIPPED'
  approvedBy: number[]
  decidedAt: string | null
  decisionBy: number | null
  remark: string | null
}

/** 审批实例视图。`status` 是**审批**的七态，与单据七态无关。 */
export interface ApprovalInstanceView {
  id: number
  flowId: number
  bizType: string
  bizId: number
  bizRefNo: string
  amount: number
  currentStep: number
  status: 'DRAFT' | 'PENDING' | 'TRANSFERRED' | 'APPROVED' | 'REJECTED' | 'WITHDRAWN' | 'CANCELLED'
  initiatorId: number
  steps: ApprovalStepView[]
}

/** 审批流定义视图（含节点）。 */
export interface ApprovalFlowView {
  id: number
  code: string
  name: string
  bizType: string
  description: string | null
  enabled: boolean
  steps: ApprovalStepView[]
}

/** 用户与角色管理（FR-IAM-05）+ 数据权限配置（FR-IAM-04）。 */
export const iamApi = {
  users: (keyword?: string, page = 1, size = 50) =>
    http.get<never, PageData<UserView>>('/iam/users', { params: { keyword, page, size } }),
  user: (id: number) => http.get<never, UserView>(`/iam/users/${id}`),
  createUser: (body: {
    username: string
    password: string
    displayName?: string
    enabled?: boolean
    roles?: string[]
  }) => http.post<never, UserView>('/iam/users', body),
  updateRoles: (id: number, roles: string[]) =>
    http.put<never, UserView>(`/iam/users/${id}/roles`, { roles }),
  setEnabled: (id: number, enabled: boolean) =>
    http.post<never, UserView>(`/iam/users/${id}/enabled`, { enabled }),
  resetPassword: (id: number, newPassword: string) =>
    http.post<never, { userId: number; mustChangePassword: boolean }>(
      `/iam/users/${id}/reset-password`,
      { newPassword },
    ),

  roles: () => http.get<never, { items: RoleView[] }>('/iam/roles'),
  createRole: (body: { code: string; name?: string; description?: string }) =>
    http.post<never, RoleView>('/iam/roles', body),
  rolePermissions: (id: number) =>
    http.get<never, { role: RoleView; permissions: string[] }>(`/iam/roles/${id}/permissions`),
  replacePermissions: (id: number, permissions: string[]) =>
    http.put<never, { role: RoleView; permissions: string[] }>(
      `/iam/roles/${id}/permissions`,
      { permissions },
    ),

  userScopes: (id: number) => http.get<never, DataScopeView>(`/iam/users/${id}/data-scopes`),
  replaceUserScopes: (id: number, scopes: string[]) =>
    http.put<never, DataScopeView>(`/iam/users/${id}/data-scopes`, { scopes }),
  roleScopes: (id: number) =>
    http.get<never, { scopes: string[] }>(`/iam/roles/${id}/data-scopes`),
  replaceRoleScopes: (id: number, scopes: string[]) =>
    http.put<never, { scopes: string[] }>(`/iam/roles/${id}/data-scopes`, { scopes }),
}

/**
 * 审批流（FR-APS-02）。
 *
 * ★ 前端只暴露"同意/驳回/撤回/转办"，**没有**"改单据状态"的入口 ——
 *   审批通过后单据仍需在单据页点审核。这条边界写在这里，免得有人图省事加一个
 *   `approveAndApplyDocument()`。
 */
export const approvalApi = {
  list: (status?: string, bizType?: string, page = 1, size = 50) =>
    http.get<never, PageData<ApprovalInstanceView>>('/approvals', {
      params: { status, bizType, page, size },
    }),
  detail: (id: number) => http.get<never, ApprovalInstanceView>(`/approvals/${id}`),
  submit: (body: { bizType: string; bizId: number; bizRefNo: string; amount: number }) =>
    http.post<never, ApprovalInstanceView>('/approvals', body),
  decide: (id: number, action: 'approve' | 'reject', remark?: string) =>
    http.post<never, ApprovalInstanceView>(`/approvals/${id}/${action}`, { remark }),
  withdraw: (id: number, reason?: string) =>
    http.post<never, ApprovalInstanceView>(`/approvals/${id}/withdraw`, { remark: reason }),
  transfer: (id: number, targetUserId: number, remark?: string) =>
    http.post<never, ApprovalInstanceView>(`/approvals/${id}/transfer`, { targetUserId, remark }),
  acceptTransfer: (id: number) =>
    http.post<never, ApprovalInstanceView>(`/approvals/${id}/accept-transfer`),
  flows: () => http.get<never, { items: ApprovalFlowView[] }>('/approvals/flows'),
  setFlowEnabled: (id: number, enabled: boolean) =>
    http.post<never, ApprovalFlowView>(`/approvals/flows/${id}/enabled`, null, {
      params: { enabled },
    }),
}

// ═══════════════════════ 财务中心（V3-2 · W2，纯追加） ═══════════════════════

/**
 * ★★ 单位分界线：财务域（/fin/**）金额一律「元」两位小数（简报 §5：numeric(18,2)），
 * 与业务域的「整数分」（ADR-0005）不同。分→元的精确换算发生在**后端**生成凭证处。
 * 因此 fin 页面直接 `money(v)` 显示，**绝不再过一遍 fenToYuan** ——
 * 两处各除一次 100 是最典型的"看起来对、实际差两个数量级"。
 */
export const finApi = {
  // ── 科目（FR-V3-06）──
  accounts: () => http.get<never, { items: FinAccountView[] }>('/fin/accounts'),
  accountTree: () => http.get<never, { items: FinAccountView[] }>('/fin/accounts/tree'),
  createAccount: (body: FinAccountCreatePayload) =>
    http.post<never, FinAccountView>('/fin/accounts', body),
  updateAccount: (id: number, body: FinAccountUpdatePayload) =>
    http.put<never, FinAccountView>(`/fin/accounts/${id}`, body),
  deleteAccount: (id: number) => http.delete<never, { deleted: number }>(`/fin/accounts/${id}`),

  // ── 凭证（FR-V3-07/08）──
  vouchers: (params: {
    status?: string
    sourceType?: string
    period?: string
    from?: string
    to?: string
    keyword?: string
    page?: number
    size?: number
  }) => http.get<never, PageData<FinVoucherRow>>('/fin/vouchers', { params }),
  voucher: (id: number) => http.get<never, FinVoucherDetail>(`/fin/vouchers/${id}`),
  createVoucher: (body: FinVoucherCreatePayload) =>
    http.post<never, FinVoucherDetail>('/fin/vouchers', body),
  updateVoucher: (id: number, body: FinVoucherCreatePayload) =>
    http.put<never, FinVoucherDetail>(`/fin/vouchers/${id}`, body),
  auditVoucher: (id: number) => http.post<never, FinVoucherDetail>(`/fin/vouchers/${id}/audit`),
  postVoucher: (id: number) => http.post<never, FinVoucherDetail>(`/fin/vouchers/${id}/post`),
  /**
   * 删除凭证（V4-1 接驳：此前该路径无 DELETE，返回 405，录错的草稿删不掉）。
   *
   * 后端只允许 DRAFT；AUDITED/POSTED 返回 409 FIN_VOUCHER_DELETE_FORBIDDEN，
   * 已月结的账期同样拒绝。分录由后端连带清除，前端不需要单独调。
   */
  deleteVoucher: (id: number) =>
    http.delete<never, { deleted: boolean; id: number }>(`/fin/vouchers/${id}`),

  // ── 银行账户 + 日记账（FR-V3-09）──
  bankAccounts: () => http.get<never, { items: BankAccountView[] }>('/fin/bank-accounts'),
  createBankAccount: (body: BankAccountPayload) =>
    http.post<never, BankAccountView>('/fin/bank-accounts', body),
  updateBankAccount: (id: number, body: BankAccountPayload) =>
    http.put<never, BankAccountView>(`/fin/bank-accounts/${id}`, body),
  deleteBankAccount: (id: number) =>
    http.delete<never, { deleted: number }>(`/fin/bank-accounts/${id}`),
  setDefaultBankAccount: (id: number) =>
    http.post<never, BankAccountView>(`/fin/bank-accounts/${id}/default`),
  cashJournal: (params: { accountId?: number; from?: string; to?: string }) =>
    http.get<never, { items: CashJournalRow[] }>('/fin/cash-journal', { params }),
  addCashJournal: (body: CashJournalCreatePayload) =>
    http.post<never, CashJournalRow>('/fin/cash-journal', body),

  // ── 费用报销（FR-V3-10）──
  expenses: (params: { status?: string; keyword?: string; page?: number; size?: number }) =>
    http.get<never, PageData<FinExpenseRow>>('/fin/expenses', { params }),
  expense: (id: number) => http.get<never, FinExpenseDetail>(`/fin/expenses/${id}`),
  createExpense: (body: FinExpenseCreatePayload) =>
    http.post<never, FinExpenseDetail>('/fin/expenses', body),
  updateExpense: (id: number, body: FinExpenseCreatePayload) =>
    http.put<never, FinExpenseDetail>(`/fin/expenses/${id}`, body),
  deleteExpense: (id: number) => http.delete<never, { deleted: number }>(`/fin/expenses/${id}`),
  submitExpense: (id: number) => http.post<never, FinExpenseDetail>(`/fin/expenses/${id}/submit`),
  approveExpense: (id: number) =>
    http.post<never, FinExpenseDetail>(`/fin/expenses/${id}/approve`),
  rejectExpense: (id: number, reason: string) =>
    http.post<never, FinExpenseDetail>(`/fin/expenses/${id}/reject`, { reason }),
  payExpense: (id: number) => http.post<never, FinExpenseDetail>(`/fin/expenses/${id}/pay`),

  // ── 发票台账（FR-V3-11）──
  invoices: (params: {
    type?: 'OUTPUT' | 'INPUT'
    status?: string
    keyword?: string
    from?: string
    to?: string
    page?: number
    size?: number
  }) => http.get<never, PageData<FinInvoiceRow>>('/fin/invoices', { params }),
  createInvoice: (body: FinInvoiceCreatePayload) =>
    http.post<never, FinInvoiceRow>('/fin/invoices', body),
  updateInvoice: (id: number, body: FinInvoiceCreatePayload) =>
    http.put<never, FinInvoiceRow>(`/fin/invoices/${id}`, body),
  voidInvoice: (id: number) => http.post<never, FinInvoiceRow>(`/fin/invoices/${id}/void`),
  deleteInvoice: (id: number) =>
    http.delete<never, { deleted: number }>(`/fin/invoices/${id}`),

  // ── 报表 + 总览（FR-V3-12/13）──
  balanceSheet: (period: string) =>
    http.get<never, BalanceSheetView>('/fin/reports/balance-sheet', { params: { period } }),
  incomeStatement: (period: string) =>
    http.get<never, IncomeStatementView>('/fin/reports/income-statement', { params: { period } }),
  overview: () => http.get<never, FinanceOverviewView>('/fin/overview'),
}

/** 科目金额字段展示：元口径，直接两位小数（见上方分界线说明）。 */
export function money(yuan: number | string | null | undefined): string {
  if (yuan == null || yuan === '') return '-'
  const n = typeof yuan === 'string' ? Number.parseFloat(yuan) : yuan
  return Number.isNaN(n) ? '-' : n.toFixed(2)
}

export interface FinAccountView {
  id: number
  code: string
  name: string
  parentId: number | null
  category: string
  categoryLabel: string
  direction: string
  directionLabel: string
  leaf: boolean
  initBalance: number
  enabled: boolean
  remark?: string | null
  children?: FinAccountView[]
}

export interface FinAccountCreatePayload {
  code: string
  name: string
  parentId?: number | null
  category: string
  direction: string
  initBalance?: number
  enabled?: boolean
  remark?: string
}

export interface FinAccountUpdatePayload {
  name?: string
  direction?: string
  initBalance?: number
  enabled?: boolean
  remark?: string
}

export interface FinVoucherLineRow {
  id: number
  lineNo: number
  accountId: number
  accountCode: string
  summary: string
  debit: number
  credit: number
}

export interface FinVoucherRow {
  id: number
  voucherNo: string
  voucherDate: string
  period: string
  sourceType: string
  sourceTypeLabel: string
  sourceId: number | null
  sourceNo: string | null
  status: string
  statusLabel: string
  totalDebit: number
  totalCredit: number
  summary?: string | null
  lineCount: number
  remark?: string | null
}

export interface FinVoucherDetail extends FinVoucherRow {
  createdBy: number | null
  auditedBy: number | null
  postedAt: string | null
  lines: FinVoucherLineRow[]
}

export interface FinVoucherCreatePayload {
  voucherDate?: string
  remark?: string
  lines: { accountId: number; summary?: string; debit?: number; credit?: number }[]
}

export interface BankAccountView {
  id: number
  code: string
  name: string
  bankName?: string | null
  accountNo?: string | null
  currency: string
  balance: number
  isDefault: boolean
  enabled: boolean
  remark?: string | null
}

export interface BankAccountPayload {
  code?: string
  name: string
  bankName?: string
  accountNo?: string
  currency?: string
  initBalance?: number
  isDefault?: boolean
  enabled?: boolean
  remark?: string
}

export interface CashJournalRow {
  id: number
  accountId: number
  bizDate: string
  direction: 'DEBIT' | 'CREDIT'
  directionLabel: string
  amount: number
  balance: number
  sourceType: string
  sourceId: number | null
  summary: string
}

export interface CashJournalCreatePayload {
  accountId: number
  bizDate?: string
  direction: 'DEBIT' | 'CREDIT'
  amount: number
  summary?: string
}

export interface FinExpenseRow {
  id: number
  expenseNo: string
  applicantId: number | null
  applicantName: string
  departmentId: number | null
  departmentName?: string | null
  expenseType: string
  amount: number
  status: string
  statusLabel: string
  applyDate: string
  rejectReason?: string | null
  paidAt?: string | null
  remark?: string | null
}

export interface FinExpenseDetail extends FinExpenseRow {
  lines: { id: number; lineNo: number; expenseItem: string; amount: number; invoiceNo?: string | null; remark?: string | null }[]
}

export interface FinExpenseCreatePayload {
  expenseType: string
  departmentId?: number | null
  departmentName?: string
  applyDate?: string
  remark?: string
  lines: { expenseItem: string; amount: number; invoiceNo?: string; remark?: string }[]
}

export interface FinInvoiceRow {
  id: number
  invoiceNo: string
  type: 'OUTPUT' | 'INPUT'
  typeLabel: string
  partnerId: number | null
  partnerName: string
  amount: number
  taxAmount: number
  totalAmount: number
  invoiceDate: string
  sourceType?: 'PURCHASE_ORDER' | 'SALES_ORDER' | null
  sourceId?: number | null
  status: string
  statusLabel: string
  remark?: string | null
}

export interface FinInvoiceCreatePayload {
  invoiceNo: string
  type: 'OUTPUT' | 'INPUT'
  partnerId?: number | null
  partnerName: string
  amount: number
  taxAmount?: number
  invoiceDate?: string
  sourceType?: 'PURCHASE_ORDER' | 'SALES_ORDER'
  sourceId?: number
  remark?: string
}

export interface BalanceSheetView {
  period: string
  assets: { code: string; name: string; amount: number }[]
  liabilities: { code: string; name: string; amount: number }[]
  equity: { code: string; name: string; amount: number }[]
  assetTotal: number
  liabilityTotal: number
  equityTotal: number
  accumulatedProfit: number
  liabilityEquityTotal: number
  diff: number
  balanced: boolean
}

export interface IncomeStatementView {
  period: string
  revenue: { code: string; name: string; amount: number }[]
  cost: { code: string; name: string; amount: number }[]
  expense: { code: string; name: string; amount: number }[]
  revenueTotal: number
  costTotal: number
  expenseTotal: number
  grossProfit: number
  netProfit: number
}

export interface FinanceOverviewView {
  period: string
  cards: {
    fundsBalance: number
    receivableTotal: number
    payableTotal: number
    monthInflow: number
    monthOutflow: number
  }
  cashFlow: { period: string; inflow: number; outflow: number }[]
  postedVoucherCount: number
  periodVoucherCount: number
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
