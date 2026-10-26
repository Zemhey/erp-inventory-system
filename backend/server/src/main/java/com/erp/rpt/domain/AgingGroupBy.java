package com.erp.rpt.domain;

/** 账龄展示粒度（契约 {@code groupBy}：partner | document，默认 partner）。 */
public enum AgingGroupBy {

    /** 按往来单位一行（既有口径，默认值）。 */
    PARTNER,
    /** 按单据一行：同一单位的每张未结清单据各占一行，可看单号与到期日。 */
    DOCUMENT
}
