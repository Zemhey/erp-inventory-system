import { Checked, ShoppingCart, Tickets, Wallet } from '@element-plus/icons-vue'
import type { NavGroup } from '../nav'

/** 采购：请购与质检、进货单、应付与付款。 */
const group: NavGroup = {
  key: 'purchase',
  title: '采购',
  icon: ShoppingCart,
  order: 40,
  items: [
    { path: '/purchase-requisitions', title: '请购与质检', icon: Tickets, permission: 'pur:read' },
    { path: '/purchase-orders', title: '进货单', icon: ShoppingCart, permission: 'pur:read' },
    { path: '/supplier-payables', title: '应付与付款', icon: Wallet, permission: 'pur:payable' },
    // V3-3 追加：已有后端（PurchaseReturnController / FR-PUR-05 质检记录）的独立入口
    { path: '/purchase-returns-page', title: '采购退货', icon: Tickets, permission: 'pur:read' },
    { path: '/purchase-quality', title: '到货质检', icon: Checked, permission: 'pur:read' },
  ],
  routes: [
    {
      path: 'purchase-requisitions',
      name: 'purchase-requisitions',
      component: () => import('@/views/pur/PurchaseRequisitionView.vue'),
      meta: { title: '请购与质检', permission: 'pur:read' },
    },
    {
      path: 'purchase-orders',
      name: 'purchase-orders',
      component: () => import('@/views/pur/PurchaseOrderView.vue'),
      meta: { title: '进货单', permission: 'pur:read' },
    },
    {
      path: 'supplier-payables',
      name: 'supplier-payables',
      component: () => import('@/views/pur/SupplierPayableView.vue'),
      meta: { title: '应付与付款', permission: 'pur:payable' },
    },
    {
      path: 'purchase-returns-page',
      name: 'purchase-returns-page',
      component: () => import('@/views/pur/PurchaseReturnView.vue'),
      meta: { title: '采购退货', permission: 'pur:read' },
    },
    {
      path: 'purchase-quality',
      name: 'purchase-quality',
      component: () => import('@/views/pur/QcInspectView.vue'),
      meta: { title: '到货质检', permission: 'pur:read' },
    },
  ],
}

export default group
