package com.erp.inv.domain;

/**
 * 补货建议（FR-INV-13）。
 *
 * <p>纯查询结果、不落表，因此天然幂等；已下单未到货的 SKU 在查询里被排除。
 */
public record ReplenishmentSuggestion(
        long skuId,
        long warehouseId,
        int availableQty,
        int minLevel,
        int maxLevel,
        int suggestedQty,
        int warehousePriority) {
}
