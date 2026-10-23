package com.erp.cst.domain;

import java.time.OffsetDateTime;

/**
 * 一条库存流水对应的成本层。
 *
 * @param qty           绝对值（方向由 {@link CostDirection} 表达）
 * @param totalCostAmount 本条流水结转的成本总额（分）；守恒断言以它为准
 * @param poolQtyAfter  回放后成本池数量
 * @param poolCostAfter 回放后成本池金额（分）
 */
public record CostLayer(
        long ledgerId,
        long skuId,
        long warehouseId,
        String batchNo,
        String bizType,
        String bizRefNo,
        CostDirection direction,
        int qty,
        long unitCostAmount,
        long totalCostAmount,
        long poolQtyAfter,
        long poolCostAfter,
        OffsetDateTime occurredAt) {

    public CostPoolKey poolKey() {
        return new CostPoolKey(skuId, warehouseId);
    }
}
