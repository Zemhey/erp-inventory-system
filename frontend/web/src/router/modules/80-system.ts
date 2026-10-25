import {
  Checked,
  Clock,
  Document,
  Key,
  Notebook,
  OfficeBuilding,
  Search,
  Setting,
} from '@element-plus/icons-vue'
import type { NavGroup } from '../nav'

/**
 * 系统：组织架构、审批、用户角色、审计、字典、租户、系统参数、单号规则、登录日志。
 *
 * tenant:manage 只种给平台租户的 ADMIN；普通租户管理员既看不到这个入口，
 * 直接请求也会 403（后端不接受 ADMIN 短路）。
 * 其余各项的权限码与后端 @RequirePermission 一致：sys:org / sys:config /
 * sys:numberrule / sys:loginlog（ADMIN 由后端 adminBypass 直通）。
 */
const group: NavGroup = {
  key: 'system',
  title: '系统',
  icon: Setting,
  order: 80,
  items: [
    { path: '/approvals', title: '审批工作台', icon: Checked, permission: 'aps:read' },
    { path: '/iam', title: '用户与角色', icon: Key, permission: 'iam:read' },
    { path: '/audits', title: '审计查询', icon: Search, permission: 'sys:read' },
    { path: '/dicts', title: '数据字典', icon: Notebook, permission: 'sys:read' },
    { path: '/tenants', title: '租户管理', icon: OfficeBuilding, permission: 'tenant:manage' },
    { path: '/org', title: '组织架构', icon: OfficeBuilding, permission: 'sys:org' },
    { path: '/sys-config', title: '系统参数', icon: Setting, permission: 'sys:config' },
    { path: '/number-rules', title: '单号规则', icon: Document, permission: 'sys:numberrule' },
    { path: '/login-logs', title: '登录日志', icon: Clock, permission: 'sys:loginlog' },
  ],
  routes: [
    {
      path: 'approvals',
      name: 'approvals',
      component: () => import('@/views/iam/ApprovalView.vue'),
      meta: { title: '审批工作台', permission: 'aps:read' },
    },
    {
      path: 'iam',
      name: 'iam',
      component: () => import('@/views/iam/UserRoleView.vue'),
      meta: { title: '用户与角色', permission: 'iam:read' },
    },
    {
      path: 'audits',
      name: 'audits',
      component: () => import('@/views/sys/AuditView.vue'),
      meta: { title: '审计查询', permission: 'sys:read' },
    },
    {
      path: 'dicts',
      name: 'dicts',
      component: () => import('@/views/sys/DictView.vue'),
      meta: { title: '数据字典', permission: 'sys:read' },
    },
    {
      path: 'tenants',
      name: 'tenants',
      component: () => import('@/views/iam/TenantManageView.vue'),
      meta: { title: '租户管理', permission: 'tenant:manage' },
    },
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
