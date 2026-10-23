package com.erp.rpt.domain;

/**
 * 进货 / 出货统计的一行聚合结果（契约 {@code ReportRow}）。
 *
 * <p>金额单位为**分**（ADR-0005），与单据表同源，不做任何展示层换算。
 *
 * @param key        分组键：供应商/客户/商品编码、SKU 组合编码、日期或账期
 * @param label      展示名称
 * @param qty        期间内数量（进货=累计入库量，出货=累计出货量）
 * @param amount     期间内金额（分）
 * @param orderCount 涉及的单据数（{@code COUNT(DISTINCT 单据 id)}）
 */
public record ReportRow(String key, String label, long qty, long amount, long orderCount) {
}
