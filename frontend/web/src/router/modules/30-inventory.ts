import { AlarmClock, Box, Files, Menu as MenuIcon, Refresh, Van } from '@element-plus/icons-vue'
import type { NavGroup } from '../nav'

/** 库存：查询、调拨、盘点与调整。 */
const group: NavGroup = {
  key: 'inventory',
  title: '库存',
  icon: Box,
  order: 30,
  items: [
    { path: '/inventory', title: '库存查询', icon: MenuIcon, permission: 'inv:read' },
    { path: '/inventory-transfers', title: '调拨作业', icon: Van, permission: 'inv:read' },
    { path: '/inventory-stock-ops', title: '盘点与调整', icon: Files, permission: 'inv:read' },
    // V3-3 追加：库存智能两页（数据源为 V2-1 既有 alerts/replenishment 端点）
    { path: '/inventory-alerts', title: '库存预警', icon: AlarmClock },
    { path: '/replenishment', title: '补货建议', icon: Refresh },
  ],
  routes: [
    {
      path: 'inventory',
      name: 'inventory',
      component: () => import('@/views/inv/InventoryView.vue'),
      meta: { title: '库存查询', permission: 'inv:read' },
    },
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
    {
      path: 'inventory-alerts',
      name: 'inventory-alerts',
      component: () => import('@/views/inv/InventoryAlertView.vue'),
      meta: { title: '库存预警' },
    },
    {
      path: 'replenishment',
      name: 'replenishment',
      component: () => import('@/views/inv/ReplenishmentView.vue'),
      meta: { title: '补货建议' },
    },
  ],
}

export default group
