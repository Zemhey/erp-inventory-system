package com.erp.rpt.repository;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 待办中心只读仓储（FR-V3-03）。 */
@Repository
public class TodoRepository {

    private final JdbcTemplate jdbc;

    public TodoRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 待收货：已审核通过但尚未收货的进货单。 */
    public List<Map<String, Object>> purchaseToReceive(int limit) {
        return jdbc.query(
                """
                        SELECT po.id, po.order_no AS no, po.status, po.biz_date,
                               po.total_qty AS total_qty, po.total_amount AS total_amount,
                               s.name AS party_name, w.name AS warehouse_name
                          FROM purchase_orders po
                          JOIN suppliers s ON s.id = po.supplier_id
                          JOIN warehouses w ON w.id = po.warehouse_id
                         WHERE po.status = 'APPROVED'
                         ORDER BY po.created_at DESC LIMIT ?
                        """,
                (rs, i) -> mapDoc(rs, "purchase"), limit);
    }

    /** 待发货：已审核通过但尚未发货的出货单。 */
    public List<Map<String, Object>> salesToShip(int limit) {
        return jdbc.query(
                """
                        SELECT so.id, so.order_no AS no, so.status, so.biz_date,
                               so.total_qty AS total_qty, so.total_amount AS total_amount,
                               c.name AS party_name, w.name AS warehouse_name
                          FROM sales_orders so
                          JOIN customers c ON c.id = so.customer_id
                          JOIN warehouses w ON w.id = so.warehouse_id
                         WHERE so.status = 'APPROVED'
                         ORDER BY so.created_at DESC LIMIT ?
                        """,
                (rs, i) -> mapDoc(rs, "sales"), limit);
    }

    /**
     * 库存预警：可用量低于 skus.min_level 的 (SKU, 仓库)。
     *
     * <p>★ 口径必须与「库存预警」页（{@code GET /inventory/alerts} 的 SAFETY）一致 ——
     * 同一个标签在两地给出不同的数，比给出 0 更糟。汇总粒度也是 SKU+仓库而不是批次，
     * 因为 min_level 定义在 SKU 上。
     */
    public List<Map<String, Object>> lowStock(int limit) {
        // 缺口最大的排前面。这里必须写聚合式而不是 (min_level - available_qty)：
        // ORDER BY 的名字优先解析到输入列，available_qty 会撞成 b.available_qty，
        // 报 "must appear in the GROUP BY clause"（实测）。
        return jdbc.query(lowStockSql() + " ORDER BY (s.min_level - SUM(b.available_qty)) DESC, b.sku_id LIMIT ?",
                (rs, i) -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("skuId", rs.getLong("sku_id"));
                    m.put("warehouseId", rs.getLong("warehouse_id"));
                    m.put("productCode", rs.getString("product_code"));
                    m.put("productName", rs.getString("product_name"));
                    m.put("variant", rs.getString("color") + " / " + rs.getString("size"));
                    m.put("warehouseName", rs.getString("warehouse_name"));
                    m.put("onHandQty", rs.getLong("on_hand_qty"));
                    m.put("availableQty", rs.getLong("available_qty"));
                    m.put("minLevel", rs.getInt("min_level"));
                    return m;
                }, limit);
    }

    /** 与 {@link #lowStock} 同一个谓词的条数，供工作台角标用（角标必须等于列表长度）。 */
    public long countLowStock() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM (" + lowStockSql() + ") g", Long.class);
        return n == null ? 0L : n;
    }

    private static String lowStockSql() {
        return """
                SELECT b.sku_id, b.warehouse_id, s.min_level,
                       p.code AS product_code, p.name AS product_name,
                       s.color, s.size, w.name AS warehouse_name,
                       SUM(b.on_hand_qty)   AS on_hand_qty,
                       SUM(b.available_qty) AS available_qty
                  FROM inventory_balances b
                  JOIN skus s ON s.id = b.sku_id AND s.enabled AND s.min_level > 0
                  JOIN products p ON p.id = s.product_id
                  JOIN warehouses w ON w.id = b.warehouse_id AND w.enabled
                 GROUP BY b.sku_id, b.warehouse_id, s.min_level, p.code, p.name,
                          s.color, s.size, w.name
                HAVING SUM(b.available_qty) < s.min_level
                """;
    }

    private Map<String, Object> mapDoc(java.sql.ResultSet rs, String type) throws java.sql.SQLException {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("type", type);
        m.put("id", rs.getLong("id"));
        m.put("no", rs.getString("no"));
        m.put("status", rs.getString("status"));
        m.put("bizDate", rs.getDate("biz_date").toLocalDate().toString());
        m.put("partyName", rs.getString("party_name"));
        m.put("warehouseName", rs.getString("warehouse_name"));
        m.put("totalQty", rs.getLong("total_qty"));
        m.put("totalAmount", rs.getLong("total_amount"));
        return m;
    }
}
