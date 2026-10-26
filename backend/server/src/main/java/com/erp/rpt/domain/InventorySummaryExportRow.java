package com.erp.rpt.domain;

/**
 * 进销存汇总的导出行（与 {@link InventorySummaryRow} 来自同一条 SQL）。
 *
 * <p>V4-4 追加：{@code skuCode} 与四个成本金额列（<b>单位分</b>，可空 = 该账期未月结）。
 * 原有 6 列的 CSV 顺序不变，金额列一律排在最后，所以老脚本按列位解析仍然能读到
 * 前六列；新增列是纯追加。
 */
public record InventorySummaryExportRow(
        long skuId,
        String skuName,
        long warehouseId,
        String warehouseName,
        long openingQty,
        long inboundQty,
        long outboundQty,
        long closingQty,
        String skuCode,
        Long openingCostAmount,
        Long inboundCostAmount,
        Long outboundCostAmount,
        Long closingCostAmount) {
}
