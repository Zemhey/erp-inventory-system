package com.erp.pur.domain;

import java.time.LocalDate;
import java.util.List;

/**
 * 到货单（FR-PUR-05）：一次入库确认生成一张。
 *
 * <p>不合格数量只存在于这张单与质检列里，<b>不会</b>出现在库存流水或应付金额中。
 */
public record PurchaseReceipt(long id, String receiptNo, long purchaseOrderId, long supplierId,
                              long warehouseId, LocalDate bizDate, String period,
                              int totalQty, int acceptedQty, int rejectedQty,
                              long acceptedAmount, List<PurchaseReceiptLine> lines) {

    public PurchaseReceipt {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }
}
