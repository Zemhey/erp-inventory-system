p='frontend/web/src/router/index.ts'
s=open(p,encoding='utf-8').read()
old="""      {
        path: 'reports',
        name: 'reports',
        component: () => import('@/views/rpt/ReportView.vue'),
        meta: { title: '报表', permission: 'rpt:read' },
      },
    ],"""
new="""      {
        path: 'reports',
        name: 'reports',
        component: () => import('@/views/rpt/ReportView.vue'),
        meta: { title: '报表', permission: 'rpt:read' },
      },
      // V2-6 追加：审批工作台与用户角色管理。仍遵守"只往后加"的约定，
      // 通配兜底路由继续留在数组最后。
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
    ],"""
assert old in s
s=s.replace(old,new,1)
open(p,'w',encoding='utf-8').write(s)
print("router updated")

p2='frontend/web/src/layouts/MainLayout.vue'
s2=open(p2,encoding='utf-8').read()
old2="""  { path: '/reports', title: '报表', icon: DataAnalysis, permission: 'rpt:read' },
]"""
new2="""  { path: '/reports', title: '报表', icon: DataAnalysis, permission: 'rpt:read' },
  // V2-6 追加：审批与权限管理（同样是"只往后加"）
  { path: '/approvals', title: '审批工作台', icon: Checked, permission: 'aps:read' },
  { path: '/iam', title: '用户与角色', icon: Key, permission: 'iam:read' },
]"""
assert old2 in s2
s2=s2.replace(old2,new2,1)
s2=s2.replace("""import {
  Box,
  Coin,""","""import {
  Box,
  Checked,
  Coin,""",1)
s2=s2.replace("""  House,
  Menu as MenuIcon,""","""  House,
  Key,
  Menu as MenuIcon,""",1)
open(p2,'w',encoding='utf-8').write(s2)
print("layout updated")
