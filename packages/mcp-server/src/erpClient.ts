/**
 * 进销存后端 HTTP 客户端（MCP Server 的唯一出口）。
 *
 * 设计原则：
 * - 只做 HTTP 适配，不实现业务规则、不缓存、不重试写操作；
 * - 写请求自动生成 X-Request-Id（与 Web 端 client.ts 同一约定）；
 * - 后端错误码/消息原样向上透出，Agent 才能根据 code 决定下一步。
 */
import { randomUUID } from 'node:crypto'

export type DocType = 'purchase' | 'sales' | 'return'
export type MasterType = 'product' | 'sku' | 'supplier' | 'customer' | 'warehouse'

export interface ErpConfig {
  baseUrl: string
  token: string
  timeoutMs?: number
}

export class ErpHttpError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly details?: unknown,
  ) {
    super(message)
    this.name = 'ErpHttpError'
  }
}

type QueryValue = string | number | boolean | undefined

export class ErpClient {
  private readonly baseUrl: string
  private readonly token: string
  private readonly timeoutMs: number

  constructor(config: ErpConfig) {
    const trimmed = config.baseUrl.trim().replace(/\/+$/, '')
    if (!trimmed) {
      throw new Error('ERP_BASE_URL 不能为空（例如 http://127.0.0.1:8080/api/v1）')
    }
    if (!config.token.trim()) {
      throw new Error('ERP_TOKEN 不能为空：请先登录后把 JWT 传给 MCP Server')
    }
    this.baseUrl = trimmed
    this.token = config.token.trim()
    this.timeoutMs = config.timeoutMs ?? 15000
  }

  // ─────────────────────────── 库存 ───────────────────────────

  queryInventory(params: {
    skuId?: number
    warehouseId?: number
    batchNo?: string
    onlyAvailable?: boolean
  }) {
    return this.request<unknown>('GET', '/inventory', params)
  }

  // ─────────────────────────── 单据 ───────────────────────────

  listDocuments(params: {
    type: DocType
    status?: string
    partyId?: number
    originSalesOrderId?: number
    page?: number
    size?: number
  }) {
    const path = this.documentBase(params.type)
    if (params.type === 'purchase') {
      return this.request<unknown>('GET', path, {
        status: params.status,
        supplierId: params.partyId,
        page: params.page,
        size: params.size,
      })
    }
    return this.request<unknown>('GET', path, {
      status: params.status,
      customerId: params.partyId,
      originSalesOrderId: params.type === 'return' ? params.originSalesOrderId : undefined,
      page: params.page,
      size: params.size,
    })
  }

  getDocument(type: DocType, id: number) {
    return this.request<unknown>('GET', `${this.documentBase(type)}/${id}`)
  }

  // ─────────────────────────── 报表 ───────────────────────────

  inventorySummary(params: { period: string; warehouseId?: number }) {
    return this.request<unknown>('GET', '/reports/inventory-summary', params)
  }

  // ─────────────────────────── 主数据 ───────────────────────────

  queryMasterData(params: {
    type: MasterType
    keyword?: string
    productId?: number
    color?: string
    skuSize?: string
    onlyEnabled?: boolean
    page?: number
    size?: number
  }) {
    const path = this.masterBase(params.type)
    if (params.type === 'warehouse') {
      return this.request<unknown>('GET', path, { onlyEnabled: params.onlyEnabled })
    }
    if (params.type === 'sku') {
      return this.request<unknown>('GET', path, {
        productId: params.productId,
        color: params.color,
        skuSize: params.skuSize,
        page: params.page,
        size: params.size,
      })
    }
    return this.request<unknown>('GET', path, {
      keyword: params.keyword,
      page: params.page,
      size: params.size,
    })
  }

  // ─────────────────────────── 自然语言 ───────────────────────────

  nlCommand(command: string) {
    return this.request<unknown>('POST', '/nl/command', undefined, { command })
  }

  // ─────────────────────────── 内部实现 ───────────────────────────

  private documentBase(type: DocType): string {
    switch (type) {
      case 'purchase':
        return '/purchase-orders'
      case 'sales':
        return '/sales-orders'
      case 'return':
        return '/return-orders'
    }
  }

  private masterBase(type: MasterType): string {
    switch (type) {
      case 'product':
        return '/products'
      case 'sku':
        return '/skus'
      case 'supplier':
        return '/suppliers'
      case 'customer':
        return '/customers'
      case 'warehouse':
        return '/warehouses'
    }
  }

  private async request<T>(
    method: 'GET' | 'POST',
    path: string,
    query?: Record<string, QueryValue>,
    body?: unknown,
  ): Promise<T> {
    const url = new URL(this.baseUrl + path)
    for (const [key, value] of Object.entries(query ?? {})) {
      if (value !== undefined && value !== null && value !== '') {
        url.searchParams.set(key, String(value))
      }
    }

    const headers: Record<string, string> = {
      Accept: 'application/json',
      Authorization: `Bearer ${this.token}`,
    }
    if (body !== undefined) {
      headers['Content-Type'] = 'application/json'
      // 写操作幂等键：与 Web 端同一约定（后端对所有 POST 都要求该头）
      headers['X-Request-Id'] = randomUUID()
    }

    let response: Response
    try {
      response = await fetch(url, {
        method,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body),
        signal: AbortSignal.timeout(this.timeoutMs),
      })
    } catch (error) {
      throw new ErpHttpError(0, 'ERP_UNREACHABLE',
        `无法连接后端 ${this.baseUrl}：${(error as Error).message}`)
    }

    const raw = await response.text()
    let payload: { code?: string; message?: string; data?: T; details?: unknown } = {}
    if (raw) {
      try {
        payload = JSON.parse(raw)
      } catch {
        throw new ErpHttpError(response.status, 'ERP_BAD_RESPONSE',
          `后端返回非 JSON（HTTP ${response.status}）：${raw.slice(0, 200)}`)
      }
    }

    if (!response.ok || (payload.code && payload.code !== 'OK')) {
      throw new ErpHttpError(
        response.status,
        payload.code ?? `HTTP_${response.status}`,
        payload.message ?? `后端返回 HTTP ${response.status}`,
        payload.details,
      )
    }
    return payload.data as T
  }
}
