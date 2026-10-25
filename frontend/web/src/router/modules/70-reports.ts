import { DataAnalysis } from '@element-plus/icons-vue'
import type { NavGroup } from '../nav'

/** 报表：报表中心（V3-4 会在此组继续扩展 10 张报表与数据大屏）。 */
const group: NavGroup = {
  key: 'reports',
  title: '报表',
  icon: DataAnalysis,
  order: 70,
  items: [{ path: '/reports', title: '报表', icon: DataAnalysis, permission: 'rpt:read' }],
  routes: [
    {
      path: 'reports',
      name: 'reports',
      component: () => import('@/views/rpt/ReportView.vue'),
      meta: { title: '报表', permission: 'rpt:read' },
    },
  ],
}

export default group
