package com.erp.fin.domain;

import java.util.Locale;

/** 发票方向：OUTPUT 销项（我开给客户）/ INPUT 进项（供应商开给我）。页面两个 Tab 按它分组。 */
public enum InvoiceType {
    OUTPUT("销项"),
    INPUT("进项");

    private final String label;

    InvoiceType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static InvoiceType parse(String raw) {
        return valueOf(raw.trim().toUpperCase(Locale.ROOT));
    }
}
