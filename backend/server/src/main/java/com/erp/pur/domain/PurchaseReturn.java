package com.erp.pur.domain;

import java.time.LocalDate;
import java.util.List;

/**
 * 采购退货单（FR-PUR-06）。
 *
 * <p>明细引用原到货行（{@code po_receipt_lines}）：这样扣库存能精确落到原批次，
 * 冲应付也能精确落到该到货单对应的应付单。
 */
public record PurchaseReturn(long id, String returnNo, long purchaseOrderId, long supplierId,
                             long warehouseId, LocalDate bizDate, String period,
                             PurchaseReturnStatus status, int totalQty, long totalAmount,
                             String remark, int version, List<PurchaseReturnLine> lines) {

    public PurchaseReturn {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }
}
