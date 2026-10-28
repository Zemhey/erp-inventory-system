package com.erp.sys.domain;

import java.util.Locale;

/**
 * 一条自然语言操作记录的状态（FR-V4-03 · S5/S6）。
 *
 * <p><b>为什么是七个状态而不是"待确认 / 已执行"两个</b>：
 * 审计的价值一半在"做成了什么"，另一半在"**被什么挡住了**"。
 * 把"参数不全""复核否决""人主动驳回""过期作废""执行时报错"混成一个"失败"，
 * 事后就没法回答"这条到底是 AI 抽错参数，还是人改主意了"。
 *
 * <p><b>只有 {@link #PENDING} 会进待确认队列</b>，其余六种都只出现在审计里。
 */
public enum NlExecutionStatus {

    /** 可以执行，正在等人点确认。超过有效期自动结转 EXPIRED。 */
    PENDING("待确认"),

    /** 已经执行并落库（例如建出了草稿单）。 */
    EXECUTED("已执行"),

    /** 人点了确认，但执行时业务报错（缺权限、账期锁定、库存不足…）。 */
    FAILED("执行失败"),

    /** 超过有效期没人处理，自动作废。 */
    EXPIRED("已过期"),

    /** 人主动驳回："这条我不批"。 */
    DISMISSED("已驳回"),

    /** 计划本身不可执行（缺参数、引用的对象不存在），根本没资格进队列。 */
    BLOCKED("不可执行"),

    /** 复核否决（规则或 AI 判定 REJECT）。 */
    VETOED("被否决");

    private final String label;

    NlExecutionStatus(String label) {
        this.label = label;
    }

    /** 中文标签，直接给界面用；集中在这里，避免前端再维护一份映射。 */
    public String label() {
        return label;
    }

    public boolean pending() {
        return this == PENDING;
    }

    public static NlExecutionStatus fromCode(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        for (NlExecutionStatus status : values()) {
            if (status.name().equals(normalized)) {
                return status;
            }
        }
        return null;
    }
}
