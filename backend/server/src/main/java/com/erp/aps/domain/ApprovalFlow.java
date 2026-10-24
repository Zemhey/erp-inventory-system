package com.erp.aps.domain;

import java.util.List;

/**
 * 审批流定义（FR-APS-01）：一种单据类型的一条流程 = 头 + 有序节点。
 *
 * <p><b>只做"单据审批"这一种流程</b>（蓝图 §0.3 明确砍掉通用 BPMN）：
 * 没有并行网关、没有子流程、没有条件表达式语言。节点是<b>线性有序</b>的，
 * 唯一的"条件"是金额阈值（{@code thresholdAmount}）—— 这是 §6.9 给
 * FR-IAM-07「多级审批授权（金额阈值触发上级）」划定的范围。
 *
 * <p>刻意不做的事，都记在这里，免得后来者以为是遗漏：
 * <ul>
 *   <li>不做图形化流程设计器 —— 节点用接口维护，界面只做只读展示 + 启停；</li>
 *   <li>不做"按部门/角色动态选审批人"—— 审批人由<b>权限码</b>决定，
 *       与 RBAC 同一套字符串，不引入第二套主体体系；</li>
 *   <li>不做超时自动通过/自动驳回 —— 那需要调度与通知，属 V2-7 之后。</li>
 * </ul>
 *
 * @param id          主键
 * @param code        流程编码（唯一）
 * @param name        流程名
 * @param bizType     适用单据类型
 * @param description 说明
 * @param enabled     是否启用；同一 {@code bizType} 最多一条启用流（数据库部分唯一索引兜底）
 * @param steps       定义节点（按 step_no 升序；仅 {@code instanceId == null} 的行）
 */
public record ApprovalFlow(long id, String code, String name, String bizType,
                           String description, boolean enabled, List<ApprovalStep> steps) {

    public ApprovalFlow {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    /**
     * 按给定金额挑出本次要经过的节点。
     *
     * <p>阈值语义：{@code amount >= thresholdAmount} 才进入该节点。
     * 第一个节点的阈值恒为 0（无条件必经），所以任何金额都至少走一个节点 ——
     * 若配置成"所有节点都被跳过"，那等价于"这个流程形同虚设"，属配置错误，
     * 由 {@link #validate()} 在保存时拒绝，而不是在提交审批时才暴露。
     */
    public List<ApprovalStep> stepsFor(long amount) {
        return steps.stream().filter(s -> s.appliesTo(amount)).toList();
    }

    /**
     * 配置合法性自检。
     *
     * <p>返回错误消息（空 = 合法）。刻意返回消息而不是抛异常：
     * 这条检查同时被"保存流程"（要转成 400）与"启动期种子校验"使用，
     * 两种场景的异常包装不同，但判断规则必须只有一份。
     */
    public java.util.Optional<String> validate() {
        if (steps.isEmpty()) {
            return java.util.Optional.of("审批流 %s 没有任何节点".formatted(code));
        }
        int expected = 1;
        for (ApprovalStep s : steps) {
            if (s.stepNo() != expected) {
                return java.util.Optional.of("审批流 %s 的节点序号必须从 1 连续递增，"
                        .formatted(code) + "实际第 %d 个节点是 %d".formatted(expected, s.stepNo()));
            }
            if (s.thresholdAmount() > 0 && s.stepNo() == 1) {
                return java.util.Optional.of("审批流 %s 的首节点阈值必须为 0："
                        .formatted(code) + "否则小额单据将无任何节点可走");
            }
            expected++;
        }
        return java.util.Optional.empty();
    }
}
