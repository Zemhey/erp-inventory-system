package com.erp.rpt.domain;

import java.math.BigDecimal;

/**
 * 库存周转与呆滞的一行（契约 {@code InventoryTurnoverRow}），粒度 (SKU, 仓库)。
 *
 * <p>周转天数 = {@code ((期初 + 期末) / 2) × 区间天数 / 区间出库}，保留一位小数；
 * 区间内没有出库时为 {@code null}（除零没有业务含义，页面显示 {@code -}）。
 *
 * <p>呆滞以观察日 {@code to} 为准：最近一次出库距今天数；从未出库时从首次流水日起算。
 * 只有期末仍有库存的行才出现在结果里 —— 零库存的 SKU 没有「周转」可谈。
 *
 * @param turnoverDays 周转天数，可为 null
 * @param idleDays     距最近出库（或首次流水）的天数
 * @param stale        {@code idleDays >= deadDays}
 */
public record InventoryTurnoverRow(
        long skuId,
        String skuCode,
        String skuName,
        long warehouseId,
        String warehouseName,
        long openingQty,
        long closingQty,
        long outboundQty,
        BigDecimal turnoverDays,
        long idleDays,
        boolean stale) {
}
