/**
 * 单据状态 → 动作镜像（移动端只读展示 + ship / receive 两个单动作）。
 *
 * ★ 事实源是后端 `DocumentStatus` 的转移表与各 Controller 的 @RequirePermission，
 *   本文件与 `frontend/web/src/utils/documentStatus.ts` 是同源的**镜像**，
 *   不是第二套状态机。移动端不根据数量、日期或角色自行推断"能不能出货/入库"：
 *   按钮显隐只看 [status, 权限码]，最终能否执行仍由后端校验（409/403 原样展示）。
 *
 * 本文件刻意不 import vue / uni，保持零副作用，便于将来做镜像一致性校验。
 */

export const DOCUMENT_KINDS = ['PURCHASE', 'SALES'] as const
export type DocumentKind = (typeof DOCUMENT_KINDS)[number]

export const DOCUMENT_STATUSES = [
  'DRAFT',
  'SUBMITTED',
  'APPROVED',
  'PARTIAL',
  'COMPLETED',
  'VOIDED',
  'REJECTED',
] as const
export type DocumentStatus = (typeof DOCUMENT_STATUSES)[number]

export interface StatusMeta {
  label: string
  /** 与 Web 的 el-tag type 同义，移动端映射成自己的色彩类 */
  tone: 'info' | 'primary' | 'success' | 'warning' | 'danger'
  description: string
  terminal: boolean
}

export const STATUS_META: Record<DocumentStatus, StatusMeta> = {
  DRAFT: {
    label: '草稿',
    tone: 'info',
    description: '可修改内容，不影响库存',
    terminal: false,
  },
  SUBMITTED: {
    label: '已提交',
    tone: 'primary',
    description: '等待审核，内容已锁定',
    terminal: false,
  },
  APPROVED: {
    label: '已审核',
    tone: 'success',
    description: '可执行入库 / 出货',
    terminal: false,
  },
  PARTIAL: {
    label: '部分执行',
    tone: 'warning',
    description: '已执行一部分，可继续执行剩余数量',
    terminal: false,
  },
  COMPLETED: {
    label: '已完成',
    tone: 'success',
    description: '全部数量已执行完毕，终态',
    terminal: true,
  },
  VOIDED: {
    label: '已作废',
    tone: 'info',
    description: '单据作废，不再参与任何统计，终态',
    terminal: true,
  },
  REJECTED: {
    label: '已驳回',
    tone: 'danger',
    description: '审核未通过，只能作废后重开',
    terminal: false,
  },
}

/**
 * 后端 ALLOWED_ACTIONS 的镜像（只收录移动端会出现的进货/出货两列）。
 *
 * 与 Web 表逐项一致；其中 PARTIAL 不给「作废」、REJECTED 不给「退回草稿」
 * 都是后端实际能力，不是移动端的裁剪。
 */
export const ALLOWED_ACTIONS: Record<
  DocumentKind,
  Record<DocumentStatus, Array<'receive' | 'ship'>>
> = {
  PURCHASE: {
    DRAFT: [],
    SUBMITTED: [],
    APPROVED: ['receive'],
    PARTIAL: ['receive'],
    COMPLETED: [],
    VOIDED: [],
    REJECTED: [],
  },
  SALES: {
    DRAFT: [],
    SUBMITTED: [],
    APPROVED: ['ship'],
    PARTIAL: ['ship'],
    COMPLETED: [],
    VOIDED: [],
    REJECTED: [],
  },
}

export const ACTION_META = {
  'PURCHASE:receive': {
    label: '确认入库',
    permission: 'pur:receive',
    confirmText: '将把单据全部未入库数量一次入库，并写入库存流水。已入库部分不可撤销。确定入库吗？',
  },
  'SALES:ship': {
    label: '确认出货',
    permission: 'sal:ship',
    confirmText: '出货将从库存真实扣减（按 FEFO 拆批次），不可撤销。确定出货吗？',
  },
} as const

export function isDocumentStatus(value: string | null | undefined): value is DocumentStatus {
  return !!value && (DOCUMENT_STATUSES as readonly string[]).includes(value)
}

/** 未知状态给安全兜底，而不是让整页崩掉。 */
export function statusMeta(status: string | null | undefined): StatusMeta {
  if (isDocumentStatus(status)) return STATUS_META[status]
  return {
    label: status || '未知状态',
    tone: 'info',
    description: '后端返回了移动端未识别的状态，请检查状态机是否有新增分支',
    terminal: true,
  }
}

/**
 * 当前状态下是否应显示某个动作按钮。
 *
 * @param kind        单据种类
 * @param status      后端返回的状态
 * @param action      receive / ship
 * @param can         权限判断（页面传 session.hasPermission）
 *
 * 只看镜像表与权限，不看 openQty、余额或日期 —— 那些是后端执行时的事实。
 */
export function canExecuteAction(
  kind: DocumentKind,
  status: string | null | undefined,
  action: 'receive' | 'ship',
  can: (permission: string) => boolean,
): boolean {
  if (!isDocumentStatus(status)) return false
  if (!ALLOWED_ACTIONS[kind][status].includes(action)) return false
  const key: 'PURCHASE:receive' | 'SALES:ship' =
    kind === 'PURCHASE' ? 'PURCHASE:receive' : 'SALES:ship'
  return can(ACTION_META[key].permission)
}
