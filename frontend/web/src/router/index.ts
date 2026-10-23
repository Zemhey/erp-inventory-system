import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

/**
 * 路由表。
 *
 * 结构：登录页独立，其余全部挂在 MainLayout 下（带侧边栏与顶栏）。
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
    children: [
      {
        path: 'dashboard',
        name: 'dashboard',
        component: () => import('@/views/DashboardView.vue'),
        meta: { title: '工作台' },
      },
      {
        path: 'products',
        name: 'products',
        component: () => import('@/views/mdm/ProductView.vue'),
        meta: { title: '商品与 SKU', permission: 'mdm:read' },
      },
      {
        path: 'suppliers',
        name: 'suppliers',
        component: () => import('@/views/mdm/SupplierView.vue'),
        meta: { title: '供应商', permission: 'mdm:read' },
      },
      {
        path: 'customers',
        name: 'customers',
        component: () => import('@/views/mdm/CustomerView.vue'),
        meta: { title: '客户 / 会员', permission: 'mdm:read' },
      },
      {
        path: 'warehouses',
        name: 'warehouses',
        component: () => import('@/views/mdm/WarehouseView.vue'),
        meta: { title: '仓库', permission: 'mdm:read' },
      },
      {
        path: 'inventory',
        name: 'inventory',
        component: () => import('@/views/inv/InventoryView.vue'),
        meta: { title: '库存查询', permission: 'inv:read' },
      },
      {
        path: 'purchase-orders',
        name: 'purchase-orders',
        component: () => import('@/views/pur/PurchaseOrderView.vue'),
        meta: { title: '进货单', permission: 'pur:read' },
      },
      {
        path: 'sales-orders',
        name: 'sales-orders',
        component: () => import('@/views/sal/SalesOrderView.vue'),
        meta: { title: '出货单', permission: 'sal:read' },
      },
      {
        path: 'return-orders',
        name: 'return-orders',
        component: () => import('@/views/rma/ReturnOrderView.vue'),
        meta: { title: '退货单', permission: 'rma:read' },
      },
      // 追加式新增：两条路由接在后面，通配兜底路由必须继续留在数组最后
      {
        path: 'settlements',
        name: 'settlements',
        component: () => import('@/views/set/SettlementView.vue'),
        meta: { title: '结算', permission: 'set:read' },
      },
      {
        path: 'reports',
        name: 'reports',
        component: () => import('@/views/rpt/ReportView.vue'),
        meta: { title: '报表', permission: 'rpt:read' },
      },
      // V2-6 追加：审批工作台与用户角色管理。仍遵守"只往后加"的约定，
      // 通配兜底路由继续留在数组最后。
      {
        path: 'approvals',
        name: 'approvals',
        component: () => import('@/views/iam/ApprovalView.vue'),
        meta: { title: '审批工作台', permission: 'aps:read' },
      },
      {
        path: 'iam',
        name: 'iam',
        component: () => import('@/views/iam/UserRoleView.vue'),
        meta: { title: '用户与角色', permission: 'iam:read' },
      },
    ],
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
