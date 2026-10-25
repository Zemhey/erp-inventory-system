import { Box, Goods, Grid, OfficeBuilding, User } from '@element-plus/icons-vue'
import type { NavGroup } from '../nav'

/** 主数据：商品与 SKU、供应商、客户、仓库（含客户收货地址、仓库库位、商品规格属性）。 */
const group: NavGroup = {
  key: 'master-data',
  title: '主数据',
  icon: Grid,
  order: 20,
  items: [
    { path: '/products', title: '商品与 SKU', icon: Goods, permission: 'mdm:read' },
    { path: '/suppliers', title: '供应商', icon: OfficeBuilding, permission: 'mdm:read' },
    { path: '/customers', title: '客户 / 会员', icon: User, permission: 'mdm:read' },
    { path: '/warehouses', title: '仓库', icon: Box, permission: 'mdm:read' },
  ],
  routes: [
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
  ],
}

export default group
