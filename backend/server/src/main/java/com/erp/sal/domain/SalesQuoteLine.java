package com.erp.sal.domain;

/** 报价明细（FR-SAL-05），金额公式与出货明细一致。 */
public record SalesQuoteLine(long id, int lineNo, long skuId, int qty,
                             long unitPriceAmount, int discountRate, long amount) {
}
