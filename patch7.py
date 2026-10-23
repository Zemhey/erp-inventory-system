p='backend/server/src/main/java/com/erp/sal/application/SalesOrderApplicationService.java'
s=open(p,encoding='utf-8').read()

old="""                totalAmount, totalQty, periodOf(bizDate), trimToNull(cmd.remark()), cmd.requestId());"""
new="""                totalAmount, totalQty, periodOf(bizDate), trimToNull(cmd.remark()), cmd.requestId(),
                actors.current().map(com.erp.shared.security.ActorContext.Actor::id).orElse(null));"""
assert old in s
s=s.replace(old,new,1)

old_appr="""    public SalesOrder approve(long id) {
        SalesOrder o = require(id);
        periodGuard.requireOpen(o.bizDate());
        DocumentStatusMachine.requireTransition(o.status(), DocumentStatus.APPROVED,"""
new_appr="""    public SalesOrder approve(long id) {
        SalesOrder o = require(id);
        periodGuard.requireOpen(o.bizDate());
        // ★ 审批闸门：无启用流程时静默通过；有流程时要求审批已通过。不代审批改状态。
        approvalGate.requireApproved(ApprovalGate.BizType.SALES_ORDER, o.id(),
                o.orderNo(), o.totalAmount());
        DocumentStatusMachine.requireTransition(o.status(), DocumentStatus.APPROVED,"""
assert old_appr in s
s=s.replace(old_appr,new_appr,1)
open(p,'w',encoding='utf-8').write(s)
print("patched SO service body")
