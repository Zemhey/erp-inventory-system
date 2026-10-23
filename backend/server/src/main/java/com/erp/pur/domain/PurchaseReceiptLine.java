package com.erp.pur.domain;

import java.time.LocalDate;

/**
 * 到货行（含质检与批次）。
 *
 * @param id                  主键
 * @param receiptId           所属到货单
 * @param lineNo              行号（到货单内从 1 开始）
 * @param purchaseOrderLineId 原进货单明细行
 * @param skuId               SKU
 * @param batchNo             入库批次（不合格量同样记录批次，便于退货追溯）
 * @param receivedQty         本次到货量
 * @param qcQty               本次合格量（入库存、进应付）
 * @param rejectedQty         本次不合格量（不入库存、不进应付）
 * @param unitPriceAmount     单价（分）
 * @param acceptedAmount      合格金额 = qcQty × unitPriceAmount
 * @param qcStatus            本次质检状态
 * @param qcNote              质检备注
 */
public record PurchaseReceiptLine(long id, long receiptId, int lineNo,
                                  long purchaseOrderLineId, long skuId, String batchNo,
                                  LocalDate productionDate, LocalDate expiryDate,
                                  int receivedQty, int qcQty, int rejectedQty,
                                  long unitPriceAmount, long acceptedAmount,
                                  QcStatus qcStatus, String qcNote) {
}
