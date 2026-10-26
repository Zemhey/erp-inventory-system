const BIZ_TYPE_LABELS: Record<string, string> = {
  PURCHASE_IN: '采购入库',
  SALES_OUT: '销售出库',
  RETURN_IN: '退货入库',
  TRANSFER_IN: '调拨入库',
  TRANSFER_OUT: '调拨出库',
  ADJUST_IN: '盘盈入库',
  ADJUST_OUT: '盘亏出库',
}

export function bizTypeLabel(type: string): string {
  return BIZ_TYPE_LABELS[type] ?? type
}

function pad(value: number): string {
  return value < 10 ? `0${value}` : String(value)
}

/** ISO-8601 → 本地 `YYYY-MM-DD HH:mm`；解析失败时原样返回，不吞数据。 */
export function formatDateTime(value: string | null | undefined): string {
  if (!value) return '-'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(
    date.getHours(),
  )}:${pad(date.getMinutes())}`
}

export function formatDate(value: string | null | undefined): string {
  if (!value) return '-'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

/** 数量带符号：入库显示 +N，出库显示 -N。 */
export function signedQty(qty: number): string {
  return qty > 0 ? `+${qty}` : String(qty)
}

export function formatQty(qty: number | null | undefined): string {
  return qty === null || qty === undefined ? '-' : String(qty)
}
