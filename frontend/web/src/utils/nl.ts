/**
 * 自然语言操作的中文标签（意图 / 状态 / 调用方）。
 *
 * ★ 为什么单独抽一个 util：
 *   操作台、待确认队列、操作审计三个地方都要显示同一批字段。
 *   三处各写一份映射，结果是"同一个 create_purchase_order ，
 *   在操作台叫'建进货草稿'、在审计里叫'创建进货单'"——这种不一致
 *   出现在答辩现场，比任何 bug 都更像"没做完"。
 *
 * ★ 为什么不用后端返回的 label：
 *   状态后端确实带了 statusLabel（NlExecutionStatus.label()），这里只做兜底；
 *   意图没有中文名，后端只给 code，所以映射必须在某一处集中维护。
 */

/** 意图代码 → 中文名。新增意图时**必须**同步这里，否则界面会露出英文代码。 */
export const INTENT_LABELS: Record<string, string> = {
  help: '能力清单',
  who_am_i: '模型自述',
  query_inventory: '查库存',
  list_documents: '单据列表',
  get_document: '单据详情',
  inventory_summary: '进销存汇总',
  replenish_advice: '补货建议',
  query_master_data: '查主数据',
  create_purchase_order: '建进货草稿',
  create_sales_order: '建出货草稿',
  submit_document: '提交单据',
}

export function intentLabel(code: string | undefined | null): string {
  return code ? (INTENT_LABELS[code] ?? code) : ''
}

/** 执行状态 → 视觉分组。用语义色而不是给每个状态配一种颜色（七种颜色没人分得清）。 */
export type StatusTone = 'pending' | 'ok' | 'bad' | 'muted'

export function statusTone(status: string | undefined | null): StatusTone {
  switch (status) {
    case 'PENDING':
      return 'pending'
    case 'EXECUTED':
      return 'ok'
    case 'FAILED':
    case 'VETOED':
    case 'BLOCKED':
      return 'bad'
    default:
      // DISMISSED / EXPIRED：既不是成功也不是故障，是"没发生"
      return 'muted'
  }
}

/** 调用方 → 人话。审计里"是页面点的还是 Agent 发的"必须一眼看得出来。 */
export function sourceLabel(source: string | undefined | null): string {
  switch (source) {
    case 'MCP':
      return 'MCP Agent'
    case 'API':
      return '接口调用'
    case 'WEB':
      return '网页'
    default:
      return source || '-'
  }
}

/** 复核结论 → 中文。APPROVE 一律说"不反对"，不能说"批准"。 */
export function verdictLabel(verdict: string | undefined | null): string {
  switch (verdict) {
    case 'APPROVE':
      return '不反对'
    case 'NEED_HUMAN':
      return '需人判断'
    case 'REJECT':
      return '否决'
    default:
      return verdict || '-'
  }
}
