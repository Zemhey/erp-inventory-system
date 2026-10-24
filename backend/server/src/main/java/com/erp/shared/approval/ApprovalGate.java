package com.erp.shared.approval;

/**
 * 单据审批的**查询/回调**契约（FR-IAM-07 / FR-APS-02）。
 *
 * <p><b>★ 为什么审批模块只输出"可否"，绝不改单据状态</b>（任务书坑 2、坑 3）：
 *
 * <ul>
 *   <li><b>两套状态机不能混。</b>单据有自己的七态（{@code DocumentStatus}，含 V8 的
 *       红冲补偿转移），审批有自己的七态（{@code ApprovalStatus}）。若审批通过时顺手
 *       执行 {@code UPDATE purchase_orders SET status='APPROVED'}，就等于在审批模块里
 *       复制了一份单据状态机 —— 而红冲规则、账期锁（{@code PeriodGuard}）、
 *       乐观锁 version 全都在单据侧，审批模块<b>无从</b>正确执行它们。
 *       症状会是"审批通过了但单据卡在 SUBMITTED"或"审批通过绕过了锁账"。</li>
 *   <li><b>依赖方向会成环。</b>审批要读单据金额，单据要问审批可否。若审批直接依赖
 *       {@code pur.repository}，而 PUR 又依赖 APS，就是编译能过、模块拆不开的包依赖环
 *       （与 {@code PeriodGuard} 的处境一模一样）。</li>
 * </ul>
 *
 * <p>因此：接口定义在 {@code shared}，单据侧只依赖接口；实现由 {@code aps} 提供。
 * 单据在 {@code approve()} 之前调 {@link ApprovalGate#requireApproved}，
 * 状态推进仍然由它自己调 {@code DocumentStatusMachine.requireTransition}。
 *
 * <pre>
 * PurchaseOrderApplicationService.approve(id)
 *   └─▶ ApprovalGate.requireApproved(PURCHASE_ORDER, id, orderNo, totalAmount)
 *         ├─ 无启用流程 / 无需审批 → 静默通过（存量 234 条用例因此零改动）
 *         ├─ 有流程且已有 APPROVED 实例 → 通过
 *         ├─ 有流程且实例 PENDING → 抛 APS_APPROVAL_PENDING（提示先去审批）
 *         └─ 有流程但无实例 → 抛 APS_APPROVAL_REQUIRED（提示先提交审批）
 *   └─▶ DocumentStatusMachine.requireTransition(status, APPROVED, ...)   ← 单据侧自己走
 * </pre>
 *
 * <p><b>为什么这些判定放在"单据审核"而不是"单据提交"</b>：本项目的
 * {@code submit} 是"提交给内部流程"，{@code approve} 才是真正的"负责人批准"。
 * 把审批挂到 {@code approve} 上，语义正好一一对应，且不改变
 * {@code DRAFT → SUBMITTED → APPROVED} 这条既有主线的可测性。
 */
public interface ApprovalGate {

    /**
     * 校验某张单据是否已被审批放行。
     *
     * @param bizType    单据类型
     * @param bizId      单据 id
     * @param bizRefNo   单据号（进错误消息，便于排障与测试断言）
     * @param amount     单据金额（分）。实现用它判断是否需要"金额阈值"节点
     * @throws com.erp.shared.error.BusinessException 需要审批但尚未通过
     */
    void requireApproved(BizType bizType, long bizId, String bizRefNo, long amount);

    /**
     * 审批结论（供单据侧在审批通过后同步自己的状态，或作废时取消审批）。
     *
     * @param bizType 单据类型
     * @param bizId   单据 id
     * @return 当前审批状态；无任何审批实例时返回 {@link ApprovalState#NOT_REQUIRED}
     */
    ApprovalState stateOf(BizType bizType, long bizId);

    /**
     * 单据被作废/红冲时取消其未结审批。
     *
     * <p>幂等：没有未结实例时静默返回。作废是单据侧的动作，
     * 审批侧只负责"别让一个已死单据的审批继续挂在待办列表里"。
     *
     * @return 是否真的取消了一个未结实例
     */
    boolean cancelOpen(BizType bizType, long bizId, String reason);

    /** 支持审批的单据类型（与 {@code approval_flows.biz_type} 的 CHECK 逐字一致）。 */
    enum BizType {
        PURCHASE_ORDER,
        SALES_ORDER,
        RETURN_ORDER
    }

    /** 审批对单据侧暴露的状态视图。 */
    enum ApprovalState {
        /** 该单据类型没有启用中的审批流 —— 不必审批（存量行为）。 */
        NOT_REQUIRED,
        /** 有流程，但还没提交审批。 */
        NOT_SUBMITTED,
        /** 已提交，等待审批（含已转办）。 */
        PENDING,
        /** 审批通过 —— 单据可以放行。 */
        APPROVED,
        /** 审批驳回 —— 单据需退回修改。 */
        REJECTED,
        /** 发起人撤回 / 单据取消。 */
        CLOSED
    }
}
