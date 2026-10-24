import io
p='backend/server/src/test/java/com/erp/support/TestDataFactory.java'
s=io.open(p,encoding='utf-8').read()
old="""        if (seedPermissionsSnapshot == null) {
            seedPermissionsSnapshot = jdbc.query(
                    "SELECT role_id, permission FROM app_role_permissions",
                    (rs, i) -> new long[]{rs.getLong("role_id")})
                    .isEmpty() ? java.util.List.of() : jdbc.query(
                    "SELECT role_id, permission FROM app_role_permissions",
                    (rs, i) -> new String[]{String.valueOf(rs.getLong("role_id")),
                            rs.getString("permission")});
            seedRoleCodes = jdbc.query("SELECT code FROM app_roles",
                    (rs, i) -> rs.getString("code"));
        }
        jdbc.update("DELETE FROM app_role_permissions");
        for (Object[] row : seedPermissionsSnapshot) {
            jdbc.update("INSERT INTO app_role_permissions (role_id, permission) VALUES (?, ?) "
                    + "ON CONFLICT DO NOTHING", Long.valueOf((String) row[0]), (String) row[1]);
        }"""
new="""        if (seedPermissionsSnapshot == null) {
            seedPermissionsSnapshot = jdbc.query(
                    "SELECT role_id, permission FROM app_role_permissions",
                    (rs, i) -> new Object[]{rs.getLong("role_id"), rs.getString("permission")});
            seedRoleCodes = jdbc.query("SELECT code FROM app_roles",
                    (rs, i) -> rs.getString("code"));
        }
        jdbc.update("DELETE FROM app_role_permissions");
        for (Object[] row : seedPermissionsSnapshot) {
            jdbc.update("INSERT INTO app_role_permissions (role_id, permission) VALUES (?, ?) "
                    + "ON CONFLICT DO NOTHING", (Long) row[0], (String) row[1]);
        }"""
assert old in s
s=s.replace(old,new,1)
io.open(p,'w',encoding='utf-8').write(s)
print("snapshot code simplified")
