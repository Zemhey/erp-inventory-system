package com.erp.fin.domain;

/** 报销单状态机：DRAFT → SUBMITTED → APPROVED → PAID；驳回回 DRAFT 并留原因（FR-V3-10）。 */
public enum ExpenseStatus {
    DRAFT("草稿"),
    SUBMITTED("已提交"),
    APPROVED("已审批"),
    PAID("已付款"),
    REJECTED("已驳回");

    private final String label;

    ExpenseStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean canTransitionTo(ExpenseStatus target) {
        return switch (this) {
            // 简报口径：驳回"回 DRAFT 并留原因"——REJECTED 不驻留状态，
            // 原因写在 reject_reason 上，申请人改完直接重提。
            case DRAFT -> target == SUBMITTED;
            case SUBMITTED -> target == APPROVED || target == DRAFT;
            case APPROVED -> target == PAID;
            case PAID, REJECTED -> false;
        };
    }
}
