import { Clock, Document, OfficeBuilding, Setting } from '@element-plus/icons-vue'
import type { NavGroup } from '../nav'

/**
 * 系统·骨架组（V3-1 骨架组）：组织架构、系统参数、单号规则、登录日志。
 *
 * 四项都挂在「系统」之下，但单独成组，避免与 80-system（审批/用户/审计/字典/租户）
 * 在同文件里互相干扰 —— 并行泳道各自加菜单、互不冲突。
 *
 * 权限码与后端 @RequirePermission 完全一致：sys:org / sys:config /
 * sys:numberrule / sys:loginlog（ADMIN 由后端 adminBypass 直通）。
 */
const group: NavGroup = {
  key: 'system-plus',
  title: '系统·骨架组',
  icon: Setting,
  order: 82,
  items: [
    { path: '/org', title: '组织架构', icon: OfficeBuilding, permission: 'sys:org' },
    { path: '/sys-config', title: '系统参数', icon: Setting, permission: 'sys:config' },
    { path: '/number-rules', title: '单号规则', icon: Document, permission: 'sys:numberrule' },
    { path: '/login-logs', title: '登录日志', icon: Clock, permission: 'sys:loginlog' },
  ],
  routes: [
    {
      path: 'org',
      name: 'org',
      component: () => import('@/views/sys/OrganizationView.vue'),
      meta: { title: '组织架构', permission: 'sys:org' },
    },
    {
      path: 'sys-config',
      name: 'sys-config',
      component: () => import('@/views/sys/SystemConfigView.vue'),
      meta: { title: '系统参数', permission: 'sys:config' },
    },
    {
      path: 'number-rules',
      name: 'number-rules',
      component: () => import('@/views/sys/NumberRuleView.vue'),
      meta: { title: '单号规则', permission: 'sys:numberrule' },
    },
    {
      path: 'login-logs',
      name: 'login-logs',
      component: () => import('@/views/sys/LoginLogView.vue'),
      meta: { title: '登录日志', permission: 'sys:loginlog' },
    },
  ],
}

export default group
