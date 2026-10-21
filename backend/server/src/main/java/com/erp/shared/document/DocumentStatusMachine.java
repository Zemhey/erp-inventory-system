package com.erp.shared.document;

import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import java.util.Objects;

/**
 * 状态转移校验器 —— 单据状态机的唯一执行点（BR-DOC-01）。
 *
 * <p><b>为什么不把这段逻辑复制到每个 Service</b>：状态机测试的价值在于
 * "7 × 7 组合穷举，非法转移全部被拒"。如果每个 Service 各写一遍 if，
 * 这个穷举测试就要写 N 遍，而且必然有人漏写一条。
 *
 * <p><b>错误码为什么交给调用方指定</b>：{@code PUR_STATUS_ILLEGAL} 与
 * {@code SAL_STATUS_ILLEGAL} 是契约里两个不同的业务码（前端据此提示不同文案），
 * 但它们走的是<b>同一套</b>转移规则。因此把"规则"和"错误码"解耦：
 * 规则集中在这里，错误码由单据模块传入。
 */
public final class DocumentStatusMachine {

    private DocumentStatusMachine() {
    }

    /**
     * 校验转移是否合法，非法则抛业务异常。
     *
     * @param from        当前状态
     * @param to          目标状态
     * @param errorCode   非法转移时使用的业务错误码
     * @param documentRef 单据标识（单号或 id），进错误消息便于排障
     * @return {@code to}（便于链式赋值）
     * @throws BusinessException 转移非法
     */
    public static DocumentStatus requireTransition(DocumentStatus from, DocumentStatus to,
                                                   ErrorCode errorCode, Object documentRef) {
        Objects.requireNonNull(from, "from 状态不能为空");
        Objects.requireNonNull(errorCode, "errorCode 不能为空");
        if (to == null) {
            throw new BusinessException(errorCode,
                    "目标状态不能为空：单据 %s 当前状态 %s".formatted(documentRef, from));
        }
        if (!from.canTransitionTo(to)) {
            throw new BusinessException(errorCode,
                    "单据状态非法转移：单据 %s 不能从 %s 变更为 %s（允许的目标态：%s）"
                            .formatted(documentRef, from, to, from.allowedNext()));
        }
        return to;
    }
}
