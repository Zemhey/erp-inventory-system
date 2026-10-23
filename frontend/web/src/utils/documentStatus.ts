/**
 * 单据状态机 → 界面动作映射（M3 计划书 §3.2）。
 *
 * ★ 为什么要有这个文件：七个状态 × 几张单据 = 十几个「什么状态能点什么按钮」的判断。
 *   散落写成 `v-if="status === 'DRAFT'"` 的话，改一次状态规则要动十几处模板，
 *   必然漏改一处 —— 而漏改的表现是用户点了按钮收到 409，看起来像系统坏了。
 *
 * ★ 这个文件是**镜像**，不是事实源。事实源是后端
 *   `shared/document/DocumentStatus.java`（转移表）与各 Controller 的
 *   `@RequirePermission`（动作权限）。镜像会漂移，所以配了一个可执行的校验：
 *     `npm run check:status-parity`
 *   它现场解析 Java 源码与前端这张表逐项比对，不一致即失败（见 scripts/check-status-parity.mjs）。
 *   没有这个校验，"照抄一张表"就是一次性动作，三个月后一定对不上。
 *
 * ★ 刻意**只收录后端真有接口的动作**：
 *   计划书 §3.2 的表里 `REJECTED` 写的是「退回草稿、作废」，
 *   但后端没有 `POST /{id}/reopen` 之类的接口（DocumentStatus 允许
 *   REJECTED → DRAFT 的转移，却没有暴露成 HTTP 动作）。
 *   照抄计划书就会做出一个点了必然 404/405 的按钮，因此这里只给「作废」，
 *   并在 S1 的验收记录里把这条差异登记为待办（见 .plan/M3-计划书.md §5）。
 *
 * 本文件保持**零依赖、零副作用**（不 import vue / element-plus），
 * 这样它可以被 Node 直接加载做校验，也能在组件外单独测试。
 */

/** 单据种类：状态机共用，动作集不同。 */
export const DOCUMENT_KINDS = ['PURCHASE', 'SALES', 'RETURN'] as const
export type DocumentKind = (typeof DOCUMENT_KINDS)[number]

/** 七个状态，顺序与后端 DocumentStatus.java 的枚举声明顺序一致。 */
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

/**
 * 退货单会用到的状态 = 七态去掉 PARTIAL。
 *
 * 依据不是页面偏好，而是两处写死的约束：契约 `ReturnOrder.status` 的 enum
 * （packages/api-contract/openapi.yaml）与 `V4__rma.sql` 的
 * `ck_return_order_status` CHECK —— 退货整单一次回补，不存在"部分入库"。
 * 列表筛选若给出 PARTIAL，用户只会得到一个永远为空的查询结果，所以这里直接不给。
 */
export const RETURN_STATUSES = DOCUMENT_STATUSES.filter((s) => s !== 'PARTIAL') as readonly DocumentStatus[]

/**
 * 状态元数据。
 *
 * 配色按计划书 §7-5 的建议值：
 * DRAFT 灰 / SUBMITTED 蓝 / APPROVED 绿 / PARTIAL 橙 /
 * COMPLETED 深绿 / VOIDED 深灰 / REJECTED 红。
 *
 * 深绿与深灰用 `effect: 'dark'` 表达 —— Element Plus 的 el-tag 只有
 * primary/success/info/warning/danger 五种色，没有"更深一级"，
 * 用 dark 效果是唯一不引入自定义色板的做法（自定义色板在换主题时会失效）。
 */
export interface StatusMeta {
  /** 中文名，给表格列与筛选下拉用 */
  label: string
  /** el-tag 的 type */
  type: 'primary' | 'success' | 'info' | 'warning' | 'danger'
  /** el-tag 的 effect：终态用 dark，与"还在流转中"的单据形成视觉区分 */
  effect: 'light' | 'dark' | 'plain'
  /** 一句话解释，给 tooltip / 空状态用 */
  description: string
  /** 终态：再也不会变（后端 DocumentStatus#isTerminal） */
  terminal: boolean
  /** 可执行：能对库存产生变动（后端 DocumentStatus#isExecutable） */
  executable: boolean
}

