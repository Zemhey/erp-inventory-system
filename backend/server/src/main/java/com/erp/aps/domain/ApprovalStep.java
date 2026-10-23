package com.erp.aps.domain;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 审批节点（定义态与实例快照态共用一个类型，FR-APS-01/02）。
 *
 * <p><b>为什么定义与实例同类型</b>：两者的字段集合完全一致（名称、权限码、会签模式、
 * 阈值）。分成 {@code StepDefinition} 与 {@code StepSnapshot} 两个 record 只会迫使我们
 * 在实例化时写一遍逐字段拷贝，而那份拷贝每加一个字段就要改一次 —— 漏改的症状是
 * "快照里少了阈值，实例化后金额判定失效"，很难在评审时看出来。
 * 两者真正需要区分的是<b>生命周期</b>（{@code instanceId} 是否存在、{@code status} 取值域），
 * 那由 {@link StepStatus} 与数据库 CHECK 表达。
 *
 * @param id                 主键
 * @param flowId             所属流程
 * @param instanceId         实例 id；{@code null} 表示这是流程定义节点
 * @param stepNo             节点序号，从 1 开始
 * @param name               节点名
 * @param approverPermission 审批人必须具备的权限码（与 {@code @RequirePermission} 同一套字符串）
 * @param approveMode        会签模式
 * @param requiredApprovals  需要的同意人数（{@code ALL_SIGN} 时 >1 才有意义）
 * @param thresholdAmount    金额阈值（分）：实例金额 ≥ 本值才进入该节点；0 = 必经
 * @param status             节点状态
 * @param approvedBy         已同意的审批人 id（去重、有序）
 * @param decidedAt          结论时刻
 * @param decisionBy         做出结论的人（驳回时是关键信息，同意时会签情形下为最后一人）
 * @param remark             审批意见
 */
public record ApprovalStep(long id, long flowId, Long instanceId, int stepNo, String name,
                           String approverPermission, ApproveMode approveMode,
                           int requiredApprovals, long thresholdAmount, StepStatus status,
                           List<Long> approvedBy, OffsetDateTime decidedAt, Long decisionBy,
                           String remark) {

    public ApprovalStep {
        approvedBy = approvedBy == null ? List.of() : List.copyOf(approvedBy);
    }

    /** 节点是否在没有实例的情况下被阈值跳过（定义态不适用）。 */
    public boolean isSkipped() {
        return status == StepStatus.SKIPPED;
    }

    /** 会签模式（与 {@code approval_steps.approve_mode} 的 CHECK 逐字一致）。 */
    public enum ApproveMode {
        /** 单人审批：第一个人同意即通过。 */
        SINGLE,
        /** 会签：{@code requiredApprovals} 人全部同意才通过。 */
        ALL_SIGN,
        /** 或签：任一人同意即通过。 */
        ANY_SIGN
    }

    /** 节点状态。 */
    public enum StepStatus {
        /** 定义态：流程定义里的一行，还没有被任何实例使用。 */
        DEFINED,
        /** 等待处理。 */
        PENDING,
        /** 已同意。 */
        APPROVED,
        /** 已驳回。 */
        REJECTED,
        /** 已跳过：实例金额未达到该节点阈值，实例化时就不生成该行 —— 
         * 但为了"哪些节点被跳过"这件事可追溯，实例化后会留一条 SKIPPED 快照。 */
        SKIPPED
    }

    /**
     * 这个节点在给定金额下是否进入。
     *
     * <p>阈值为 0 表示无条件必经；这是"金额超阈值才触发上级"（FR-IAM-07）的判定点，
     * 也是 {@code approval_steps.threshold_amount} 存在的唯一理由。
     */
    public boolean appliesTo(long instanceAmount) {
        return instanceAmount >= thresholdAmount;
    }

    /**
     * 该节点在已同意 {@code approvals} 人后是否算通过。
     *
     * <p>三种模式的差异只在这里：
     * <ul>
     *   <li>{@code SINGLE} / {@code ANY_SIGN} —— 一人同意即通过（{@code requiredApprovals}
     *       在 SINGLE 下恒为 1；ANY_SIGN 允许配置 >1 但仍按"任一人"处理，
     *       因为"或签"的语义就是"任意一个即可"，配置值只作展示）；</li>
     *   <li>{@code ALL_SIGN} —— 必须凑够 {@code requiredApprovals} 个<b>不同</b>的人。</li>
     * </ul>
     *
     * <p>去重已经在 {@code approvedBy} 里保证（数据库用 int8[] + 数组包含判断），
     * 所以这里直接看 size —— 但<b>不</b>信任调用方传入的重复计数。
     */
    public boolean isSatisfiedBy(List<Long> approvals) {
        if (status == StepStatus.APPROVED || status == StepStatus.SKIPPED) {
            return true;
        }
        if (approvals == null || approvals.isEmpty()) {
            return false;
        }
        long distinct = approvals.stream().distinct().count();
        return switch (approveMode) {
            case SINGLE, ANY_SIGN -> distinct >= 1;
            case ALL_SIGN -> distinct >= requiredApprovals;
        };
    }
}
