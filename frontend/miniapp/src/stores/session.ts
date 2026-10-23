import { reactive } from 'vue'
import { authApi, clearToken, getToken, setToken } from '@/api/client'

/**
 * 登录会话。
 *
 * token 与权限都持久化到 uni storage：H5 刷新 / 小程序冷启动后，
 * 页面守卫可以在首屏同步判断，不必先发一次请求。
 * 本地权限副本只用于 UI 显隐，**不是安全边界** —— 后端仍会 403。
 */

const PERMISSIONS_KEY = 'erp.permissions'
const USER_KEY = 'erp.user'

interface StoredUser {
  username: string
  displayName: string
  roles: string[]
}

interface SessionState extends StoredUser {
  token: string
  permissions: string[]
}

function readJson<T>(key: string, fallback: T): T {
  try {
    const raw = uni.getStorageSync(key)
    if (!raw) return fallback
    return typeof raw === 'string' ? (JSON.parse(raw) as T) : (raw as T)
  } catch {
    return fallback
  }
}

function loadUser(): StoredUser {
  return readJson<StoredUser>(USER_KEY, { username: '', displayName: '', roles: [] })
}

function loadPermissions(): string[] {
  return readJson<string[]>(PERMISSIONS_KEY, [])
}

export const session = reactive<SessionState>({
  token: getToken(),
  ...loadUser(),
  permissions: loadPermissions(),
})

export async function login(username: string, password: string): Promise<void> {
  const result = await authApi.login(username.trim(), password)

  session.token = result.token
  session.username = result.username
  session.displayName = result.displayName
  session.roles = result.roles
  session.permissions = result.permissions

  setToken(result.token)
  uni.setStorageSync(PERMISSIONS_KEY, JSON.stringify(result.permissions))
  uni.setStorageSync(
    USER_KEY,
    JSON.stringify({
      username: result.username,
      displayName: result.displayName,
      roles: result.roles,
    }),
  )
}

export function logout(): void {
  clearToken()
  session.token = ''
  session.username = ''
  session.displayName = ''
  session.roles = []
  session.permissions = []
  uni.reLaunch({ url: '/pages/login/login' })
}

/** 是否拥有某权限（支持 "模块:*" 通配，与后端语义一致）。 */
export function hasPermission(permission: string): boolean {
  if (session.permissions.includes(permission)) return true
  return session.permissions.includes(`${permission.split(':')[0]}:*`)
}

export function isLoggedIn(): boolean {
  return Boolean(session.token || getToken())
}
