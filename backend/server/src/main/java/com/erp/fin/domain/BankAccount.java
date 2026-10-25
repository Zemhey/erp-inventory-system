package com.erp.fin.domain;

import java.math.BigDecimal;

/** 银行账户（FR-V3-09）。balance 是"上日余额 + 借 − 贷"链上最后一笔记账后的快照。 */
public record BankAccount(
        long id,
        String code,
        String name,
        String bankName,
        String accountNo,
        String currency,
        BigDecimal balance,
        boolean isDefault,
        boolean enabled,
        String remark) {
}
