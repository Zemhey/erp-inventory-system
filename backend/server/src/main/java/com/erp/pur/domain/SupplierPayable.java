package com.erp.pur.domain;

import java.time.LocalDate;
import java.util.List;

/**
 * 供应商应付单（FR-PUR-07）：一张到货单一张应付单。
 *
 * <p><b>余额是数据库生成列</b>（{@code original - returned - paid}），
 * 应用层只读不算，避免"页面算一遍、后端算一遍"两套口径。
 */
public record SupplierPayable(long id, String payableNo, long supplierId,
                              long purchaseOrderId, String orderNo, long receiptId,
                              LocalDate bizDate, String period,
                              long originalAmount, long returnedAmount, long paidAmount,
                              long balanceAmount, int version,
                              List<PayableAllocation> allocations) {

    public SupplierPayable {
        allocations = allocations == null ? List.of() : List.copyOf(allocations);
    }

    /** 展示/筛选用状态：OPEN=未发生退货或付款；PARTIAL=已有部分变动但仍有余额；SETTLED=余额为 0。 */
    public String status() {
        if (balanceAmount == 0) {
            return "SETTLED";
        }
        return paidAmount == 0 && returnedAmount == 0 ? "OPEN" : "PARTIAL";
    }
}
