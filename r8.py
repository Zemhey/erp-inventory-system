import io
p='backend/server/src/test/java/com/erp/support/TestDataFactory.java'
s=io.open(p,encoding='utf-8').read()
bad="""                         data_scopes,
                         app_user_roles, app_users
                         app_user_roles, app_users,
                         audit_logs
                RESTART IDENTITY CASCADE"""
good="""                         data_scopes,
                         app_user_roles, app_users,
                         audit_logs
                RESTART IDENTITY CASCADE"""
assert bad in s
s=s.replace(bad,good,1)
io.open(p,'w',encoding='utf-8').write(s)
print("duplicate truncate entry fixed")
