package com.erp.rpt.domain;

/**
 * 经营日报 / 月报的一行（契约 {@code OperatingReportRow}）。
 *
 * <p><b>两组口径刻意放在同一行但绝不互相推算</b>：
 * <ul>
 *   <li>{@code opening/inbound/outbound/closing} 来自库存流水 {@code occurred_at}，
 *       满足 INV-6：{@code opening + inbound − outbound == closing}；</li>
 *   <li>{@code purchase/sales} 来自单据 {@code biz_date}，用于看业务量。</li>
 * </ul>
 * 两组数字不可相加，也不可互相校验（跨天执行的单据本就落在不同期间）。
 *
 * @param period         日：yyyy-MM-dd；月：yyyy-MM
 * @param openingQty     期初（分桶起点前的流水净和）
 * @param inboundQty     本期入库（正变动）
 * @param outboundQty    本期出库（负变动取正）
 * @param closingQty     期末（分桶终点前的流水净和）
 * @param purchaseQty    进货数量（单据口径）
 * @param purchaseAmount 进货金额（分，单据口径）
 * @param salesQty       出货数量（单据口径）
 * @param salesAmount    出货金额（分，单据口径）
 */
public record OperatingReportRow(
        String period,
        long openingQty,
        long inboundQty,
        long outboundQty,
        long closingQty,
        long purchaseQty,
        long purchaseAmount,
        long salesQty,
        long salesAmount) {
}
