package com.erp.cst.domain;

/** 成本池状态：数量 + 金额（分）。 */
public record CostPoolState(long qty, long costAmount) {

    public static CostPoolState empty() {
        return new CostPoolState(0, 0);
    }
}
