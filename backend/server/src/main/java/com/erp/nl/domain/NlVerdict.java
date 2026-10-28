package com.erp.nl.domain;

import java.util.Locale;

/**
 * 复核结论（FR-V4-03 · S4）。
 *
 * <p><b>三档刻意不对称</b>：{@link #APPROVE} 的含义是"我不反对"，不是通行证；
 * {@link #NEED_HUMAN} 是复核者说"我看不清，别让我替你决定"；{@link #REJECT} 才是硬拦截。
 * 如果 APPROVE 就等于放行，等于把决策权交给了模型——两个模型一起错时没人拦得住。
 *
 * <p>枚举声明顺序即严格程度递增，{@link #stricter} 依赖这一点合并多方意见。
 */
public enum NlVerdict {

    /** 不反对。**不等于**批准，执行仍需人工确认。 */
    APPROVE,

    /** 需要人仔细看：参数可疑、影响面大、或复核者自己拿不准。 */
    NEED_HUMAN,

    /** 确定有问题，不允许执行（人也不能凭这条计划直接放行，得先改指令）。 */
    REJECT;

    /** 是否阻断执行。只有 {@link #REJECT} 阻断。 */
    public boolean blocksExecution() {
        return this == REJECT;
    }

    /** 取更严格的一方：多方复核时永远往"更谨慎"的方向合并，不允许互相抵消。 */
    public NlVerdict stricter(NlVerdict other) {
        if (other == null) {
            return this;
        }
        return ordinal() >= other.ordinal() ? this : other;
    }

    public static NlVerdict fromCode(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (NlVerdict verdict : values()) {
            if (verdict.name().equals(normalized)) {
                return verdict;
            }
        }
        return null;
    }
}
