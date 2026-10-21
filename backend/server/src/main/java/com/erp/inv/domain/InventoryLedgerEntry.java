package com.erp.inv.domain;

import java.time.OffsetDateTime;

/**
 * 库存流水（不可变事实源，ADR-0007）。
 *
 * @param changeQty     正=入库，负=出库；不允许为 0
 * @param occurredAt    业务时间（ClockProvider 注入），不是数据库写入时间
 * @param unitCostAmount 入库时的单位成本（分），出库为 null
 */
public record InventoryLedgerEntry(
        long id,
        long skuId,
        long warehouseId,
        String batchNo,
        int changeQty,
        String bizType,
        String bizRefNo,
        Long unitCostAmount,
        OffsetDateTime occurredAt) {
}
