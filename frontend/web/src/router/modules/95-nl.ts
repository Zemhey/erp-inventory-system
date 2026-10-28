import { AlarmClock, Notebook, Setting } from '@element-plus/icons-vue'
import type { NavGroup } from '../nav'

/**
 * 自然语言操作（FR-V4-03 · S5/S6）。
 *
 * ★ 为什么单独一个分组，而不是塞进「系统工具」：
 *   这两页是"AI 代操作"的**治理入口**——待确认队列是人的签字台，
 *   操作审计是事后复盘的地方。它们服务的是同一个新能力（自然语言操作），
 *   和打印、导入导出那种工具页不是一类东西。
 *
 * ★ 刻意不写前端 permission：与 90-tools.ts 同样的理由（避免"没种通配符 → 菜单消失"）。
 *   后端 `@RequirePermission("sys:read")` 才是真正的门。
 *   所以没有该权限的账号能看到菜单、点进去会收到 403 —— 这是有意的：
 *   宁可让人看到"这里有个我进不去的门"，也不要让他以为"系统没这个功能"。
 */
const group: NavGroup = {
  key: 'nl',
  title: '自然语言',
  icon: AlarmClock,
  order: 95,
  items: [
    { path: '/nl/pending', title: '待确认队列', icon: AlarmClock },
    { path: '/nl/audit', title: '操作审计', icon: Notebook },
    { path: '/nl/settings', title: 'AI 接入', icon: Setting },
  ],
  routes: [
    {
      path: 'nl/pending',
      name: 'nl-pending',
      component: () => import('@/views/nl/PendingView.vue'),
      meta: { title: '待确认队列' },
    },
    {
      path: 'nl/audit',
      name: 'nl-audit',
      component: () => import('@/views/nl/AuditView.vue'),
      meta: { title: '操作审计' },
    },
    {
      path: 'nl/settings',
      name: 'nl-settings',
      component: () => import('@/views/nl/SettingsView.vue'),
      meta: { title: 'AI 接入' },
    },
  ],
}

export default group
