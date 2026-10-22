package com.erp.set.domain;

/**
 * 账期状态。与 {@code settlement_periods.status} 的 CHECK 约束同一取值集合。
 *
 * <p><b>只有 CLOSED 会拦写操作</b>（计划书 §6 坑 3）：OPEN 与"该行根本不存在"
 * 在锁账语义上完全等价 —— 生产默认数据不建期间行，测试库若残留 OPEN 行也不能误伤
 * M1 的 170 条基线用例。
 */
public enum SettlementPeriodStatus {
    OPEN,
    CLOSED;

    public boolean locked() {
        return this == CLOSED;
    }
}
