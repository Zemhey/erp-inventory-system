import {
  Box,
  Coin,
  DataAnalysis,
  Files,
  Money,
  Monitor,
  Notebook,
  RefreshLeft,
  Sell,
  ShoppingCart,
  Tickets,
  TrendCharts,
  User,
  Wallet,
} from '@element-plus/icons-vue'
import type { NavGroup, NavItem } from '../nav'
import type { RouteRecordRaw } from 'vue-router'

/**
 * 报表：报表中心与 11 张单报表，外加数据大屏（独立全屏页，列在最后一项）。
 *
 * ★ 每张报表一条独立路由（props 固定 report id），好处是侧边栏能直达、能收藏、
 *   浏览器标题正确；它们共用同一个 ReportCenterView，组件按 :key 重建，
 *   不存在「一份页面三个 if」的问题。
 *
 * ★ path 'reports'（旧版 8 tab 单页 ReportView.vue）刻意保留：
 *   别的页面已有链接指向它，删了就 404。
 *
 * ★ 这里不 import core/registry：注册表由 eager 装载的模块引入会把
 *   api/client 拖进首屏包。路由与标题在此文件写死，报表内容仍由 registry 单一来源。
 */

interface ReportEntry {
  id: string
  title: string
  icon: unknown
}

/** 目录顺序 = 报表中心左侧目录顺序（与 core/registry.ts 的 REPORTS 一一对应）。 */
const ENTRIES: ReportEntry[] = [
  { id: 'operating', title: '经营报表', icon: TrendCharts },
  { id: 'profit', title: '毛利分析', icon: Coin },
  { id: 'inventory-summary', title: '进销存汇总', icon: Box },
  { id: 'turnover', title: '库存周转', icon: RefreshLeft },
  { id: 'slow-moving', title: '呆滞分析', icon: Files },
  { id: 'purchase', title: '进货统计', icon: ShoppingCart },
  { id: 'sales', title: '出货统计', icon: Sell },
  { id: 'sales-top', title: '单品销售排行', icon: Tickets },
  { id: 'contribution', title: '客户贡献', icon: User },
  { id: 'ar-aging', title: '应收账龄', icon: Wallet },
  { id: 'ap-aging', title: '应付账龄', icon: Money },
]

const items: NavItem[] = [
  { path: '/reports/catalog', title: '报表中心', icon: DataAnalysis, permission: 'rpt:read' },
  ...ENTRIES.map((entry) => ({
    path: `/reports/${entry.id}`,
    title: entry.title,
    icon: entry.icon,
    permission: 'rpt:read',
  })),
  { path: '/reports', title: '报表（旧版多标签）', icon: Notebook, permission: 'rpt:read' },
  { path: '/screen', title: '数据大屏', icon: Monitor, permission: 'rpt:read' },
]

const routes: RouteRecordRaw[] = [
  {
    path: 'reports',
    name: 'reports',
    component: () => import('@/views/rpt/ReportView.vue'),
    meta: { title: '报表（旧版多标签）', permission: 'rpt:read' },
  },
  {
    path: 'reports/catalog',
    name: 'report-catalog',
    component: () => import('@/views/rpt/ReportCenterView.vue'),
    meta: { title: '报表中心', permission: 'rpt:read' },
  },
  ...ENTRIES.map((entry) => ({
    path: `reports/${entry.id}`,
    name: `report-${entry.id}`,
    component: () => import('@/views/rpt/ReportCenterView.vue'),
    // props 静态注入：目录页与单报表页因此共用同一组件
    props: { report: entry.id },
    meta: { title: entry.title, permission: 'rpt:read' },
  })),
  {
    path: 'screen',
    name: 'data-screen',
    component: () => import('@/views/rpt/DataScreenView.vue'),
    meta: { title: '数据大屏', permission: 'rpt:read' },
  },
]

const group: NavGroup = {
  key: 'reports',
  title: '报表',
  icon: DataAnalysis,
  order: 70,
  items,
  routes,
}

export default group
