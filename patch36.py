p='backend/server/src/test/java/com/erp/aps/ApprovalIT.java'
s=open(p,encoding='utf-8').read()
# 1) asUser must run body INSIDE authenticated context (fix double wrap)
old="""    private void asUser(long userId, Runnable body) {
        invokeAs(userId, () -> {
            body.run();
            return Boolean.TRUE;
        });
    }"""
new="""    private void asUser(long userId, Runnable body) {
        // ★ 直接在 try 内执行 body（invokeAs 内部就是 try/finally）——
        //   不能写成 invokeAs(id, () -> { body.run(); return null; }) 之外再包一层，
        //   否则恢复上下文发生在 body 之前（第一次实测时 16 条用例全报"未登录"）。
        invokeAs(userId, () -> {
            body.run();
            return Boolean.TRUE;
        });
    }"""
assert old in s
s=s.replace(old,new,1)

# make invokeAs the only place that sets/restores
old2="""    private <T> T invokeAs(long userId, java.util.function.Supplier<T> body) {
        var user = users.findById(userId).orElseThrow();"""
new2="""    private <T> T invokeAs(long userId, java.util.function.Supplier<T> body) {
        var user = users.findById(userId).orElseThrow();"""
assert old2 in s
print("checked invokeAs exists")

# 2) TC-APS-019 uses purchaser (aps:submit) — ensure APS_SUBMITTER role has aps:submit (it does)
# 3) TC-APS-024 message: use orderNo from DB (already patched? check)
if 'hasMessageContaining("PO-GATE-1")' in s:
    s=s.replace("""                .hasFieldOrPropertyWithValue("code", ErrorCode.APS_APPROVAL_REQUIRED)
                .hasMessageContaining("PO-GATE-1");""",
"""                .hasFieldOrPropertyWithValue("code", ErrorCode.APS_APPROVAL_REQUIRED)
                .hasMessageContaining(orderNo);""")
    print("message assertion patched")
open(p,'w',encoding='utf-8').write(s)
print("done")
