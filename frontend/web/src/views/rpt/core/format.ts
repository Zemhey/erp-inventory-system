/**
 * 报表显示层格式化（FR-V3-34 的「数字千分位」）。
 *
 * ★ 全系统金额以「整数分」传输（ADR-0005），这里只在展示时除以 100，
 *   绝不把「元」回传后端。
 * ★ 千分位用 Intl 而不是手写正则：浏览器原生，中文 locale 下分组一致。
 */

const GROUPER = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 0 })
const DECIMAL2 = new Intl.NumberFormat('zh-CN', {
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
})

/** 整数（数量、单数），带千分位。 */
export function fmtInt(value: unknown): string {
  const n = toNumber(value)
  if (n == null) return '-'
  return GROUPER.format(Math.round(n))
}

/** 数量列：与整数同形，但保留后端可能给出的一位小数。 */
export function fmtQty(value: unknown): string {
  const n = toNumber(value)
  if (n == null) return '-'
  return Number.isInteger(n) ? GROUPER.format(n) : DECIMAL2.format(n)
}

/** 金额列：分 → 元，千分位 + 两位小数。 */
export function fmtMoney(value: unknown): string {
  const n = toNumber(value)
  if (n == null) return '-'
  return DECIMAL2.format(n / 100)
}

/** 万分比（basis point，1 万 = 100%）→ 百分数文本。 */
export function fmtRateBp(value: unknown): string {
  const n = toNumber(value)
  if (n == null) return '-'
  return `${(n / 100).toFixed(2)}%`
}

/** 天数列：null 表示「无出库、周转不可计算」。 */
export function fmtDays(value: unknown): string {
  const n = toNumber(value)
  if (n == null) return '—'
  return `${Math.round(n)} 天`
}

/** 布尔列 → 是/否文本。 */
export function fmtBool(value: unknown): string {
  if (value === true) return '是'
  if (value === false) return '否'
  return '-'
}

export type CellKind = 'text' | 'qty' | 'int' | 'money' | 'rate' | 'days' | 'bool'

export function formatCell(kind: CellKind, value: unknown): string {
  switch (kind) {
    case 'money':
      return fmtMoney(value)
    case 'qty':
      return fmtQty(value)
    case 'int':
      return fmtInt(value)
    case 'rate':
      return fmtRateBp(value)
    case 'days':
      return fmtDays(value)
    case 'bool':
      return fmtBool(value)
    default:
      return value == null || value === '' ? '-' : String(value)
  }
}

function toNumber(value: unknown): number | null {
  if (typeof value === 'number') return Number.isFinite(value) ? value : null
  if (typeof value === 'string' && value.trim() !== '') {
    const n = Number(value)
    return Number.isFinite(n) ? n : null
  }
  return null
}

/** 合计：按列取值函数对若干行求数值和（报表合计行用）。 */
export function sumBy(rows: readonly Record<string, unknown>[], pick: (row: Record<string, unknown>) => number): number {
  let total = 0
  for (const row of rows) {
    const n = pick(row)
    if (Number.isFinite(n)) total += n
  }
  return total
}

/**
 * 前端生成 CSV（后端没有对应 /export 端点的报表用）。
 *
 * ★ 必须带 UTF-8 BOM：Excel 双击打开无 BOM 的 UTF-8 CSV 会把中文显示成乱码，
 *   这是简报里点名的验收项。后端 ReportExportController 已经在做同一件事，
 *   这里保持字节级一致，两种导出结果才能互相印证。
 */
export function buildCsv(header: string[], rows: string[][]): string {
  const esc = (raw: string) => `"${raw.replace(/"/g, '""')}"`
  const lines = [header.map(esc).join(',')]
  for (const row of rows) lines.push(row.map(esc).join(','))
  return `\uFEFF${lines.join('\r\n')}\r\n`
}

export function downloadTextCsv(filename: string, content: string): void {
  const blob = new Blob([content], { type: 'text/csv;charset=UTF-8' })
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  URL.revokeObjectURL(url)
}
