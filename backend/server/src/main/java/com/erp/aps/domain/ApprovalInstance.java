package com.erp.aps.domain;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 审批实例（FR-APS-02）。
 *
 * @param id          主键
 * @param flowId      所属流程定义
 * @param bizType     单据类型（{@code PURCHASE_ORDER} 等，与 {@code approval_flows.biz_type} 同集合）
 * @param bizId       单据 id —— <b>只存 id，不 join 单据表</b>（见类注释）
 * @param bizRefNo    单据号（冗余，便于按单号直接查审批而不跨模块）
 * @param amount      单据金额（分）。阈值节点据此决定是否实例化
 * @param currentStep 当前节点序号
 * @param status      审批状态
 * @param initiatorId 发起人
 * @param version     乐观锁版本（任何状态推进都必须带它）
 * @param steps       实例快照节点（按 step_no 升序）
 *
 * <p><b>★ 为什么只存 bizId/bizRefNo，而不把单据实体挂进来</b>：
 * 任务书要求 ArchUnit 新增一条规则「{@code aps} 不得依赖单据模块的 repository」。
 * 这条规则的<b>精神</b>是：审批只管"这次审批走完了没有"，不替单据做业务判断。
 * 因此本类连 {@code PurchaseOrder} 这个领域类型都不引用 —— 单据侧通过
 * {@code shared.approval.ApprovalGate} 把自己的 id、单号、金额<b>推</b>进来。
 * 依赖方向就此单向：单据 → 审批接口，审批不知道任何单据类型的存在。
 */
public record ApprovalInstance(long id, long flowId, String bizType, long bizId,
                               String bizRefNo, long amount, int currentStep,
                               ApprovalStatus status, long initiatorId, int version,
                               List<ApprovalStep> steps) {

    public ApprovalInstance {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    /** 当前正在等待处理的节点；没有（终态或节点全走完时）为空。 */
    public java.util.Optional<ApprovalStep> currentNode() {
        return steps.stream()
                .filter(s -> s.stepNo() == currentStep)
                .filter(s -> s.status() == ApprovalStep.StepStatus.PENDING
                        || s.status() == ApprovalStep.StepStatus.DEFINED)
                .findFirst();
    }

    /** 是否在审批中（占用"一张单据一个未结实例"的槽位）。 */
    public boolean isInFlight() {
        return status.isInFlight();
    }
}
