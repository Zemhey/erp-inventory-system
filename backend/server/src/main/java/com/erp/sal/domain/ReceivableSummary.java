package com.erp.sal.domain;

/**
 * 应收汇总（FR-SAL-07）。
 *
 * <p>放在 domain：Controller 需要读取这些字段，
 * 而 ArchUnit 禁止 api 层依赖 repository（包括 repository 的嵌套类型）。
 */
public record ReceivableSummary(long shippedAmount, long returnedAmount, long receivedAmount,
                                long balance, long overdueAmount) {
}
