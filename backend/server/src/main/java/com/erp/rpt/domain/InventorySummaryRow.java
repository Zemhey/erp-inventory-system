package com.erp.rpt.domain;

/**
 * 进销存汇总的一行（契约 {@code InventorySummaryRow}），粒度是 <b>(SKU, 仓库)</b>。
 *
 * <p><b>★ 恒等式（不变量 RPT-03，计划书 §3.5）</b>：
 * {@code openingQty + inboundQty - outboundQty == closingQty}，
 * 且 {@code closingQty} 等于该 (SKU, 仓库) <b>截至期末的流水净和</b>。
 * 这四个数在同一条聚合 SQL 里分别求和（不是先算三个再推第四个），
 * 因此恒等式成立本身就是"口径没写错"的证据 —— 由 TC-RPT-006 断言。
 *
 * <p>口径与另两张报表<b>不同</b>（§3.5 明确要求写清楚，不要混用）：
 * 进货/出货统计按单据 {@code biz_date}，本表按流水 {@code occurred_at}，
 * 因为要回答的是"货什么时候真的动了"，流水才是事实源（ADR-0007）。
 *
 * @param openingQty  期初 = 期间开始前的流水净和
 * @param inboundQty  本期入库 = 期间内正变动合计（取正数）
 * @param outboundQty 本期出库 = 期间内负变动合计（已取正数，故恒等式里是减号）
 * @param closingQty  期末 = 截至期末的流水净和
 */
public record InventorySummaryRow(long skuId, long warehouseId,
                                  long openingQty, long inboundQty,
                                  long outboundQty, long closingQty) {
}
