import { randomUUID } from 'node:crypto'
import type { APIRequestContext } from '@playwright/test'

/**
 * E1 泳道自己的读接口客户端（审计 / 字典）。
 *
 * 为什么不塞进 `helpers/api.ts`：那个文件是所有泳道共用的热点，
 * 而本泳道需要的断言形态不一样 —— 接口级用例要看**状态码与业务错误码本身**
 * （405 / 400 / 404），不是"抛异常"。所以这里刻意提供"永不上抛、返回结构化响应"的调用。
 *
 * 写接口只有一个：`updateSupplier`，用途是给审计页造出真实的 before/after 差异
 * （只有 `PUT /x/{id}` 这种"路径带资源 id"的写操作，后端才会去取上一次快照）。
 */

export interface AuditRowDto {
  id: number
  actorId: number | null
  actorName: string | null
  action: string
  resourceType: string
  resourceId: string | null
  bizRefNo: string | null
  beforeJson: string | null
  afterJson: string | null
  traceId: string | null
  ip: string | null
  occurredAt: string
  result: string
  errorCode: string | null
}

export interface PageDto<T> {
  items: T[]
  total: number
  page: number
  size: number
}

export interface DictTypeDto {
  id: number
  code: string
  name: string
  description: string | null
  enabled: boolean
}

export interface DictItemDto {
  id: number
  typeCode: string
  code: string
  label: string
  sortNo: number
  enabled: boolean
}

/** 结构化响应：断言写成 `expect(res.status).toBe(405)` 而不是 try/catch。 */
export interface SysResponse<T> {
  status: number
  code: string
  message: string
  data: T | null
  /** 原始响应文本，错误态断言要看它 */
  raw: string
}

type Query = Record<string, string | number | boolean | undefined>

export class SysApi {
  constructor(
    private readonly request: APIRequestContext,
    private readonly token: string,
  ) {}

  private async call<T>(
    method: 'GET' | 'POST' | 'PUT' | 'DELETE',
    path: string,
    options: { query?: Query; body?: unknown } = {},
  ): Promise<SysResponse<T>> {
    const headers: Record<string, string> = { Authorization: `Bearer ${this.token}` }
    if (method !== 'GET') headers['X-Request-Id'] = randomUUID()

    const params: Record<string, string | number | boolean> = {}
    for (const [key, value] of Object.entries(options.query ?? {})) {
      if (value !== undefined) params[key] = value
    }

    const response = await this.request.fetch(`/api/v1${path}`, {
      method,
      headers,
      params,
      ...(options.body === undefined ? {} : { data: options.body }),
      failOnStatusCode: false,
    })
    const raw = await response.text()
    let parsed: { code?: string; message?: string; data?: T } | null = null
    try {
      parsed = raw ? JSON.parse(raw) : null
    } catch {
      parsed = null
    }
    return {
      status: response.status(),
      code: parsed?.code ?? '',
      message: parsed?.message ?? '',
      data: (parsed?.data as T) ?? null,
      raw,
    }
  }

  audits(query: Query = {}): Promise<SysResponse<PageDto<AuditRowDto>>> {
    return this.call<PageDto<AuditRowDto>>('GET', '/audits', { query })
  }

  audit(id: number | string): Promise<SysResponse<AuditRowDto>> {
    return this.call<AuditRowDto>('GET', `/audits/${id}`)
  }

  timeline(bizRefNo: string): Promise<SysResponse<AuditRowDto[]>> {
    return this.call<AuditRowDto[]>('GET', '/audits/timeline', { query: { bizRefNo } })
  }

  byTrace(traceId: string): Promise<SysResponse<AuditRowDto[]>> {
    return this.call<AuditRowDto[]>('GET', '/audits/by-trace', { query: { traceId } })
  }

  dictTypes(query: Query = {}): Promise<SysResponse<DictTypeDto[]>> {
    return this.call<DictTypeDto[]>('GET', '/dicts', { query })
  }

  dictItems(typeCode: string, query: Query = {}): Promise<SysResponse<DictItemDto[]>> {
    return this.call<DictItemDto[]>('GET', `/dicts/${typeCode}/items`, { query })
  }

  /** 只读承诺的反证：对审计资源发写方法，应当是 405 而不是"成功"或 500。 */
  attempt(method: 'POST' | 'PUT' | 'DELETE', path: string): Promise<SysResponse<unknown>> {
    return this.call<unknown>(method, path, { body: method === 'DELETE' ? undefined : {} })
  }

  /**
   * 改供应商名字：这是"能造出 before/after 差异"的最短路径。
   *
   * 非 200 直接抛，因为它是安排数据，不是被验收对象 ——
   * 失败应当让用例立刻红在"造数"这一步，而不是让后面的界面断言对着空表猜原因。
   */
  async updateSupplier(id: number, body: { name: string }): Promise<{ id: number; name: string }> {
    const res = await this.call<{ id: number; name: string }>('PUT', `/suppliers/${id}`, { body })
    if (res.status !== 200) {
      throw new Error(`安排数据失败：PUT /suppliers/${id} -> ${res.status} ${res.code} ${res.message}`)
    }
    return res.data as { id: number; name: string }
  }
}

/** 用账号密码换一个 token（权限边界的用例要用"另一个身份"的会话调接口）。 */
export async function loginForToken(
  request: APIRequestContext,
  username: string,
  password: string,
): Promise<string> {
  const response = await request.fetch('/api/v1/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Request-Id': randomUUID() },
    data: { username, password },
    failOnStatusCode: false,
  })
  const text = await response.text()
  if (!response.ok()) {
    throw new Error(`登录失败（安排数据）：${username} -> HTTP ${response.status()} ${text}`)
  }
  const token = (JSON.parse(text) as { data?: { token?: string } }).data?.token
  if (!token) throw new Error(`登录响应里没有 token：${text}`)
  return token
}
