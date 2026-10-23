p='backend/server/src/main/java/com/erp/rma/application/ReturnOrderApplicationService.java'
s=open(p,encoding='utf-8').read()

s=s.replace("import com.erp.shared.period.PeriodGuard;",
"import com.erp.shared.approval.ApprovalGate;\nimport com.erp.shared.period.PeriodGuard;\nimport com.erp.shared.security.ActorContext;",1)

old="    private final PeriodGuard periodGuard;\n"
new="""    private final PeriodGuard periodGuard;
    /** 审批闸门（V2-6 FR-IAM-07）：只输出可否，不改单据状态。 */
    private final ApprovalGate approvalGate;
    /** 当前操作人（数据权限"按本人"）。 */
    private final ActorContext actors;
"""
assert old in s
s=s.replace(old,new,1)

old_ctor="""                                           PeriodGuard periodGuard) {"""
new_ctor="""                                           PeriodGuard periodGuard,
                                           ApprovalGate approvalGate,
                                           ActorContext actors) {"""
assert old_ctor in s
s=s.replace(old_ctor,new_ctor,1)

old_assign="        this.periodGuard = periodGuard;\n    }"
new_assign="""        this.periodGuard = periodGuard;
        this.approvalGate = approvalGate;
        this.actors = actors;
    }"""
assert old_assign in s
s=s.replace(old_assign,new_assign,1)

old_ins="""                trimToNull(cmd.remark()), cmd.requestId());"""
new_ins="""                trimToNull(cmd.remark()), cmd.requestId(),
                actors.current().map(com.erp.shared.security.ActorContext.Actor::id).orElse(null));"""
assert old_ins in s
s=s.replace(old_ins,new_ins,1)

old_appr="""    public ReturnOrder approve(long id) {
        ReturnOrder o = require(id);
        periodGuard.requireOpen(o.bizDate());
        DocumentStatusMachine.requireTransition(o.status(), DocumentStatus.APPROVED,"""
new_appr="""    public ReturnOrder approve(long id) {
        ReturnOrder o = require(id);
        periodGuard.requireOpen(o.bizDate());
        // ★ 审批闸门：无启用流程时静默通过；有流程时要求审批已通过。不代审批改状态。
        approvalGate.requireApproved(ApprovalGate.BizType.RETURN_ORDER, o.id(),
                o.orderNo(), o.totalAmount());
        DocumentStatusMachine.requireTransition(o.status(), DocumentStatus.APPROVED,"""
assert old_appr in s
s=s.replace(old_appr,new_appr,1)
open(p,'w',encoding='utf-8').write(s)
print("patched RMA service")
