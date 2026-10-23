p='backend/server/src/test/java/com/erp/aps/ApprovalIT.java'
s=open(p,encoding='utf-8').read()

# 1) setUp must run as an authenticated actor: use asUser? But setUp happens before any user created... 
#    Easiest: give ApprovalIT a default actor for plain approvals.submit() calls.
#    Approach: add @BeforeEach set an ADMIN SecurityContext (persist across test) and tests that
#    need a specific identity override via asUser.
old="""    @BeforeEach
    void setUp() {
        data = new TestDataFactory(jdbc);
        data.truncateAll();"""
new="""    @BeforeEach
    void setUp() {
        // 清掉上一条用例可能残留的身份（asUser 的 finally 会恢复，但双保险）
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        data = new TestDataFactory(jdbc);
        data.truncateAll();"""
assert old in s
s=s.replace(old,new,1)

# 2) after users are created, install a default "system ADMIN" context so plain approvals.submit works
old2="""        jdbc.update("UPDATE approval_flows SET enabled = TRUE WHERE code IN "
                + "('PO-STD','SO-STD','RO-STD')");
    }"""
new2="""        jdbc.update("UPDATE approval_flows SET enabled = TRUE WHERE code IN "
                + "('PO-STD','SO-STD','RO-STD')");

        // ★ 默认身份：绝大多数用例调 approvals.submit/approve 时需要一个登录人。
        //   本类不用 MockMvc 的 token 链，而是直接把一个 ADMIN 放进 SecurityContext，
        //   由 asUser(...) 在需要"某个人"时覆盖它。
        //   （不这样做的话，每次都必须在 asUser 里包一层，
        //    而漏一处就得到"未登录或登录已过期"——第一版正是如此。）
        installDefaultActor();
    }

    /** 装一个 ADMIN 身份作为默认操作人。 */
    private void installDefaultActor() {
        invokeAs(adminId, () -> Boolean.TRUE);
    }"""
assert old2 in s
s=s.replace(old2,new2,1)
open(p,'w',encoding='utf-8').write(s)
print("default actor added")