export const STATUS_META: Record<DocumentStatus, StatusMeta> = {
  DRAFT: {
    label: '草稿',
    type: 'info',
    effect: 'light',
    description: '可修改内容，不影响库存',
    terminal: false,
    executable: false,
  },
  SUBMITTED: {
    label: '已提交',
    type: 'primary',
    effect: 'light',
    description: '等待审核，内容已锁定',
    terminal: false,
    executable: false,
  },
  APPROVED: {
    label: '已审核',
    type: 'success',
    effect: 'light',
    description: '可执行入库 / 出货',
    terminal: false,
    executable: true,
  },
  PARTIAL: {
    label: '部分执行',
    type: 'warning',
    effect: 'light',
    description: '已执行一部分，可继续执行剩余数量',
    terminal: false,
    executable: true,
  },
  COMPLETED: {
    label: '已完成',
    type: 'success',
    effect: 'dark',
    description: '全部数量已执行完毕，终态',
    terminal: true,
    executable: false,
  },
  VOIDED: {
    label: '已作废',
    type: 'info',
    effect: 'dark',
    description: '单据作废，不再参与任何统计，终态',
    terminal: true,
    executable: false,
  },
  REJECTED: {
    label: '已驳回',
    type: 'danger',
    effect: 'light',
    description: '审核未通过，只能作废后重开（后端暂无"退回草稿"接口）',
    terminal: false,
    executable: false,
  },
}

/**
 * 后端状态转移表（DocumentStatus.java 的 TRANSITIONS）的镜像。
 *
 * 只有校验脚本与测试读它 —— 页面**不要**用它判断按钮显隐，
 * 那会把"状态能不能转移"和"当前用户能不能点"混在一起。
 * 页面用 {@link nextActions}。
 */
export const ALLOWED_TRANSITIONS: Record<DocumentStatus, DocumentStatus[]> = {
  DRAFT: ['SUBMITTED', 'VOIDED'],
  SUBMITTED: ['APPROVED', 'REJECTED', 'VOIDED'],
  APPROVED: ['PARTIAL', 'COMPLETED', 'VOIDED'],
  PARTIAL: ['PARTIAL', 'COMPLETED', 'VOIDED'],
  COMPLETED: [],
  VOIDED: [],
  REJECTED: ['DRAFT', 'VOIDED'],
}

/** 单据动作。命名与后端子路径一致（/submit /approve /reject /void /receive /ship）。 */
export const DOCUMENT_ACTIONS = ['submit', 'approve', 'reject', 'void', 'receive', 'ship'] as const
export type DocumentAction = (typeof DOCUMENT_ACTIONS)[number]

/**
 * 「种类:动作」联合类型。
 *
 * ★ 为什么逐字列出来，而不用 `${DocumentKind}:${DocumentAction}` 拼：
 *   拼出来的类型包含 `PURCHASE:ship`、`SALES:receive`、`RETURN:ship` 这些**不存在**的组合
 *   （进货不会"出货"，退货不会"出货"），于是 Record 会要求为它们也提供条目 ——
 *   变相强迫人写两条无意义的配置，或者把类型断言成 any 糊过去。
 *   逐字列出 15 个真实组合，多一个少一个都由编译器指出。
 */
export type ActionKey =
  | 'PURCHASE:submit'
  | 'PURCHASE:approve'
  | 'PURCHASE:reject'
  | 'PURCHASE:void'
  | 'PURCHASE:receive'
  | 'SALES:submit'
  | 'SALES:approve'
  | 'SALES:reject'
  | 'SALES:void'
  | 'SALES:ship'
  | 'RETURN:submit'
  | 'RETURN:approve'
  | 'RETURN:reject'
  | 'RETURN:void'
  | 'RETURN:receive'

