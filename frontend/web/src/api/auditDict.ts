import http, { type PageData } from './client'

/**
 * 审计查询（FR-SYS-07）与数据字典浏览（V2-5 读模型）。
 *
 * 单独成文件而不是继续堆在 client.ts：本泳道只追加自己的端点与类型，
 * 减少多泳道在 client.ts 同一区域撞车（V2-3 的 salesV23.ts 是同一个做法）。
 *
 * ★ 这一层**一个写方法都没有**：契约里 `/audits*` 与 `/dicts*` 只有 GET，
 *   审计表另有 append-only 触发器。页面上"只读"三个字不是文案装饰，
 *   而是"这里调不出任何写接口"的事实陈述。
 */

/** 四个查询维度可任意组合；时间区间是半开区间 `[from, to)`。 */
export interface AuditQuery {
  actorId?: number
  /** 后端按 `actor_name = ?` **精确**匹配，不是模糊查询 —— 页面的 placeholder 必须写清这一点 */
  actorName?: string
  action?: AuditActionCode
  resourceType?: string
  resourceId?: string
  bizRefNo?: string
  result?: AuditResultCode
  /** ISO-8601 带时区，如 2026-10-24T00:00:00+08:00 */
  from?: string
  to?: string
}

/**
 * 审计动作码（契约 `AuditLog.action` 的 enum）。
 *
 * ★ 事实源是 `backend/.../sys/domain/AuditAction.java`，这里是界面侧的镜像。
 *   镜像会漂移，所以两处都留了余地：
 *     ① 下拉候选来自这份清单（用它做筛选条件的枚举必须由界面穷举）；
 *     ② 列表/详情**按原始字符串显示**，后端新增动作而这里没跟上时，
 *        界面显示的是那个新动作码本身，而不是空白 —— 空白会让人以为"没记录"。
 *   把它变成可执行门禁（前端清单 ↔ Java 枚举）需要改 `check:status-parity` 的规则，
 *   本泳道不动那条门禁，已把诉求写进 .plan/E1-契约申请.md。
 */
export const AUDIT_ACTIONS = [
  'CREATE',
  'UPDATE',
  'DELETE',
  'DISABLE',
  'SUBMIT',
  'APPROVE',
  'REJECT',
  'VOID',
  'RECEIVE',
  'SHIP',
  'RESERVE',
  'RELEASE',
  'SETTLE_DAILY',
  'SETTLE_MONTHLY',
  'LOGIN',
  'COMMAND',
  'OTHER',
] as const

export type AuditActionCode = (typeof AUDIT_ACTIONS)[number]

/** 审计结果只有两态（与 audit_logs.result 的 CHECK 约束一致）。 */
export const AUDIT_RESULTS = ['SUCCESS', 'FAIL'] as const
export type AuditResultCode = (typeof AUDIT_RESULTS)[number]

/** 动作码 → 中文（纯文案，不含任何业务判断）。 */
export const AUDIT_ACTION_LABELS: Record<string, string> = {
  CREATE: '新建',
  UPDATE: '修改',
  DELETE: '删除',
  DISABLE: '停用',
  SUBMIT: '提交',
  APPROVE: '审核通过',
  REJECT: '驳回',
  VOID: '作废',
  RECEIVE: '入库',
  SHIP: '出货',
  RESERVE: '预占',
  RELEASE: '释放',
  SETTLE_DAILY: '日清',
  SETTLE_MONTHLY: '月结',
  LOGIN: '登录',
  COMMAND: '自然语言指令',
  OTHER: '其他',
}

export function auditActionLabel(code: string): string {
  return AUDIT_ACTION_LABELS[code] ?? code
}

export const auditApi = {
  /** 分页列表，后端按 occurredAt **倒序**（最新在前）。 */
  list: (params: AuditQuery & { page?: number; size?: number }) =>
    http.get<never, PageData<AuditLog>>('/audits', { params }),
  /** 单条详情：列表已带全部字段，这一调用是"这条记录还能单独取到"的读路径。 */
  get: (id: number) => http.get<never, AuditLog>(`/audits/${id}`),
  /** 某张单据的轨迹，按时间**正序**（读"这张单的经历"是从建单往下看的）。 */
  timeline: (bizRefNo: string) =>
    http.get<never, AuditLog[]>('/audits/timeline', { params: { bizRefNo } }),
  /** 一次请求产生的全部审计（FR-SYS-08 traceId 贯穿的落库侧证据）。 */
  byTrace: (traceId: string) =>
    http.get<never, AuditLog[]>('/audits/by-trace', { params: { traceId } }),
}

export const dictApi = {
  /** 字典类型：后端返回数组，不分页。 */
  types: (onlyEnabled = true) =>
    http.get<never, DictType[]>('/dicts', { params: { onlyEnabled } }),
  items: (typeCode: string, onlyEnabled = true) =>
    http.get<never, DictItem[]>(`/dicts/${encodeURIComponent(typeCode)}/items`, {
      params: { onlyEnabled },
    }),
}

// ═══════════════════════ 类型（与契约 schema 对齐） ═══════════════════════

/**
 * 一条操作审计（契约 `AuditLog`）。
 *
 * `required: [id, action, resourceType, occurredAt, result]` —— 其余字段按契约都可空，
 * 所以类型上写成可空；把 nullable 抹成必有，界面就会把"真的没有"显示成空白。
 */
export interface AuditLog {
  id: number
  /** 匿名操作（如登录失败）为 null */
  actorId: number | null
  actorName: string | null
  action: string
  resourceType: string
  resourceId: string | null
  /** 业务单据号；主数据的"编码"不算单据号，所以主数据审计这里是 null */
  bizRefNo: string | null
  /** 变更前快照（JSON 文本）；首次创建为 null，敏感字段已脱敏为 *** */
  beforeJson: string | null
  /** 变更后快照（JSON 文本）；超大响应不记录（null），不截断 */
  afterJson: string | null
  traceId: string | null
  ip: string | null
  occurredAt: string
  result: string
  /** 失败时的业务错误码，与 ApiError.code 同源 */
  errorCode: string | null
}

export interface DictType {
  id: number
  code: string
  name: string
  description: string | null
  enabled: boolean
}

export interface DictItem {
  id: number
  typeCode: string
  code: string
  label: string
  sortNo: number
  enabled: boolean
}
