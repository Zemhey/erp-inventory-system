p='backend/server/src/main/java/com/erp/aps/application/ApprovalApplicationService.java'
s=open(p,encoding='utf-8').read()
old="""        if (next.isPresent()) {
            int nextNo = next.get().stepNo();
            ApprovalStatusMachine.requireTransition(instance.status(), ApprovalStatus.PENDING,
                    "instance-" + instanceId);
            if (!instances.updateStatus(instanceId, ApprovalStatus.PENDING, ApprovalStatus.PENDING,
                    nextNo, instance.version())) {"""
new="""        if (next.isPresent()) {
            int nextNo = next.get().stepNo();
            // ★ 这里**刻意不调** ApprovalStatusMachine.requireTransition(PENDING, PENDING)：
            //   审批状态机把自环定义为非法（"原地不动"没有业务语义），
            //   而"推进到下一节点"根本不是状态变化 —— 状态仍是 PENDING，
            //   变的只是 current_step 指针。把指针移动硬塞进状态转移校验，
            //   会得到"PENDING 不能变更为 PENDING"这种自相矛盾的报错
            //   （第一版就是这样，7 条用例全挂在同一句上）。
            //   真正的状态合法性由"当前必须在途（PENDING/TRANSFERRED）"在
            //   requireInFlight 里保证。
            if (!instances.updateStatus(instanceId, ApprovalStatus.PENDING, ApprovalStatus.PENDING,
                    nextNo, instance.version())) {"""
assert old in s
s=s.replace(old,new,1)
open(p,'w',encoding='utf-8').write(s)
print("step advance fixed")
