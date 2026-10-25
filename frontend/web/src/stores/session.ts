import { defineStore } from 'pinia'
import {
  authApi,
  clearToken,
  getToken,
  setToken,
  type LoginPayload,
  type SessionSnapshot,
  type TenantView,
} from '@/api/client'

/**
 * 登录会话（含 B1 的租户上下文）。
 *
 * 为什么 permissions 也要存 localStorage：刷新页面后路由守卫要立即判断权限，
 * 若依赖一次异步接口，会在首屏渲染前出现"闪一下无权限页面"。
 * token 里其实也带了角色，但那只是签发时的快照；后端每次请求都会回库校验，
 * 因此这里的本地副本只用于 UI 展示，**不作为安全依据**。
 *
 * ★ 租户上下文同样持久化（erp.tenant / erp.tenants）：
 *   刷新页面时顶栏必须先显示正确的租户，不能等 /auth/me 回来才填 ——
 *   否则会出现"先闪一下别人的租户/空白，再跳回来"。
 *   真正的安全边界仍在服务端：token 的 tid 决定数据范围，切换租户由后端
 *   重新签发 token，前端本地字段只是显示副本。
 */

interface SessionState {
  token: string | null
  username: string
  displayName: string
  roles: string[]
  permissions: string[]
  /** 当前行动租户；旧的本地存储没有这些字段时为 null/空串，首屏 refresh 会补齐 */
  tenantId: number | null
  tenantCode: string
  tenantName: string
  /** 该账号的全部租户成员关系；长度 > 1 才显示切换器 */
  tenants: TenantView[]
}

const PERM_KEY = 'erp.permissions'
const USER_KEY = 'erp.user'
const TENANT_KEY = 'erp.tenant'
const TENANTS_KEY = 'erp.tenants'

function loadPermissions(): string[] {
  const raw = localStorage.getItem(PERM_KEY)
  return raw ? (JSON.parse(raw) as string[]) : []
}

function loadUser(): { username: string; displayName: string; roles: string[] } {
  const raw = localStorage.getItem(USER_KEY)
  return raw
    ? (JSON.parse(raw) as { username: string; displayName: string; roles: string[] })
    : { username: '', displayName: '', roles: [] }
}

function loadTenant(): { tenantId: number | null; tenantCode: string; tenantName: string } {
  const raw = localStorage.getItem(TENANT_KEY)
  if (!raw) return { tenantId: null, tenantCode: '', tenantName: '' }
  try {
    const parsed = JSON.parse(raw) as {
      tenantId?: number | null
      tenantCode?: string
      tenantName?: string
    }
    return {
      tenantId: typeof parsed.tenantId === 'number' ? parsed.tenantId : null,
      tenantCode: parsed.tenantCode ?? '',
      tenantName: parsed.tenantName ?? '',
    }
  } catch {
    return { tenantId: null, tenantCode: '', tenantName: '' }
  }
}

function loadTenants(): TenantView[] {
  const raw = localStorage.getItem(TENANTS_KEY)
  if (!raw) return []
  try {
    const parsed = JSON.parse(raw) as TenantView[]
    return Array.isArray(parsed) ? parsed : []
  } catch {
    return []
  }
}

export const useSessionStore = defineStore('session', {
  state: (): SessionState => ({
    token: getToken(),
    ...loadUser(),
    permissions: loadPermissions(),
    ...loadTenant(),
    tenants: loadTenants(),
  }),

  getters: {
    isLoggedIn: (s) => Boolean(s.token),
    /** 只有真的是多租户成员时才显示切换器 —— 单租户用户不应看到切换入口。 */
    isMultiTenant: (s) => s.tenants.length > 1,
    currentTenant(): TenantView | null {
      return this.tenants.find((t) => t.id === this.tenantId) ?? null
    },
  },

  actions: {
    /**
     * 登录（可带租户编码）。
     *
     * 不传 tenantCode = 进入账号的主租户；多租户成员可显式选择。
     */
    async login(username: string, password: string, tenantCode?: string) {
      const result = await authApi.login(username, password, tenantCode)
      this.applyLogin(result)
    },

    /**
     * 切换行动租户。
     *
     * ★ 后端会返回**新 token**（tid 已变）；这里整体覆盖 token、角色、权限与租户信息。
     *   任何"只改 tenantName 不换 token"的写法都是错的：服务端仍按旧 tid 过滤，
     *   界面与数据边界会分家。
     */
    async switchTenant(tenantCode: string): Promise<LoginPayload> {
      const result = await authApi.switchTenant(tenantCode)
      this.applyLogin(result)
      return result
    },

    /**
     * 刷新会话（页面加载 / 刷新后调用）。
     *
     * 除了补齐租户字段，它还顺带验证"租户是否被停用、token 是否仍有效"：
     * 停用租户的既有 token 会在这里返回 401，由 axios 拦截器统一跳登录页。
     */
    async refresh() {
      const snapshot = await authApi.me()
      this.applySnapshot(snapshot)
    },

    applyLogin(result: LoginPayload) {
      this.token = result.token
      setToken(result.token)
      this.applySnapshot(result)
    },

    applySnapshot(snapshot: SessionSnapshot) {
      this.username = snapshot.username
      this.displayName = snapshot.displayName ?? ''
      this.roles = snapshot.roles
      this.permissions = snapshot.permissions
      this.tenantId = snapshot.tenantId
      this.tenantCode = snapshot.tenantCode
      this.tenantName = snapshot.tenantName
      this.tenants = snapshot.tenants

      localStorage.setItem(PERM_KEY, JSON.stringify(snapshot.permissions))
      localStorage.setItem(
        USER_KEY,
        JSON.stringify({
          username: snapshot.username,
          displayName: snapshot.displayName ?? '',
          roles: snapshot.roles,
        }),
      )
      localStorage.setItem(
        TENANT_KEY,
        JSON.stringify({
          tenantId: snapshot.tenantId,
          tenantCode: snapshot.tenantCode,
          tenantName: snapshot.tenantName,
        }),
      )
      localStorage.setItem(TENANTS_KEY, JSON.stringify(snapshot.tenants))
    },

    logout() {
      this.token = null
      this.username = ''
      this.displayName = ''
      this.roles = []
      this.permissions = []
      this.tenantId = null
      this.tenantCode = ''
      this.tenantName = ''
      this.tenants = []
      clearToken()
      localStorage.removeItem(PERM_KEY)
      localStorage.removeItem(USER_KEY)
      localStorage.removeItem(TENANT_KEY)
      localStorage.removeItem(TENANTS_KEY)
    },

    /** 是否拥有某权限（支持 "模块:*" 通配），用于按钮级显隐。 */
    hasPermission(permission: string): boolean {
      if (this.permissions.includes(permission)) return true
      return this.permissions.includes(permission.split(':')[0] + ':*')
    },
  },
})
