package com.erp.set.domain;

import java.time.LocalDate;

/**
 * 进货 / 退货口径的源数据行：某 (业务日期, 仓库) 的已执行数量与金额。
 *
 * <p>金额已在 SQL 里聚合完毕 —— 进货是"单价 × 入库量"的纯整数乘积，
 * 退货金额在建单时就已按 SAL 公式定稿存进行表，两者都不需要再舍入，
 * 交给数据库一次 SUM 比逐行取回 Java 更省。
 *
 * <p>与销售口径（{@link SalesSourceLine}）的区别正是要紧的地方：
 * 出货行金额要用<b>实际出货数量</b>重算，而折扣舍入必须与
 * {@code SalesOrderApplicationService.lineAmount} 逐位一致，所以那一路只能逐行回 Java。
 */
public record SourceAmountLine(LocalDate bizDate, long warehouseId, long qty, long amount) {

    public SettlementKey key() {
        return new SettlementKey(bizDate, warehouseId);
    }
}
