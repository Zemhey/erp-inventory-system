package com.erp.pur.domain;

import com.erp.shared.document.DocumentStatus;
import java.time.LocalDate;
import java.util.List;

/**
 * 请购单（FR-PUR-04）。
 *
 * <p>存储上复用 {@code purchase_orders}（{@code doc_type='REQUISITION'}），
 * 明细复用 {@code purchase_order_lines}；领域上仍是独立单据：
 * 它不能入库、不能红冲，转化后进入 {@link DocumentStatus#COMPLETED} 终态。
 *
 * @param convertedOrderId 已转化时指向生成的进货单；非空即不可再改
 */
public record PurchaseRequisition(long id, String orderNo, DocumentStatus status,
                                  long supplierId, long warehouseId, LocalDate bizDate,
                                  long totalAmount, int totalQty, String period, String remark,
                                  int version, Long convertedOrderId,
                                  List<PurchaseOrderLine> lines) {

    public PurchaseRequisition {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    public boolean converted() {
        return convertedOrderId != null;
    }
}
