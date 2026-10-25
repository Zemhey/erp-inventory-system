/**
 * 报表目录（FR-V3-32）—— 11 张表，全部对着后端真实端点写，没有一张是猜的。
 *
 * ★ 端点核实结果（grep 自 rpt/api + cst/api，基线 a0cf1ba）：
 *   ReportController        /api/v1/reports/{purchase, sales, inventory-summary}
 *   ReportAdvancedController /api/v1/reports/{operating, inventory-turnover,
 *                            customer-contribution, aging}
 *   ReportExportController  以上 7 个各有一个同名 /export（text/csv + UTF-8 BOM）
 *   MarginController(cst)   /api/v1/reports/profit（账期 yyyy-MM，无 /export）
 *
 * ★ 与简报不一致的三处（以代码为准）：
 *   1. /reports/aging 的 type 收 **AR / AP**（ReportRequestParams.parseAgingType），
 *      不是简报写的 RECEIVABLE / PAYABLE —— 照简报写会 400。
 *   2. 呆滞分析**不需要新后端**：/reports/inventory-turnover 已经带
 *      deadDays / idleDays / stale 三个字段，前端按 stale 过滤即为呆滞表。
 *   3. /reports/profit 的 from/to 是 **yyyy-MM**，与其他报表的 ISO 日期不同。
 */
import { cstApi, rptApi, type MarginGroupBy, type ReportGroupBy } from '@/api/client'
import { reportApi, type ReportPeriodDim } from '@/api/report'
import { isoDate, isoMonth, monthEnd, monthStart, periodOf, presetRange, shiftMonth } from './dates'
import type { ReportDefinition, ReportGroupMeta, Row } from './types'

// ─────────────────────────── 公共参数 ───────────────────────────

const WAREHOUSE_PARAM: ReportDefinition['params'][number] = {
  key: 'warehouseId',
  label: '仓库',
  kind: 'warehouse',
  hint: '留空 = 全部仓库',
}

const DIMENSION_OPTIONS = [
  { value: 'day', label: '按日' },
  { value: 'month', label: '按月' },
]

const PURCHASE_DIMS = [
  { value: 'supplier', label: '按供应商' },
  { value: 'product', label: '按商品' },
  { value: 'sku', label: '按 SKU' },
  { value: 'day', label: '按日' },
  { value: 'month', label: '按月' },
]

const SALES_DIMS = [
  { value: 'customer', label: '按客户' },
  { value: 'product', label: '按商品' },
  { value: 'sku', label: '按 SKU' },
  { value: 'day', label: '按日' },
  { value: 'month', label: '按月' },
]

const PROFIT_DIMS = [
  { value: 'product', label: '按商品' },
  { value: 'customer', label: '按客户' },
  { value: 'period', label: '按账期' },
]

/** 空条件值（undefined）不发给后端，否则 LocalDate 绑定会 400。 */
function str(v: string | number | undefined): string | undefined {
  return v === undefined || v === '' ? undefined : String(v)
}
function num(v: string | number | undefined): number | undefined {
  const s = str(v)
  return s === undefined ? undefined : Number(s)
}

/** 按列求和（分或件）：汇总 chip 与表尾合计行共用同一条公式。 */
export function totalOf(rows: Row[], key: string): number {
  return rows.reduce((s, r) => s + Number(r[key] ?? 0), 0)
}

// ─────────────────────────── 报表定义 ───────────────────────────

export const REPORT_GROUPS: ReportGroupMeta[] = [
  { key: 'analysis', title: '经营分析' },
  { key: 'inventory', title: '库存报表' },
  { key: 'purchase', title: '采购报表' },
  { key: 'sales', title: '销售报表' },
  { key: 'finance', title: '财务账龄' },
]

