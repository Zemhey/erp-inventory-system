package com.erp.pur.domain;

/**
 * 采购退货明细（FR-PUR-06）。
 *
 * @param receiptLineId       原到货行：决定扣哪个批次、冲哪张应付
 * @param purchaseOrderLineId 原进货单明细行（追溯用）
 * @param skuId               SKU
 * @param qty                 退货数量
 * @param unitPriceAmount     单价（从到货行派生，不接受前端传值）
 * @param amount              退货金额 = qty × unitPriceAmount
 * @param batchNo             原批次
 */
public record PurchaseReturnLine(long receiptLineId, long purchaseOrderLineId, long skuId,
                                 int qty, long unitPriceAmount, long amount, String batchNo) {
}
