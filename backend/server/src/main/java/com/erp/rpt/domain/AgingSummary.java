package com.erp.rpt.domain;

/** 账龄汇总（契约 {@code AgingSummary}），各桶同样必须合计为 {@code totalBalance}。 */
public record AgingSummary(
        int partnerCount,
        long totalBalance,
        long bucket0,
        long bucket1,
        long bucket2,
        long bucket3) {
}
