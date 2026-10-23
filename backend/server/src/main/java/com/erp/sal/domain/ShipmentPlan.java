package com.erp.sal.domain;

import java.util.List;

/** 一次发货计划：同键重放返回同一批按仓发货单。 */
public record ShipmentPlan(long id, String planNo, long salesOrderId, String requestId,
                           List<Shipment> shipments) {

    public ShipmentPlan {
        shipments = shipments == null ? List.of() : List.copyOf(shipments);
    }
}
