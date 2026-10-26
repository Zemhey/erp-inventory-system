/**
 * 金额工具（ADR-0005）。
 *
 * 接口传输与数据库存储一律是整数「分」。这里只在**展示层**转元，
 * 页面里不得出现散落的 `÷100`；未来表单提交也必须用 yuanToFen() 转回整数分。
 */

export function fenToYuan(fen: number | null | undefined): string {
  if (fen === null || fen === undefined) return '-'
  return (fen / 100).toFixed(2)
}

/** 带币种符号的展示；null 表示"这个业务没有成本金额"，不是 0。 */
export function formatFen(fen: number | null | undefined): string {
  if (fen === null || fen === undefined) return '-'
  return `¥${fenToYuan(fen)}`
}

/** 元 → 分（用户输入用；四舍五入到整数分）。 */
export function yuanToFen(yuan: number | string): number {
  const value = typeof yuan === 'string' ? Number.parseFloat(yuan) : yuan
  if (Number.isNaN(value)) return 0
  return Math.round(value * 100)
}
