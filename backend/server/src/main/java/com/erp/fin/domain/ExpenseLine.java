package com.erp.fin.domain;

import java.math.BigDecimal;

/** 报销明细行（一条 = 一项费用 + 可选票据号）。 */
public record ExpenseLine(
        long id,
        int lineNo,
        String expenseItem,
        BigDecimal amount,
        String invoiceNo,
        String remark) {
}
