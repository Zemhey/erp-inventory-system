package com.erp.sal.domain;

/**
 * 套装组件（FR-SAL-10 / INV-16）。
 *
 * <p>1 个套装 = {@code ratioQty} 个组件 SKU。配比是守恒基准：
 * 套装出库数 × 配比必须等于组件扣减总量。
 */
public record BundleItem(long id, long componentSkuId, int ratioQty) {
}
