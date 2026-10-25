import { Money, RefreshLeft, Sell, Tickets, Van } from '@element-plus/icons-vue'
import type { NavGroup } from '../nav'

/** 销售：报价、出货、拣货发货、退货、应收收款。 */
const group: NavGroup = {
  key: 'sales',
  title: '销售',
  icon: Sell,
  order: 50,
  items: [
    { path: '/sales-quotes', title: '报价单', icon: Tickets, permission: 'sal:read' },
    { path: '/sales-orders', title: '出货单', icon: Sell, permission: 'sal:read' },
    { path: '/shipments', title: '拣货发货', icon: Van, permission: 'sal:read' },
    { path: '/return-orders', title: '退货单', icon: RefreshLeft, permission: 'rma:read' },
    { path: '/receivables', title: '应收 / 收款', icon: Money, permission: 'sal:read' },
    // V3-3 追加：组合商品/信用控制的独立入口 + PRM 价格与促销
    { path: '/product-bundles-page', title: '组合商品', icon: Tickets, permission: 'sal:read' },
    { path: '/customer-credit', title: '信用控制', icon: Money, permission: 'mdm:read' },
  ],
  routes: [
    {
      path: 'sales-quotes',
      name: 'sales-quotes',
      component: () => import('@/views/sal/SalesQuoteView.vue'),
      meta: { title: '报价单', permission: 'sal:read' },
    },
    {
      path: 'sales-orders',
      name: 'sales-orders',
      component: () => import('@/views/sal/SalesOrderView.vue'),
      meta: { title: '出货单', permission: 'sal:read' },
    },
    {
      path: 'shipments',
      name: 'shipments',
      component: () => import('@/views/sal/ShipmentView.vue'),
      meta: { title: '拣货发货', permission: 'sal:read' },
    },
    {
      path: 'return-orders',
      name: 'return-orders',
      component: () => import('@/views/rma/ReturnOrderView.vue'),
      meta: { title: '退货单', permission: 'rma:read' },
    },
    {
      path: 'receivables',
      name: 'receivables',
      component: () => import('@/views/sal/ReceivableView.vue'),
      meta: { title: '应收 / 收款 / 信用', permission: 'sal:read' },
    },
    {
      path: 'product-bundles-page',
      name: 'product-bundles-page',
      component: () => import('@/views/sal/ProductBundleView.vue'),
      meta: { title: '组合商品', permission: 'sal:read' },
    },
    {
      path: 'customer-credit',
      name: 'customer-credit',
      component: () => import('@/views/sal/CreditView.vue'),
      meta: { title: '信用控制', permission: 'mdm:read' },
    },
  ],
}

export default group
