package com.erp.inv.domain;

import java.time.OffsetDateTime;

/** 库存冻结（FR-INV-10）：冻结量不进可用量，超时按 {@code expiresAt} 自动解冻。 */
public record InventoryFreeze(
        long id,
        long skuId,
        long warehouseId,
        String batchNo,
        int qty,
        String reason,
        FreezeStatus status,
        OffsetDateTime expiresAt,
        OffsetDateTime releasedAt,
        OffsetDateTime createdAt) {
}
