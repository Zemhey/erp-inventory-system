import io
p='backend/server/src/test/java/com/erp/iam/IamDeepeningIT.java'
s=io.open(p,encoding='utf-8').read()

old1="""        long adminId = admin.createUser("apiadmin", "Ap1!admin", "接口管理员", true,
                List.of("ADMIN"));
        String adminToken = authentication.login("apiadmin", "Ap1!admin").token();"""
new1="""        long adminId = admin.createUser("apiadmin", "Ap1!admin", "接口管理员", true,
                List.of("ADMIN"));
        // ★ createUser 一律置 must_change_password=TRUE（FR-IAM-06 的正确行为）。
        //   若不在此清掉，PasswordExpiryFilter 会把所有业务请求拦成
        //   IAM_PASSWORD_EXPIRED，本用例就会得到一个与"权限"毫无关系的 403。
        //   这里显式清标记，是为了让用例只考"用户管理接口的权限与脱敏"。
        jdbc.update("UPDATE app_users SET must_change_password = FALSE WHERE id = ?", adminId);
        String adminToken = authentication.login("apiadmin", "Ap1!admin").token();"""
assert old1 in s
s=s.replace(old1,new1,1)

old2="""        long viewerId = admin.createUser("apiviewer", "Ap1!viewer", "接口只读", true,
                List.of("VIEWER"));
        String viewerToken = authentication.login("apiviewer", "Ap1!viewer").token();"""
new2="""        long viewerId = admin.createUser("apiviewer", "Ap1!viewer", "接口只读", true,
                List.of("VIEWER"));
        jdbc.update("UPDATE app_users SET must_change_password = FALSE WHERE id = ?", viewerId);
        String viewerToken = authentication.login("apiviewer", "Ap1!viewer").token();"""
assert old2 in s
s=s.replace(old2,new2,1)
io.open(p,'w',encoding='utf-8').write(s)
print("must_change flags cleared in tests")
