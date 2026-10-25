import { Bell, Printer, Setting, Tickets, TrendCharts } from '@element-plus/icons-vue'
import type { NavGroup } from '../nav'

/**
 * 工具（V3-3 泳道新建，FR-V3-17/26/27/28）。
 *
 * ★ 刻意不写 permission：ADMIN 持有的是既有模块通配符（pur:* sal:* …），
 *   新页面对象权限由后端接口自己的 @RequirePermission 兜底；
 *   前端 meta 不写权限可避免"没有种通配符 → 菜单直接消失"的联调黑洞。
 *   prm 两个页面的权限码（prm:*）已在 V21 种给 ADMIN，双保险。
 */
const group: NavGroup = {
  key: 'tools',
  title: '工具',
  icon: Setting,
  order: 90,
  items: [
    { path: '/advanced-reports', title: '高级报表', icon: TrendCharts },
    { path: '/notifications', title: '消息通知', icon: Bell },
    { path: '/print', title: '打印模板', icon: Printer },
    { path: '/import-export', title: '导入导出', icon: Tickets },
  ],
  routes: [
    {
      path: 'advanced-reports',
      name: 'advanced-reports',
      component: () => import('@/views/tools/AdvancedReportView.vue'),
      meta: { title: '高级报表' },
    },
    {
      path: 'notifications',
      name: 'notifications',
      component: () => import('@/views/tools/NotificationView.vue'),
      meta: { title: '消息通知' },
    },
    {
      path: 'print',
      name: 'print',
      component: () => import('@/views/tools/PrintView.vue'),
      meta: { title: '打印模板' },
    },
    {
      path: 'import-export',
      name: 'import-export',
      component: () => import('@/views/tools/ImportExportView.vue'),
      meta: { title: '导入导出中心' },
    },
  ],
}

export default group
