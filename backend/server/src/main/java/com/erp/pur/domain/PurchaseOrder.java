package com.erp.pur.domain;

import com.erp.shared.document.DocumentStatus;
import java.time.LocalDate;
import java.util.List;

/**
 * 进货单（FR-PUR-01/02）。
 *
 * @param id          主键
 * @param orderNo     单号，如 PO20250601-0001
 * @param status      状态（七态，见 {@link com.erp.shared.document.DocumentStatus}）
 * @param supplierId  供应商
 * @param warehouseId 入库仓库
 * @param bizDate     业务日期（由 ClockProvider 注入，不是 now()）
 * @param totalAmount 单据总金额（分），由明细汇总，不允许反算
 * @param totalQty    单据总数量
 * @param period      账期 yyyy-MM（M2 月结锁账用）
 * @param remark      备注
 * @param version     乐观锁版本号
 * @param lines       明细（按行号排序）
 */
public record PurchaseOrder(long id, String orderNo, DocumentStatus status, long supplierId,
                            long warehouseId, LocalDate bizDate, long totalAmount, int totalQty,
                            String period, String remark, int version,
                            List<PurchaseOrderLine> lines) {

    public PurchaseOrder {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /** 是否所有明细都已足额入库。 */
    public boolean fullyReceived() {
        return lines.stream().allMatch(l -> l.receivedQty() >= l.qty());
    }

    /** 是否有任一行已入库（用于判断进 PARTIAL 还是 COMPLETED）。 */
    public boolean anyReceived() {
        return lines.stream().anyMatch(l -> l.receivedQty() > 0);
    }
}
