package com.erp.inv.domain;

import java.time.OffsetDateTime;

/** 库存调整（FR-INV-09）：{@code changeQty} 有符号，原因必填。 */
public record InventoryAdjustment(
        long id,
        String adjustNo,
        long skuId,
        long warehouseId,
        String batchNo,
        int changeQty,
        String reason,
        OffsetDateTime createdAt) {
}
