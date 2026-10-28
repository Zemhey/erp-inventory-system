package com.erp.nl.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 复核结论（FR-V4-03 · S4）：**另一个复核方对计划本身的意见**。
 *
 * <p><b>它和 {@link NlPlan} 的区别</b>：计划是"提案"，复核是对提案的"评审意见"。
 * 两者都不是执行结果。把三者（plan / review / result）分成三个类型，
 * 是因为它们在审计里回答三个不同问题：想做什么、谁挑了什么毛病、最后到底干了什么。
 *
 * <p><b>它和权限的区别</b>：权限回答"你能不能做"，复核对"这件事本身对不对"提意见。
 * 复核结论不改变权限判定，反之亦然——一个越权的人即使计划完美也执行不了。
 *
 * @param verdict  合并后的最终结论
 * @param blocking 是否阻断（等价于 {@code verdict == REJECT}，单独给出是为了前端少写判断）
 * @param source   结论来源：{@code RULE} / {@code AI} / {@code RULE+AI}
 * @param summary  一句话结论，直接给人看
 * @param findings 逐条意见，带来源与等级
 */
public record NlReview(
        NlVerdict verdict,
        boolean blocking,
        String source,
        String summary,
        List<NlFinding> findings) {

    public NlReview {
        findings = findings == null ? List.of() : List.copyOf(findings);
    }

    public static NlReview of(NlVerdict verdict, String source, String summary, List<NlFinding> findings) {
        return new NlReview(verdict, verdict.blocksExecution(), source, summary, findings);
    }

    /**
     * 合并另一方意见：结论永远取**更严格**的那个。
     *
     * <p>这条规则就是"A I 复核只能否决或降级、不能授权"的实现形式：
     * 规则说 {@code REJECT}、模型说 {@code APPROVE}，合并结果仍是 {@code REJECT}——
     * 模型无法把自己的"不反对"变成通行证，也无法把已有的否决抬回去。
     *
     * <p>摘要取更严格那一方的原文，避免出现"结论是否决、摘要却写着没问题"。
     */
    public NlReview merge(NlReview other) {
        if (other == null) {
            return this;
        }
        NlVerdict merged = verdict.stricter(other.verdict);
        List<NlFinding> all = new ArrayList<>(findings);
        all.addAll(other.findings);
        // 模型只是"不反对"时补一句，防止界面把 APPROVE 读成"AI 批准了"
        // （来源已经由 finding.source 标明，这里不再重复"AI"两个字，避免渲染成「AI · AI 复核…」）
        if (other.verdict == NlVerdict.APPROVE && other.source != null && other.source.startsWith("AI")) {
            all.add(NlFinding.info(other.source,
                    "未提出反对意见 —— 这不等于批准，执行仍需人工确认"));
        }
        String mergedSource = source.equals(other.source) ? source : source + "+" + other.source;
        String mergedSummary = merged == verdict ? summary : other.summary;
        return new NlReview(merged, merged.blocksExecution(), mergedSource, mergedSummary, all);
    }

    /** 追加一条说明，不改变结论（用于记录"某一层没跑起来"这类事实）。 */
    public NlReview withNote(String message) {
        List<NlFinding> all = new ArrayList<>(findings);
        all.add(NlFinding.info("SYSTEM", message));
        return new NlReview(verdict, blocking, source, summary, all);
    }
}
