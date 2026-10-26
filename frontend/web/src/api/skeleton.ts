import http from '@/api/client'

/**
 * V3-1 骨架组 API（FR-V3-02/03/04/05/23/24/25/29）。
 *
 * 全部走只读聚合或简单 CRUD，权限码统一为 dashboard:read（facade 端点）
 * 或 sys:org / sys:config / sys:numberrule / sys:loginlog（系统页）。
 * ADMIN 角色由后端 @RequirePermission.adminBypass 默认直通。
 *
 * ★ 与既有 client.ts 同一套 axios 实例：写操作自动带 X-Request-Id，
 *   401 统一跳登录，错误消息优先取后端 message。
 */

// ─────────────────────────── 工作台聚合（FR-V3-02） ───────────────────────────

export interface TrendPoint {
  label: string
  value: number
}

export interface PiePoint {
  label: string
  value: number
}

export interface WarehousePoint {
  id: number
  /** 仓库名（V4-2 起后端 LEFT JOIN warehouses 取回）；极端情况下可能为 null，前端按 id 兜底 */
  name?: string | null
  value: number
}

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

export interface ApprovalTodo {
  id: number
  bizType: string
  bizId: number
  bizRefNo: string
  amount: number
  currentStep: number
  status: string
  createdAt: string
}

export interface RecentDocument {
  type: string
  id: number
  no: string
  status: string
  bizDate: string
  partyId: number | null
  warehouseId: number | null
  totalQty: number
  totalAmount: number
}

export interface DashboardSummary {
  kpis: DashboardKpis
  charts: {
    purchaseTrend: TrendPoint[]
    salesTrend: TrendPoint[]
    docStatusPie: PiePoint[]
    inventoryByWarehouse: WarehousePoint[]
  }
  todos: {
    approvalPending: number
    purchaseReceipt: number
    salesShip: number
    lowStock: number
    items: ApprovalTodo[]
  }
  recentDocuments: RecentDocument[]
}

// ─────────────────────────── 待办中心（FR-V3-03） ───────────────────────────

/**
 * 四种待办的字段并集。前端按 type 决定渲染哪一列，
 * 不需要为每个 Tab 单独定义一套类型。
 */
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
  batchNo?: string | null
  onHandQty?: number
  reservedQty?: number
  frozenQty?: number
  availableQty?: number
  bizType?: string
  bizId?: number
  bizRefNo?: string
  amount?: number
  currentStep?: number
  createdAt?: string
}

export type TodoType = 'approval' | 'purchase-receipt' | 'sales-ship' | 'low-stock'

// ─────────────────────────── 全局搜索（FR-V3-05） ───────────────────────────

export interface SearchHit {
  id: number
  code?: string
  name?: string
  type?: string
  no?: string
  status?: string
}

export interface SearchResult {
  products: SearchHit[]
  customers: SearchHit[]
  suppliers: SearchHit[]
  orders: SearchHit[]
}

// ─────────────────────────── 单据详情（FR-V3-04） ───────────────────────────

export interface DocLine {
  lineNo: number
  skuId: number
  skuName: string
  qty: number
  unitPriceAmount: number
  amount: number
}

export interface DocumentDetail {
  type: string
  id: number
  no: string
  status: string
  bizDate: string
  partyName: string
  warehouseName: string
  totalQty: number
  totalAmount: number
  lines: DocLine[]
}

// ─────────────────────────── 组织架构（FR-V3-23） ───────────────────────────

export interface DeptNode {
  id: number
  parentId: number | null
  code: string
  name: string
  sortNo: number
  leaderUserId: number | null
  enabled: boolean
  children: DeptNode[]
}

export interface Position {
  id: number
  departmentId: number
  code: string
  name: string
  level: number
  enabled: boolean
  createdAt: string
  updatedAt: string
}

// ─────────────────────────── 系统参数（FR-V3-24） ───────────────────────────

