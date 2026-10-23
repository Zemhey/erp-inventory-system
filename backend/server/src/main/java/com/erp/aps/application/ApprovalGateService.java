package com.erp.aps.application;

import com.erp.aps.domain.ApprovalFlow;
import com.erp.aps.domain.ApprovalInstance;
import com.erp.aps.domain.ApprovalStatus;
import com.erp.shared.approval.ApprovalGate;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ApprovalGate} 的实现 —— 单据侧唯一的审批入口（FR-IAM-07）。
 *
 * <p><b>★ 这个类的存在就是为了让单据侧"问一句"而不"依赖"</b>：
 * 单据模块只 import {@code shared.approval.ApprovalGate}，
 * 不知道 {@code com.erp.aps} 的存在。依赖方向单向：
 * {@code pur/sal/rma → shared.approval → (实现) aps}。
 *
 * <p><b>三种放行/拒绝的判定</b>（这是"金额阈值触发上级"与存量系统的接缝）：
 * <table border="1">
 *   <caption>判定表</caption>
 *   <tr><th>情形</th><th>结论</th><th>为什么</th></tr>
 *   <tr><td>该单据类型没有启用流程</td><td>放行</td>
 *       <td>存量行为不变：V1~V8 的用例与生产数据都没配审批流，
 *           引入审批不能把老流程锁死</td></tr>
 *   <tr><td>最近实例是 APPROVED</td><td>放行</td><td>审批已通过</td></tr>
 *   <tr><td>有未结实例（DRAFT/PENDING/TRANSFERRED）</td><td>拒 {@code APS_APPROVAL_PENDING}</td>
 *       <td>审批在途，单据不能先斩后奏</td></tr>
 *   <tr><td>有流程但从未提交审批</td><td>拒 {@code APS_APPROVAL_REQUIRED}</td>
 *       <td>配了流程就等于"这类单据必须审批"，否则流程形同虚设</td></tr>
 *   <tr><td>最近实例是 REJECTED/WITHDRAWN/CANCELLED</td><td>拒 {@code APS_APPROVAL_REQUIRED}</td>
 *       <td>结论不通过，需重新发起</td></tr>
 * </table>
 */
@Service
public class ApprovalGateService implements ApprovalGate {

    private final ApprovalApplicationService approvals;
    private final com.erp.aps.repository.ApprovalFlowRepository flows;

    public ApprovalGateService(ApprovalApplicationService approvals,
                               com.erp.aps.repository.ApprovalFlowRepository flows) {
        this.approvals = approvals;
        this.flows = flows;
    }

    @Override
    @Transactional(readOnly = true)
    public void requireApproved(BizType bizType, long bizId, String bizRefNo, long amount) {
        ApprovalState state = stateOf(bizType, bizId);
        switch (state) {
            case NOT_REQUIRED, APPROVED -> {
                // 放行
            }
            case PENDING -> throw new BusinessException(ErrorCode.APS_APPROVAL_PENDING,
                    "单据 %s 的审批仍在进行中，请先完成审批再审核单据".formatted(bizRefNo));
            case NOT_SUBMITTED -> throw new BusinessException(ErrorCode.APS_APPROVAL_REQUIRED,
                    "单据 %s 需要审批但尚未提交（单据类型 %s 已配置审批流），请先提交审批"
                            .formatted(bizRefNo, bizType));
            case REJECTED, CLOSED -> throw new BusinessException(ErrorCode.APS_APPROVAL_REQUIRED,
                    "单据 %s 的审批未通过（%s），需修改后重新发起审批"
                            .formatted(bizRefNo, state));
        }
    }

    @Override
    @Transactional(readOnly = true)
    public ApprovalState stateOf(BizType bizType, long bizId) {
        // 没有启用流程 → 这类单据根本不走审批（存量行为）
        Optional<ApprovalFlow> flow = flows.findEnabledByBizType(bizType);
        if (flow.isEmpty()) {
            return ApprovalState.NOT_REQUIRED;
        }
        Optional<ApprovalInstance> open = approvals.findOpenInstance(bizType, bizId);
        if (open.isPresent()) {
            return open.get().status().isInFlight()
                    ? ApprovalState.PENDING
                    : ApprovalState.NOT_SUBMITTED;     // DRAFT：建了但没提交
        }
        return approvals.findLatestInstance(bizType, bizId)                .map(instance -> switch (instance.status()) {
                    case APPROVED -> ApprovalState.APPROVED;
                    case REJECTED -> ApprovalState.REJECTED;
                    case WITHDRAWN, CANCELLED -> ApprovalState.CLOSED;
                    default -> ApprovalState.NOT_SUBMITTED;
                })
                .orElse(ApprovalState.NOT_SUBMITTED);
    }

    @Override
    @Transactional
    public boolean cancelOpen(BizType bizType, long bizId, String reason) {
        return approvals.cancelOpen(bizType, bizId, reason);
    }

    /** 供测试与诊断：某单据类型的启用流程编码。 */
    @Transactional(readOnly = true)
    public Optional<String> enabledFlowCode(BizType bizType) {
        return flows.findEnabledByBizType(bizType).map(ApprovalFlow::code);
    }
}
