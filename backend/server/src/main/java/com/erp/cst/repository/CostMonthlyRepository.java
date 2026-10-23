package com.erp.cst.repository;

import com.erp.cst.domain.CostMonthlyRow;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 成本月结转仓储（{@code cost_monthly}）。
 *
 * <p>月结行不是"累加"出来的：每次都从 {@code cost_layers} 重新聚合，
 * 再与库中同键行比较。这样重跑同一账期不会把数字翻倍，
 * 也能在数据修正后把差异显式更新出来。
 */
@Repository
public class CostMonthlyRepository {

    private static final RowMapper<CostMonthlyRow> MAPPER = (rs, rowNum) -> new CostMonthlyRow(
            rs.getString("period"),
            rs.getLong("sku_id"),
            rs.getLong("warehouse_id"),
            rs.getLong("opening_qty"),
            rs.getLong("opening_cost_amount"),
            rs.getLong("inbound_qty"),
            rs.getLong("inbound_cost_amount"),
            rs.getLong("outbound_qty"),
            rs.getLong("outbound_cost_amount"),
            rs.getLong("closing_qty"),
            rs.getLong("closing_cost_amount"),
            rs.getInt("version"));

    private final JdbcTemplate jdbc;

    public CostMonthlyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<CostMonthlyRow> findByPeriod(String period) {
        return jdbc.query("""
                SELECT * FROM cost_monthly WHERE period = ?
                 ORDER BY sku_id, warehouse_id
                """, MAPPER, period);
    }

    /**
     * 从成本层计算某账期的月结行。
     *
     * <p>期初取期间开始前最后一条成本层的池状态；期内按方向汇总；
     * 期末由恒等式得出，并由数据库 CHECK 再验一次。
     */
    public List<CostMonthlyRow> computeRows(String period, LocalDateTime from, LocalDateTime to) {
        return jdbc.query("""
                WITH opening AS (
                    SELECT DISTINCT ON (sku_id, warehouse_id)
                           sku_id, warehouse_id, pool_qty_after, pool_cost_after
                      FROM cost_layers
                     WHERE occurred_at < ?
                     ORDER BY sku_id, warehouse_id, occurred_at DESC, id DESC
                ), activity AS (
                    SELECT sku_id, warehouse_id,
                           COALESCE(SUM(qty) FILTER (WHERE direction = 'IN'), 0) AS inbound_qty,
                           COALESCE(SUM(total_cost_amount) FILTER (WHERE direction = 'IN'), 0)
                               AS inbound_cost,
                           COALESCE(SUM(qty) FILTER (WHERE direction = 'OUT'), 0) AS outbound_qty,
                           COALESCE(SUM(total_cost_amount) FILTER (WHERE direction = 'OUT'), 0)
                               AS outbound_cost
                      FROM cost_layers
                     WHERE occurred_at >= ? AND occurred_at < ?
                     GROUP BY sku_id, warehouse_id
                )
                SELECT COALESCE(o.sku_id, a.sku_id) AS sku_id,
                       COALESCE(o.warehouse_id, a.warehouse_id) AS warehouse_id,
                       COALESCE(o.pool_qty_after, 0) AS opening_qty,
                       COALESCE(o.pool_cost_after, 0) AS opening_cost_amount,
                       COALESCE(a.inbound_qty, 0) AS inbound_qty,
                       COALESCE(a.inbound_cost, 0) AS inbound_cost_amount,
                       COALESCE(a.outbound_qty, 0) AS outbound_qty,
                       COALESCE(a.outbound_cost, 0) AS outbound_cost_amount,
                       COALESCE(o.pool_qty_after, 0) + COALESCE(a.inbound_qty, 0)
                           - COALESCE(a.outbound_qty, 0) AS closing_qty,
                       COALESCE(o.pool_cost_after, 0) + COALESCE(a.inbound_cost, 0)
                           - COALESCE(a.outbound_cost, 0) AS closing_cost_amount
                  FROM opening o
                  FULL OUTER JOIN activity a
                    ON a.sku_id = o.sku_id AND a.warehouse_id = o.warehouse_id
                 WHERE COALESCE(o.pool_qty_after, 0) <> 0
                    OR COALESCE(o.pool_cost_after, 0) <> 0
                    OR COALESCE(a.inbound_qty, 0) <> 0
                    OR COALESCE(a.outbound_qty, 0) <> 0
                 ORDER BY sku_id, warehouse_id
                """, (rs, rowNum) -> new CostMonthlyRow(
                        period,
                        rs.getLong("sku_id"),
                        rs.getLong("warehouse_id"),
                        rs.getLong("opening_qty"),
                        rs.getLong("opening_cost_amount"),
                        rs.getLong("inbound_qty"),
                        rs.getLong("inbound_cost_amount"),
                        rs.getLong("outbound_qty"),
                        rs.getLong("outbound_cost_amount"),
                        rs.getLong("closing_qty"),
                        rs.getLong("closing_cost_amount"),
                        0), from, from, to);
    }

    public void insert(CostMonthlyRow row, String requestId, OffsetDateTime computedAt) {
        jdbc.update("""
                INSERT INTO cost_monthly
                    (period, sku_id, warehouse_id, opening_qty, opening_cost_amount,
                     inbound_qty, inbound_cost_amount, outbound_qty, outbound_cost_amount,
                     closing_qty, closing_cost_amount, request_id, computed_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                """, row.period(), row.skuId(), row.warehouseId(), row.openingQty(),
                row.openingCostAmount(), row.inboundQty(), row.inboundCostAmount(),
                row.outboundQty(), row.outboundCostAmount(), row.closingQty(),
                row.closingCostAmount(), requestId, computedAt);
    }

    public boolean update(CostMonthlyRow row, String requestId, OffsetDateTime computedAt,
                          int expectedVersion) {
        int rows = jdbc.update("""
                UPDATE cost_monthly
                   SET opening_qty = ?, opening_cost_amount = ?,
                       inbound_qty = ?, inbound_cost_amount = ?,
                       outbound_qty = ?, outbound_cost_amount = ?,
                       closing_qty = ?, closing_cost_amount = ?,
                       request_id = ?, computed_at = ?, version = version + 1
                 WHERE period = ? AND sku_id = ? AND warehouse_id = ? AND version = ?
                """, row.openingQty(), row.openingCostAmount(),
                row.inboundQty(), row.inboundCostAmount(),
                row.outboundQty(), row.outboundCostAmount(),
                row.closingQty(), row.closingCostAmount(),
                requestId, computedAt, row.period(), row.skuId(), row.warehouseId(),
                expectedVersion);
        return rows == 1;
    }

    public void delete(String period, long skuId, long warehouseId) {
        jdbc.update("""
                DELETE FROM cost_monthly
                 WHERE period = ? AND sku_id = ? AND warehouse_id = ?
                """, period, skuId, warehouseId);
    }
}
