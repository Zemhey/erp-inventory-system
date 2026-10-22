package com.erp.set.domain;

import java.time.LocalDate;

/**
 * 销售出货口径的源数据行：<b>未聚合</b>的一条出货明细。
 *
 * <p>为什么不是一条 SUM：结算要的是"已出货数量"对应的金额
 * （{@code round(unit_price × shipped_qty × discount_rate / 10000)}），
 * 而 {@code sales_order_lines.amount} 记的是"订购数量"对应的金额。
 * 舍入只能做一次、且必须与 SAL 建单时同一次、同一实现 ——
 * 在 SQL 里另写一套 round 就是计划书 §6 坑 7 点名要防的尾差来源。
 */
public record SalesSourceLine(LocalDate bizDate, long warehouseId, int shippedQty,
                              long unitPriceAmount, int discountRate) {

    public SettlementKey key() {
        return new SettlementKey(bizDate, warehouseId);
    }
}
