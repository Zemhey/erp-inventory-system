package com.erp.mdm.domain;

/**
 * 仓库库位。
 *
 * <p>库存余额的维度仍是 {@code (SKU, 仓库, 批次)}（V1 设计），库位不参与库存数量；
 * 它承担的是“仓内分区 + 容量分配”。因此守恒口径是
 * {@code SUM(启用库位 capacity_qty) == warehouses.capacity_qty}。
 *
 * <p>停用只置 {@code enabled=false}，不删除、不改历史库存与流水。
 */
public record WarehouseLocation(long id, long warehouseId, String locationCode,
                                String name, long capacityQty, boolean enabled) {
}
