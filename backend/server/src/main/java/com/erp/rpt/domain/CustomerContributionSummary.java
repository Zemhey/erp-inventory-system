package com.erp.rpt.domain;

/**
 * 客户贡献汇总（契约 {@code CustomerContributionSummary}）。
 *
 * <p><b>汇总永远覆盖全部客户，不受 Top N 截断影响</b>；页面同时展示
 * 「Top N」和「全量复购率」，避免把前 20 名的复购率误当成全店复购率。
 *
 * @param repurchaseRateBp 复购率，万分比整数（6667 = 66.67%）
 */
public record CustomerContributionSummary(
        int customerCount,
        int repurchaseCount,
        long repurchaseRateBp,
        int newCustomerCount,
        int returningCustomerCount,
        long totalQty,
        long totalAmount,
        long orderCount) {
}
