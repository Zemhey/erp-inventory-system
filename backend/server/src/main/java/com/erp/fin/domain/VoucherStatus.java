package com.erp.fin.domain;

/**
 * 凭证状态机：{@code DRAFT → AUDITED → POSTED}（FR-V3-07）。
 *
 * <p>POSTED 后不可改（简报口径：只能红冲，红冲属下期能力）。
 * 业务单据自动生成的凭证直接落 POSTED —— 单据"确认"本身就是财务意义上的
 * 记账触发点，若停在 DRAFT，报表（只统计 POSTED）会与业务事实脱节，
 * 验收"进货入库后资产负债表配平"就不可能成立。
 *
 * <p>刻意不复用 {@code shared.document.DocumentStatus}：单据七态
 * （SUBMITTED/APPROVED/REJECTED/PARTIAL/COMPLETED/VOIDED…）与凭证三态语义不同，
 * 混用会让"单据状态机不得绕过"的 ArchUnit 规则把凭证也拖进它的辖区。
 */
public enum VoucherStatus {
    DRAFT("草稿"),
    AUDITED("已审核"),
    POSTED("已过账");

    private final String label;

    VoucherStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** 允许从本状态转移到 from 吗（线性状态机，无跳档、无回退）。 */
    public boolean canTransitionTo(VoucherStatus target) {
        return switch (this) {
            case DRAFT -> target == AUDITED;
            case AUDITED -> target == POSTED;
            case POSTED -> false;
        };
    }
}
