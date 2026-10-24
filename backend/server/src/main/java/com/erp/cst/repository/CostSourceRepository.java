package com.erp.cst.repository;

import com.erp.cst.domain.CostEvent;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 成本引擎的<b>只读</b>来源仓储。
 *
 * <p>为什么 cst 不注入 {@code InventoryLedgerRepository}：
 * 架构测试规定 INV 的仓储不对外暴露；成本引擎只读流水事实源，
 * 用本模块自己的 SQL 读取 {@code inventory_ledger}，不碰余额、预占与任何写入口。
 *
 * <p>退货的原出货单号在查询里一次性 LEFT JOIN 出来，避免回放过程中按流水逐条查库
 * （N+1 会让一次月结从几十毫秒变成几秒）。
 */
@Repository
public class CostSourceRepository {

    private static final RowMapper<CostEvent> EVENT_MAPPER = (rs, rowNum) -> new CostEvent(
            rs.getLong("id"),
            rs.getLong("sku_id"),
            rs.getLong("warehouse_id"),
            rs.getString("batch_no"),
            rs.getInt("change_qty"),
            rs.getString("biz_type"),
            rs.getString("biz_ref_no"),
            rs.getObject("unit_cost_amount", Long.class),
            rs.getString("origin_sales_order_no"),
            rs.getObject("occurred_at", OffsetDateTime.class));

    private static final String EVENT_SELECT = """
            SELECT l.id, l.sku_id, l.warehouse_id, l.batch_no, l.change_qty, l.biz_type,
                   l.biz_ref_no, l.unit_cost_amount, l.occurred_at,
                   so.order_no AS origin_sales_order_no
              FROM inventory_ledger l
              LEFT JOIN return_orders ro
                     ON l.biz_type = 'RETURN_IN' AND ro.order_no = l.biz_ref_no
              LEFT JOIN sales_orders so ON so.id = ro.origin_sales_order_id
            """;

    private final JdbcTemplate jdbc;

    public CostSourceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<CostEvent> events() {
        return jdbc.query(EVENT_SELECT + " ORDER BY l.occurred_at, l.id", EVENT_MAPPER);
    }

    public List<CostEvent> events(long skuId, long warehouseId) {
        return jdbc.query(EVENT_SELECT + """
                 WHERE l.sku_id = ? AND l.warehouse_id = ?
                 ORDER BY l.occurred_at, l.id
                """, EVENT_MAPPER, skuId, warehouseId);
    }

    // ─────────────────────────── 毛利来源 ───────────────────────────

    /**
     * 期间内已出货的销售行。金额不在 SQL 里算：折扣舍入必须走 MoneyMath。
     */
    public List<SalesLineSource> shippedSalesLines(LocalDate from, LocalDate to) {
        return jdbc.query("""
                SELECT so.id AS order_id, so.order_no, so.customer_id, so.warehouse_id,
                       so.biz_date, sol.sku_id, sol.shipped_qty, sol.returned_qty,
                       sol.unit_price_amount, sol.discount_rate
                  FROM sales_orders so
                  JOIN sales_order_lines sol ON sol.order_id = so.id
                 WHERE so.biz_date BETWEEN ? AND ?
                   AND sol.shipped_qty > 0
                 ORDER BY so.id, sol.sku_id, sol.id
                """, (rs, i) -> new SalesLineSource(
                rs.getLong("order_id"), rs.getString("order_no"), rs.getLong("customer_id"),
                rs.getLong("warehouse_id"), rs.getDate("biz_date").toLocalDate(),
                rs.getLong("sku_id"), rs.getInt("shipped_qty"), rs.getInt("returned_qty"),
                rs.getLong("unit_price_amount"), rs.getInt("discount_rate")), from, to);
    }

    /** 期间内出货单的退货数量与金额（按出货单 × SKU 汇总）。 */
    public List<AmountSource> returnAmounts(LocalDate from, LocalDate to) {
        return jdbc.query("""
                SELECT so.id AS order_id, sol.sku_id,
                       COALESCE(SUM(rol.qty), 0) AS qty,
                       COALESCE(SUM(rol.amount), 0) AS amount
                  FROM return_order_lines rol
                  JOIN return_orders ro ON ro.id = rol.order_id
                  JOIN sales_order_lines sol ON sol.id = rol.origin_line_id
                  JOIN sales_orders so ON so.id = sol.order_id
                 WHERE so.biz_date BETWEEN ? AND ?
                   AND ro.status = 'COMPLETED'
                 GROUP BY so.id, sol.sku_id
                """, (rs, i) -> new AmountSource(
                rs.getLong("order_id"), rs.getLong("sku_id"),
                rs.getLong("qty"), rs.getLong("amount")), from, to);
    }

    /** 期间内出货单的 SALES_OUT 成本（来自成本层）。 */
    public List<CostAmountSource> salesOutCosts(LocalDate from, LocalDate to) {
        return jdbc.query("""
                SELECT so.id AS order_id, cl.sku_id,
                       COALESCE(SUM(cl.qty), 0) AS qty,
                       COALESCE(SUM(cl.total_cost_amount), 0) AS cost_amount
                  FROM cost_layers cl
                  JOIN sales_orders so ON so.order_no = cl.biz_ref_no
                 WHERE cl.biz_type = 'SALES_OUT'
                   AND so.biz_date BETWEEN ? AND ?
                 GROUP BY so.id, cl.sku_id
                """, (rs, i) -> new CostAmountSource(
                rs.getLong("order_id"), rs.getLong("sku_id"),
                rs.getLong("qty"), rs.getLong("cost_amount")), from, to);
    }

    /** 期间内出货单对应的 RETURN_IN 成本（退货按原出货成本回补）。 */
    public List<CostAmountSource> returnCosts(LocalDate from, LocalDate to) {
        return jdbc.query("""
                SELECT ro.origin_sales_order_id AS order_id, cl.sku_id,
                       COALESCE(SUM(cl.qty), 0) AS qty,
                       COALESCE(SUM(cl.total_cost_amount), 0) AS cost_amount
                  FROM cost_layers cl
                  JOIN return_orders ro ON ro.order_no = cl.biz_ref_no
                  JOIN sales_orders so ON so.id = ro.origin_sales_order_id
                 WHERE cl.biz_type = 'RETURN_IN'
                   AND ro.status = 'COMPLETED'
                   AND so.biz_date BETWEEN ? AND ?
                 GROUP BY ro.origin_sales_order_id, cl.sku_id
                """, (rs, i) -> new CostAmountSource(
                rs.getLong("order_id"), rs.getLong("sku_id"),
                rs.getLong("qty"), rs.getLong("cost_amount")), from, to);
    }

    public record SalesLineSource(
            long orderId,
            String orderNo,
            long customerId,
            long warehouseId,
            LocalDate bizDate,
            long skuId,
            int shippedQty,
            int returnedQty,
            long unitPriceAmount,
            int discountRate) {
    }

    public record AmountSource(long orderId, long skuId, long qty, long amount) {
    }

    public record CostAmountSource(long orderId, long skuId, long qty, long costAmount) {
    }
}
