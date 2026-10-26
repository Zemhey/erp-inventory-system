package com.erp.rpt.domain;

/**
 * 单据行级明细（进货明细 / 销售明细共用一个行形状）。
 *
 * <p><b>为什么是一张"按单据展开"的表而不再是聚合</b>：现有 {@code /reports/purchase}、
 * {@code /reports/sales} 只回 {@code key/label/qty/amount/orderCount}，
 * 拿不到单号、业务日期、经手人，任务要的"明细"必须下探到单据行。
 *
 * <p><b>★ 单位分</b>（ADR-0005）：{@code unitPriceAmount} 与 {@code amount} 都是分。
 * 金额算式与聚合端点<b>逐字相同</b>，因此"明细全量求和 == 聚合端点同期同参的结果"可断言：
 * <ul>
 *   <li>进货：{@code received_qty × unit_price_amount}；</li>
 *   <li>销售：{@code round(unit_price_amount × shipped_qty × discount_rate / 10000)}。</li>
 * </ul>
 * 数量、金额都按<b>已执行量</b>（received/shipped）计，口径同样是单据 {@code biz_date}
 * 闭区间、状态集合 {@code PARTIAL|COMPLETED}、执行量 &gt; 0。
 *
 * @param discountRate 仅销售有值；进货单没有折扣列，此处为 {@code null}
 */
public record DocumentDetailRow(
        String orderNo,
        String bizDate,
        String status,
        String partnerCode,
        String partnerName,
        String warehouseName,
        String operator,
        int lineNo,
        String skuCode,
        String skuName,
        long orderedQty,
        long executedQty,
        long unitPriceAmount,
        Long discountRate,
        long amount) {
}
