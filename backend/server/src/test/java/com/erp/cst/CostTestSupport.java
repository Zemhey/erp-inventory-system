package com.erp.cst;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.springframework.jdbc.core.JdbcTemplate;

/** 成本集成测试的直接造数工具：只碰流水与成本配对所需的最小单据行。 */
public final class CostTestSupport {

    private CostTestSupport() {
    }

    public static long ledger(JdbcTemplate jdbc, long skuId, long warehouseId, String batchNo,
                              int changeQty, String bizType, String bizRefNo, Long unitCost,
                              OffsetDateTime occurredAt) {
        return jdbc.queryForObject("""
                INSERT INTO inventory_ledger
                    (sku_id, warehouse_id, batch_no, change_qty, biz_type, biz_ref_no,
                     unit_cost_amount, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, skuId, warehouseId, batchNo, changeQty, bizType, bizRefNo,
                unitCost, occurredAt);
    }

    /** 成本配对只需要 sales_orders 的 id/order_no；不建明细，也不参与毛利。 */
    public static long stubSalesOrder(JdbcTemplate jdbc, String orderNo, long customerId,
                                      long warehouseId, LocalDate bizDate) {
        return jdbc.queryForObject("""
                INSERT INTO sales_orders
                    (order_no, status, customer_id, warehouse_id, biz_date,
                     total_amount, total_qty)
                VALUES (?, 'COMPLETED', ?, ?, ?, 0, 0)
                RETURNING id
                """, Long.class, orderNo, customerId, warehouseId, bizDate);
    }

    /** 成本配对只需要 return_orders 的原出货单；退货明细由真实 RMA 流程创建。 */
    public static long stubReturnOrder(JdbcTemplate jdbc, String orderNo, long originSalesOrderId,
                                       long customerId, long warehouseId, LocalDate bizDate) {
        return jdbc.queryForObject("""
                INSERT INTO return_orders
                    (order_no, status, origin_sales_order_id, customer_id, warehouse_id,
                     biz_date, total_amount, total_qty)
                VALUES (?, 'COMPLETED', ?, ?, ?, ?, 0, 0)
                RETURNING id
                """, Long.class, orderNo, originSalesOrderId, customerId, warehouseId, bizDate);
    }
}
