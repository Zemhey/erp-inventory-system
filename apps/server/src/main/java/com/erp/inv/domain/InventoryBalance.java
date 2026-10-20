package com.erp.inv.domain;

import java.time.LocalDate;

/**
 * 库存余额（派生快照，ADR-0007）。
 *
 * @param availableQty 可用量 = onHandQty − reservedQty（不变量 INV-3，由数据库生成列保证）
 */
public record InventoryBalance(
        long id,
        long skuId,
        long warehouseId,
        String batchNo,
        LocalDate expiryDate,
        int onHandQty,
        int reservedQty,
        int availableQty,
        int version) {
}
