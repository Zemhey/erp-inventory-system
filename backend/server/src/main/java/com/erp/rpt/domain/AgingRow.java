package com.erp.rpt.domain;

/**
 * 账龄行（契约 {@code AgingRow}）。
 *
 * <p>桶合计守恒：{@code bucket0 + bucket1 + bucket2 + bucket3 == balanceAmount}。
 * 未到期、无到期日都归入 0–30 桶（当前未逾期）。
 */
public record AgingRow(
        long partnerId,
        String partnerCode,
        String partnerName,
        long balanceAmount,
        long bucket0,
        long bucket1,
        long bucket2,
        long bucket3) {
}