/** 某动作对应的「种类:动作」键，供 ACTION_META 与 nextActions 共用。 */
function actionKeyOf(kind: DocumentKind, action: DocumentAction): ActionKey {
  return `${kind}:${action}` as ActionKey
}

export interface ActionMeta {
  action: DocumentAction
  label: string
  /** 后端 @RequirePermission 的权限码（前端只用它做按钮显隐，安全边界在后端） */
  permission: string
  /** el-button 的 type */
  buttonType: 'primary' | 'success' | 'warning' | 'danger' | 'info'
  /** 执行前是否必须二次确认（作废会释放预占/改变库存，必须确认） */
  needsConfirm: boolean
  /** 确认弹窗正文（含后果说明，而不是干巴巴一句"确定吗"） */
  confirmText?: string
  /** 是否还要用户在弹窗里填参数（入库量 / 批次 / 效期） */
  needsInput: boolean
}

/**
 * 动作元数据。
 *
 * 权限码**逐字**取自后端 Controller，不是自己编的：
 * - pur:submit / pur:approve / pur:reject / pur:void / pur:receive
 * - sal:submit / sal:approve / sal:reject / sal:void / sal:ship
 *
 * 注意 `SALES + submit/approve/reject/void` 与 `PURCHASE` 完全同名，
 * 但 `ship` / `receive` 不同 —— 所以动作表按"种类:动作"作键，不能只按动作。
 */
export const ACTION_META: Record<ActionKey, ActionMeta> = {
  'PURCHASE:submit': {
    action: 'submit',
    label: '提交',
    permission: 'pur:submit',
    buttonType: 'primary',
    needsConfirm: false,
    needsInput: false,
  },
  'PURCHASE:approve': {
    action: 'approve',
    label: '审核通过',
    permission: 'pur:approve',
    buttonType: 'success',
    needsConfirm: false,
    needsInput: false,
  },
  'PURCHASE:reject': {
    action: 'reject',
    label: '驳回',
    permission: 'pur:reject',
    buttonType: 'warning',
    needsConfirm: true,
    confirmText: '驳回后单据不能被修改，只能作废。确定驳回吗？',
    needsInput: false,
  },
  'PURCHASE:void': {
    action: 'void',
    label: '作废',
    permission: 'pur:void',
    buttonType: 'danger',
    needsConfirm: true,
    confirmText: '作废是终态，不可撤销。已有入库的单据要走红冲（M2.5 仅提供接口，本页按钮只给未入库单据）。确定作废吗？',
    needsInput: false,
  },
  'PURCHASE:receive': {
    action: 'receive',
    label: '入库',
    permission: 'pur:receive',
    buttonType: 'success',
    needsConfirm: false,
    needsInput: true,
  },
  'SALES:submit': {
    action: 'submit',
    label: '提交',
    permission: 'sal:submit',
    buttonType: 'primary',
    needsConfirm: false,
    needsInput: false,
  },
  'SALES:approve': {
    action: 'approve',
    label: '审核通过',
    permission: 'sal:approve',
    buttonType: 'success',
    needsConfirm: false,
    needsInput: false,
  },
  'SALES:reject': {
    action: 'reject',
    label: '驳回',
    permission: 'sal:reject',
    buttonType: 'warning',
    needsConfirm: true,
    confirmText: '驳回后单据不能被修改，只能作废。确定驳回吗？',
    needsInput: false,
  },
  'SALES:void': {
    action: 'void',
    label: '作废',
    permission: 'sal:void',
    buttonType: 'danger',
    needsConfirm: true,
    // ★ 这句必须在确认框里出现：作废会释放该单占用的库存，
    //   用户不知道这一点就会以为"作废只是标记一下"。
    confirmText: '作废会释放本单占用的库存（预占）。已出货的单据要走红冲（M2.5 仅提供接口，本页按钮只给未出货单据）。确定作废吗？',
    needsInput: false,
  },
  'SALES:ship': {
    action: 'ship',
    label: '出货',
    permission: 'sal:ship',
    buttonType: 'success',
    needsConfirm: true,
    confirmText: '出货将从库存真实扣减（按 FEFO 拆批次），不可撤销。确定出货吗？',
    needsInput: false,
  },
  // ★ 退货的权限码逐字取自 ReturnOrderController 的 @RequirePermission。
  //   它的动作名与进货/出货同形（submit/approve/reject/void），
  //   但执行动作复用 `receive`：后端路径是 /return-orders/{id}/receive，
  //   语义是"退货入库（回补库存）"，与进货入库同名不同单据。
  'RETURN:submit': {
    action: 'submit',
    label: '提交',
    permission: 'rma:submit',
    buttonType: 'primary',
    needsConfirm: false,
    needsInput: false,
  },
  'RETURN:approve': {
    action: 'approve',
    label: '审核通过',
    permission: 'rma:approve',
    buttonType: 'success',
    needsConfirm: false,
    needsInput: false,
  },
  'RETURN:reject': {
    action: 'reject',
    label: '驳回',
    permission: 'rma:reject',
    buttonType: 'warning',
    needsConfirm: true,
    confirmText: '驳回后单据不能被修改，只能作废。确定驳回吗？',
    needsInput: false,
  },
  'RETURN:void': {
    action: 'void',
    label: '作废',
    permission: 'rma:void',
    buttonType: 'danger',
    needsConfirm: true,
    // 退货单在入库前作废不动库存；已入库的会被后端拒绝 —— 这句必须让用户看见，
    // 否则会以为"作废"能把回补的库存退回去。
    confirmText:
      '入库前作废不产生任何库存变动，且是终态、不可撤销。已入库（已完成）的退货单作废会被后端拒绝（需红冲）。确定作废吗？',
    needsInput: false,
  },
  'RETURN:receive': {
    action: 'receive',
    label: '退货入库',
    permission: 'rma:receive',
    buttonType: 'success',
    needsConfirm: true,
    confirmText:
      '退货入库会整单一次把明细数量回补到原出货仓库，并写 RETURN_IN 流水、'
      + '累加原出货行的已退数量；不可分批、不可撤销。确定入库吗？',
    needsInput: false,
  },
}

