package com.erp.cst.domain;

import java.time.OffsetDateTime;

/**
 * 成本回放输入：一条库存流水的成本计算所需信息。
 *
 * @param ledgerId             inventory_ledger.id
 * @param explicitUnitCostAmount 流水自带的单位成本（进货/进货红冲有值，其余通常为 null）
 * @param originSalesOrderNo   退货流水对应的原出货单号（由应用层从 return_orders 解析）；
 *                             非退货流水为 null
 * @param occurredAt           业务时间，回放顺序的第一关键字（第二关键字是 ledgerId）
 */
public record CostEvent(
        long ledgerId,
        long skuId,
        long warehouseId,
        String batchNo,
        int changeQty,
        String bizType,
        String bizRefNo,
        Long explicitUnitCostAmount,
        String originSalesOrderNo,
        OffsetDateTime occurredAt) {

    public CostEvent {
        batchNo = batchNo == null ? "" : batchNo;
    }

    public boolean inbound() {
        return changeQty > 0;
    }

    public int qty() {
        return Math.abs(changeQty);
    }
}
