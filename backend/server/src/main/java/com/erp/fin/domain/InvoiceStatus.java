package com.erp.fin.domain;

/** 发票台账状态：NORMAL 正常 / VOID 已作废（作废票不计入可开票额度）。 */
public enum InvoiceStatus {
    NORMAL("正常"),
    VOID("已作废");

    private final String label;

    InvoiceStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
