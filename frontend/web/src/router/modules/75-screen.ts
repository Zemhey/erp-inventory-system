import { Monitor } from '@element-plus/icons-vue'
import type { NavGroup } from '../nav'

/**
 * 数据大屏（FR-V3-33）。
 *
 * ★ 简报要求「独立全屏页 + meta.fullscreen，不走 MainLayout」，但路由骨架
 *   （router/index.ts、MainLayout.vue）不归本泳道改 —— 三条泳道并行，动了必冲突。
 *   这里改用等价方案：路由仍注册在 MainLayout 之下，页面自己用
 *   position: fixed; inset: 0; z-index: 2000 铺满视口，再叠一层原生 Fullscreen API
 *   按钮。视觉与操作等同独立全屏页，代价为零。
 *   （已把这个妥协记进报告，交给主控在合并后补 meta.fullscreen 支持。）
 */
const group: NavGroup = {
  key: 'screen',
  title: '数据大屏',
  icon: Monitor,
  order: 75,
  flat: true,
  items: [{ path: '/screen', title: '数据大屏', icon: Monitor, permission: 'rpt:read' }],
  routes: [
    {
      path: 'screen',
      name: 'data-screen',
      component: () => import('@/views/rpt/DataScreenView.vue'),
      meta: { title: '数据大屏', permission: 'rpt:read' },
    },
  ],
}

export default group
