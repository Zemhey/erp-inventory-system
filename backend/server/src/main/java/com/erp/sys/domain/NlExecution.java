package com.erp.sys.domain;

import java.time.Instant;

/**
 * 一条自然语言操作的**完整记录**（FR-V4-03 · S5/S6）。
 *
 * <p><b>它和 {@code NlPlan} / {@code NlReview} 的关系</b>：那两个是"一次请求的产物"，
 * 用完就丢；这一行是"这件事的档案"，要留到审计结束。所以这里存的是
 * **当时生成的那两份 JSON 原文**，而不是外键或"按现在的代码重算"——
 * 审计认证的是"当时看到的东西"，代码演化了不该让历史记录跟着变。
 *
 * <p><b>为什么不复用 {@code audit_logs}</b>：那张表是 append-only 的"已发生事实"，
 * 而这件事有生命周期（PENDING → EXECUTED）。塞进去只有两个结果：要么改不动，
 * 要么把"待确认"写成不可更新的一行垃圾。所以单独一张有状态表，与 audit_logs 并列。
 *
 * @param id            主键
 * @param requestId     计划请求的 X-Request-Id（执行时由回执带回，用来串起前后）
 * @param actorId       发起人
 * @param actorName     发起人用户名（冗余存一份：查审计时不必再关联 app_users）
 * @param source        调用方：WEB / MCP / API
 * @param commandText   用户原话（审计最需要的就是它）
 * @param intent        解析出的意图
 * @param write         是否写操作
 * @param status        当前状态
 * @param verdict       复核结论（APPROVE / NEED_HUMAN / REJECT）
 * @param reviewSource  复核来源（RULE / AI / RULE+AI）
 * @param summary       计划摘要（列表里显示，不必解析 JSON）
 * @param planJson      计划快照
 * @param reviewJson    复核快照
 * @param docType       落成的单据类型（PURCHASE / SALES）
 * @param docId         落成的单据 id
 * @param docNo         落成的单据号
 * @param resultJson    执行结果快照
 * @param errorMessage  失败原因（只存一行，详细堆栈看日志）
 * @param createdAt     创建时间
 * @param expiresAt     待确认截止时间
 * @param decidedAt     拍板时间（确认或驳回）
 * @param decidedBy     拍板人
 * @param decidedByName 拍板人用户名
 */
public record NlExecution(
        long id,
        String requestId,
        long actorId,
        String actorName,
        String source,
        String commandText,
        String intent,
        boolean write,
        NlExecutionStatus status,
        String verdict,
        String reviewSource,
        String summary,
        String planJson,
        String reviewJson,
        String docType,
        Long docId,
        String docNo,
        String resultJson,
        String errorMessage,
        Instant createdAt,
        Instant expiresAt,
        Instant decidedAt,
        Long decidedBy,
        String decidedByName) {
}
