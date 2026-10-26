/**
 * 审批状态机镜像（FR-APS-02，ApprovalStatus 七态）。
 *
 * ★★ 这是与 `documentStatus.ts` **完全独立的第二套状态机**，不要合并、不要共用色板：
 *
 * | | DocumentStatus | ApprovalStatus |
 * | --- | --- | --- |
 * | 回答的问题 | 这张单子在业务上走到哪一步 | 这次审批走完了没有 |
 * | APPROVED | 「已审核」，且 isExecutable() → 可以动库存 | 「审批通过」，**不**代表可以出货 |
 * | REJECTED | 非终态，可退回草稿重提 | **终态**，重提是**新实例**（历史不可改写） |
 * | 特有态 | PARTIAL / VOIDED | TRANSFERRED / WITHDRAWN / CANCELLED |
 *
 * 合并的诱惑很大（都有 APPROVED/REJECTED），但一旦合并，审批就会继承 isExecutable()
 * 的语义，症状是「审批通过了就以为能发货」；反过来红冲也要在审批态里长出补偿分支。
 * 事实源是后端 `aps/domain/ApprovalStatus.java` 的转移表，本文件只是它的展示镜像。
 *
 * 本文件刻意不 import vue / uni，也不 import documentStatus —— 两套词表必须物理隔离。
 */

export const APPROVAL_STATUSES = [
  'DRAFT',
  'PENDING',
  'TRANSFERRED',
  'APPROVED',
  'REJECTED',
  'WITHDRAWN',
  'CANCELLED',
] as const
export type ApprovalStatus = (typeof APPROVAL_STATUSES)[number]

export interface ApprovalStatusMeta {
  label: string
  /** 审批页专用色板类名后缀，与单据页的 tone-* 不共用含义 */
  tone: 'wait' | 'handover' | 'pass' | 'refuse' | 'quiet'
  description: string
  terminal: boolean
  /** 是否占用「一张单据一个未结实例」的槽位 */
  inFlight: boolean
}

export const APPROVAL_STATUS_META: Record<ApprovalStatus, ApprovalStatusMeta> = {
  DRAFT: {
    label: '审批草稿',
    tone: 'quiet',
    description: '实例已建但未提交，尚未进入任何人的待办',
    terminal: false,
    inFlight: true,
  },
  PENDING: {
    label: '等待审批',
    tone: 'wait',
    description: '停在某个节点，等具备该节点权限的人处理',
    terminal: false,
    inFlight: true,
  },
  TRANSFERRED: {
    label: '已转办',
    tone: 'handover',
    description: '当前节点换人处理，仍在审批中，接办后回到等待审批',
    terminal: false,
    inFlight: true,
  },
  APPROVED: {
    label: '审批通过',
    tone: 'pass',
    description: '审批已放行；单据能否执行仍由单据自己的状态决定',
    terminal: true,
    inFlight: false,
  },
  REJECTED: {
    label: '审批驳回',
    tone: 'refuse',
    description: '终态。单据退回修改后重新提交会生成一条新的审批记录',
    terminal: true,
    inFlight: false,
  },
  WITHDRAWN: {
    label: '已撤回',
    tone: 'quiet',
    description: '终态。发起人在通过之前自行收回',
    terminal: true,
    inFlight: false,
  },
  CANCELLED: {
    label: '已取消',
    tone: 'quiet',
    description: '终态。单据作废或红冲时连带取消，不留死人待办',
    terminal: true,
    inFlight: false,
  },
}

const FALLBACK: ApprovalStatusMeta = {
  label: '未知审批状态',
  tone: 'quiet',
  description: '后端返回了移动端未识别的审批状态，请检查审批状态机是否有新增分支',
  terminal: true,
  inFlight: false,
}

export function isApprovalStatus(value: string | null | undefined): value is ApprovalStatus {
  return !!value && (APPROVAL_STATUSES as readonly string[]).includes(value)
}

/** 未知状态给安全兜底（按终态处理，不给动作按钮），而不是让整页崩掉。 */
export function approvalStatusMeta(status: string | null | undefined): ApprovalStatusMeta {
  return isApprovalStatus(status) ? APPROVAL_STATUS_META[status] : { ...FALLBACK, label: status || FALLBACK.label }
}

/** 审批节点状态（ApprovalStep.StepStatus 五态），与实例七态不是一张表。 */
export const STEP_STATUS_META: Record<string, { label: string; tone: ApprovalStatusMeta['tone'] }> =
  {
    DEFINED: { label: '定义中', tone: 'quiet' },
    PENDING: { label: '待处理', tone: 'wait' },
    APPROVED: { label: '已同意', tone: 'pass' },
    REJECTED: { label: '已驳回', tone: 'refuse' },
    SKIPPED: { label: '已跳过', tone: 'quiet' },
  }

export function stepStatusMeta(status: string | null | undefined): {
  label: string
  tone: ApprovalStatusMeta['tone']
} {
  const meta = status ? STEP_STATUS_META[status] : undefined
  return meta ?? { label: status || '未知', tone: 'quiet' }
}

/**
 * 「待我审批」的判定口径。
 *
 * ★ 后端 `GET /approvals` 只有 status / bizType 两个过滤参数，没有「按当前人」的过滤
 *   （逐节点资格在 ApprovalApplicationService 里按节点的 approverPermission 判），
 *   所以待办只能在客户端这样推：实例在飞（PENDING / TRANSFERRED）
 *   且当前节点的 approverPermission ∈ 我的权限。
 * 这只决定**列表与按钮显隐**，不是安全边界：真正能不能同意，后端 403 说话。
 */
export function isPendingForMe(
  instance: { status: string; steps: Array<{ stepNo: number; approverPermission: string }>; currentStep: number },
  can: (permission: string) => boolean,
): boolean {
  const meta = approvalStatusMeta(instance.status)
  if (!meta.inFlight) return false
  if (instance.status !== 'PENDING' && instance.status !== 'TRANSFERRED') return false
  const current = instance.steps.find((step) => step.stepNo === instance.currentStep)
  if (!current) return false
  return can(current.approverPermission)
}

/**
 * 发起人与「撤回」的关系：权限码是必要条件，只有发起人本人这一条是第二道门。
 *
 * 后端 `/auth/me` 不返回用户 id，所以 `actorId` 要靠 iam:read 反查才能拿到。
 * 传 `null` 表示**查不到自己的 id**（账号没有 iam:read 是正常配置），
 * 这时不能因此把撤回按钮藏起来 —— 前端没有足够信息做判定，就交给后端那条
 * 「只有发起人本人」的规则去拒（消息原样显示）。前端只在自己**确定**不是
 * 发起人时才收窄展示。
 */
export function canWithdraw(
  instance: { status: string; initiatorId: number },
  actorId: number | null | undefined,
  can: (permission: string) => boolean,
): boolean {
  if (!can('aps:withdraw')) return false
  if (!approvalStatusMeta(instance.status).inFlight) return false
  if (actorId === null || actorId === undefined) return true
  return instance.initiatorId === actorId
}
