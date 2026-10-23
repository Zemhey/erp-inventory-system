package com.erp.cst.domain;

/**
 * 成本池键：移动加权平均的核算对象。
 *
 * <p>批次只是库存可追溯维度，不是财务核算维度；同一 SKU 在同一仓库的
 * 所有批次共用一个成本池，销售按 FEFO 拆批次时仍按 SKU 级均价结转。
 */
public record CostPoolKey(long skuId, long warehouseId) {
}
