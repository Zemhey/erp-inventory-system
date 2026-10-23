package com.erp.inv.domain;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 盘点单（FR-INV-08）。
 *
 * <p>建单时把账面量快照到明细，过账时按 {@code diffQty} 生成调整流水；
 * 差异为 0 的行不产生任何流水。
 */
public record Stocktake(
        long id,
        String stocktakeNo,
        long warehouseId,
        StocktakeStatus status,
        LocalDate bizDate,
        String remark,
        OffsetDateTime postedAt,
        OffsetDateTime createdAt,
        List<StocktakeLine> lines) {

    public int totalDiffQty() {
        return lines.stream().mapToInt(StocktakeLine::diffQty).sum();
    }

    public boolean hasDifference() {
        return lines.stream().anyMatch(l -> l.diffQty() != 0);
    }
}