export interface SystemConfig {
  id: number
  configGroup: string
  configKey: string
  configValue: string
  valueType: string
  remark: string | null
  createdAt: string
  updatedAt: string
}

// ─────────────────────────── 单号规则（FR-V3-25） ───────────────────────────

export interface NumberRule {
  id: number
  docType: string
  prefix: string
  datePattern: string
  seqLength: number
  resetCycle: string
  enabled: boolean
  createdAt: string
  updatedAt: string
}

export interface NumberRulePreview {
  docType: string
  prefix: string
  datePattern: string
  seqLength: number
  resetCycle: string
  currentNo: number
  sample: string
  dayKey: string
}

// ─────────────────────────── 登录日志（FR-V3-29） ───────────────────────────

export interface LoginLog {
  id: number
  userId: number
  username: string
  ip: string | null
  userAgent: string | null
  loginAt: string
  success: boolean
  failReason: string | null
}

// ═══════════════════════ API ═══════════════════════

export const skeletonApi = {
  // 工作台聚合
  dashboardSummary: () => http.get<never, DashboardSummary>('/dashboard/summary'),

  // 待办中心
  todos: (type: TodoType, limit = 50) =>
    http.get<never, { items: TodoItem[] }>('/todos', { params: { type, limit } }),

  // 全局搜索
  search: (keyword: string) =>
    http.get<never, SearchResult>('/search', { params: { keyword } }),

  // 单据详情（type: purchase / sales / return / transfer）
  document: (type: string, id: number) =>
    http.get<never, DocumentDetail>(`/documents/${type}/${id}`),

  // 组织架构：部门树
  departmentTree: (includeDisabled = false) =>
    http.get<never, { items: DeptNode[] }>('/org/departments', { params: { includeDisabled } }),
  createDepartment: (body: {
    code: string
    name: string
    parentId?: number | null
    sortNo?: number
  }) => http.post<never, DeptNode>('/org/departments', body),
  updateDepartment: (
    id: number,
    body: { name: string; parentId?: number | null; sortNo?: number; enabled?: boolean },
  ) => http.put<never, DeptNode>(`/org/departments/${id}`, body),
  deleteDepartment: (id: number) => http.delete<never, { deleted: number }>(`/org/departments/${id}`),

  // 组织架构：岗位
  positions: (departmentId?: number) =>
    http.get<never, { items: Position[] }>('/org/positions', {
      params: departmentId != null ? { departmentId } : {},
    }),
  createPosition: (body: {
    departmentId: number
    code: string
    name: string
    level?: number
  }) => http.post<never, Position>('/org/positions', body),
  updatePosition: (
    id: number,
    body: { departmentId?: number; name: string; level?: number; enabled?: boolean },
  ) => http.put<never, Position>(`/org/positions/${id}`, body),
  deletePosition: (id: number) => http.delete<never, { deleted: number }>(`/org/positions/${id}`),

  // 系统参数
  configs: (group?: string) =>
    http.get<never, { items: SystemConfig[] }>('/sys-config', {
      params: group ? { group } : {},
    }),
  updateConfig: (id: number, value: string) =>
    http.put<never, SystemConfig>(`/sys-config/${id}`, { value }),

  // 单号规则
  numberRules: () => http.get<never, { items: NumberRule[] }>('/number-rules'),
  updateNumberRule: (
    id: number,
    body: { prefix: string; seqLength?: number; enabled?: boolean },
  ) => http.put<never, NumberRule>(`/number-rules/${id}`, body),
  previewNumberRule: (docType: string) =>
    http.post<never, NumberRulePreview>('/number-rules/preview', { docType }),

  // 登录日志
  loginLogs: (limit = 100) =>
    http.get<never, { items: LoginLog[] }>('/login-logs', { params: { limit } }),
  onlineUsers: (minutes = 30) =>
    http.get<never, { items: LoginLog[] }>('/login-logs/online', { params: { minutes } }),
}
