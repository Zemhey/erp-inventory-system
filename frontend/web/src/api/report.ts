import http, { getToken } from '@/api/client'

/**
 * V2-8 报表 API（FR-RPT-05~09）。
 *
 * ★ 查询与导出的关系：JSON 端点给页面渲染，CSV 端点给下载；后端两者调用
 *   同一个 ApplicationService，金额/周转天数的格式化规则也一致。
 *
 * ★ CSV 不能用 `http`（axios 实例）：响应拦截器会把 JSON 包络剥成 data，
 *   对 text/csv 会得到 undefined。这里用原生 fetch 取 Blob，并手动带 token。
 */
export type ReportPeriodDim = 'day' | 'month'
export type AgingType = 'AR' | 'AP'

export interface OperatingReportRow {
  period: string
  openingQty: number
  inboundQty: number
  outboundQty: number
  closingQty: number
  purchaseQty: number
  purchaseAmount: number
  salesQty: number
  salesAmount: number
}

export interface InventoryTurnoverRow {
  skuId: number
  skuCode: string
  skuName: string
  warehouseId: number
  warehouseName: string
  openingQty: number
  closingQty: number
  outboundQty: number
  turnoverDays: number | null
  idleDays: number
  stale: boolean
}

export interface CustomerContributionRow {
  customerId: number
  customerCode: string
  customerName: string
  orderCount: number
  qty: number
  amount: number
  repurchase: boolean
  newCustomer: boolean
}

export interface CustomerContributionSummary {
  customerCount: number
  repurchaseCount: number
  repurchaseRateBp: number
  newCustomerCount: number
  returningCustomerCount: number
  totalQty: number
  totalAmount: number
  orderCount: number
}

export interface CustomerContributionResult {
  items: CustomerContributionRow[]
  summary: CustomerContributionSummary
}

export interface AgingRow {
  partnerId: number
  partnerCode: string
  partnerName: string
  balanceAmount: number
  bucket0: number
  bucket1: number
  bucket2: number
  bucket3: number
}

export interface AgingSummary {
  partnerCount: number
  totalBalance: number
  bucket0: number
  bucket1: number
  bucket2: number
  bucket3: number
}

export interface AgingResult {
  items: AgingRow[]
  summary: AgingSummary
}

export const reportApi = {
  operating: (params: {
    from: string
    to: string
    dimension?: ReportPeriodDim
    warehouseId?: number
  }) => http.get<never, { items: OperatingReportRow[] }>('/reports/operating', { params }),

  turnover: (params: {
    from: string
    to: string
    deadDays?: number
    warehouseId?: number
  }) => http.get<never, { items: InventoryTurnoverRow[] }>('/reports/inventory-turnover', { params }),

  contribution: (params: {
    from: string
    to: string
    limit?: number
    warehouseId?: number
  }) => http.get<never, CustomerContributionResult>('/reports/customer-contribution', { params }),

  aging: (params: { type: AgingType; asOf: string }) =>
    http.get<never, AgingResult>('/reports/aging', { params }),
}

type ExportValue = string | number | boolean | undefined | null

/** 与后端 CSV 相同的文件名在 Content-Disposition 里；取不到时用兜底名。 */
function filenameOf(disposition: string, fallback: string): string {
  const match = /filename="?([^";]+)"?/i.exec(disposition)
  return match?.[1] ?? fallback
}

/**
 * 下载服务端 CSV。参数名与页面查询完全一致，后端用同一条查询生成内容。
 * 401 交给登录页；其他错误把后端 message 抛给调用方展示。
 */
export async function downloadReportCsv(
  path: string,
  params: Record<string, ExportValue>,
  fallbackName = 'report.csv',
): Promise<void> {
  const query = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null && value !== '') {
      query.set(key, String(value))
    }
  }
  const token = getToken()
  const response = await fetch(`/api/v1${path}?${query.toString()}`, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  })
  if (response.status === 401) {
    window.location.href = '/login'
    throw new Error('登录已过期，请重新登录')
  }
  if (!response.ok) {
    let message = `导出失败（HTTP ${response.status}）`
    try {
      const payload = (await response.json()) as { message?: string }
      message = payload.message || message
    } catch {
      // 非 JSON 错误响应：保留状态码文案
    }
    throw new Error(message)
  }
  const blob = await response.blob()
  const disposition = response.headers.get('Content-Disposition') ?? ''
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filenameOf(disposition, fallbackName)
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  URL.revokeObjectURL(url)
}
