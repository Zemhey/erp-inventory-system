/**
 * 报表期间工具（查询条件区的「期间」都从这里出）。
 *
 * ★ 刻意只用原生 Date + 字符串拼接，不引 dayjs：
 *   报表只需要「月初/月末/年前」这几种粗粒度，引一个日期库换来 3 行代码不值得，
 *   而且本泳道的依赖是冻结的（package.json 不许改）。
 */

const PAD = (n: number) => String(n).padStart(2, '0')

export function isoDate(d: Date): string {
  return `${d.getFullYear()}-${PAD(d.getMonth() + 1)}-${PAD(d.getDate())}`
}

export function isoMonth(d: Date): string {
  return `${d.getFullYear()}-${PAD(d.getMonth() + 1)}`
}

export function monthStart(period: string): Date {
  const [y, m] = period.split('-').map(Number)
  return new Date(y, (m ?? 1) - 1, 1)
}

export function monthEnd(period: string): Date {
  const [y, m] = period.split('-').map(Number)
  return new Date(y, m ?? 1, 0)
}

export function addDays(d: Date, delta: number): Date {
  const next = new Date(d.getTime())
  next.setDate(next.getDate() + delta)
  return next
}

export function shiftMonth(period: string, delta: number): string {
  const [y, m] = period.split('-').map(Number)
  const idx = y * 12 + (m - 1) + delta
  return `${Math.floor(idx / 12)}-${PAD((idx % 12) + 1)}`
}

export type RangePreset = 'today' | 'week' | 'month' | 'lastMonth' | 'quarter' | 'year' | 'last30'

export const PRESET_LABELS: Record<RangePreset, string> = {
  today: '今天',
  week: '近 7 天',
  last30: '近 30 天',
  month: '本月',
  lastMonth: '上月',
  quarter: '本季',
  year: '本年',
}

export const PRESET_ORDER: RangePreset[] = ['today', 'week', 'last30', 'month', 'lastMonth', 'quarter', 'year']

/** 预设 → [from, to]（闭区间，ISO 日期）。 */
export function presetRange(preset: RangePreset, base = new Date()): [string, string] {
  const y = base.getFullYear()
  switch (preset) {
    case 'today':
      return [isoDate(base), isoDate(base)]
    case 'week':
      return [isoDate(addDays(base, -6)), isoDate(base)]
    case 'last30':
      return [isoDate(addDays(base, -29)), isoDate(base)]
    case 'month':
      return [isoDate(new Date(y, base.getMonth(), 1)), isoDate(base)]
    case 'lastMonth': {
      const first = new Date(y, base.getMonth() - 1, 1)
      return [isoDate(first), isoDate(new Date(y, base.getMonth(), 0))]
    }
    case 'quarter': {
      const q = Math.floor(base.getMonth() / 3) * 3
      return [isoDate(new Date(y, q, 1)), isoDate(base)]
    }
    case 'year':
    default:
      return [`${y}-01-01`, isoDate(base)]
  }
}

/** 预设 → [fromPeriod, toPeriod]（yyyy-MM 账期区间，毛利分析用）。 */
export function presetPeriods(preset: RangePreset, base = new Date()): [string, string] {
  const y = base.getFullYear()
  switch (preset) {
    case 'month':
    case 'today':
    case 'week':
    case 'last30':
      return [isoMonth(base), isoMonth(base)]
    case 'lastMonth':
      return [shiftMonth(isoMonth(base), -1), shiftMonth(isoMonth(base), -1)]
    case 'quarter':
      return [isoMonth(new Date(y, Math.floor(base.getMonth() / 3) * 3, 1)), isoMonth(base)]
    case 'year':
    default:
      return [`${y}-01`, isoMonth(base)]
  }
}

/** 从 from/to 反推所属账期（期间与区间联动显示用）。 */
export function periodOf(dateIso: string | undefined): string {
  if (!dateIso || dateIso.length < 7) return isoMonth(new Date())
  return dateIso.slice(0, 7)
}

export const WEEKDAY_NAMES = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']

export function nowLabel(d: Date): string {
  return `${isoDate(d)} ${PAD(d.getHours())}:${PAD(d.getMinutes())}:${PAD(d.getSeconds())} ${WEEKDAY_NAMES[d.getDay()]}`
}
