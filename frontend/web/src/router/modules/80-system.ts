import { Checked, Key, Notebook, OfficeBuilding, Search, Setting } from '@element-plus/icons-vue'
import type { NavGroup } from '../nav'

/**
 * 系统：审批、用户角色、审计、字典、租户。
 *
 * tenant:manage 只种给平台租户的 ADMIN；普通租户管理员既看不到这个入口，
 * 直接请求也会 403（后端不接受 ADMIN 短路）。
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
  ],
}

export default group
