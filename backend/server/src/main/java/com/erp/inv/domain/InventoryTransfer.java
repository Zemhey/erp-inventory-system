package com.erp.inv.domain;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 仓间调拨单（FR-INV-07）。
 *
 * <p>守恒口径（INV-7）：
 * {@code Σ源仓 TRANSFER_OUT + Σ在途量 + Σ目的仓 TRANSFER_IN == 0}。
 */
public record InventoryTransfer(
        long id,
        String transferNo,
        long fromWarehouseId,
        long toWarehouseId,
        TransferStatus status,
        LocalDate bizDate,
        String remark,
        OffsetDateTime receivedAt,
        OffsetDateTime createdAt,
        List<TransferLine> lines) {

    public int totalInTransitQty() {
        return lines.stream().mapToInt(TransferLine::inTransitQty).sum();
    }
}
