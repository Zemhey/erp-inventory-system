package com.erp.rpt.domain;

/**
 * 聚合报表的排序方式（契约 {@code orderBy}：key | amount，默认 key）。
 *
 * <p>默认值 {@code KEY} 就是既有行为 —— {@code ORDER BY 1}（分组键字典序/时间序），
 * 所以不传这个参数的老调用方看到的顺序一个不变。
 *
 * <p>{@code AMOUNT} 是为 {@code limit} 服务的：TOP N 只有在"按金额降序"上截断
 * 才是前 N 名，否则切掉的是字典序靠后的行，等于随机丢数据。
 */
public enum ReportOrderBy {

    /** 按分组键升序（既有默认）。 */
    KEY,
    /** 按金额（分）降序，同额再按分组键升序，保证结果顺序确定可复现。 */
    AMOUNT
}
