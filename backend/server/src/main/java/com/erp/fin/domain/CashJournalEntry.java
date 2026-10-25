package com.erp.fin.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 现金/银行日记账一行（FR-V3-09）：balance = 上日余额 + 借 − 贷（资产账户借增贷减）。 */
public record CashJournalEntry(
        long id,
        long accountId,
        LocalDate bizDate,
        AccountDirection direction,
        BigDecimal amount,
        BigDecimal balance,
        String sourceType,
        Long sourceId,
        String summary) {
}
