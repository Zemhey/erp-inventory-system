/**
 * 进销存后端 HTTP 客户端（MCP Server 的唯一出口）。
 *
 * 设计原则：
 * - 只做 HTTP 适配，不实现业务规则、不缓存业务数据、不重试写操作；
 * - 写请求自动生成 X-Request-Id（与 Web 端 client.ts 同一约定）；
 * - 后端错误码/消息原样向上透出，Agent 才能根据 code 决定下一步。
 *
 * ★ 凭据有两种给法，按优先级：
 *   1. ERP_TOKEN      —— 已经登录得到的 JWT，直接透传（最适合临时排障）；
 *   2. ERP_USERNAME / ERP_PASSWORD —— 首次调用时自动登录并缓存 token，
 *      遇到 401 再自动重登一次。Agent 常驻数小时，手抄 JWT 一定会过期，
 *      让人每天改环境变量不是能用的方案。
 *   两种都没给 → 直接报错，不要静默降级成"匿名调用"。
 */
import { randomUUID } from 'node:crypto'

export type DocType = 'purchase' | 'sales' | 'return'
export type MasterType = 'product' | 'sku' | 'supplier' | 'customer' | 'warehouse'

export interface ErpConfig {
  baseUrl: string
  token?: string
  username?: string
  password?: string
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

interface Envelope<T> {
  code?: string
  message?: string
  data?: T
  details?: unknown
}

export class ErpClient {
  private readonly baseUrl: string
  private readonly timeoutMs: number
  private readonly username?: string
  private readonly password?: string

  /** 当前持有的 JWT；来自 ERP_TOKEN 或自动登录结果。 */
  private tokenCache: string | null
  /** 登录进行中时复用同一个 Promise，避免并发首调把账号打成"多次登录"。 */
  private loginPromise: Promise<string> | null = null
  /** 只读诊断用：最近一次登录时间与身份，不落盘、不打印 token。 */
  private lastLoginAt: Date | null = null
  private lastUsername: string | null = null

  constructor(config: ErpConfig) {
    const trimmed = config.baseUrl.trim().replace(/\/+$/, '')
    if (!trimmed) {
      throw new Error('ERP_BASE_URL 不能为空（例如 http://127.0.0.1:8080/api/v1）')
    }
    this.baseUrl = trimmed
    this.timeoutMs = config.timeoutMs ?? 15000
    this.tokenCache = config.token?.trim() || null
    this.username = config.username?.trim() || undefined
    this.password = config.password?.trim() || undefined
  }

  /** 是否具备自动登录能力（用于 /health 里如实汇报，而不是假装已认证）。 */
  get canAutoLogin(): boolean {
    return Boolean(this.username && this.password)
  }

  get hasToken(): boolean {
    return Boolean(this.tokenCache)
  }

  describeAuth(): string {
    if (this.canAutoLogin) return 'password'
    if (this.hasToken) return 'token'
    return 'none'
  }

  authInfo(): { mode: string; username: string | null; loggedInAt: string | null } {
    return {
      mode: this.describeAuth(),
      username: this.lastUsername,
      loggedInAt: this.lastLoginAt ? this.lastLoginAt.toISOString() : null,
    }
  }

  /** 后端根地址（/actuator 挂在根路径，不在 /api/v1 下）。 */
  get origin(): string {
    try {
      return new URL(this.baseUrl).origin
    } catch {
      return this.baseUrl.replace(/\/api\/.*$/, '')
    }
  }

  // ─────────────────────────── 系统 ───────────────────────────

  /**
   * 健康检查。不带 token 也能读到 status；带上 token（admin）后端会返回
   * components 明细（db / redis / diskSpace），Agent 就能回答"数据库通不通"。
   */
  async health(): Promise<{
    status: string
    latencyMs: number
    components: { name: string; status: string }[]
  }> {
    const started = Date.now()
    const response = await fetch(`${this.origin}/actuator/health`, {
      headers: this.tokenCache ? { Authorization: `Bearer ${this.tokenCache}` } : {},
      signal: AbortSignal.timeout(this.timeoutMs),
    })
    const latencyMs = Date.now() - started
    const payload = (await response.json().catch(() => ({}))) as {
      status?: string
      components?: Record<string, { status?: string }>
    }
    const components = Object.entries(payload.components ?? {}).map(([name, value]) => ({
      name,
      status: value?.status ?? 'UNKNOWN',
    }))
    return { status: payload.status ?? (response.ok ? 'UNKNOWN' : 'DOWN'), latencyMs, components }
  }

