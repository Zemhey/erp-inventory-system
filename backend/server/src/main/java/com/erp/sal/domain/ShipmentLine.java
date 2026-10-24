package com.erp.sal.domain;

/**
 * 拣货行 = 一条 ACTIVE 预占分段。
 *
 * <p>plannedQty 来自预占量；扫码累计不得超过计划量。
 * 一条预占只能被一个拣货行占用（数据库唯一约束），防止重复计划。
 */
public record ShipmentLine(long id, int lineNo, long salesOrderLineId, long skuId,
                           long reservationId, String batchNo, int plannedQty, int pickedQty) {
}
