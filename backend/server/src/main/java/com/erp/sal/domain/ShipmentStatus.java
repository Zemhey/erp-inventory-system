package com.erp.sal.domain;

/** 发货单状态（FR-SAL-06）。不是共享单据状态机：发货单不参与收付款/账期统计。 */
public enum ShipmentStatus {
    DRAFT,
    PICKING,
    PICKED,
    COMPLETED,
    VOIDED
}
