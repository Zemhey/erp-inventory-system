package com.erp.rpt.domain;

/**
 * 经营报表的时间粒度（契约 {@code dimension}：day / month）。
 *
 * <p>{@code month} 覆盖 from/to 所在的完整自然月：首月从月初开始、末月到月末结束。
 * 只有按完整自然月取数，每月一行的「期初 + 入 − 出 = 期末」才成立。
 */
public enum ReportPeriodDim {

    /** 逐自然日，覆盖 [from, to]，空日也出行。 */
    DAY,
    /** 按自然月，覆盖 from/to 所在的完整月份。 */
    MONTH
}
