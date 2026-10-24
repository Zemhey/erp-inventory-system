package com.erp.sal.domain;

import java.time.LocalDate;

/**
 * 客户应收（FR-SAL-07）。
 *
 * <p><b>不变量</b>：{@code balance == shippedAmount - returnedAmount - receivedAmount}
 * 在任意时刻成立（数据库 CHECK 与服务层同口径）。
 */
public record CustomerReceivable(long id, long salesOrderId, long customerId,
                                 LocalDate bizDate, LocalDate dueDate,
                                 long shippedAmount, long returnedAmount, long receivedAmount,
                                 long balance, String status, int version) {

    public boolean open() {
        return "OPEN".equals(status);
    }

    public boolean overdue(LocalDate today) {
        return open() && balance > 0 && dueDate != null && dueDate.isBefore(today);
    }
}
