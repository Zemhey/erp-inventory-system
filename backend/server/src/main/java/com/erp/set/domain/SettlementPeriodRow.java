package com.erp.set.domain;

import java.time.OffsetDateTime;

/**
 * 账期列表的一行（契约 {@code SettlementPeriodRow}，<b>只读视图</b>）。
 *
 * <p><b>为什么不复用 {@link SettlementPeriod}</b>：那个聚合是锁账的事实源，带着
 * {@code version}（乐观锁，属服务端内部并发控制，契约里不该出现），却没有
 * {@code closedBy}。本视图正好相反 —— 面向结算页展示，要多一个操作人、
 * 不要版本号。两者各自演进，比塞进一个五字段记录再到处省略要诚实。
 *
 * @param closedBy 锁账操作人；月结路径目前不写这一列，历史行一律为 null
 *                 （计划书 §6 坑 15 的读出口只负责如实呈现，不负责补数据）
 */
public record SettlementPeriodRow(String period, SettlementPeriodStatus status,
                                  SettlementTotals totals, OffsetDateTime closedAt, Long closedBy) {
}
