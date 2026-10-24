import io
p='backend/server/src/test/java/com/erp/iam/IamDeepeningIT.java'
s=io.open(p,encoding='utf-8').read()
old="""        long adminId = admin.createUser("scope_writer", "Sc0pe!writ", "范围管理员", true,
                List.of("ADMIN"));
        String adminToken = authentication.login("scope_writer", "Sc0pe!writ").token();"""
new="""        long adminId = admin.createUser("scope_writer", "Sc0pe!writ", "范围管理员", true,
                List.of("ADMIN"));
        // 同上：createUser 会置 must_change_password，先清掉再登录
        jdbc.update("UPDATE app_users SET must_change_password = FALSE WHERE id = ?", adminId);
        String adminToken = authentication.login("scope_writer", "Sc0pe!writ").token();"""
assert old in s
s=s.replace(old,new,1)
io.open(p,'w',encoding='utf-8').write(s)
print("scope_writer flag cleared")
