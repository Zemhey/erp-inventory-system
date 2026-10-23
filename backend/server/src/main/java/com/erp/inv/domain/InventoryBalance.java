package com.erp.inv.domain;

import java.time.LocalDate;

/**
 * 库存余额（派生快照，ADR-0007）。
 *
 * @param frozenQty    冻结量（FR-INV-10）：不可预占、不可出库、不可调拨
 * @param availableQty 可用量 = onHandQty − reservedQty − frozenQty
 *                     （不变量 INV-3，由数据库生成列保证）
 */
public record InventoryBalance(
        long id,
        long skuId,
        long warehouseId,
        String batchNo,
        LocalDate productionDate,
        LocalDate expiryDate,
        int onHandQty,
        int reservedQty,
        int frozenQty,
        int availableQty,
        int version) {
}
