import { List } from '@element-plus/icons-vue'
import type { NavGroup } from '../nav'

/**
 * 待办中心（FR-V3-03）：把工作台里散落的待办收敛到一个页面，按四类 Tab 切换。
 *
 * 权限沿用 dashboard:read（与工作台聚合端点同一套 facade 权限）；
 * ADMIN 由后端 adminBypass 直通，普通账号看不到此入口（visibleGroups 过滤）。
 */
const group: NavGroup = {
  key: 'todo-center',
  title: '待办中心',
  icon: List,
  order: 15,
  items: [{ path: '/todos', title: '待办中心', icon: List, permission: 'dashboard:read' }],
  routes: [
    {
      path: 'todos',
      name: 'todos',
      component: () => import('@/views/TodoCenterView.vue'),
      meta: { title: '待办中心', permission: 'dashboard:read' },
    },
  ],
}

export default group
