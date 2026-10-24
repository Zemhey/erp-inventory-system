package com.erp.pur.domain;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 付款核销明细（FR-PUR-08）。
 *
 * <p>一行 = 一张付款单对一张应付单的核销。同一 {@code paymentNo} 的多行共享
 * {@code paymentAmount}，守恒关系为 {@code Σ amount == paymentAmount}。
 */
public record PayableAllocation(long id, String paymentNo, long supplierId, long payableId,
                                long amount, long paymentAmount, LocalDate bizDate,
                                String remark, OffsetDateTime createdAt) {
}
