package com.erp.nl.domain;

import java.util.List;
import java.util.Map;

/**
 * 执行计划（FR-V4-03 S3）：这条指令**打算做什么**，以及做完之后会发生什么。
 *
 * <p><b>它是纯描述，不是执行结果</b>——`/nl/plan` 只生成它，不落库、不动库存。
 * 这样"预览"这个动作天然幂等、天然安全，可以随便点。
 *
 * <p><b>为什么要单独一个类型而不是复用 {@link NlCommandResult}</b>：
 * 两者回答的是不同问题。`NlCommandResult` 说"发生了什么"，`NlPlan` 说"将要发生什么"，
 * 前者是既成事实（不可改），后者是提案（人可驳回）。混成一个类型，
 * 调用方迟早会分不清手上这份东西到底有没有执行过。
 *
 * @param intent         意图代码，与 {@link NlIntent#code()} 一致
 * @param write          是否会产生写操作
 * @param requiresReview 是否需要人工确认（分级：写操作才需要，只读不需要）
 * @param reviewReason   为什么需要/不需要审核（一句话，直接给人看）
 * @param executable     参数是否完整到可以执行；false 时看 {@code blockers}
 * @param summary        一句话摘要，是给人读的那一行
 * @param steps          将要做的步骤（按顺序，人能对着核）
 * @param impact         影响预演：{@code [{label, value}]}，给表格渲染
 * @param warnings       需要人注意但不阻止执行的点（单价异常、已停用…）
 * @param blockers       阻止执行的原因（缺参数、id 不存在…）
 * @param args           解析出的原始参数，供排查与审计
 */
public record NlPlan(
        String intent,
        boolean write,
        boolean requiresReview,
        String reviewReason,
        boolean executable,
        String summary,
        List<String> steps,
        List<Map<String, Object>> impact,
        List<String> warnings,
        List<String> blockers,
        Map<String, Object> args) {

    /** 影响预演的一行：{@code label} 是给人看的字段名，{@code value} 已格式化。 */
    public static Map<String, Object> row(String label, Object value) {
        return Map.of("label", label, "value", value == null ? "-" : String.valueOf(value));
    }
}
