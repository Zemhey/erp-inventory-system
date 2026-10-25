import { House, List } from '@element-plus/icons-vue'
import type { NavGroup } from '../nav'

/**
 * 工作台：首页概览 + 待办中心。
 *
 * 待办中心（FR-V3-03）把散落在首页的待办收敛成一页、按四类 Tab 切换，
 * 权限沿用 dashboard:read（与工作台聚合端点同一套 facade 权限）；
 * ADMIN 由后端 adminBypass 直通，普通账号看不到此入口（visibleGroups 过滤）。
 *
 * 注：本组已有两项菜单，因此不再声明 flat —— flat 只渲染 items[0]，
 * 多于一条时必须走二级菜单，否则第二项在侧边栏不可见。
 */
const group: NavGroup = {
  key: 'workbench',
  title: '工作台',
  icon: House,
  order: 10,
  items: [
    { path: '/dashboard', title: '工作台', icon: House },
    { path: '/todos', title: '待办中心', icon: List, permission: 'dashboard:read' },
  ],
  routes: [
    {
      path: 'dashboard',
      name: 'dashboard',
      component: () => import('@/views/DashboardView.vue'),
      meta: { title: '工作台' },
    },
    {
      path: 'todos',
      name: 'todos',
      component: () => import('@/views/TodoCenterView.vue'),
      meta: { title: '待办中心', permission: 'dashboard:read' },
    },
  ],
}

export default group
