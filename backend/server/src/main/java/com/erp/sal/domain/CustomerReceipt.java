package com.erp.sal.domain;

import java.time.LocalDate;
import java.util.List;

/** 收款单与核销明细（FR-SAL-08）。Σ allocations.amount 必须等于 amount。 */
public record CustomerReceipt(long id, String receiptNo, long customerId, long amount,
                              LocalDate bizDate, String remark,
                              List<Allocation> allocations) {

    public CustomerReceipt {
        allocations = allocations == null ? List.of() : List.copyOf(allocations);
    }

    public record Allocation(long id, long receivableId, long amount) {
    }
}
