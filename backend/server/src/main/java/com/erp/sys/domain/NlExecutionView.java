package com.erp.sys.domain;

import java.time.Instant;
import java.util.Map;

/**
 * 给界面用的记录视图：在 {@link NlExecution} 之上把两份 JSON 解析成结构，
 * 并补一个中文状态标签。
 *
 * <p><b>为什么解析放在服务端而不是让前端 parse</b>：
 * 队列页要直接渲染"影响预演"和"复核意见"，前端再解一次字符串等于把展示逻辑
 * 复制到两个地方；而且 JSON 解析失败时，服务端可以退化成一个空对象让页面照常渲染，
 * 前端解析失败就只能白屏。
 *
 * @param plan   计划快照（解析后）。解析失败时是空 Map，页面仍可显示 commandText。
 * @param review 复核快照（解析后）。同上。
 */
public record NlExecutionView(
        long id,
        String requestId,
        long actorId,
        String actorName,
        String source,
        String commandText,
        String intent,
        boolean write,
        String status,
        String statusLabel,
        String verdict,
        String reviewSource,
        String summary,
        Map<String, Object> plan,
        Map<String, Object> review,
        String docType,
        Long docId,
        String docNo,
        String errorMessage,
        Instant createdAt,
        Instant expiresAt,
        Instant decidedAt,
        Long decidedBy,
        String decidedByName) {
}
