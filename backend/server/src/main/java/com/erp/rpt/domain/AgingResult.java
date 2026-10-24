package com.erp.rpt.domain;

import java.util.List;

/** 账龄报表结果（契约 {@code AgingResult}）。 */
public record AgingResult(List<AgingRow> items, AgingSummary summary) {
}
