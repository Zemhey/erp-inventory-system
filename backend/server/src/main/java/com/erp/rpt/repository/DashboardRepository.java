package com.erp.rpt.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 工作台只读聚合仓储（FR-V3-02）。
 *
 * <p>整个 rpt 模块只有 GET、只读、不依赖其它模块的应用服务（见 ArchitectureTest）。
 * 本仓储直接读各业务表做聚合，是"横切只读泳道"的标准做法。
 */
@Repository
public class DashboardRepository {

    private final JdbcTemplate jdbc;

    public DashboardRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private long count(String sql) {
        Long v = jdbc.queryForObject(sql, Long.class);
        return v == null ? 0L : v;
    }

    public long countProducts() {
        return count("SELECT COUNT(*) FROM products");
    }

    public long countSkus() {
        return count("SELECT COUNT(*) FROM skus");
    }

    public long countCustomers() {
        return count("SELECT COUNT(*) FROM customers");
    }

    public long countSuppliers() {
        return count("SELECT COUNT(*) FROM suppliers");
    }

    public long countPurchaseOrders() {
        return count("SELECT COUNT(*) FROM purchase_orders");
    }

    public long countSalesOrders() {
        return count("SELECT COUNT(*) FROM sales_orders");
    }

    public long countInventoryRows() {
        return count("SELECT COUNT(*) FROM inventory_balances");
    }

    /** 零可用库存行数（工作台 KPI「零可用库存」）。★ 不是"库存预警"，预警口径见 TodoRepository.countLowStock()。 */
    public long countZeroAvailable() {
        return count("SELECT COUNT(*) FROM inventory_balances WHERE available_qty <= 0");
    }

