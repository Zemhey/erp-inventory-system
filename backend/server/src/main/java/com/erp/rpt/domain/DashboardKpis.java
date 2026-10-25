package com.erp.rpt.domain;

/** 工作台 KPI 汇总（FR-V3-02）。金额单位：分。 */
public record DashboardKpis(
        long productTotal,
        long skuTotal,
        long customerTotal,
        long supplierTotal,
        long purchaseOrderTotal,
        long salesOrderTotal,
        long inventoryRowTotal,
        long lowStockTotal) {
}
