import { randomUUID } from 'node:crypto'

/** 直接打后端（不走 H5 代理），用于造数与登录态准备。 */
export const API_BASE = process.env.E2E_BACKEND_URL ?? 'http://127.0.0.1:8085'
export const API_PREFIX = `${API_BASE}/api/v1`

export class ApiError extends Error {
  readonly status: number
  readonly code: string

  constructor(status: number, code: string, message: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
  }
}

interface Envelope<T> {
  code: string
  message: string
  data: T
  traceId?: string
}

export type Method = 'GET' | 'POST' | 'PUT'

export async function api<T>(
  method: Method,
  path: string,
  options: { token?: string; body?: unknown } = {},
): Promise<T> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  if (options.token) headers.Authorization = `Bearer ${options.token}`
  if (method !== 'GET') headers['X-Request-Id'] = `e2e-${randomUUID()}`

  const response = await fetch(`${API_PREFIX}${path.startsWith('/') ? path : `/${path}`}`, {
    method,
    headers,
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
    signal: AbortSignal.timeout(20_000),
  })

  const text = await response.text()
  let payload: Envelope<T> | null = null
  try {
    payload = text ? (JSON.parse(text) as Envelope<T>) : null
  } catch {
    // 非 JSON 响应（例如反向代理错误页）在下面统一报出
  }

  if (!response.ok) {
    throw new ApiError(
      response.status,
      payload?.code ?? `HTTP_${response.status}`,
      payload?.message ?? text.slice(0, 500) ?? response.statusText,
    )
  }
  if (!payload || payload.code !== 'OK') {
    throw new ApiError(
      response.status,
      payload?.code ?? 'SYS_UNEXPECTED_RESPONSE',
      payload?.message ?? '响应格式异常',
    )
  }
  return payload.data
}

export interface LoginResult {
  token: string
  username: string
  displayName: string
  roles: string[]
  permissions: string[]
}

export function login(username = 'admin', password = 'Admin@12345'): Promise<LoginResult> {
  return api<LoginResult>('POST', '/auth/login', { body: { username, password } })
}
