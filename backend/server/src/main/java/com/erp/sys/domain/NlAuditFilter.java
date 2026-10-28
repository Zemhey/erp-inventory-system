package com.erp.sys.domain;

import java.time.Instant;

/**
 * 自然语言操作审计的查询条件（FR-V4-03 · S6）。
 *
 * <p>字段与 {@code GET /audits} 保持同一套维度（人 / 时间 / 类型 / 结果），
 * 这样两处审计查询的心智模型一致，不用另学一遍。
 *
 * <p>所有字段都可空：`null` 表示"这一维不筛"。**不做模糊匹配**——
 * 审计查询要的是可复现，`%杭州%` 这种条件事后没法解释"当时到底筛了什么"。
 *
 * @param status  状态（PENDING / EXECUTED / …）
 * @param intent  意图代码
 * @param actorId 发起人
 * @param source  调用方（WEB / MCP / API）
 * @param write   只看写操作 / 只看只读
 * @param from    起始时间（含）
 * @param to      截止时间（不含）
 */
public record NlAuditFilter(
        String status,
        String intent,
        Long actorId,
        String source,
        Boolean write,
        Instant from,
        Instant to) {

    public static NlAuditFilter none() {
        return new NlAuditFilter(null, null, null, null, null, null, null);
    }

    public NlAuditFilter {
        status = blankToNull(status);
        intent = blankToNull(intent);
        source = blankToNull(source);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