/**
 * 每个状态下**允许**的动作。
 *
 * 逐条对应计划书 §3.2 的表，并已按后端实现校正：
 *
 * | 状态      | 进货单        | 出货单        | 退货单        |
 * |-----------|---------------|---------------|---------------|
 * | DRAFT     | 提交、作废     | 提交、作废     | 提交、作废     |
 * | SUBMITTED | 审核、驳回、作废 | 审核、驳回、作废 | 审核、驳回、作废 |
 * | APPROVED  | 入库、作废     | 出货、作废     | 入库、作废     |
 * | PARTIAL   | 继续入库       | 继续出货       | —（不会到达）   |
 * | COMPLETED | —             | —             | —             |
 * | VOIDED    | —             | —             | —             |
 * | REJECTED  | 作废           | 作废           | 作废           |
 *
 * 三处与计划书原文的差异（都是按后端实际能力收敛的，已在 S1 报告里登记）：
 * 1. REJECTED 没有「退回草稿」——后端无该接口；
 * 2. PARTIAL 不给「作废」——已部分入库/出货的单据作废会被后端拒绝（提示需红冲），
 *    给按钮只会让用户白点一次；
 * 3. APPROVED/PARTIAL 的入库/出货在出货侧带二次确认（真实扣减库存）。
 *
 * RETURN 一列的三条依据（§5.6.1 的 check:status-parity 会逐项验证）：
 * - 退货单的 PARTIAL 是**不可达**状态（整单一次回补，DB CHECK 与契约 enum 都没有它），
 *   所以给空数组而不是"照抄进货单"；
 * - APPROVED 的「作废」保留：入库前不动库存，后端允许；
 * - 「入库」是真实回补库存的动作，因此 needsConfirm = true（见 ACTION_META）。
 */
