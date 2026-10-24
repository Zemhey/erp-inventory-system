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
      // V2-2 采购闭环：只追加，不动上面既有条目；通配兜底仍留在数组最后
      {
        path: 'purchase-requisitions',
        name: 'purchase-requisitions',
        component: () => import('@/views/pur/PurchaseRequisitionView.vue'),
        meta: { title: '请购与质检', permission: 'pur:read' },
      },
      {
        path: 'supplier-payables',
        name: 'supplier-payables',
        component: () => import('@/views/pur/SupplierPayableView.vue'),
        meta: { title: '应付与付款', permission: 'pur:payable' },
      },
      // V2-1 库存作业：两条路由追加在报表之后，通配兜底路由继续留在数组最后
      {
        path: 'inventory-transfers',
        name: 'inventory-transfers',
        component: () => import('@/views/inv/TransferView.vue'),
        meta: { title: '调拨作业', permission: 'inv:read' },
      },
      {
        path: 'inventory-stock-ops',
        name: 'inventory-stock-ops',
        component: () => import('@/views/inv/StockOpsView.vue'),
        meta: { title: '盘点与调整', permission: 'inv:read' },
      },
      // V2-4 追加：毛利分析页；通配兜底路由必须继续留在数组最后
      {
        path: 'margin',
        name: 'margin',
        component: () => import('@/views/cst/MarginView.vue'),
        meta: { title: '毛利分析', permission: 'cst:read' },
      },
      // V2-3 销售闭环：继续追加，通配兜底路由仍在最后
      {
        path: 'sales-quotes',
        name: 'sales-quotes',
        component: () => import('@/views/sal/SalesQuoteView.vue'),
        meta: { title: '报价单', permission: 'sal:read' },
      },
      {
        path: 'shipments',
        name: 'shipments',
        component: () => import('@/views/sal/ShipmentView.vue'),
        meta: { title: '拣货发货', permission: 'sal:read' },
      },
      {
        path: 'receivables',
        name: 'receivables',
        component: () => import('@/views/sal/ReceivableView.vue'),
        meta: { title: '应收 / 收款 / 信用', permission: 'sal:read' },
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
