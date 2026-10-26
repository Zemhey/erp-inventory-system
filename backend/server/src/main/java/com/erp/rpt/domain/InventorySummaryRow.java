package com.erp.rpt.domain;

/**
 * 进销存汇总行（数量来自库存流水，金额来自成本月结）。
 *
 * <p><b>★ V4-4 追加字段（纯加法）</b>：原有 6 个数量字段的名字、类型、顺序一个没动，
 * 前端 11 张报表按现状消费的部分不受影响。新增的两组字段都是<b>可空</b>：
 * <ul>
 *   <li>{@code skuCode} / {@code skuName} / {@code warehouseName}：与
 *       {@code /reports/inventory-turnover} <b>逐字相同</b>的取名口径
 *       （页面名 = 商品名 · 颜色-尺码 / 商品名 颜色/尺码、停用仓库加「（已停用）」、
 *       主数据缺失时回退 {@code SKU#id} / {@code 仓库#id}）。此前只有 CSV 侧读名称列，
 *       JSON 丢掉名称，前端只能自己拿 {@code /skus} 拼字典（还被每页 200 条上限卡住）。</li>
 *   <li>四个金额列：来源 {@code cost_monthly}（成本月结按账期 × SKU × 仓库落的一行），
 *       <b>单位分</b>（ADR-0005，与 rpt/pur/sal 三域一致）。该账期尚未做成本月结、
 *       或该 (SKU, 仓库) 不在月结结果里时为 {@code null} —— 刻意不返回 0，
 *       因为「没算」与「算出来是 0」在报表上是两回事。</li>
 * </ul>
 *
 * <p>金额列来自月结，数量列来自流水，两者<b>口径不同源</b>但同一页面列示、互不推算
 * （数量不许用金额反推，反之亦然）。
 */
public record InventorySummaryRow(long skuId, long warehouseId,
                                  long openingQty, long inboundQty,
                                  long outboundQty, long closingQty,
                                  String skuCode, String skuName, String warehouseName,
                                  Long openingCostAmount, Long inboundCostAmount,
                                  Long outboundCostAmount, Long closingCostAmount) {

    /** 兼容旧构造：只给数量（测试与历史调用点仍可用）。 */
    public InventorySummaryRow(long skuId, long warehouseId, long openingQty, long inboundQty,
                               long outboundQty, long closingQty) {
        this(skuId, warehouseId, openingQty, inboundQty, outboundQty, closingQty,
                null, null, null, null, null, null, null);
    }
}
