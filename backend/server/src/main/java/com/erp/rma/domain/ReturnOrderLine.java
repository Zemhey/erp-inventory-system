package com.erp.rma.domain;

import java.time.LocalDate;

/**
 * 退货单明细行（FR-RMA-01）。
 *
 * <p>单价与折扣是从原出货行<b>复制</b>过来的，而不是前端传入 ——
 * 退货金额必须与当初的出货金额同口径，否则日清的净额就对不上。
 *
 * @param id              主键
 * @param lineNo          行号，单据内从 1 开始
 * @param originLineId    原出货单明细行 id（累计退货量记在它身上）
 * @param skuId           SKU（同样来自原出货行）
 * @param qty             本次退货数量
 * @param unitPriceAmount 原行单价（分）
 * @param discountRate    原行折扣率，整数万分比
 * @param amount          行退货金额 = round(qty × unitPrice × discountRate / 10000)（分）
 * @param batchNo         退货入库批次，默认空批次
 */
public record ReturnOrderLine(long id, int lineNo, long originLineId, long skuId, int qty,
                              long unitPriceAmount, int discountRate, long amount,
                              String batchNo, LocalDate productionDate, LocalDate expiryDate) {
}
