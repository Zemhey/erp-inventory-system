/**
 * 自然语言意图的中文标签。
 *
 * ★ 为什么单独抽一个 util：工作台入口、自然语言页都要显示意图中文名，
 *   两处各写一份映射会出现"操作台叫'查库存'、别处叫'查询库存'"的不一致，
 *   答辩现场这种不一致最像"没做完"。集中维护，新增意图时只改这里。
 *
 * ★ 为什么不用后端返回的 label：后端只给意图 code（如 query_inventory），
 *   中文名必须由某一端补，这里就是移动端的唯一来源（不跨端 import web）。
 */

/** 意图代码 → 中文名。新增意图时必须同步这里，否则界面会露出英文代码。 */
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
