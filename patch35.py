p='backend/server/src/test/java/com/erp/iam/DataScopeMatrixIT.java'
s=open(p,encoding='utf-8').read()
# use data.supplier/customer unconditionally (idempotent ON CONFLICT)
s=s.replace("""        Long supplier = jdbc.queryForObject("SELECT id FROM suppliers LIMIT 1", Long.class);
        long supplierId = supplier == null ? data.supplier("SUP-MX") : supplier;""",
"""        long supplierId = data.supplier("SUP-MX");""")
s=s.replace("""        Long customer = jdbc.queryForObject("SELECT id FROM customers LIMIT 1", Long.class);
        long customerId = customer == null ? data.customer("CUS-MX") : customer;""",
"""        long customerId = data.customer("CUS-MX");""")
s=s.replace("""        Long customer = jdbc.queryForObject("SELECT id FROM customers LIMIT 1", Long.class);
        long customerId = customer == null ? data.customer("CUS-MX") : customer;""",
"""        long customerId = data.customer("CUS-MX");""")
open(p,'w',encoding='utf-8').write(s)
print("matrix helper fixed:", s.count('data.supplier("SUP-MX")'), s.count('data.customer("CUS-MX")'))

# IAM: fix 403 in user list (VIEWER lacks iam:read? seed gives iam:read to VIEWER) -> the account 'leaky' is VIEWER,
# test asserts 403 for GET /iam/users with token of 'leaky' but VIEWER has iam:read -> should be 200.
# Change: use an account with no iam:read: create role without iam:read.
p='backend/server/src/test/java/com/erp/iam/IamDeepeningIT.java'
s=open(p,encoding='utf-8').read()
old="""        admin.createUser("leaky", "Le4ky!pass", "敏感字段测试", true, List.of("VIEWER"));
        jdbc.update("UPDATE app_users SET must_change_password = FALSE WHERE username = 'leaky'");
        String token = authentication.login("leaky", "Le4ky!pass").token();"""
new="""        admin.createUser("leaky", "Le4ky!pass", "敏感字段测试", true, List.of("VIEWER"));
        jdbc.update("UPDATE app_users SET must_change_password = FALSE WHERE username = 'leaky'");
        // VIEWER 的种子里有 iam:read，所以要让"读不到用户清单"这个断言成立，
        // 必须用一个**没有** iam:read 的角色。这也更接近真实场景：
        // 业务角色（采购/销售）不该看到账号清单。
        long noIamRoleId = admin.createRole("NO_IAM_ROLE", "无权限管理", "测试用");
        admin.replacePermissions(noIamRoleId, List.of("mdm:read", "inv:read"));
        long noIamUserId = admin.createUser("noiam", "N0!iampass", "无权限管理账号", true,
                List.of("NO_IAM_ROLE"));
        jdbc.update("UPDATE app_users SET must_change_password = FALSE WHERE id = ?", noIamUserId);
        String token = authentication.login("noiam", "N0!iampass").token();"""
assert old in s
s=s.replace(old,new,1)
open(p,'w',encoding='utf-8').write(s)
print("IAM user-list test identity fixed")
