package com.erp.rpt.domain;

/** 单据明细行（FR-V3-04）：单据详情抽屉用。金额单位：分。 */
public record DocLine(
        long lineNo,
        long skuId,
        String skuName,
        long qty,
        long unitPriceAmount,
        long amount) {
}
