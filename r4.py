import io
p='frontend/web/src/router/index.ts'
s=io.open(p,encoding='utf-8').read()
s=s.replace("""        meta: { title: '用户与角色', permission: 'iam:read' },
      // V2-2 采购闭环""","""        meta: { title: '用户与角色', permission: 'iam:read' },
      },
      // V2-2 采购闭环""",1)
s=s.replace("""        meta: { title: '毛利分析', permission: 'cst:read' },      },""",
"""        meta: { title: '毛利分析', permission: 'cst:read' },
      },""",1)
io.open(p,'w',encoding='utf-8').write(s)
print("router braces fixed")

p='frontend/web/src/layouts/MainLayout.vue'
s=io.open(p,encoding='utf-8').read()
print('Key' in s, 'Checked' in s, 'Tickets' in s, 'Wallet' in s, 'Van' in s, 'Files' in s, 'TrendCharts' in s)
