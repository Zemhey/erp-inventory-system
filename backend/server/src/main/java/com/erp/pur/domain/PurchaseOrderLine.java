package com.erp.pur.domain;

/**
 * 进货单明细行（FR-PUR-01 / FR-PUR-05）。
 *
 * @param id              主键
 * @param lineNo          行号，单据内从 1 开始
 * @param skuId           SKU
 * @param qty             单据数量
 * @param receivedQty     累计已到货数量（含不合格，分批入库，不得超过 qty）
 * @param qcQty           累计合格数量（只有合格量入库存、生成应付）
 * @param qcStatus        累计质检状态
 * @param qcNote          最近一次非空质检备注
 * @param unitPriceAmount 单价（分）
 * @param amount          行金额 = qty × unitPriceAmount（分）
 */
public record PurchaseOrderLine(long id, int lineNo, long skuId, int qty, int receivedQty,
                                int qcQty, QcStatus qcStatus, String qcNote,
                                long unitPriceAmount, long amount) {

    public PurchaseOrderLine {
        qcStatus = qcStatus == null ? QcStatus.NONE : qcStatus;
        qcQty = qcQty < 0 ? 0 : qcQty;
    }

    /** 尚未到货的数量。 */
    public int openQty() {
        return qty - receivedQty;
    }

    /** 已到货但质检不合格的数量。 */
    public int rejectedQty() {
        return receivedQty - qcQty;
    }
}
