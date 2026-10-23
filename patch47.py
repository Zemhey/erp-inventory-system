p='backend/server/src/test/java/com/erp/aps/ApprovalIT.java'
s=open(p,encoding='utf-8').read()

# ALL_SIGN: all three approvers (supervisor/finance/third-ADMIN) must share the node permission.
# supervisor has pur:approve; finance has aps:approve; ADMIN has '*'. Use aps:approve and give supervisor it.
s=s.replace("""        long flowId = createFlow("ALLSIGN-FLOW", "PURCHASE_ORDER", "ALL_SIGN", 3, 0, "pur:approve");""",
"""        // 会签要求"三个不同的人都具备该节点的权限"，因此节点权限必须选三人共有的那个。
        // supervisor 的 PUR_APPROVER 角色里补上 aps:approve，才能作为会签人之一。
        long approveOnly = roles.findByCode("PUR_APPROVER").orElseThrow().id();
        roles.replacePermissions(approveOnly, List.of("mdm:read", "pur:read", "pur:approve",
                "aps:submit", "aps:read", "aps:approve", "iam:read"));
        long flowId = createFlow("ALLSIGN-FLOW", "PURCHASE_ORDER", "ALL_SIGN", 3, 0, "aps:approve");""")

# ANY_SIGN: supervisor lacks aps:approve in default seed; use pur:approve + SALES? supervisor is PURCHASER.
s=s.replace("""        long flowId = createFlow("ANYSIGN-FLOW", "SALES_ORDER", "ANY_SIGN", 2, 0, "aps:approve");""",
"""        long flowId = createFlow("ANYSIGN-FLOW", "SALES_ORDER", "ANY_SIGN", 2, 0, "pur:approve");""")

# transfer target: otherSupervisor got PUR_APPROVER via assignRoleById, but the predicate checks aps:approve.
# Keep pur:approve-based node but predicate uses aps:approve -> align predicate to node needs.
s=s.replace("""                    "我出差，请你审", target -> users.findById(target)
                            .map(u -> u.hasPermission("aps:approve")).orElse(false));""",
"""                    "我出差，请你审", target -> users.findById(target)
                            .map(u -> u.hasPermission("pur:approve")).orElse(false));""")
s=s.replace("""                () -> approvals.transfer(another.id(), purchaserId, "给你", target ->
                        users.findById(target).map(u -> u.hasPermission("aps:approve"))
                                .orElse(false))))""",
"""                () -> approvals.transfer(another.id(), purchaserId, "给你", target ->
                        users.findById(target).map(u -> u.hasPermission("pur:approve"))
                                .orElse(false))))""")
# otherSupervisor must actually have pur:approve -> give via PUR_APPROVER (already assigned). OK.

# list/detail 401: default actor makes MockMvc authenticated even without header? No—MockMvc builds its own
# SecurityContext per request via filters; but SecurityContextHolder is thread-local and MockMvc reuses the thread,
# so a pre-set authentication leaks in. Clear it around that assertion.
s=s.replace("""        mvc.perform(get("/api/v1/approvals/" + instance.id()))
                .andExpect(status().isUnauthorized());""",
"""        // ★ MockMvc 与用例共用同一个线程：SecurityContextHolder 里预置的默认身份会"泄漏"进
        //   无 token 的请求，于是本该 401 的断言变成 200。断言无身份时必须先清上下文。
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        mvc.perform(get("/api/v1/approvals/" + instance.id()))
                .andExpect(status().isUnauthorized());
        installDefaultActor();""")
open(p,'w',encoding='utf-8').write(s)
print("approval test fixes applied")
