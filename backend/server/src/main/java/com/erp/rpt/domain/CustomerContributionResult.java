package com.erp.rpt.domain;

import java.util.List;

/**
 * 客户贡献报表结果（契约 {@code CustomerContributionResult}）。
 *
 * @param items   Top N 客户，按金额降序
 * @param summary 全量客户汇总
 */
public record CustomerContributionResult(
        List<CustomerContributionRow> items,
        CustomerContributionSummary summary) {
}
