/**
 * 报表中心的声明式类型（FR-V3-31）。
 *
 * ★ 为什么是「配置 + 框架」而不是 10 个各写一遍的页面：
 *   后端 11 个报表端点的形状差异只在参数名和列名上。把差异收敛成一份
 *   ReportDefinition，加载态 / 空状态 / 错误重试 / 合计行 / 千分位 / CSV 导出 /
 *   打印样式就只需要实现一次，10 张表同时受益 —— 也让「加一张报表」
 *   的成本降到十几行配置。
 */

/** 表格行：后端 DTO 的字段并集，列取值一律走 column.get。 */
export type Row = Record<string, any>

/** 查询条件控件类型。 */
export type ParamKind =
  | 'daterange'
  | 'period'
  | 'periodrange'
  | 'date'
  | 'select'
  | 'text'
  | 'number'
  | 'warehouse'

export interface ParamOption {
  value: string | number
  label: string
}

export interface ReportParam {
  /** 查询条件对象的键；必须与后端 @RequestParam 名一致 */
  key: string
  label: string
  kind: ParamKind
  /** select 的候选；number 的 min/max 走原生属性 */
  options?: ParamOption[]
  /** daterange / periodrange 实际落到的两个键 */
  pair?: [string, string]
  default?: string | number
  min?: number
  max?: number
  step?: number
  hint?: string
  /** 该条件依赖的其他条件（变化时触发 definition.linkage 重算） */
  dependsOn?: string[]
}

export type ColumnKind = 'text' | 'qty' | 'int' | 'money' | 'rate' | 'days' | 'bool'

export interface ReportColumn {
  key: string
  label: string
  kind: ColumnKind
  width?: number
  align?: 'left' | 'right' | 'center'
  /** 合计行的算法；money/qty/int 求和，text 显示条数 */
  total?: 'sum' | 'count' | 'none'
  /** 从行取原始值；缺省按 key 直取 */
  get?: (row: Row) => unknown
}

export type ChartType = 'line' | 'bar' | 'hbar' | 'donut' | 'stack'

export interface ChartSeries {
  name: string
  /** 从行取数值（分 / 件 原样，MiniChart 内部按 unit 换算） */
  value: (row: Row) => number
  color?: string
}

export interface ReportChart {
  type: ChartType
  title: string
  /** X 轴 / 分类取值 */
  category: (row: Row) => string
  series: ChartSeries[]
  /** 金额类图表按分传入，由组件转成万元显示 */
  unit?: 'money' | 'qty' | 'rate'
  /** 只画前 N 行（TOP 榜） */
  top?: number
}

export type CsvSource =
  /**
   * 服务端 CSV：后端 ReportExportController 与页面查询走同一个 ApplicationService，
   * 所以「导出 == 页面」。params 缺省时由框架按声明的条件自动提取
   * （aging 这类「type 是报表身份而不是筛选条件」的端点要自己给出）。
   */
  | {
      kind: 'server'
      path: string
      fallbackName: string
      params?: (ctx: ReportQueryContext) => Record<string, string | number | undefined>
    }
  /** 前端 CSV：后端没有对应 /export 端点，用当前结果集本地生成（含 UTF-8 BOM）。 */
  | { kind: 'local'; prefix: string }

export interface ReportSummaryChip {
  label: string
  /** 由行集算出的展示值（已格式化） */
  value: (rows: Row[]) => string
}

export interface ReportQueryContext {
  /** 当前查询条件值（键 = ReportParam.key，daterange 展开到 pair 的两个键） */
  values: Record<string, string | number | undefined>
  /** 仓库字典（warehouse 条件用），load 里可按 id 反查名称 */
  warehouses: { id: number; name: string }[]
  /**
   * SKU 字典（needsSkuDict 时由框架装载）。
   * ★ 后端 /reports/inventory-summary 只回 skuId / warehouseId，名称必须在前端补，
   *   且分页上限 200 条 —— 装不下的行会退化成 "SKU#id"，页面要明说。
   */
  skus: { id: number; code: string; name: string }[]
  /**
   * SKU 字典是否被 200 条分页上限截断。
   * ★ 截断时名称列会退化成 "SKU#id"，必须在页面上说清楚，
   *   否则用户以为「报表算错了」。
   */
  dictTruncated: boolean
}

export interface ReportDefinition {
  id: string
  /** 侧边栏 / 目录里的短名 */
  title: string
  /** 分组键（经营分析 / 库存 / 采购 / 销售 / 财务） */
  group: string
  /** 页面上的完整标题 */
  subtitle?: string
  /**
   * ★ 口径说明：后端 ReportController 的类注释明确要求「口径要在这一层写清楚，
   *   前端页面必须照抄展示」。这段话直接渲染在参数区下方，不做隐藏。
   */
  caliber: string
  params: ReportParam[]
  columns: ReportColumn[]
  load: (ctx: ReportQueryContext) => Promise<Row[]>
  /** 需要框架装载 SKU 字典（后端只回 skuId 的端点） */
  needsSkuDict?: boolean
  /** 查询条件联动：changedKey 变化时对整份条件做纯函数修正 */
  linkage?: (values: Record<string, string | number | undefined>, changedKey: string) => Record<string, string | number | undefined>
  chart?: ReportChart
  chips?: ReportSummaryChip[]
  csv: CsvSource
  /** 导出 / 打印时是否附上汇总行 */
  csvTotals?: boolean
}

export interface ReportGroupMeta {
  key: string
  title: string
}

/** 取列原始值。 */
export function columnValue(column: ReportColumn, row: Row): unknown {
  return column.get ? column.get(row) : row[column.key]
}