    /** 近 N 日单据趋势（按业务日期聚合单数）。 */
    public List<Map<String, Object>> orderTrend(String table, LocalDate from, LocalDate to) {
        return jdbc.query(
                """
                        SELECT biz_date AS label, COUNT(*) AS value
                          FROM %s
                         WHERE biz_date >= ? AND biz_date <= ?
                         GROUP BY biz_date ORDER BY biz_date
                        """.formatted(table),
                (rs, i) -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("label", rs.getDate("label").toLocalDate().toString());
                    m.put("value", rs.getLong("value"));
                    return m;
                }, from, to);
    }

    /** 出货单状态分布（饼图）。 */
    public List<Map<String, Object>> salesStatusPie() {
        return jdbc.query(
                """
                        SELECT status AS label, COUNT(*) AS value
                          FROM sales_orders GROUP BY status ORDER BY value DESC
                        """,
                (rs, i) -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("label", rs.getString("label"));
                    m.put("value", rs.getLong("value"));
                    return m;
                });
    }

    /**
     * 各仓库 SKU 覆盖数（TOP 6 柱状图）。
     *
     * <p>带仓库名返回：前端此前只有 {@code id}，只能渲染成"仓 #421"这种用户读不懂的兜底。
     * 列名以 {@code \d warehouses} / {@code \d inventory_balances} 实测为准
     * （{@code warehouses.name}，FK 列 {@code inventory_balances.warehouse_id}）——
     * 猜错列名会让整个 /dashboard/summary 500，且三道编译门禁都拦不住。
     *
     * <p>用 LEFT JOIN：{@code warehouse_id} 虽是 FK 非空，但停用的仓库仍可能有历史余额行，
     * INNER JOIN 会让这些仓从图上无声消失。理论上 name 不会为 null（NOT NULL 约束），
     * 仍保留 id 让前端兜底。
     */
    public List<Map<String, Object>> inventoryByWarehouse() {
        return jdbc.query(
                """
                        SELECT b.warehouse_id AS id, w.name AS name,
                               COUNT(DISTINCT b.sku_id) AS value
                          FROM inventory_balances b
                          LEFT JOIN warehouses w ON w.id = b.warehouse_id
                         GROUP BY b.warehouse_id, w.name ORDER BY value DESC LIMIT 6
                        """,
                (rs, i) -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("name", rs.getString("name"));
                    m.put("value", rs.getLong("value"));
                    return m;
                });
    }

    /**
     * 一张单据来源表的列名映射。
     *
     * 为什么不用一套通用列名：各单据表字段并不统一 ——
     * 进货/出货/退货是 {@code order_no + total_qty + total_amount}，
     * 调拨是 {@code transfer_no} 且没有数量金额列（明细在 transfer_lines）。
     * 通用列名会在调拨单上直接 SQL 报错，把整个工作台打成 500。
     *
     * @param table     业务表名
     * @param type      单据类型标签
     * @param noCol     单号列
     * @param partyCol  往来单位列（supplier_id / customer_id），无则 null
     * @param qtyCol    数量列，无则 null（返回 0）
     * @param amountCol 金额列，无则 null（返回 0）
     */
    public record DocSource(String table, String type, String noCol, String partyCol,
                            String qtyCol, String amountCol) {
    }

    /** 只有订单类单据有单一 warehouse_id 列；调拨单是 from/to 两列，不能直接当仓库列用。 */
    private static boolean hasWarehouse(String table) {
        return "purchase_orders".equals(table)
                || "sales_orders".equals(table)
                || "return_orders".equals(table);
    }

    /**
     * 最近单据（跨类型聚合）。一次取各类型最近 limit 条，Java 侧合并排序。
     */
    public List<Map<String, Object>> recentDocuments(DocSource src, int limit) {
        String sql = """
                SELECT id, %s AS no, status, biz_date,
                       %s AS party_id,
                       %s AS warehouse_id,
                       COALESCE(%s, 0) AS total_qty,
                       COALESCE(%s, 0) AS total_amount
                  FROM %s ORDER BY created_at DESC LIMIT ?
                """.formatted(
                src.noCol(),
                src.partyCol() == null ? "NULL" : src.partyCol(),
                hasWarehouse(src.table()) ? "warehouse_id" : "NULL",
                src.qtyCol() == null ? "NULL" : src.qtyCol(),
                src.amountCol() == null ? "NULL" : src.amountCol(),
                src.table());
        String type = src.type();
        return jdbc.query(sql, (rs, i) -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("type", type);
            m.put("id", rs.getLong("id"));
            m.put("no", rs.getString("no"));
            m.put("status", rs.getString("status"));
            m.put("bizDate", rs.getDate("biz_date").toLocalDate().toString());
            m.put("partyId", rs.getObject("party_id", Long.class));
            m.put("warehouseId", rs.getObject("warehouse_id", Long.class));
            m.put("totalQty", rs.getLong("total_qty"));
            m.put("totalAmount", rs.getLong("total_amount"));
            return m;
        }, limit);
    }

    /** 最近待审批实例（待办中心"待审批"用）。 */
    public List<Map<String, Object>> recentPendingApprovals(int limit) {
        return jdbc.query(
                """
                        SELECT id, biz_type, biz_id, biz_ref_no, amount, current_step, status,
                               created_at
                          FROM approval_instances
                         WHERE status IN ('PENDING', 'TRANSFERRED')
                         ORDER BY created_at DESC LIMIT ?
                        """,
                (rs, i) -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("bizType", rs.getString("biz_type"));
                    m.put("bizId", rs.getLong("biz_id"));
                    m.put("bizRefNo", rs.getString("biz_ref_no"));
                    m.put("amount", rs.getLong("amount"));
                    m.put("currentStep", rs.getInt("current_step"));
                    m.put("status", rs.getString("status"));
                    m.put("createdAt", rs.getObject("created_at", java.time.OffsetDateTime.class));
                    return m;
                }, limit);
    }

    public long countPendingApprovals() {
        return count(
                "SELECT COUNT(*) FROM approval_instances WHERE status IN ('PENDING','TRANSFERRED')");
    }

    public long countPurchaseToReceive() {
        return count("SELECT COUNT(*) FROM purchase_orders WHERE status = 'APPROVED'");
    }

    public long countSalesToShip() {
        return count("SELECT COUNT(*) FROM sales_orders WHERE status = 'APPROVED'");
    }
}