export const REPORTS: ReportDefinition[] = [
  // 1. 经营报表 —— GET /reports/operating
  {
    id: 'operating',
    title: '经营报表',
    group: 'analysis',
    subtitle: '期间经营全景：库存流水口径 + 单据口径并列',
    caliber:
      '期初/入/出/期末来自库存流水（occurred_at），进货/销售来自单据业务日期（biz_date）。两组数口径不同，绝不跨口径相加；跨天分批执行导致同一天两边不等不是 bug。',
    params: [
      { key: '__preset', label: '统计期间', kind: 'daterange', pair: ['from', 'to'], dependsOn: ['dimension'] },
      { key: 'dimension', label: '时间粒度', kind: 'select', options: DIMENSION_OPTIONS, default: 'day' },
      WAREHOUSE_PARAM,
    ],
    columns: [
      { key: 'period', label: '期间', kind: 'text', width: 130, total: 'count' },
      { key: 'openingQty', label: '期初量', kind: 'qty', total: 'sum' },
      { key: 'inboundQty', label: '入库量', kind: 'qty', total: 'sum' },
      { key: 'outboundQty', label: '出库量', kind: 'qty', total: 'sum' },
      { key: 'closingQty', label: '期末量', kind: 'qty', total: 'sum' },
      { key: 'purchaseQty', label: '进货量', kind: 'qty', total: 'sum' },
      { key: 'purchaseAmount', label: '进货金额(元)', kind: 'money', total: 'sum' },
      { key: 'salesQty', label: '出货量', kind: 'qty', total: 'sum' },
      { key: 'salesAmount', label: '出货金额(元)', kind: 'money', total: 'sum' },
    ],
    async load({ values }) {
      const rows = await reportApi.operating({
        from: str(values.from) ?? isoDate(new Date()),
        to: str(values.to) ?? isoDate(new Date()),
        dimension: (str(values.dimension) as ReportPeriodDim | undefined) ?? 'day',
        warehouseId: num(values.warehouseId),
      })
      return rows.items as Row[]
    },
    linkage(values, changed) {
      // 期间与维度联动：切到「按月」把区间对齐到整月，避免出现半个自然月
      if (changed === 'dimension' && values.dimension === 'month') {
        const from = str(values.from)
        const to = str(values.to)
        if (from && to) return { ...values, from: `${from.slice(0, 7)}-01`, to: isoDate(monthEnd(periodOf(to))) }
      }
      return values
    },
    chart: {
      type: 'line',
      title: '出货金额与进货金额走势',
      category: (r) => String(r.period ?? ''),
      unit: 'money',
      series: [
        { name: '出货金额', value: (r) => Number(r.salesAmount ?? 0) },
        { name: '进货金额', value: (r) => Number(r.purchaseAmount ?? 0) },
      ],
    },
    chips: [
      { label: '期间数', value: (rows) => String(rows.length) },
      { label: '出货金额', value: (rows) => yuan(rows.reduce((s, r) => s + Number(r.salesAmount ?? 0), 0)) },
      { label: '进货金额', value: (rows) => yuan(rows.reduce((s, r) => s + Number(r.purchaseAmount ?? 0), 0)) },
    ],
    csv: { kind: 'server', path: '/reports/operating/export', fallbackName: 'operating.csv' },
  },

  // 2. 毛利分析 —— GET /reports/profit（cst 模块，from/to 是账期）
  {
    id: 'profit',
    title: '毛利分析',
    group: 'analysis',
    subtitle: '收入 / 成本 / 毛利，按商品·客户·账期',
    caliber:
      '数据来自成本月结生成的 margin_records：未执行月结的期间结果为空，不是「卖出去没有毛利」。毛利率是后端算好的万分比（grossMarginRateBp），前端只除 100 显示。',
    params: [
      { key: '__preset', label: '账期区间', kind: 'periodrange', pair: ['from', 'to'], default: isoMonth(new Date()) },
      { key: 'groupBy', label: '统计维度', kind: 'select', options: PROFIT_DIMS, default: 'product' },
    ],
    columns: [
      { key: 'label', label: '名称', kind: 'text', width: 220, total: 'none' },
      { key: 'qty', label: '数量', kind: 'qty', total: 'sum' },
      { key: 'revenueAmount', label: '收入(元)', kind: 'money', total: 'sum' },
      { key: 'costAmount', label: '成本(元)', kind: 'money', total: 'sum' },
      { key: 'grossProfitAmount', label: '毛利(元)', kind: 'money', total: 'sum' },
      {
        key: 'grossMarginRateBp',
        label: '毛利率',
        kind: 'rate',
        total: 'none',
        get: (r) => (Number(r.revenueAmount ?? 0) === 0 ? null : Number(r.grossMarginRateBp ?? 0)),
      },
      { key: 'orderCount', label: '单数', kind: 'int', total: 'sum' },
    ],
    async load({ values }) {
      const rows = await cstApi.profit({
        from: str(values.from) ?? isoMonth(new Date()),
        to: str(values.to) ?? isoMonth(new Date()),
        groupBy: (str(values.groupBy) as MarginGroupBy | undefined) ?? 'product',
      })
      return rows.items as Row[]
    },
    linkage(values, changed) {
      // 账期与维度联动：切「按账期」时把区间放开到全年，否则单点没有对比意义
      if (changed === 'groupBy' && values.groupBy === 'period') {
        const to = str(values.to)
        if (to) return { ...values, from: `${to.slice(0, 4)}-01` }
      }
      return values
    },
    chart: {
      type: 'bar',
      title: '毛利额与毛利率',
      category: (r) => String(r.label ?? r.key ?? ''),
      unit: 'money',
      top: 12,
      series: [{ name: '毛利额', value: (r) => Number(r.grossProfitAmount ?? 0) }],
    },
    chips: [
      { label: '收入', value: (rows) => yuan(rows.reduce((s, r) => s + Number(r.revenueAmount ?? 0), 0)) },
      { label: '毛利', value: (rows) => yuan(rows.reduce((s, r) => s + Number(r.grossProfitAmount ?? 0), 0)) },
    ],
    // 后端没有 /reports/profit/export —— 见报告的「缺失端点清单」
    csv: { kind: 'local', prefix: '毛利分析' },
  },

  // 3. 进销存汇总 —— GET /reports/inventory-summary
  {
    id: 'inventory-summary',
    title: '进销存汇总',
    group: 'inventory',
    subtitle: '期初 + 入 − 出 = 期末（按 SKU × 仓库）',
    caliber:
      '按流水发生时间（occurred_at）统计，参数是账期 yyyy-MM 而不是日期区间 —— 任意区间没有「期初」概念。恒等式 期初+入−出=期末 由后端保证，页面把差额单列出来做目视验证。',
    params: [
      { key: 'period', label: '账期', kind: 'period', default: isoMonth(new Date()) },
      WAREHOUSE_PARAM,
    ],
    columns: [
      { key: 'skuCode', label: 'SKU 编码', kind: 'text', width: 150, total: 'none' },
      { key: 'skuName', label: 'SKU 名称', kind: 'text', width: 220, total: 'none' },
      { key: 'warehouseName', label: '仓库', kind: 'text', width: 140, total: 'none' },
      { key: 'openingQty', label: '期初', kind: 'qty', total: 'sum' },
      { key: 'inboundQty', label: '入库', kind: 'qty', total: 'sum' },
      { key: 'outboundQty', label: '出库', kind: 'qty', total: 'sum' },
      { key: 'closingQty', label: '期末', kind: 'qty', total: 'sum' },
      {
        key: '__balance',
        label: '恒等式差额',
        kind: 'qty',
        width: 120,
        total: 'sum',
        get: (r) => Number(r.openingQty ?? 0) + Number(r.inboundQty ?? 0) - Number(r.outboundQty ?? 0) - Number(r.closingQty ?? 0),
      },
    ],
    needsSkuDict: true,
    async load({ values, warehouses, skus }) {
      const rows = await rptApi.inventorySummary({
        period: str(values.period) ?? isoMonth(new Date()),
        warehouseId: num(values.warehouseId),
      })
      const skuById = new Map(skus.map((s) => [s.id, s]))
      const whById = new Map(warehouses.map((w) => [w.id, w.name]))
      return rows.items.map((r) => ({
        ...r,
        skuCode: skuById.get(r.skuId)?.code ?? `SKU#${r.skuId}`,
        skuName: skuById.get(r.skuId)?.name ?? '（超出字典 200 条）',
        warehouseName: whById.get(r.warehouseId) ?? `仓#${r.warehouseId}`,
      })) as Row[]
    },
    chart: {
      type: 'hbar',
      title: '期末库存 TOP10（SKU × 仓库）',
      category: (r) => `${r.skuName ?? ''}`,
      unit: 'qty',
      top: 10,
      series: [{ name: '期末库存', value: (r) => Number(r.closingQty ?? 0) }],
    },
    chips: [
      { label: '行数', value: (rows) => String(rows.length) },
      { label: '期初合计', value: (rows) => String(rows.reduce((s, r) => s + Number(r.openingQty ?? 0), 0)) },
      { label: '期末合计', value: (rows) => String(rows.reduce((s, r) => s + Number(r.closingQty ?? 0), 0)) },
      {
        label: '恒等式',
        value: (rows) => {
          const diff = rows.reduce(
            (s, r) => s + Number(r.openingQty ?? 0) + Number(r.inboundQty ?? 0) - Number(r.outboundQty ?? 0) - Number(r.closingQty ?? 0),
            0,
          )
          return diff === 0 ? '期初+入−出=期末 ✓' : `差额 ${diff}`
        },
      },
    ],
    csv: { kind: 'server', path: '/reports/inventory-summary/export', fallbackName: 'inventory-summary.csv' },
  },

  // 4. 库存周转 —— GET /reports/inventory-turnover
  {
    id: 'turnover',
    title: '库存周转',
    group: 'inventory',
    subtitle: '周转天数 / 空闲天数（含呆滞标记）',
    caliber:
      '按流水发生时间统计。turnoverDays 为 null 表示期间内无出库、周转不可计算，页面显示「—」而不是 0；deadDays 是呆滞判定的空闲天数阈值。',
    params: [
      { key: '__preset', label: '统计期间', kind: 'daterange', pair: ['from', 'to'] },
      { key: 'deadDays', label: '呆滞阈值(天)', kind: 'number', default: 30, min: 1, max: 365, step: 1, dependsOn: ['__preset'] },
      WAREHOUSE_PARAM,
    ],
    columns: [
      { key: 'skuCode', label: 'SKU 编码', kind: 'text', width: 150, total: 'none' },
      { key: 'skuName', label: 'SKU 名称', kind: 'text', width: 200, total: 'none' },
      { key: 'warehouseName', label: '仓库', kind: 'text', width: 130, total: 'none' },
      { key: 'openingQty', label: '期初', kind: 'qty', total: 'sum' },
      { key: 'closingQty', label: '期末', kind: 'qty', total: 'sum' },
      { key: 'outboundQty', label: '出库量', kind: 'qty', total: 'sum' },
      { key: 'turnoverDays', label: '周转天数', kind: 'days', total: 'none' },
      { key: 'idleDays', label: '空闲天数', kind: 'days', total: 'none' },
      { key: 'stale', label: '呆滞', kind: 'bool', width: 80, total: 'none' },
    ],
    needsSkuDict: true,
    async load({ values, warehouses, skus }) {
      const rows = await reportApi.turnover({
        from: str(values.from) ?? presetRange('last30')[0],
        to: str(values.to) ?? presetRange('last30')[1],
        deadDays: num(values.deadDays) ?? 30,
        warehouseId: num(values.warehouseId),
      })
      const skuById = new Map(skus.map((s) => [s.id, s]))
      const whById = new Map(warehouses.map((w) => [w.id, w.name]))
      // 周转端点已经回 skuName / warehouseName，字典只兜底
      return rows.items.map((r) => ({
        ...r,
        skuCode: r.skuCode || skuById.get(r.skuId)?.code || `SKU#${r.skuId}`,
        skuName: r.skuName || skuById.get(r.skuId)?.name || '-',
        warehouseName: r.warehouseName || whById.get(r.warehouseId) || `仓#${r.warehouseId}`,
      })) as Row[]
    },
    chart: {
      type: 'hbar',
      title: '周转最慢 TOP10',
      category: (r) => String(r.skuName ?? ''),
      unit: 'qty',
      top: 10,
      series: [
        {
          name: '周转天数',
          value: (r) => (r.turnoverDays == null ? 999 : Number(r.turnoverDays)),
        },
      ],
    },
    chips: [
      { label: 'SKU×仓', value: (rows) => String(rows.length) },
      { label: '呆滞条目', value: (rows) => String(rows.filter((r) => r.stale === true).length) },
    ],
    csv: { kind: 'server', path: '/reports/inventory-turnover/export', fallbackName: 'inventory-turnover.csv' },
  },

  // 5. 呆滞分析 —— 同一端点 /reports/inventory-turnover，前端按 stale 过滤
  {
    id: 'slow-moving',
    title: '呆滞分析',
    group: 'inventory',
    subtitle: '有库存但长期无出库（纯前端：周转端点 + stale 过滤）',
    caliber:
      '不新增后端：直接复用 /reports/inventory-turnover 的 idleDays / stale 字段，把「期末>0 且 空闲天数≥阈值」的行挑出来。口径与周转表完全一致，只是过滤条件不同。',
    params: [
      { key: '__preset', label: '统计期间', kind: 'daterange', pair: ['from', 'to'] },
      { key: 'deadDays', label: '呆滞阈值(天)', kind: 'number', default: 90, min: 1, max: 365, step: 1 },
      { key: 'minIdle', label: '空闲天数下限', kind: 'number', default: 90, min: 0, max: 365, step: 5, dependsOn: ['deadDays'] },
      WAREHOUSE_PARAM,
    ],
    columns: [
      { key: 'skuCode', label: 'SKU 编码', kind: 'text', width: 150, total: 'none' },
      { key: 'skuName', label: 'SKU 名称', kind: 'text', width: 220, total: 'none' },
      { key: 'warehouseName', label: '仓库', kind: 'text', width: 130, total: 'none' },
      { key: 'closingQty', label: '期末库存', kind: 'qty', total: 'sum' },
      { key: 'outboundQty', label: '期间出库', kind: 'qty', total: 'sum' },
      { key: 'idleDays', label: '空闲天数', kind: 'days', total: 'none' },
      { key: 'turnoverDays', label: '周转天数', kind: 'days', total: 'none' },
    ],
    needsSkuDict: true,
    async load({ values, warehouses, skus }) {
      const from = str(values.from) ?? presetRange('last30')[0]
      const to = str(values.to) ?? presetRange('last30')[1]
      const deadDays = num(values.deadDays) ?? 90
      const minIdle = num(values.minIdle) ?? deadDays
      const rows = await reportApi.turnover({ from, to, deadDays, warehouseId: num(values.warehouseId) })
      const skuById = new Map(skus.map((s) => [s.id, s]))
      const whById = new Map(warehouses.map((w) => [w.id, w.name]))
      return rows.items
        .map((r) => ({
          ...r,
          skuCode: r.skuCode || skuById.get(r.skuId)?.code || `SKU#${r.skuId}`,
          skuName: r.skuName || skuById.get(r.skuId)?.name || '-',
          warehouseName: r.warehouseName || whById.get(r.warehouseId) || `仓#${r.warehouseId}`,
        }))
        .filter((r) => Number(r.closingQty) > 0 && Number(r.idleDays) >= minIdle)
        .sort((a, b) => Number(b.idleDays) - Number(a.idleDays)) as Row[]
    },
    linkage(values, changed) {
      // 阈值与下限联动：改呆滞阈值时把「空闲天数下限」一起带走，两个条件不会打架
      if (changed === 'deadDays') return { ...values, minIdle: values.deadDays }
      return values
    },
    chart: {
      type: 'hbar',
      title: '压货最久 TOP10',
      category: (r) => `${r.skuName ?? ''}（${r.warehouseName ?? ''}）`,
      unit: 'qty',
      top: 10,
      series: [{ name: '空闲天数', value: (r) => Number(r.idleDays ?? 0) }],
    },
    chips: [
      { label: '呆滞条目', value: (rows) => String(rows.length) },
      { label: '压货数量', value: (rows) => String(rows.reduce((s, r) => s + Number(r.closingQty ?? 0), 0)) },
    ],
    csv: { kind: 'local', prefix: '呆滞分析' },
  },

  // 6. 进货统计 —— GET /reports/purchase
  {
    id: 'purchase',
    title: '进货统计',
    group: 'purchase',
    subtitle: '按供应商 / 商品 / SKU / 日 / 月聚合',
    caliber:
      '按单据业务日期（biz_date）统计，与进销存汇总的流水口径未必相等。groupBy 只允许 supplier|product|sku|day|month，传别的后端返 400 并把允许值列在错误消息里。',
    params: [
      { key: '__preset', label: '统计期间', kind: 'daterange', pair: ['from', 'to'] },
      { key: 'groupBy', label: '统计维度', kind: 'select', options: PURCHASE_DIMS, default: 'supplier' },
      WAREHOUSE_PARAM,
    ],
    columns: [
      { key: 'label', label: '维度名称', kind: 'text', width: 240, total: 'none' },
      { key: 'qty', label: '数量', kind: 'qty', total: 'sum' },
      { key: 'amount', label: '金额(元)', kind: 'money', total: 'sum' },
      { key: 'orderCount', label: '单数', kind: 'int', total: 'sum' },
    ],
    async load({ values }) {
      const rows = await rptApi.purchase({
        from: str(values.from) ?? presetRange('last30')[0],
        to: str(values.to) ?? presetRange('last30')[1],
        groupBy: (str(values.groupBy) as ReportGroupBy | undefined) ?? 'supplier',
        warehouseId: num(values.warehouseId),
      })
      return rows.items as Row[]
    },
    chart: {
      type: 'bar',
      title: '进货金额分布',
      category: (r) => String(r.label ?? ''),
      unit: 'money',
      top: 12,
      series: [{ name: '进货金额', value: (r) => Number(r.amount ?? 0) }],
    },
    chips: [
      { label: '金额合计', value: (rows) => yuan(rows.reduce((s, r) => s + Number(r.amount ?? 0), 0)) },
      { label: '数量合计', value: (rows) => String(rows.reduce((s, r) => s + Number(r.qty ?? 0), 0)) },
    ],
    csv: { kind: 'server', path: '/reports/purchase/export', fallbackName: 'purchase.csv' },
  },

  // 7. 出货统计 —— GET /reports/sales
  {
    id: 'sales',
    title: '出货统计',
    group: 'sales',
    subtitle: '按客户 / 商品 / SKU / 日 / 月聚合',
    caliber: '按单据业务日期（biz_date）统计。groupBy 允许 customer|product|sku|day|month。',
    params: [
      { key: '__preset', label: '统计期间', kind: 'daterange', pair: ['from', 'to'] },
      { key: 'groupBy', label: '统计维度', kind: 'select', options: SALES_DIMS, default: 'customer' },
      WAREHOUSE_PARAM,
    ],
    columns: [
      { key: 'label', label: '维度名称', kind: 'text', width: 240, total: 'none' },
      { key: 'qty', label: '数量', kind: 'qty', total: 'sum' },
      { key: 'amount', label: '金额(元)', kind: 'money', total: 'sum' },
      { key: 'orderCount', label: '单数', kind: 'int', total: 'sum' },
    ],
    async load({ values }) {
      const rows = await rptApi.sales({
        from: str(values.from) ?? presetRange('last30')[0],
        to: str(values.to) ?? presetRange('last30')[1],
        groupBy: (str(values.groupBy) as ReportGroupBy | undefined) ?? 'customer',
        warehouseId: num(values.warehouseId),
      })
      return rows.items as Row[]
    },
    chart: {
      type: 'bar',
      title: '出货金额分布',
      category: (r) => String(r.label ?? ''),
      unit: 'money',
      top: 12,
      series: [{ name: '出货金额', value: (r) => Number(r.amount ?? 0) }],
    },
    chips: [
      { label: '金额合计', value: (rows) => yuan(rows.reduce((s, r) => s + Number(r.amount ?? 0), 0)) },
      { label: '单数合计', value: (rows) => String(rows.reduce((s, r) => s + Number(r.orderCount ?? 0), 0)) },
    ],
    csv: { kind: 'server', path: '/reports/sales/export', fallbackName: 'sales.csv' },
  },

  // 8. 单品销售排行 —— 同一端点 /reports/sales?groupBy=sku，前端排序截断
  {
    id: 'sales-top',
    title: '单品销售排行',
    group: 'sales',
    subtitle: 'TOP N 畅销 SKU（纯前端：出货统计 groupBy=sku）',
    caliber:
      '端点没有 topN 参数，因此取回全量按 SKU 聚合的行，在前端按金额排序取前 N。N 只影响展示与 CSV，不影响后端查询 —— 与「导出 == 页面」的服务端导出不同，这里走前端 CSV。',
    params: [
      { key: '__preset', label: '统计期间', kind: 'daterange', pair: ['from', 'to'] },
      { key: 'topN', label: '榜单长度', kind: 'number', default: 20, min: 5, max: 100, step: 5 },
      WAREHOUSE_PARAM,
    ],
    columns: [
      { key: '__rank', label: '名次', kind: 'int', width: 80, total: 'none' },
      { key: 'label', label: 'SKU', kind: 'text', width: 240, total: 'none' },
      { key: 'qty', label: '销量', kind: 'qty', total: 'sum' },
      { key: 'amount', label: '销售额(元)', kind: 'money', total: 'sum' },
      {
        key: '__share',
        label: '占比',
        kind: 'rate',
        width: 110,
        total: 'none',
      },
      { key: 'orderCount', label: '单数', kind: 'int', total: 'sum' },
    ],
    async load({ values }) {
      const from = str(values.from) ?? presetRange('last30')[0]
      const to = str(values.to) ?? presetRange('last30')[1]
      const limit = num(values.topN) ?? 20
      const rows = await rptApi.sales({ from, to, groupBy: 'sku', warehouseId: num(values.warehouseId) })
      const sorted = [...rows.items].sort((a, b) => Number(b.amount) - Number(a.amount)).slice(0, limit)
      const total = sorted.reduce((s, r) => s + Number(r.amount ?? 0), 0)
      return sorted.map((r, i) => ({
        ...r,
        __rank: i + 1,
        // 万分比整数，交给 rate 列渲染
        __share: total === 0 ? null : Math.round((Number(r.amount ?? 0) / total) * 10000),
      })) as Row[]
    },
    chart: {
      type: 'hbar',
      title: '销售额 TOP10',
      category: (r) => String(r.label ?? ''),
      unit: 'money',
      top: 10,
      series: [{ name: '销售额', value: (r) => Number(r.amount ?? 0) }],
    },
    csv: { kind: 'local', prefix: '单品销售排行' },
  },

  // 9. 客户贡献与复购 —— GET /reports/customer-contribution
  {
    id: 'contribution',
    title: '客户贡献',
    group: 'sales',
    subtitle: '销售额 / 单数 / 复购率（含后端 summary）',
    caliber:
      'limit 是后端参数（返回前 N 个客户），不是前端截断；summary 由后端一起算，复购率是万分比。表底合计行走后端 summary，比前端累加更可信。',
    params: [
      { key: '__preset', label: '统计期间', kind: 'daterange', pair: ['from', 'to'] },
      { key: 'limit', label: '客户数上限', kind: 'number', default: 20, min: 5, max: 200, step: 5 },
      WAREHOUSE_PARAM,
    ],
    columns: [
      { key: 'customerCode', label: '客户编码', kind: 'text', width: 140, total: 'none' },
      { key: 'customerName', label: '客户名称', kind: 'text', width: 220, total: 'none' },
      { key: 'orderCount', label: '订单数', kind: 'int', total: 'sum' },
      { key: 'qty', label: '购买量', kind: 'qty', total: 'sum' },
      { key: 'amount', label: '贡献金额(元)', kind: 'money', total: 'sum' },
      { key: 'repurchase', label: '复购', kind: 'bool', width: 80, total: 'none' },
      { key: 'newCustomer', label: '新客', kind: 'bool', width: 80, total: 'none' },
    ],
    async load({ values }) {
      const from = str(values.from) ?? presetRange('last30')[0]
      const to = str(values.to) ?? presetRange('last30')[1]
      const result = await reportApi.contribution({
        from,
        to,
        limit: num(values.limit) ?? 20,
        warehouseId: num(values.warehouseId),
      })
      // 把后端 summary 挂到第一行以外的地方：框架用 __summary 读合计行
      return result.items.map((r) => ({
        ...r,
        __summaryTotalAmount: result.summary.totalAmount,
        __summaryTotalQty: result.summary.totalQty,
        __summaryOrderCount: result.summary.orderCount,
      })) as Row[]
    },
    chart: {
      type: 'donut',
      title: '客户贡献占比',
      category: (r) => String(r.customerName ?? ''),
      unit: 'money',
      top: 8,
      series: [{ name: '贡献金额', value: (r) => Number(r.amount ?? 0) }],
    },
    chips: [
      { label: '客户数', value: (rows) => String(rows.length) },
      {
        label: '总贡献',
        value: (rows) => yuan(rows.reduce((s, r) => s + Number(r.amount ?? 0), 0)),
      },
      {
        label: '新客/复购',
        value: (rows) =>
          `${rows.filter((r) => r.newCustomer === true).length} / ${rows.filter((r) => r.repurchase === true).length}`,
      },
    ],
    csv: { kind: 'server', path: '/reports/customer-contribution/export', fallbackName: 'customer-contribution.csv' },
  },

  // 10. 应收账龄 —— GET /reports/aging?type=AR
  {
    id: 'ar-aging',
    title: '应收账龄',
    group: 'finance',
    subtitle: 'AR 四桶：0-30 / 31-60 / 61-90 / 90+',
    caliber:
      '以到期日（due date）起算，截止日 asOf 是单日快照。四桶合计必须等于 balanceAmount —— 后端以此作验收，页面把差额列出来。注意 type 取值是 AR/AP（简报里写的 RECEIVABLE 会 400）。',
    params: [{ key: 'asOf', label: '截止日', kind: 'date', default: isoDate(new Date()) }],
    columns: [
      { key: 'partnerCode', label: '客户编码', kind: 'text', width: 140, total: 'none' },
      { key: 'partnerName', label: '客户名称', kind: 'text', width: 220, total: 'none' },
      { key: 'balanceAmount', label: '应收余额(元)', kind: 'money', total: 'sum' },
      { key: 'bucket0', label: '0-30 天', kind: 'money', total: 'sum' },
      { key: 'bucket1', label: '31-60 天', kind: 'money', total: 'sum' },
      { key: 'bucket2', label: '61-90 天', kind: 'money', total: 'sum' },
      { key: 'bucket3', label: '90 天以上', kind: 'money', total: 'sum' },
      {
        key: '__balance',
        label: '四桶差额',
        kind: 'money',
        width: 120,
        total: 'sum',
        get: (r) =>
          Number(r.balanceAmount ?? 0) -
          (Number(r.bucket0 ?? 0) + Number(r.bucket1 ?? 0) + Number(r.bucket2 ?? 0) + Number(r.bucket3 ?? 0)),
      },
    ],
    async load({ values }) {
      const result = await reportApi.aging({ type: 'AR', asOf: str(values.asOf) ?? isoDate(new Date()) })
      return result.items as Row[]
    },
    chart: {
      type: 'stack',
      title: '账龄结构（按客户）',
      category: (r) => String(r.partnerName ?? ''),
      unit: 'money',
      top: 10,
      series: [
        { name: '0-30 天', value: (r) => Number(r.bucket0 ?? 0), color: '#3fb27f' },
        { name: '31-60 天', value: (r) => Number(r.bucket1 ?? 0), color: '#e6a23c' },
        { name: '61-90 天', value: (r) => Number(r.bucket2 ?? 0), color: '#f56c6c' },
        { name: '90+ 天', value: (r) => Number(r.bucket3 ?? 0), color: '#8b5cf6' },
      ],
    },
    chips: [
      { label: '应收总额', value: (rows) => yuan(rows.reduce((s, r) => s + Number(r.balanceAmount ?? 0), 0)) },
      {
        label: '逾期(>90)',
        value: (rows) => yuan(rows.reduce((s, r) => s + Number(r.bucket3 ?? 0), 0)),
      },
    ],
    csv: {
      kind: 'server',
      path: '/reports/aging/export',
      fallbackName: 'aging-ar.csv',
      // type 是「报表身份」而不是筛选条件，必须显式带上
      params: ({ values }) => ({ type: 'AR', asOf: values.asOf }),
    },
  },

  // 11. 应付账龄 —— GET /reports/aging?type=AP
  {
    id: 'ap-aging',
    title: '应付账龄',
    group: 'finance',
    subtitle: 'AP 四桶：0-30 / 31-60 / 61-90 / 90+',
    caliber:
      '以应付单业务日起算（AR 用到期日，两者锚点不同，不能直接横向比较）。端点没有 partnerId 参数，所以「按供应商筛选」只能在前端结果里过滤。',
    params: [
      { key: 'asOf', label: '截止日', kind: 'date', default: isoDate(new Date()) },
      { key: 'keyword', label: '供应商名称', kind: 'text', default: '', hint: '端点无 partnerId 参数，前端按名称/编码包含过滤' },
    ],
    columns: [
      { key: 'partnerCode', label: '供应商编码', kind: 'text', width: 140, total: 'none' },
      { key: 'partnerName', label: '供应商名称', kind: 'text', width: 220, total: 'none' },
      { key: 'balanceAmount', label: '应付余额(元)', kind: 'money', total: 'sum' },
      { key: 'bucket0', label: '0-30 天', kind: 'money', total: 'sum' },
      { key: 'bucket1', label: '31-60 天', kind: 'money', total: 'sum' },
      { key: 'bucket2', label: '61-90 天', kind: 'money', total: 'sum' },
      { key: 'bucket3', label: '90 天以上', kind: 'money', total: 'sum' },
      {
        key: '__balance',
        label: '四桶差额',
        kind: 'money',
        width: 120,
        total: 'sum',
        get: (r) =>
          Number(r.balanceAmount ?? 0) -
          (Number(r.bucket0 ?? 0) + Number(r.bucket1 ?? 0) + Number(r.bucket2 ?? 0) + Number(r.bucket3 ?? 0)),
      },
    ],
    async load({ values }) {
      const result = await reportApi.aging({ type: 'AP', asOf: str(values.asOf) ?? isoDate(new Date()) })
      const kw = str(values.keyword)
      return (kw ? result.items.filter((r) => r.partnerName.includes(kw) || r.partnerCode.includes(kw)) : result.items) as Row[]
    },
    chart: {
      type: 'stack',
      title: '账龄结构（按供应商）',
      category: (r) => String(r.partnerName ?? ''),
      unit: 'money',
      top: 10,
      series: [
        { name: '0-30 天', value: (r) => Number(r.bucket0 ?? 0), color: '#3fb27f' },
        { name: '31-60 天', value: (r) => Number(r.bucket1 ?? 0), color: '#e6a23c' },
        { name: '61-90 天', value: (r) => Number(r.bucket2 ?? 0), color: '#f56c6c' },
        { name: '90+ 天', value: (r) => Number(r.bucket3 ?? 0), color: '#8b5cf6' },
      ],
    },
    chips: [
      { label: '应付总额', value: (rows) => yuan(rows.reduce((s, r) => s + Number(r.balanceAmount ?? 0), 0)) },
      { label: '90+ 天', value: (rows) => yuan(rows.reduce((s, r) => s + Number(r.bucket3 ?? 0), 0)) },
    ],
    csv: {
      kind: 'server',
      path: '/reports/aging/export',
      fallbackName: 'aging-ap.csv',
      params: ({ values }) => ({ type: 'AP', asOf: values.asOf }),
    },
  },
]

