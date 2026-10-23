package com.erp.pur.domain;

/**
 * 采购退货单状态（FR-PUR-06）。
 *
 * <p>刻意不复用七态 {@link com.erp.shared.document.DocumentStatus}：
 * 退货没有 PARTIAL（整单一次执行），也不需要 SUBMITTED/APPROVED 两段等待。
 * 但与既有单据一样，状态转移仍由应用服务显式校验，不靠裸字符串。
 */
public enum PurchaseReturnStatus {
    DRAFT,
    COMPLETED,
    VOIDED;

    public boolean canExecute() {
        return this == DRAFT;
    }

    public boolean canVoid() {
        return this == DRAFT;
    }
}
