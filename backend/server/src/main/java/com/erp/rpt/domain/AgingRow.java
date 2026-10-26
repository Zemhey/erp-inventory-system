package com.erp.rpt.domain;

/**
 * 账龄行（按往来单位 或 按单据）。
 *
 * <p><b>★ 单位分</b>（ADR-0005）。四桶合计恒等于 {@code balanceAmount}，
 * 行级与汇总级都由 SQL 保证（应用服务再校验一次）。
 *
 * <p>V4-4 追加的最后三个字段<b>只在 {@code groupBy=document} 时有值</b>：
 * 默认维度 {@code partner} 下每行是一个往来单位，没有"哪张单"可说，
 * 于是三列全为 {@code null}（前端不渲染，也不影响既有消费方）。
 *
 * @param docNo    AR = 出货单号（{@code sales_orders.order_no}）；AP = 应付单号
 *                 （{@code supplier_payables.payable_no}）
 * @param docDate  单据业务日期（AR/AP 都取各自源的 {@code biz_date}，ISO 字符串）
 * @param dueDate  约定到期日；AP 没有到期日概念，恒为 {@code null}
 */
public record AgingRow(
        long partnerId,
        String partnerCode,
        String partnerName,
        long balanceAmount,
        long bucket0,
        long bucket1,
        long bucket2,
        long bucket3,
        String docNo,
        String docDate,
        String dueDate) {
}
