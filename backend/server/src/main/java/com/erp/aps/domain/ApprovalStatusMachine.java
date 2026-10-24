package com.erp.aps.domain;

import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import java.util.Objects;

/**
 * 审批状态转移校验器 —— 审批状态机的**唯一执行点**（BR-APS-01）。
 *
 * <p>与 {@code DocumentStatusMachine} 同构（照抄它的写法是有意的：两套状态机
 * 各自独立，但校验手法应当一致，评审时不用学第二套）。
 *
 * <p><b>为什么不复用 {@code DocumentStatusMachine}</b>：见 {@link ApprovalStatus}
 * 的类注释 —— 两套状态的语义完全不重叠，共用执行点就必然共用错误码与语义，
 * 最后"审批通过"会被误当成"单据可执行"。
 *
 * <p><b>错误码固定为 {@code APS_STATUS_ILLEGAL}</b>，不接受调用方传入：
 * 审批只有这一个域，不像单据有 PUR/SAL/RMA 三套码要区分。
 */
public final class ApprovalStatusMachine {

    private ApprovalStatusMachine() {
    }

    /**
     * 校验审批转移是否合法，非法则抛业务异常。
     *
     * @param from         当前状态
     * @param to           目标状态
     * @param instanceRef  审批实例标识（id 或单据号），进错误消息便于排障
     * @return {@code to}（便于链式赋值）
     * @throws BusinessException 转移非法
     */
    public static ApprovalStatus requireTransition(ApprovalStatus from, ApprovalStatus to,
                                                   Object instanceRef) {
        Objects.requireNonNull(from, "from 状态不能为空");
        if (to == null) {
            throw new BusinessException(ErrorCode.APS_STATUS_ILLEGAL,
                    "目标审批状态不能为空：审批 %s 当前状态 %s".formatted(instanceRef, from));
        }
        if (!from.canTransitionTo(to)) {
            throw new BusinessException(ErrorCode.APS_STATUS_ILLEGAL,
                    "审批状态非法转移：审批 %s 不能从 %s 变更为 %s（允许的目标态：%s）"
                            .formatted(instanceRef, from, to, from.allowedNext()));
        }
        return to;
    }
}
