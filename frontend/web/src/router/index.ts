import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { navGroups } from './registry'

/**
 * 路由表。
 *
 * ★ 业务路由不再写在这个文件里 —— 它们由 src/router/modules/*.ts 各自声明，
 *   由 registry.ts 自动装载。这里只保留「登录页 / 主布局 / 兜底」这三条骨架路由。
 *   加一个业务页面 = 新建一个导航模块文件，不改本文件（多人并行时不会冲突）。
 *
 * 用 route meta 声明权限码，守卫里据此判断 ——
 * 与后端 @RequirePermission 是同一套权限码（mdm:read / pur:create ...），
 * 避免前后端各写一套权限名称而逐渐对不上。
 */

declare module 'vue-router' {
  interface RouteMeta {
    /** 页面标题（顶栏与浏览器标题） */
    title?: string
    /** 所需权限码；缺省表示登录即可访问 */
    permission?: string
    /** 是否在侧边栏隐藏 */
    hidden?: boolean
  }
}

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { title: '登录', hidden: true },
  },
  {
    path: '/',
    component: () => import('@/layouts/MainLayout.vue'),
    redirect: '/dashboard',
    // 各导航模块声明的子路由（path 为相对路径），顺序由模块 order 决定
    children: navGroups.flatMap((group) => group.routes),
  },
  {
    // 兜底：未匹配的路径回工作台，避免白屏
    path: '/:pathMatch(.*)*',
    redirect: '/dashboard',
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

/**
 * 全局守卫：登录态 + 页面权限。
 *
 * 为什么前端也要判权限：后端已经会返回 403，前端判只是"体验"层面的
 * 提前拦截（不让用户点进去才看到错误）。**真正的一致性由后端保证** ——
 * 前端权限判断绝不能被当作安全边界。
 */
router.beforeEach((to) => {
  const token = localStorage.getItem('erp.token')

  if (to.name !== 'login' && !token) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }

  if (to.name === 'login' && token) {
    return { name: 'dashboard' }
  }

  const required = to.meta.permission
  if (required) {
    const raw = localStorage.getItem('erp.permissions')
    const permissions: string[] = raw ? JSON.parse(raw) : []
    // 支持 "模块:*" 通配，与后端 AppUser#hasPermission 语义一致
    const moduleWildcard = required.split(':')[0] + ':*'
    if (!permissions.includes(required) && !permissions.includes(moduleWildcard)) {
      return { name: 'dashboard' }
    }
  }

  if (to.meta.title) {
    document.title = `${to.meta.title} · 进销存系统`
  }
  return true
})

export default router
