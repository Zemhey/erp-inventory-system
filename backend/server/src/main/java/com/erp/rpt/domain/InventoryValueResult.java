package com.erp.rpt.domain;

import java.util.List;

/**
 * 库存金额报表结果：明细行 + 覆盖全部池的汇总。
 *
 * <p>与 {@code customer-contribution} 同样的约定：{@code limit} 只截断<b>展示行</b>，
 * {@code summary} 始终覆盖全部行，所以页面合计不会因为是 Top N 而变小。
 */
public record InventoryValueResult(List<InventoryValueRow> items, Summary summary) {

    /**
     * @param poolCount         期末有库存的 (SKU, 仓库) 池总数
     * @param valuedPoolCount   其中已做成本月结（有成本金额）的池数
     * @param totalQty          全部池的期末数量合计（件）
     * @param valuedQty         已估值池的期末数量合计（件）
     * @param totalCostAmount   已估值池的期末成本合计，<b>单位分</b>
     * @param coverageRateBp    估值覆盖率（已估值池数 / 池总数，万分比整数）
     */
    public record Summary(int poolCount, int valuedPoolCount, long totalQty, long valuedQty,
                          long totalCostAmount, long coverageRateBp) {
    }
}
