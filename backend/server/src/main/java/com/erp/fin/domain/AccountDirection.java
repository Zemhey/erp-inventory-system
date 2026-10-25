package com.erp.fin.domain;

/** 记账方向：借 / 贷（复式记账的两条腿，FR-V3-06）。 */
public enum AccountDirection {
    DEBIT("借"),
    CREDIT("贷");

    private final String label;

    AccountDirection(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
