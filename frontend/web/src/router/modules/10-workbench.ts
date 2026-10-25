import { House } from '@element-plus/icons-vue'
import type { NavGroup } from '../nav'

/** 工作台：单入口组，侧边栏直接渲染成一级菜单项。 */
const group: NavGroup = {
  key: 'workbench',
  title: '工作台',
  icon: House,
  order: 10,
  flat: true,
  items: [{ path: '/dashboard', title: '工作台', icon: House }],
  routes: [
    {
      path: 'dashboard',
      name: 'dashboard',
      component: () => import('@/views/DashboardView.vue'),
      meta: { title: '工作台' },
    },
  ],
}

export default group
