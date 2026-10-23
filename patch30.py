p='backend/server/src/test/java/com/erp/aps/ApprovalIT.java'
s=open(p,encoding='utf-8').read()
old="""    /** 以某人的身份执行一段需要 {@code ActorContext} 的逻辑（伪造 SecurityContext）。 */
    private void asUser(long userId, Runnable body) {
        asUser(userId, () -> {
            body.run();
            return null;
        });
    }"""
new="""    /**
     * 以某人的身份执行一段需要 {@code ActorContext} 的逻辑（伪造 SecurityContext）。
     *
     * <p><b>★ 这里刻意不写成 {@code asUser(id, () -> { body.run(); return null; })}</b>：
     * 那样会先把 {@code SecurityContext} 恢复成"进入前"（也就是空），
     * 然后再执行 {@code body.run()} —— 于是所有用例都报
     * "未登录或登录已过期，请重新登录"。
     * 正确做法是<b>在 try 块内部</b>执行 body，让恢复发生在 body 之后。
     * （这个缺陷在第一次跑测试时就真实发生了，16 条用例同一句错误信息。）
     */
    private void asUser(long userId, Runnable body) {
        invokeAs(userId, () -> {
            body.run();
            return Boolean.TRUE;
        });
    }"""
assert old in s
s=s.replace(old,new,1)

old2="""    private <T> T asUser(long userId, java.util.function.Supplier<T> body) {"""
new2="""    private <T> T asUser(long userId, java.util.function.Supplier<T> body) {
        return invokeAs(userId, body);
    }

    private <T> T invokeAs(long userId, java.util.function.Supplier<T> body) {"""
assert old2 in s
s=s.replace(old2,new2,1)

# also fix TC-APS-026 flow toggle conflict and TC-APS-024 message assertion
old3="""    void withoutFlow_documentApprovalIsUnchanged() {
        jdbc.update("UPDATE approval_flows SET enabled = FALSE WHERE biz_type = 'PURCHASE_ORDER'");
        long poId = createPurchaseOrder(10_000, "PO-NOFLOW");
        purchaseOrders.submit(poId);
        assertThat(purchaseOrders.approve(poId).status())
                .as("★ 关闭流程后必须回到 V2-6 之前的粗放行为 —— 存量 251 条用例依赖它")
                .isEqualTo(com.erp.shared.document.DocumentStatus.APPROVED);
        jdbc.update("UPDATE approval_flows SET enabled = TRUE WHERE biz_type = 'PURCHASE_ORDER'");
    }"""
new3="""    void withoutFlow_documentApprovalIsUnchanged() {
        jdbc.update("UPDATE approval_flows SET enabled = FALSE WHERE biz_type = 'PURCHASE_ORDER'");
        long poId = createPurchaseOrder(10_000, "PO-NOFLOW");
        purchaseOrders.submit(poId);
        assertThat(purchaseOrders.approve(poId).status())
                .as("★ 关闭流程后必须回到 V2-6 之前的粗放行为 —— 存量 251 条用例依赖它")
                .isEqualTo(com.erp.shared.document.DocumentStatus.APPROVED);
        // 不在用例里恢复启用态：TestDataFactory.truncateAll 每条用例前统一复位成
        // "全部停用"，在这里再 enable 会撞 uq_approval_flow_enabled_biz（同类型只剩一条槽位）。
    }"""
assert old3 in s
s=s.replace(old3,new3,1)

# TC-APS-023 similar restore
old4="""        jdbc.update("UPDATE approval_flows SET enabled = TRUE WHERE biz_type = 'SALES_ORDER'");
    }"""
new4="""        // 不恢复启用态：truncateAll 会在下一条用例前统一复位（见上一条用例的注释）
    }"""
assert old4 in s
s=s.replace(old4,new4,1)

# createFlow: must disable any enabled flow of that biz type BEFORE insert to satisfy partial unique index
old5="""        // 同类型只能有一条启用流：先停用默认的
        jdbc.update("UPDATE approval_flows SET enabled = FALSE WHERE biz_type = ? AND enabled",
                bizType);"""
new5="""        // 同类型只能有一条启用流（uq_approval_flow_enabled_biz）：先停掉默认的，
        // 否则下面这条 INSERT 会直接撞部分唯一索引。
        jdbc.update("UPDATE approval_flows SET enabled = FALSE WHERE biz_type = ? AND enabled",
                bizType);"""
assert old5 in s
s=s.replace(old5,new5,1)
open(p,'w',encoding='utf-8').write(s)
print("ApprovalIT asUser fixed")
