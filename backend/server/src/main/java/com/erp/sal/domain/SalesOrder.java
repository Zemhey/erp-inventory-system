package com.erp.sal.domain;

import com.erp.shared.document.DocumentStatus;
import java.time.LocalDate;
import java.util.List;

/**
 * 出货单（FR-SAL-01/02）。
 *
 * <p>与进货单的区别：<b>建单即预占库存</b>。因此出货单有一种额外的"半成品"风险 ——
 * 建单时如果某一行库存不足，前面几行已经预占了，必须整单回滚，
 * 否则会出现"单子没建成，但库存被神秘锁住"的幽灵预占。
 *
 * @param id          主键
 * @param orderNo     单号，如 SO20250601-0001
 * @param status      状态
 * @param customerId  客户
 * @param warehouseId 出库仓库
 * @param bizDate     业务日期
 * @param totalAmount 单据总金额（分）
 * @param totalQty    单据总数量
 * @param period      账期 yyyy-MM
 * @param remark      备注
 * @param version     乐观锁版本号
 * @param lines       明细
 */
public record SalesOrder(long id, String orderNo, DocumentStatus status, long customerId,
                         long warehouseId, LocalDate bizDate, long totalAmount, int totalQty,
                         String period, String remark, int version,
                         List<SalesOrderLine> lines) {

    public SalesOrder {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    public boolean fullyShipped() {
        return lines.stream().allMatch(l -> l.shippedQty() >= l.qty());
    }

    public boolean anyShipped() {
        return lines.stream().anyMatch(l -> l.shippedQty() > 0);
    }
}
