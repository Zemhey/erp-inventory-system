package com.erp.fin.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 发票台账（FR-V3-11）。
 *
 * <p>价税分离：{@code amount + taxAmount = totalAmount}（DB CHECK 兜底）。
 * 挂业务单据时 {@code sourceType ∈ (PURCHASE_ORDER, SALES_ORDER)}；
 * 同一单据的 NORMAL 发票合计不得超过单据金额（超额 409）。
 */
public record Invoice(
        long id,
        String invoiceNo,
        InvoiceType type,
        Long partnerId,
        String partnerName,
        BigDecimal amount,
        BigDecimal taxAmount,
        BigDecimal totalAmount,
        LocalDate invoiceDate,
        String sourceType,
        Long sourceId,
        InvoiceStatus status,
        String remark) {
}
