package com.erp.fin.domain;

/**
 * 会计科目六大类（简报 §3：category 取值）。
 *
 * <p>{@code normalDirection} 是"正常余额方向"的业务常识（资产借、负债贷……），
 * 但科目表里 {@link AccountDirection} 允许偏离它 —— 真实会计里就存在
 * "应交税费-进项税额"这种负债类借方余额科目（见 V20 种子 222101），
 * 所以这里只做展示与报表分组合计的依据，不做强校验。
 */
public enum AccountCategory {
    ASSET("资产", AccountDirection.DEBIT),
    LIABILITY("负债", AccountDirection.CREDIT),
    EQUITY("权益", AccountDirection.CREDIT),
    COST("成本", AccountDirection.DEBIT),
    REVENUE("收入", AccountDirection.CREDIT),
    EXPENSE("费用", AccountDirection.DEBIT);

    private final String label;
    private final AccountDirection normalDirection;

    AccountCategory(String label, AccountDirection normalDirection) {
        this.label = label;
        this.normalDirection = normalDirection;
    }

    public String label() {
        return label;
    }

    public AccountDirection normalDirection() {
        return normalDirection;
    }
}
