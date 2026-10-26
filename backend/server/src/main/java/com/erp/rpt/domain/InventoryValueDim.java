package com.erp.rpt.domain;

/**
 * 库存金额报表的汇总维度（契约 {@code groupBy}：sku | warehouse，默认 sku）。
 *
 * <p>刻意只有两个取值：成本月结本身就是 (账期 × SKU × 仓库) 的粒度，
 * 再往上只有"并到商品/仓库"这两种不会引入新口径的收法。
 */
public enum InventoryValueDim {

    /** 按 SKU（名称口径与周转、呆滞两张表一致）。 */
    SKU,
    /** 按仓库。 */
    WAREHOUSE
}
