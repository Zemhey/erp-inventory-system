package com.erp.inv.domain;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 库存预警行（FR-INV-12）。
 *
 * @param threshold      触发阈值（安全库存下限 / 滞销天数 / 临期天数）
 * @param expiryDate     临期预警的效期（其他类型为 null）
 * @param lastMovementAt 滞销预警的最后一次出库时间（其他类型为 null）
 */
public record InventoryAlert(
        AlertType type,
        long skuId,
        long warehouseId,
        String batchNo,
        int onHandQty,
        int availableQty,
        int threshold,
        LocalDate expiryDate,
        OffsetDateTime lastMovementAt,
        int warehousePriority) {
}
