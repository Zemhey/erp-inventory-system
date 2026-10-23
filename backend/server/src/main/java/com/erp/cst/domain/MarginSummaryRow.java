package com.erp.cst.domain;

/**
 * 毛利分析聚合行。
 *
 * @param key   分组键（SKU id / 客户 id / 账期）
 * @param label 展示名（由 SQL 关联主数据补齐）
 * @param grossMarginRateBp 毛利率万分比（6000 = 60%），按分组汇总额重算而非行平均
 */
public record MarginSummaryRow(
        String key,
        String label,
        long qty,
        long revenueAmount,
        long costAmount,
        long grossProfitAmount,
        long grossMarginRateBp,
        long orderCount) {
}
