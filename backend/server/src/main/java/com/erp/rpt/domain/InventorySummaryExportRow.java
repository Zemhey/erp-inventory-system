package com.erp.rpt.domain;

/**
 * 进销存导出行：在 {@link InventorySummaryRow} 的六个报表字段之外，
 * 带上<b>与页面同一份</b>名称（商品名 · 颜色-尺码 / 仓库名）。
 *
 * <p>名称由同一条聚合 SQL 的 LEFT JOIN 给出，不另发查询；页面字典缺失时的
 * {@code SKU#id} / {@code 仓库#id} 回退也写在同一 SQL 的 COALESCE 里，
 * 因此 CSV 与页面在字典不完整时仍然一致。
 */
public record InventorySummaryExportRow(
        long skuId,
        String skuName,
        long warehouseId,
        String warehouseName,
        long openingQty,
        long inboundQty,
        long outboundQty,
        long closingQty) {
}
