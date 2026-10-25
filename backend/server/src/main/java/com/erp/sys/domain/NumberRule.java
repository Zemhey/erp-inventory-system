package com.erp.sys.domain;

import java.time.OffsetDateTime;

/**
 * 单号规则（FR-V3-25）：定义前缀 / 日期格式 / 流水位长。
 *
 * <p>实际流水号仍由 {@code document_sequences} 原子分配
 * （见 {@code com.erp.shared.document.DocumentNumberGenerator}），
 * 本规则只决定"号长什么样"。
 *
 * @param id          主键
 * @param docType     单据类型（与 DocumentNumberGenerator.DocType 对齐）
 * @param prefix      前缀
 * @param datePattern 日期格式（yyyyMMdd / yyyyMM / yyyy）
 * @param seqLength   流水位长（2~12）
 * @param resetCycle  重置周期 NONE / DAILY / MONTHLY / YEARLY
 * @param enabled     是否启用
 * @param createdAt   创建时间
 * @param updatedAt   更新时间
 */
public record NumberRule(
        long id,
        String docType,
        String prefix,
        String datePattern,
        int seqLength,
        String resetCycle,
        boolean enabled,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
