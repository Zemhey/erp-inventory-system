import { Coin, Document, HomeFilled, Money, Notebook, Tickets, TrendCharts, Wallet } from '@element-plus/icons-vue'
import type { NavGroup } from '../nav'

/**
 * 财务中心：总账与凭证（科目、凭证、银行、日记账、报销、发票），
 * 以及结算与毛利分析两项业务财务动作。
 *
 * 菜单顺序按「业务记账 → 账簿 → 报表」排列；命名路由统一加 fin- 前缀
 * 避免与既有 name 撞车（settlements / margin 沿用原 name）。
 */
const group: NavGroup = {
  key: 'finance-center',
  title: '财务中心',
  icon: Wallet,
  order: 60,
  items: [
    { path: '/fin/overview', title: '财务首页', icon: HomeFilled, permission: 'fin:read' },
    { path: '/fin/accounts', title: '会计科目', icon: Coin, permission: 'fin:read' },
    { path: '/fin/vouchers', title: '凭证管理', icon: Document, permission: 'fin:read' },
    { path: '/fin/bank-accounts', title: '银行账户', icon: Money, permission: 'fin:read' },
    { path: '/fin/cash-journal', title: '现金日记账', icon: Notebook, permission: 'fin:read' },
    { path: '/fin/expenses', title: '费用报销', icon: Wallet, permission: 'fin:read' },
    { path: '/fin/invoices', title: '发票台账', icon: Tickets, permission: 'fin:read' },
    { path: '/settlements', title: '结算', icon: Coin, permission: 'set:read' },
    { path: '/margin', title: '毛利分析', icon: TrendCharts, permission: 'cst:read' },
    { path: '/fin/reports', title: '财务报表', icon: TrendCharts, permission: 'fin:read' },
  ],
  routes: [
    {
      path: 'fin/overview',
      name: 'fin-overview',
      component: () => import('@/views/fin/FinanceHomeView.vue'),
      meta: { title: '财务首页', permission: 'fin:read' },
    },
    {
      path: 'fin/accounts',
      name: 'fin-accounts',
      component: () => import('@/views/fin/AccountView.vue'),
      meta: { title: '会计科目', permission: 'fin:read' },
    },
    {
      path: 'fin/vouchers',
      name: 'fin-vouchers',
      component: () => import('@/views/fin/VoucherView.vue'),
      meta: { title: '凭证管理', permission: 'fin:read' },
    },
    {
      path: 'fin/bank-accounts',
      name: 'fin-bank-accounts',
      component: () => import('@/views/fin/BankAccountView.vue'),
      meta: { title: '银行账户', permission: 'fin:read' },
    },
    {
      path: 'fin/cash-journal',
      name: 'fin-cash-journal',
      component: () => import('@/views/fin/CashJournalView.vue'),
      meta: { title: '现金日记账', permission: 'fin:read' },
    },
    {
      path: 'fin/expenses',
      name: 'fin-expenses',
      component: () => import('@/views/fin/ExpenseView.vue'),
      meta: { title: '费用报销', permission: 'fin:read' },
    },
    {
      path: 'fin/invoices',
      name: 'fin-invoices',
      component: () => import('@/views/fin/InvoiceView.vue'),
      meta: { title: '发票台账', permission: 'fin:read' },
    },
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
    {
      path: 'fin/reports',
      name: 'fin-reports',
      component: () => import('@/views/fin/FinancialReportView.vue'),
      meta: { title: '财务报表', permission: 'fin:read' },
    },
  ],
}

export default group
