p='backend/server/src/main/java/com/erp/pur/application/PurchaseOrderApplicationService.java'
s=open(p,encoding='utf-8').read()

# 1) imports
s=s.replace(
"import com.erp.shared.period.PeriodGuard;",
"import com.erp.shared.approval.ApprovalGate;\nimport com.erp.shared.period.PeriodGuard;\nimport com.erp.shared.security.ActorContext;",1)

# 2) fields
old_field="    private final PeriodGuard periodGuard;\n"
new_field="""    private final PeriodGuard periodGuard;
    /**
     * 审批闸门（V2-6 FR-IAM-07）。只依赖 {@code shared.approval} 的接口，实现由 APS 提供 ——
     * 若这里 import {@code com.erp.aps}，就会与「APS 只输出可否定、不碰单据仓储」形成依赖环。
     * 这条边界由 ArchUnit 的「aps 不得依赖单据模块仓储」规则反向守住。
     */
    private final ApprovalGate approvalGate;
    /** 当前操作人（数据权限"按本人"与审批发起人）。 */
    private final ActorContext actors;
"""
assert old_field in s
s=s.replace(old_field,new_field,1)

# 3) constructor params + assignments
old_ctor_tail="""                                           ClockProvider clock,
                                           PeriodGuard periodGuard) {"""
new_ctor_tail="""                                           ClockProvider clock,
                                           PeriodGuard periodGuard,
                                           ApprovalGate approvalGate,
                                           ActorContext actors) {"""
assert old_ctor_tail in s
s=s.replace(old_ctor_tail,new_ctor_tail,1)

old_assign="        this.periodGuard = periodGuard;\n    }"
new_assign="""        this.periodGuard = periodGuard;
        this.approvalGate = approvalGate;
        this.actors = actors;
    }"""
assert old_assign in s
s=s.replace(old_assign,new_assign,1)

# 4) approve() -> gate
old_approve="""    public PurchaseOrder approve(long id) {
        PurchaseOrder o = require(id);
        periodGuard.requireOpen(o.bizDate());
        DocumentStatusMachine.requireTransition(o.status(), DocumentStatus.APPROVED,"""
new_approve="""    public PurchaseOrder approve(long id) {
        PurchaseOrder o = require(id);
        periodGuard.requireOpen(o.bizDate());
        // ★ 审批闸门：无启用流程时静默通过（存量行为不变）；有流程时要求审批已通过。
        //   注意这里只"问一句"，不代审批改单据状态 —— 状态推进仍在下一行由单据侧自己走。
        approvalGate.requireApproved(ApprovalGate.BizType.PURCHASE_ORDER, o.id(),
                o.orderNo(), o.totalAmount());
        DocumentStatusMachine.requireTransition(o.status(), DocumentStatus.APPROVED,"""
assert old_approve in s
s=s.replace(old_approve,new_approve,1)

open(p,'w',encoding='utf-8').write(s)
print("patched PO service")
