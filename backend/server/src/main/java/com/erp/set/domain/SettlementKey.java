package com.erp.set.domain;

import java.time.LocalDate;

/**
 * 日清行的自然键：{@code (业务日期, 仓库)}。
 *
 * <p>它与数据库的 {@code uq_daily_settlement} 一一对应 ——
 * 日清的幂等不是"先查再插"，而是"这个键上只允许有一行"。
 * 把键显式建成类型，重算值与已存值才能在同一个 Map 里对齐比较。
 */
public record SettlementKey(LocalDate bizDate, long warehouseId) {
}
