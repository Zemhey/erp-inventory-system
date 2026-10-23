package com.erp.set.domain;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 一条账期（{@code settlement_periods}）：期间汇总 + 锁账状态。
 *
 * @param period    账期 yyyy-MM
 * @param status    OPEN / CLOSED，只有 CLOSED 拦写
 * @param totals    期间汇总六量（net 由它派生）
 * @param closedAt  锁账时刻，来自注入时钟；OPEN 时为 null
 * @param version   乐观锁版本，并发月结时防丢更新
 */
public record SettlementPeriod(String period, SettlementPeriodStatus status,
                               SettlementTotals totals, OffsetDateTime closedAt, int version) {

    public boolean locked() {
        return status.locked();
    }
}
