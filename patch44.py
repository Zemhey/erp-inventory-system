p='backend/server/src/test/java/com/erp/aps/ApprovalIT.java'
s=open(p,encoding='utf-8').read()

# add adminId field + create admin user in setUp
old="""    /** 普通采购员：只有 aps:submit / aps:read，无 aps:approve。 */
    private long purchaserId;"""
new="""    /** 普通采购员：只有 aps:submit / aps:read，无 aps:approve。 */
    private long purchaserId;
    /** 默认操作人（ADMIN）：用于"但凡需要一个人"的调用，避免每条用例都包 asUser。 */
    private long adminId;"""
assert old in s
s=s.replace(old,new,1)

old2="""        adminId = data.user("aps_admin", "Ap5!admin", "ADMIN", encoder);"""
# insert after users creation line
anchor="""        users.clearRoles(purchaserId);
        users.assignRoleById(purchaserId, writerId);"""
new2="""        users.clearRoles(purchaserId);
        users.assignRoleById(purchaserId, writerId);

        adminId = data.user("aps_admin", "Ap5!admin", "ADMIN", encoder);"""
assert anchor in s
s=s.replace(anchor,new2,1)

# persisted context: invokeAs restores previous, so installDefaultActor won't persist...
# So instead set context WITHOUT restore in setUp.
old3="""    /** 装一个 ADMIN 身份作为默认操作人。 */
    private void installDefaultActor() {
        invokeAs(adminId, () -> Boolean.TRUE);
    }"""
new3="""    /**
     * 装一个 ADMIN 身份作为默认操作人。
     *
     * <p><b>刻意不复用 {@link #invokeAs}</b>：那个方法会在 finally 里恢复原身份，
     * 装完就没了。这里要的是"整个用例期间都在"，所以直接 set 不恢复，
     * 由下一条用例的 {@code clearContext()} 负责清。
     */
    private void installDefaultActor() {
        var user = users.findById(adminId).orElseThrow();
        var authorities = new java.util.ArrayList<
                org.springframework.security.core.GrantedAuthority>();
        user.roles().forEach(r -> authorities.add(
                new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + r)));
        user.permissions().forEach(p -> authorities.add(
                new org.springframework.security.core.authority.SimpleGrantedAuthority("PERM_" + p)));
        org.springframework.security.core.context.SecurityContextHolder.getContext()
                .setAuthentication(
                        new org.springframework.security.authentication
                                .UsernamePasswordAuthenticationToken(
                                user.username(), null, authorities));
    }"""
assert old3 in s
s=s.replace(old3,new3,1)
open(p,'w',encoding='utf-8').write(s)
print("adminId + persistent actor")
