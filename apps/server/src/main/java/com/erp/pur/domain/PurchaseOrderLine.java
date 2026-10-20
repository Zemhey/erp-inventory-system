package com.erp.pur.domain;

/**
 * 进货单明细行（FR-PUR-01）。
 *
 * @param id              主键
 * @param lineNo          行号，单据内从 1 开始
 * @param skuId           SKU
 * @param qty             单据数量
 * @param receivedQty     累计已入库数量（分批入库，不得超过 qty）
 * @param unitPriceAmount 单价（分）
 * @param amount          行金额 = qty × unitPriceAmount（分）
 */
public record PurchaseOrderLine(long id, int lineNo, long skuId, int qty, int receivedQty,
                                long unitPriceAmount, long amount) {

    /** 尚未入库的数量。 */
    public int openQty() {
        return qty - receivedQty;
    }
}
