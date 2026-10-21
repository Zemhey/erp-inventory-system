import { defineStore } from 'pinia'
import { authApi, clearToken, getToken, setToken } from '@/api/client'

/**
 * 登录会话。
 *
 * 为什么 permissions 也要存 localStorage：刷新页面后路由守卫要立即判断权限，
 * 若依赖一次异步接口，会在首屏渲染前出现"闪一下无权限页面"。
 * token 里其实也带了角色，但那只是签发时的快照；后端每次请求都会回库校验，
 * 因此这里的本地副本只用于 UI 展示，**不作为安全依据**。
 */

interface SessionState {
  token: string | null
  username: string
  displayName: string
  roles: string[]
  permissions: string[]
}

const PERM_KEY = 'erp.permissions'
const USER_KEY = 'erp.user'

function loadPermissions(): string[] {
  const raw = localStorage.getItem(PERM_KEY)
  return raw ? JSON.parse(raw) : []
}

function loadUser(): { username: string; displayName: string; roles: string[] } {
  const raw = localStorage.getItem(USER_KEY)
  return raw ? JSON.parse(raw) : { username: '', displayName: '', roles: [] }
}

export const useSessionStore = defineStore('session', {
  state: (): SessionState => ({
    token: getToken(),
    ...loadUser(),
    permissions: loadPermissions(),
  }),

  getters: {
    isLoggedIn: (s) => Boolean(s.token),
  },

  actions: {
    async login(username: string, password: string) {
      const result = await authApi.login(username, password)
      this.token = result.token
      this.username = result.username
      this.displayName = result.displayName
      this.roles = result.roles
      this.permissions = result.permissions

      setToken(result.token)
      localStorage.setItem(PERM_KEY, JSON.stringify(result.permissions))
      localStorage.setItem(
        USER_KEY,
        JSON.stringify({
          username: result.username,
          displayName: result.displayName,
          roles: result.roles,
        }),
      )
    },

    logout() {
      this.token = null
      this.username = ''
      this.displayName = ''
      this.roles = []
      this.permissions = []
      clearToken()
      localStorage.removeItem(PERM_KEY)
      localStorage.removeItem(USER_KEY)
    },

    /** 是否拥有某权限（支持 "模块:*" 通配），用于按钮级显隐。 */
    hasPermission(permission: string): boolean {
      if (this.permissions.includes(permission)) return true
      return this.permissions.includes(permission.split(':')[0] + ':*')
    },
  },
})
