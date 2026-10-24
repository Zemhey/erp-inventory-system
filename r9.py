import io
p='backend/server/src/test/java/com/erp/support/TestDataFactory.java'
s=io.open(p,encoding='utf-8').read()

start=s.index('    public void restoreSeedRolePermissions() {')
end=s.index('    /**\n     * 建测试账号并赋予角色。')
new='''    public void restoreSeedRolePermissions() {
        // ★★ 为什么"自动快照"而不是"手抄一份种子清单"：
        //
        //   角色权限是**跨泳道共享的配置**：V3 种 mdm/pur/sal/inv、V5 种 set/rpt/FINANCE、
        //   V7 给 FINANCE 补 mdm:read、V13 给 VIEWER 种 sys:read、V2-2 种 pur:payable、
        //   V2-4 种 cst:read、V14 种 iam:*/aps:*……
        //   手抄一份清单的后果是：**每来一条并行泳道，这份清单就悄悄过期一次**，
        //   而症状是"别人刚种好的权限在测试里凭空消失，一堆 403 看起来像自己的 bug"。
        //   （实测：抄写的清单漏了 V13 的 sys:read 与 V2-4 的 cst:read，
        //     AuditQueryIT / CostApiIT / ReturnOrderApiIT 一起变红。）
        //
        //   正确做法：第一次调用时把**迁移后的真实种子**拍成快照（进程内静态），
        //   之后每条用例都从快照精确恢复。新增迁移时这份快照自动跟上，无需维护。
        if (seedPermissionsSnapshot == null) {
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
        }
        // 用例临时建的角色（不在种子里的）删掉，避免 uq_app_role_code 冲突。
        // 注意这里用**快照到的编码清单**，而不是硬编码的四个/五个名字。
        if (!seedRoleCodes.isEmpty()) {
            String placeholders = String.join(", ",
                    java.util.Collections.nCopies(seedRoleCodes.size(), "?"));
            jdbc.update("DELETE FROM app_roles WHERE code NOT IN (" + placeholders + ")",
                    seedRoleCodes.toArray());
        }
    }

'''
s = s[:start] + new + s[end:]

# add static snapshot fields right after class declaration
anchor = "    private static final AtomicInteger SEQ = new AtomicInteger(1);"
assert anchor in s
s = s.replace(anchor, anchor + '''

    /**
     * 迁移种下的「角色 → 权限」快照（进程内只拍一次）。
     *
     * <p>见 {@link #restoreSeedRolePermissions()} 的说明：跨泳道共享的配置不能手抄清单。
     */
    private static java.util.List<Object[]> seedPermissionsSnapshot;
    /** 迁移种下的角色编码清单（同上）。 */
    private static java.util.List<String> seedRoleCodes = java.util.List.of();''', 1)

io.open(p,'w',encoding='utf-8').write(s)
print("restore via snapshot")
