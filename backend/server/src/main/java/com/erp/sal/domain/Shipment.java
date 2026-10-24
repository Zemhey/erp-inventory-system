package com.erp.sal.domain;

import java.util.List;

/**
 * 按仓拆分的发货单（FR-SAL-06/11）。
 *
 * @param planId  所属发货计划；同一计划可含多张按仓发货单
 * @param status   DRAFT → PICKING → PICKED → COMPLETED
 */
public record Shipment(long id, String shipmentNo, long planId, long salesOrderId,
                       long warehouseId, ShipmentStatus status, String confirmRequestId,
                       int version, List<ShipmentLine> lines) {

    public Shipment {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    public boolean fullyPicked() {
        return !lines.isEmpty() && lines.stream().allMatch(l -> l.pickedQty() >= l.plannedQty());
    }
}
