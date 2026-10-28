package com.erp.nl.domain;

/**
 * 复核意见的一条（FR-V4-03 · S4）。
 *
 * <p>{@code level} 只有三档：{@code INFO}（说明性，不影响结论）、{@code WARN}（要人看一眼）、
 * {@code REJECT}（这一条本身就足以否决）。
 *
 * <p>{@code source} 记录"是谁说的"：{@code RULE} = 确定性规则，{@code AI} = 复核模型。
 * 分开记是为了事后能回答"这条结论是人能复现的规则，还是模型说的"——
 * 把两者混成一句"系统认为有问题"，出问题时无从追溯。
 */
public record NlFinding(String level, String source, String message) {

    public static NlFinding info(String source, String message) {
        return new NlFinding("INFO", source, message);
    }

    public static NlFinding warn(String source, String message) {
        return new NlFinding("WARN", source, message);
    }

    public static NlFinding reject(String source, String message) {
        return new NlFinding("REJECT", source, message);
    }
}
