package com.erp.inv.domain;

/** 盘点明细（FR-INV-08）：{@code diffQty = countedQty - bookQty}，由数据库生成列保证。 */
public record StocktakeLine(
        long id,
        long stocktakeId,
        int lineNo,
        long skuId,
        String batchNo,
        int bookQty,
        int countedQty,
        int diffQty) {
}
