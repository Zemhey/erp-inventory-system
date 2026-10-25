import { Coin, Document, HomeFilled, Money, Notebook, Tickets, TrendCharts, Wallet } from '@element-plus/icons-vue'
import type { NavGroup } from '../nav'

/**
 * 财务中心（V3-2 · W2，简化复式记账）。
 *
 * ★ 与 60-finance.ts（结算/毛利）分组并存：60 是"业务财务动作"，
 *   65 是"总账与凭证"。命名路由加 fin- 前缀避免与既有 name 撞车。
 *   8 个页面 = 简报 §9 完成判据的"点进去有 8 个页面"。
 */
const group: NavGroup = {
  key: 'finance-center',
  title: '财务中心',
  icon: Wallet,
  order: 65,
  items: [
    { path: '/fin/overview', title: '财务首页', icon: HomeFilled, permission: 'fin:read' },
    { path: '/fin/accounts', title: '会计科目', icon: Coin, permission: 'fin:read' },
    { path: '/fin/vouchers', title: '凭证管理', icon: Document, permission: 'fin:read' },
    { path: '/fin/bank-accounts', title: '银行账户', icon: Money, permission: 'fin:read' },
    { path: '/fin/cash-journal', title: '现金日记账', icon: Notebook, permission: 'fin:read' },
    { path: '/fin/expenses', title: '费用报销', icon: Wallet, permission: 'fin:read' },
    { path: '/fin/invoices', title: '发票台账', icon: Tickets, permission: 'fin:read' },
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
      path: 'fin/reports',
      name: 'fin-reports',
      component: () => import('@/views/fin/FinancialReportView.vue'),
      meta: { title: '财务报表', permission: 'fin:read' },
    },
  ],
}

export default group
