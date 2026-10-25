package com.erp.fin.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 费用报销单（FR-V3-10）。amount 必须等于明细合计，应用层校验（FIN_EXPENSE_AMOUNT_MISMATCH）。 */
public record Expense(
        long id,
        String expenseNo,
        Long applicantId,
        String applicantName,
        Long departmentId,
        String departmentName,
        String expenseType,
        BigDecimal amount,
        ExpenseStatus status,
        LocalDate applyDate,
        String rejectReason,
        LocalDateTime paidAt,
        String remark,
        List<ExpenseLine> lines) {

    public Expense {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /** 装配明细行后的副本（仓储头/行两步组装）。 */
    public Expense withLines(List<ExpenseLine> newLines) {
        return new Expense(id, expenseNo, applicantId, applicantName, departmentId,
                departmentName, expenseType, amount, status, applyDate, rejectReason,
                paidAt, remark, newLines);
    }
}
