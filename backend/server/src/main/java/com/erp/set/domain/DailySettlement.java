package com.erp.set.domain;

import java.time.LocalDate;

/**
 * 一条日清行（{@code daily_settlements}）。
 *
 * <p>既用于"重算出来的期望值"，也用于"数据库里已存的值"，
 * 这样 {@code SettlementApplicationService} 可以直接比对两者，
 * 发现漂移时报 {@code SET_ALREADY_SETTLED} 而不是静默覆盖。
 */
public record DailySettlement(LocalDate bizDate, long warehouseId, SettlementTotals totals) {

    public SettlementKey key() {
        return new SettlementKey(bizDate, warehouseId);
    }
}
