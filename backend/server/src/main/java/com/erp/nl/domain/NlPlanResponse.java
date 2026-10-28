package com.erp.nl.domain;

/**
 * `/nl/plan` 的完整应答（FR-V4-03 · S4）：**计划 + 复核意见 + 确认凭据**。
 *
 * <p>三样东西一起给，是刻意的：调用方拿到计划的同时就知道"另一个复核方怎么看"、
 * "要执行还缺什么"。少任何一样，调用方就得再发一次请求——
 * 而"多一次往返"在 Agent 场景里就意味着多一次幻觉机会。
 *
 * @param plan         提案（将要做什么）
 * @param review       复核意见（谁挑了什么毛病）
 * @param confirmation 确认凭据（执行时需要原样带回）
 */
public record NlPlanResponse(NlPlan plan, NlReview review, NlConfirmation confirmation) {
}