  async whoami() {
    return this.request<unknown>('GET', '/auth/me')
  }

  async dashboardSummary() {
    return this.request<unknown>('GET', '/dashboard/summary')
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

  /**
   * 执行一句自然语言指令。
   *
   * `planToken` 是 `/nl/plan` 返回的确认回执：后端从 V4-S4 起对**写操作**强制校验它，
   * 没有就 409（并在 details 里附上计划，便于直接转述给人）。
   * 只读指令不需要它——读不审。
   */
  nlCommand(command: string, planToken?: string) {
    const headers: Record<string, string> = { 'X-Nl-Source': 'MCP' }
    if (planToken) {
      headers['X-Nl-Plan-Token'] = planToken
    }
    return this.request<unknown>('POST', '/nl/command', undefined, { command }, true, headers)
  }

  /**
   * 生成执行计划（不执行），同时返回复核意见与确认回执。
   *
   * Agent 应该**先调它、再决定要不要调 {@link nlCommand}**：
   * 计划里带 `requiresReview`、`blockers`，`review` 里是另一个复核方的意见，
   * `confirmation.token` 是执行写操作时要原样带回的回执。
   * 它是只读的，调用成本低、可反复调。
   */
  nlPlan(command: string) {
    // X-Nl-Source 让审计能分清"是网页点的还是 Agent 发的"——
    // 这是"AI 代操作"能不能进生产的前提之一（谁发起的必须查得到）。
    return this.request<unknown>('POST', '/nl/plan', undefined, { command }, true,
      { 'X-Nl-Source': 'MCP' })
  }

  // ─────────────────────────── 认证 ───────────────────────────

  /**
   * 取 token：有缓存直接用；否则用账号密码登录。
   * force=true 用于 401 之后强制换一张新票。
   */
  private async getToken(force = false): Promise<string> {
    if (!force && this.tokenCache) return this.tokenCache
    if (!this.canAutoLogin) {
      if (this.tokenCache) return this.tokenCache
      throw new ErpHttpError(
        0,
        'ERP_NO_CREDENTIALS',
        '未配置凭据：请设置 ERP_TOKEN，或同时设置 ERP_USERNAME / ERP_PASSWORD 以便自动登录',
      )
    }
    if (!this.loginPromise) {
      this.loginPromise = this.login().finally(() => {
        this.loginPromise = null
      })
    }
    return this.loginPromise
  }

  private async login(): Promise<string> {
    const response = await fetch(`${this.baseUrl}/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify({ username: this.username, password: this.password }),
      signal: AbortSignal.timeout(this.timeoutMs),
    })
    const raw = await response.text()
    let payload: Envelope<{ token?: string }> = {}
    if (raw) {
      try {
        payload = JSON.parse(raw) as Envelope<{ token?: string }>
      } catch {
        throw new ErpHttpError(response.status, 'ERP_BAD_RESPONSE',
          `登录接口返回非 JSON（HTTP ${response.status}）`)
      }
    }
    const token = payload.data?.token
    if (!response.ok || !token) {
      throw new ErpHttpError(
        response.status,
        payload.code ?? `HTTP_${response.status}`,
        payload.message ?? 'MCP Server 自动登录失败，请检查 ERP_USERNAME / ERP_PASSWORD',
      )
    }
    this.tokenCache = token
    this.lastLoginAt = new Date()
    this.lastUsername = this.username ?? null
    // 只写 stderr（stdio 模式下 stdout 是 JSON-RPC 通道），且绝不打印 token
    console.error(`[erp-mcp] 自动登录成功：${this.username}（token 已缓存，401 时自动续签）`)
    return token
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
    allowRetry = true,
    extraHeaders?: Record<string, string>,
  ): Promise<T> {
    const token = await this.getToken()
    const url = new URL(this.baseUrl + path)
    for (const [key, value] of Object.entries(query ?? {})) {
      if (value !== undefined && value !== null && value !== '') {
        url.searchParams.set(key, String(value))
      }
    }

    const headers: Record<string, string> = {
      Accept: 'application/json',
      Authorization: `Bearer ${token}`,
      ...extraHeaders,
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

    // token 过期：换一张票重试一次（只重试一次，避免把错误密码打成死循环）
    if (response.status === 401 && allowRetry && this.canAutoLogin) {
      this.tokenCache = null
      await this.getToken(true)
      return this.request<T>(method, path, query, body, false)
    }

    const raw = await response.text()
    let payload: Envelope<T> = {}
    if (raw) {
      try {
        payload = JSON.parse(raw) as Envelope<T>
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
