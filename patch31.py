p='backend/server/src/test/java/com/erp/aps/ApprovalIT.java'
s=open(p,encoding='utf-8').read()
# TC-APS-024 asserts message contains PO-GATE-1 but we passed remark as create marker; biz_ref_no is order_no
old="""        assertThatThrownBy(() -> purchaseOrders.approve(poId))
                .as("★ 配了启用流程后，未提交审批的单据不能直接审核")
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.APS_APPROVAL_REQUIRED)
                .hasMessageContaining("PO-GATE-1");"""
new="""        String orderNo = jdbc.queryForObject(
                "SELECT order_no FROM purchase_orders WHERE id = ?", String.class, poId);
        assertThatThrownBy(() -> purchaseOrders.approve(poId))
                .as("★ 配了启用流程后，未提交审批的单据不能直接审核")
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.APS_APPROVAL_REQUIRED)
                .hasMessageContaining(orderNo);"""
assert old in s
s=s.replace(old,new,1)

# withdraw test: submit as purchaserId but purchaser lacks aps:submit? writer role has aps:submit, ok.
# issue: "审批流 PO-STD 在金额 10000 下没有任何可执行节点" -> flow steps lost because createFlow disabled PO-STD
# and then re-enabled? No: truncateAll resets enabled FALSE for ALL flows. setUp re-enables PO/SO/RO. Then
# TC-APS-019 submit 10000 -> should be fine once asUser fixed (purchaser created instance).
# Actually error came from submit with PO-STD steps... steps exist. Wait: error says no executable node at 10000
# because step1 threshold 0 should apply. Unless approval_steps got truncated: we DELETE steps with instance_id NOT NULL only.
# But `TRUNCATE ... approval_steps` was removed... we still truncate approval_instances, cascading to steps? 
# approval_steps has FK instance_id -> instances ON DELETE CASCADE, truncate instances CASCADE would remove instance steps only.
# Hmm but TRUNCATE approval_instances CASCADE also truncates approval_steps entirely (CASCADE truncates referencing tables).
# -> definition steps (instance_id NULL) get wiped! Need to also re-seed definition steps, or avoid truncating instances.
old2="""        jdbc.update("UPDATE approval_flows SET enabled = FALSE");
        jdbc.update("DELETE FROM approval_steps WHERE instance_id IS NOT NULL");"""
new2="""        jdbc.update("UPDATE approval_flows SET enabled = FALSE");
        // ★ 不能 TRUNCATE approval_instances：CASCADE 会把 approval_steps 整表清掉，
        //   连"定义节点"（instance_id IS NULL）一起消失，于是后续用例看到
        //   "流程存在但没有任何节点"。用 DELETE 只清实例，定义节点自然保留。
        jdbc.update("DELETE FROM approval_steps WHERE instance_id IS NOT NULL");
        jdbc.update("DELETE FROM approval_instances");"""
assert old2 in s
s=s.replace(old2,new2,1)
open(p,'w',encoding='utf-8').write(s)
print("fixed step wipe + message assertion")
