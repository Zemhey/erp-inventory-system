import { Coin, TrendCharts } from '@element-plus/icons-vue'
import type { NavGroup } from '../nav'

/** 财务：结算与毛利分析（V3-2 会在此组继续扩展科目、凭证、银行、报销、发票）。 */
const group: NavGroup = {
  key: 'finance',
  title: '财务',
  icon: Coin,
  order: 60,
  items: [
    { path: '/settlements', title: '结算', icon: Coin, permission: 'set:read' },
    { path: '/margin', title: '毛利分析', icon: TrendCharts, permission: 'cst:read' },
  ],
  routes: [
    {
      path: 'settlements',
      name: 'settlements',
      component: () => import('@/views/set/SettlementView.vue'),
      meta: { title: '结算', permission: 'set:read' },
    },
    {
      path: 'margin',
      name: 'margin',
      component: () => import('@/views/cst/MarginView.vue'),
      meta: { title: '毛利分析', permission: 'cst:read' },
    },
  ],
}

export default group