/** 分 → 「x.xx 万元」，汇总 chip 与大屏共用。 */
export function yuan(fen: number): string {
  const value = fen / 100
  if (Math.abs(value) >= 10000) return `${(value / 10000).toFixed(2)} 万元`
  return `${value.toFixed(2)} 元`
}

export function findReport(id: string | undefined): ReportDefinition | undefined {
  return REPORTS.find((r) => r.id === id)
}

/** 报表默认条件（进入页面即写入，保证「首次就是一次真实查询」）。 */
export function defaultValues(definition: ReportDefinition): Record<string, string | number | undefined> {
  const out: Record<string, string | number | undefined> = {}
  const [presetFrom, presetTo] = presetRange('last30')
  const today = new Date()
  for (const param of definition.params) {
    if (param.kind === 'daterange' && param.pair) {
      out[param.pair[0]] = presetFrom
      out[param.pair[1]] = presetTo
    } else if (param.kind === 'periodrange' && param.pair) {
      out[param.pair[0]] = isoMonth(today)
      out[param.pair[1]] = isoMonth(today)
    } else if (param.kind === 'period') {
      out[param.key] = param.default ?? isoMonth(today)
    } else if (param.kind === 'date') {
      out[param.key] = param.default ?? isoDate(today)
    } else if (param.default !== undefined) {
      out[param.key] = param.default
    } else if (param.kind !== 'warehouse') {
      out[param.key] = undefined
    }
  }
  // 账期区间默认放开近 6 个月，单月毛利表通常只有一行
  if (definition.id === 'profit') {
    out.to = isoMonth(today)
    out.from = shiftMonth(isoMonth(today), -5)
  }
  return out
}

/** 该报表的账期条件与日期条件的同步（期间 ↔ 区间联动，框架调用）。 */
export function syncPeriodAndRange(
  values: Record<string, string | number | undefined>,
  changedKey: string,
): Record<string, string | number | undefined> {
  const next = { ...values }
  if (changedKey === 'from' || changedKey === 'to') {
    if (next.period !== undefined || next.__hasPeriod) {
      const anchor = changedKey === 'from' ? next.from : next.to
      if (typeof anchor === 'string' && anchor.length >= 7) next.period = anchor.slice(0, 7)
    }
  }
  if (changedKey === 'period' && typeof next.period === 'string') {
    const from = str(next.from)
    const to = str(next.to)
    if (from && to) {
      const spanMonth = from.slice(0, 7) === to.slice(0, 7)
      if (spanMonth) {
        next.from = isoDate(monthStart(next.period))
        next.to = isoDate(monthEnd(next.period))
      }
    }
  }
  return next
}
