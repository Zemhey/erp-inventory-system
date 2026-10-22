package com.erp.rma.domain;

import com.erp.shared.document.DocumentStatus;
import java.time.LocalDate;
import java.util.List;

/**
 * 退货单（FR-RMA-01）。
 *
 * <p>与出货单的方向相反：出货是"预占 → 扣实存"，退货是"回补实存"。
 * 因此退货单<b>不需要预占</b>，也<b>没有 PARTIAL</b> —— 入库是整单一次性完成。
 *
 * @param id                 主键
 * @param orderNo            单号，如 RO20261022-0001
 * @param status             状态（DRAFT/SUBMITTED/APPROVED/COMPLETED/VOIDED/REJECTED）
 * @param originSalesOrderId 原出货单，数量上限与派生字段都来自它
 * @param customerId         客户（从原出货单派生）
 * @param warehouseId        回补仓库（从原出货单派生）
 * @param bizDate            业务日期，日清与账期归属以它为准
 * @param totalAmount        退货总金额（分）
 * @param totalQty           退货总数量
 * @param period             账期 yyyy-MM
 * @param remark             备注
 * @param version            乐观锁版本号
 * @param receiveRequestId   入库幂等键；null 表示尚未入库
 * @param lines              明细
 */
public record ReturnOrder(long id, String orderNo, DocumentStatus status, long originSalesOrderId,
                          long customerId, long warehouseId, LocalDate bizDate, long totalAmount,
                          int totalQty, String period, String remark, int version,
                          String receiveRequestId, List<ReturnOrderLine> lines) {

    public ReturnOrder {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }
}
