package com.erp.cst.domain;

import java.time.LocalDate;

/**
 * 毛利快照（{@code margin_records}）：一条出货单 × SKU 的净额毛利。
 *
 * <p>净额口径：{@code qty = 已出货 - 已退货}；
 * {@code revenue = 出货行金额 - 退货行金额}；
 * {@code cost = SALES_OUT 成本 - RETURN_IN 成本}。
 */
public record MarginRecord(
        long id,
        String period,
        long salesOrderId,
        String salesOrderNo,
        long customerId,
        long skuId,
        long warehouseId,
        LocalDate bizDate,
        int qty,
        long revenueAmount,
        long costAmount,
        long grossProfitAmount,
        long grossMarginRateBp) {

    public static MarginRecord of(long salesOrderId, String salesOrderNo, long customerId,
                                  long skuId, long warehouseId, LocalDate bizDate, int qty,
                                  long revenueAmount, long costAmount, long rateBp) {
        return new MarginRecord(0, "%04d-%02d".formatted(bizDate.getYear(), bizDate.getMonthValue()),
                salesOrderId, salesOrderNo, customerId, skuId, warehouseId, bizDate, qty,
                revenueAmount, costAmount, revenueAmount - costAmount, rateBp);
    }

    /** 比较业务字段（忽略 id）；用于月结幂等判断。 */
    public boolean sameBusinessValues(MarginRecord other) {
        return other != null
                && period.equals(other.period)
                && salesOrderId == other.salesOrderId
                && salesOrderNo.equals(other.salesOrderNo)
                && customerId == other.customerId
                && skuId == other.skuId
                && warehouseId == other.warehouseId
                && bizDate.equals(other.bizDate)
                && qty == other.qty
                && revenueAmount == other.revenueAmount
                && costAmount == other.costAmount
                && grossProfitAmount == other.grossProfitAmount
                && grossMarginRateBp == other.grossMarginRateBp;
    }
}
