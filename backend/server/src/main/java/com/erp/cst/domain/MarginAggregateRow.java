package com.erp.cst.domain;

/**
 * 毛利聚合的中间行：SQL 只做分组求和，毛利率由应用层统一舍入。
 * 把毛利率放到 SQL 里算会引入第二处除法与第二套舍入规则。
 */
public record MarginAggregateRow(
        String key,
        String label,
        long qty,
        long revenueAmount,
        long costAmount,
        long grossProfitAmount,
        long orderCount) {
}
