package com.erp.rpt.domain;

/**
 * 客户贡献行（契约 {@code CustomerContributionRow}）。
 *
 * <p>数量 / 金额 / 单据数与出货统计同一个公式与状态集合（只改分组维度为客户），
 * 因此对同一区间、同一仓库，本表按客户汇总后与出货统计逐格相等。
 *
 * @param repurchase 期间内单据数 ≥ 2
 * @param newCustomer {@code from} 之前没有任何已执行出货单
 */
public record CustomerContributionRow(
        long customerId,
        String customerCode,
        String customerName,
        long orderCount,
        long qty,
        long amount,
        boolean repurchase,
        boolean newCustomer) {
}
