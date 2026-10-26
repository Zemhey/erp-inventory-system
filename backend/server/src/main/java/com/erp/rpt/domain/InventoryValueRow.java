package com.erp.rpt.domain;

/**
 * 库存金额（库存总值）行 —— 按维度汇总的期末库存成本。
 *
 * <p><b>★ 单位分</b>（ADR-0005）：{@code costAmount} 是期末库存的<b>成本总额</b>，
 * {@code unitCostAmount} 是它的加权平均单价（= {@code costAmount / qty}，四舍五入到分）。
 * 本端点全程是成本口径，不出现售价、不出现元。
 *
 * <p>{@code costAmount} 为 {@code null} 表示该维度下<b>没有任何</b>已月结的 (SKU, 仓库) 池，
 * 即"这个账期还没有成本数"，不是"库存价值为 0"。
 */
public record InventoryValueRow(String key, String label, long qty,
                                Long unitCostAmount, Long costAmount,
                                int poolCount, int valuedPoolCount) {
}
