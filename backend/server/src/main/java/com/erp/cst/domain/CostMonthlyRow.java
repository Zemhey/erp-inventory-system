package com.erp.cst.domain;

/**
 * 成本月结转行（{@code cost_monthly}）。
 *
 * <p>恒等式：{@code closing = opening + inbound - outbound}（数量与金额各一条），
 * 数据库 CHECK 与应用层各守一遍。
 *
 * @param version 乐观锁版本；仅用于并发重算时发现"读到旧行"
 */
public record CostMonthlyRow(
        String period,
        long skuId,
        long warehouseId,
        long openingQty,
        long openingCostAmount,
        long inboundQty,
        long inboundCostAmount,
        long outboundQty,
        long outboundCostAmount,
        long closingQty,
        long closingCostAmount,
        int version) {

    /** 比较业务字段（忽略 version）；用于月结幂等判断。 */
    public boolean sameBusinessValues(CostMonthlyRow other) {
        return other != null
                && period.equals(other.period)
                && skuId == other.skuId
                && warehouseId == other.warehouseId
                && openingQty == other.openingQty
                && openingCostAmount == other.openingCostAmount
                && inboundQty == other.inboundQty
                && inboundCostAmount == other.inboundCostAmount
                && outboundQty == other.outboundQty
                && outboundCostAmount == other.outboundCostAmount
                && closingQty == other.closingQty
                && closingCostAmount == other.closingCostAmount;
    }
}
