package com.erp.fin.domain;

import java.math.BigDecimal;

/**
 * 银行账户（FR-V3-09）。
 *
 * <p>{@code initBalance} 是建户/编辑时录入的期初；{@code balance} 是<b>派生值</b>
 * = 期初 + Σ借 − Σ贷，由服务端在每次写日记账或改期初后整链重算（V24）。
 * 老写法只有一个 balance，期初在第一笔流水到来时就永久丢失。
 */
public record BankAccount(
        long id,
        String code,
        String name,
        String bankName,
        String accountNo,
        String currency,
        BigDecimal initBalance,
        BigDecimal balance,
        boolean isDefault,
        boolean enabled,
        String remark) {
}
