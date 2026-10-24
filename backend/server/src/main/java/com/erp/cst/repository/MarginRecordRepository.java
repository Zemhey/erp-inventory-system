package com.erp.cst.repository;

import com.erp.cst.domain.MarginAggregateRow;
import com.erp.cst.domain.MarginGroupBy;
import com.erp.cst.domain.MarginRecord;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 毛利快照仓储（{@code margin_records}）。
 *
 * <p>聚合 SQL 的分组片段由枚举白名单给出（{@link MarginGroupBy}），用户输入只走
 * {@code ?} 绑定；毛利率不在 SQL 里算，统一由应用层用 MoneyMath 舍入一次。
 */
@Repository
public class MarginRecordRepository {

    private static final RowMapper<MarginRecord> MAPPER = (rs, rowNum) -> new MarginRecord(
            rs.getLong("id"),
            rs.getString("period"),
            rs.getLong("sales_order_id"),
            rs.getString("sales_order_no"),
            rs.getLong("customer_id"),
            rs.getLong("sku_id"),
            rs.getLong("warehouse_id"),
            rs.getDate("biz_date").toLocalDate(),
            rs.getInt("qty"),
            rs.getLong("revenue_amount"),
            rs.getLong("cost_amount"),
            rs.getLong("gross_profit_amount"),
            rs.getLong("gross_margin_rate_bp"));

    private static final RowMapper<MarginAggregateRow> AGG_MAPPER = (rs, rowNum) ->
            new MarginAggregateRow(
                    rs.getString("group_key"),
                    rs.getString("group_label"),
                    rs.getLong("qty"),
                    rs.getLong("revenue_amount"),
                    rs.getLong("cost_amount"),
                    rs.getLong("gross_profit_amount"),
                    rs.getLong("order_count"));

    private final JdbcTemplate jdbc;

    public MarginRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<MarginRecord> findByPeriod(String period) {
        return jdbc.query("""
                SELECT * FROM margin_records WHERE period = ?
                 ORDER BY sales_order_id, sku_id
                """, MAPPER, period);
    }

    public void insert(MarginRecord record) {
        jdbc.update("""
                INSERT INTO margin_records
                    (period, sales_order_id, sales_order_no, customer_id, sku_id, warehouse_id,
                     biz_date, qty, revenue_amount, cost_amount, gross_profit_amount,
                     gross_margin_rate_bp)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, record.period(), record.salesOrderId(), record.salesOrderNo(),
                record.customerId(), record.skuId(), record.warehouseId(), record.bizDate(),
                record.qty(), record.revenueAmount(), record.costAmount(),
                record.grossProfitAmount(), record.grossMarginRateBp());
    }

    public void update(MarginRecord record) {
        jdbc.update("""
                UPDATE margin_records
                   SET sales_order_no = ?, customer_id = ?, warehouse_id = ?, biz_date = ?,
                       qty = ?, revenue_amount = ?, cost_amount = ?,
                       gross_profit_amount = ?, gross_margin_rate_bp = ?
                 WHERE id = ?
                """, record.salesOrderNo(), record.customerId(), record.warehouseId(),
                record.bizDate(), record.qty(), record.revenueAmount(), record.costAmount(),
                record.grossProfitAmount(), record.grossMarginRateBp(), record.id());
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM margin_records WHERE id = ?", id);
    }

    /** 按维度聚合；from/to 为 {@code yyyy-MM} 闭区间。 */
    public List<MarginAggregateRow> aggregate(MarginGroupBy groupBy, String fromPeriod,
                                              String toPeriod) {
        return jdbc.query(aggregateSql(groupBy), AGG_MAPPER, fromPeriod, toPeriod);
    }

    private static String aggregateSql(MarginGroupBy groupBy) {
        return switch (groupBy) {
            case PRODUCT -> """
                    SELECT CAST(m.sku_id AS VARCHAR) AS group_key,
                           COALESCE(p.name || ' · ' || s.color || '-' || s.size,
                                    'SKU#' || m.sku_id) AS group_label,
                           SUM(m.qty) AS qty,
                           SUM(m.revenue_amount) AS revenue_amount,
                           SUM(m.cost_amount) AS cost_amount,
                           SUM(m.gross_profit_amount) AS gross_profit_amount,
                           COUNT(DISTINCT m.sales_order_id) AS order_count
                      FROM margin_records m
                      JOIN skus s ON s.id = m.sku_id
                      JOIN products p ON p.id = s.product_id
                     WHERE m.period BETWEEN ? AND ?
                     GROUP BY m.sku_id, p.name, s.color, s.size
                     ORDER BY SUM(m.gross_profit_amount) DESC, m.sku_id
                    """;
            case CUSTOMER -> """
                    SELECT CAST(m.customer_id AS VARCHAR) AS group_key,
                           c.name AS group_label,
                           SUM(m.qty) AS qty,
                           SUM(m.revenue_amount) AS revenue_amount,
                           SUM(m.cost_amount) AS cost_amount,
                           SUM(m.gross_profit_amount) AS gross_profit_amount,
                           COUNT(DISTINCT m.sales_order_id) AS order_count
                      FROM margin_records m
                      JOIN customers c ON c.id = m.customer_id
                     WHERE m.period BETWEEN ? AND ?
                     GROUP BY m.customer_id, c.name
                     ORDER BY SUM(m.gross_profit_amount) DESC, m.customer_id
                    """;
            case PERIOD -> """
                    SELECT m.period AS group_key,
                           m.period AS group_label,
                           SUM(m.qty) AS qty,
                           SUM(m.revenue_amount) AS revenue_amount,
                           SUM(m.cost_amount) AS cost_amount,
                           SUM(m.gross_profit_amount) AS gross_profit_amount,
                           COUNT(DISTINCT m.sales_order_id) AS order_count
                      FROM margin_records m
                     WHERE m.period BETWEEN ? AND ?
                     GROUP BY m.period
                     ORDER BY m.period
                    """;
        };
    }
}
