import { getToken } from '@/api/client'

/**
 * uni-app 没有 vue-router 的全局 beforeEach，登录守卫用两层：
 * 1. 导航拦截器：navigateTo / redirectTo / switchTab 进入非登录页且无 token 时改道登录页；
 * 2. 页面 onShow 调 ensureLoggedIn()：覆盖 H5 直接改 hash、小程序冷启动到 tab 页等入口。
 *
 * 前端守卫只是体验层，真正的一致性由后端 401/403 保证。
 */

export const LOGIN_ROUTE = '/pages/login/login'

export function ensureLoggedIn(): boolean {
  if (getToken()) return true
  uni.reLaunch({ url: LOGIN_ROUTE })
  return false
}

let installed = false

export function installRouteGuard(): void {
  if (installed) return
  installed = true

  const methods = ['navigateTo', 'redirectTo', 'switchTab'] as const

  methods.forEach((method) => {
    uni.addInterceptor(method, {
      invoke(args: { url?: string }) {
        const url = (args?.url ?? '').split('?')[0]
        if (getToken() || url === LOGIN_ROUTE) return true
        uni.reLaunch({ url: LOGIN_ROUTE })
        return false
      },
    })
  })
}
