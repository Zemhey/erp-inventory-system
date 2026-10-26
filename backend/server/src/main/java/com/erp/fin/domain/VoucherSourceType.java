package com.erp.fin.domain;

import java.util.Locale;

/**
 * 凭证来源类型（与 {@code fin_voucher.source_type} 的 CHECK 逐字一致）。
 *
 * <p>非 MANUAL 即"业务单据自动生成"，幂等键是 {@code (tenant, sourceType, sourceId)}。
 */
public enum VoucherSourceType {
    MANUAL("手工录入"),
    PUR_IN("采购入库"),
    SAL_OUT("销售出库"),
    SAL_ORDER_OUT("销售出货"),
    PUR_PAY("付款核销"),
    SAL_RCV("收款核销"),
    EXPENSE("费用报销"),
    INVOICE("发票");

    private final String label;

    VoucherSourceType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static VoucherSourceType parse(String raw) {
        return valueOf(raw.trim().toUpperCase(Locale.ROOT));
    }
}