export const ALLOWED_ACTIONS: Record<DocumentKind, Record<DocumentStatus, DocumentAction[]>> = {
  PURCHASE: {
    DRAFT: ['submit', 'void'],
    SUBMITTED: ['approve', 'reject', 'void'],
    APPROVED: ['receive', 'void'],
    PARTIAL: ['receive'],
    COMPLETED: [],
    VOIDED: [],
    REJECTED: ['void'],
  },
  SALES: {
    DRAFT: ['submit', 'void'],
    SUBMITTED: ['approve', 'reject', 'void'],
    APPROVED: ['ship', 'void'],
    PARTIAL: ['ship'],
    COMPLETED: [],
    VOIDED: [],
    REJECTED: ['void'],
  },
  RETURN: {
    DRAFT: ['submit', 'void'],
    SUBMITTED: ['approve', 'reject', 'void'],
    APPROVED: ['receive', 'void'],
    PARTIAL: [],
    COMPLETED: [],
    VOIDED: [],
    REJECTED: ['void'],
  },
}

/** 状态是否合法（后端若新增状态，这里能兜住而不会让页面崩掉）。 */
export function isDocumentStatus(value: string | null | undefined): value is DocumentStatus {
  return !!value && (DOCUMENT_STATUSES as readonly string[]).includes(value)
}

/** 取状态元数据；未知状态给一个安全兜底，而不是让整个页面报错。 */
export function statusMeta(status: string | null | undefined): StatusMeta {
  if (isDocumentStatus(status)) {
    return STATUS_META[status]
  }
  return {
    label: status || '未知状态',
    type: 'info',
    effect: 'plain',
    description: '后端返回了前端未识别的状态，请检查状态机是否有新增分支',
    terminal: true,
    executable: false,
  }
}

/**
 * 当前状态下可做的动作（含权限过滤）。
 *
 * @param kind        单据种类
 * @param status      当前状态
 * @param permissions 当前账号权限码，用于过滤不可点的按钮。
 *                    传 `undefined` 表示不按权限过滤（一般只用于测试）。
 * @param can         可选权限判断函数（会话里支持 "模块:*" 通配）。
 *                    默认按前缀通配判断，保持与后端 AppUser#hasPermission 一致。
 */
export function nextActions(
  kind: DocumentKind,
  status: string | null | undefined,
  permissions?: readonly string[],
  can?: (permission: string) => boolean,
): ActionMeta[] {
  if (!isDocumentStatus(status)) {
    return []
  }
  const actions = ALLOWED_ACTIONS[kind][status].map((a) => ACTION_META[actionKeyOf(kind, a)])
  if (!permissions && !can) {
    return actions
  }
  const allowed = can ?? ((permission: string) => hasPermission(permissions ?? [], permission))
  return actions.filter((a) => allowed(a.permission))
}

/** 权限判断：支持 "模块:*" 通配，语义与后端 AppUser#hasPermission 一致。 */
export function hasPermission(permissions: readonly string[], permission: string): boolean {
  return (
    permissions.includes(permission) ||
    permissions.includes(permission.split(':')[0] + ':*')
  )
}

/** 是否终态（终态单据不可再做任何操作，列表里应显示为灰色）。 */
export function isTerminal(status: string | null | undefined): boolean {
  return statusMeta(status).terminal
}

/**
 * 进度文本：`已执行 / 总数`。
 *
 * 为什么单独抽出来：这是老板最关心的一列（计划书 §7-3 "这单还差多少没到货"），
 * 而进货用 receivedQty、出货用 shippedQty，字段名不同、含义相同。
 * 两个页面各写一遍就会有一处写错字段，读起来像 bug。
 */
export function progressText(
  kind: DocumentKind,
  executedQty: number | null | undefined,
  totalQty: number | null | undefined,
): string {
  const done = executedQty ?? 0
  const total = totalQty ?? 0
  const verb = kind === 'PURCHASE' ? '已入' : '已出'
  return `${verb} ${done}/${total}`
}
