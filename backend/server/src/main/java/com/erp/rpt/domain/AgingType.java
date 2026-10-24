package com.erp.rpt.domain;

/** 账龄报表类型（契约 {@code type}：AR 应收 / AP 应付）。 */
public enum AgingType {

    /** 客户应收：以 {@code customer_receivables.due_date} 起算。 */
    AR,
    /** 供应商应付：无约定到期日，以 {@code supplier_payables.biz_date} 起算。 */
    AP
}
