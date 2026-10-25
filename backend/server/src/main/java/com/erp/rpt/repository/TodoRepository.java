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

    /** 库存预警：可用量 ≤ 0 的库存行。 */
    public List<Map<String, Object>> lowStock(int limit) {
        return jdbc.query(
                """
                        SELECT ib.sku_id AS sku_id, ib.warehouse_id AS warehouse_id,
                               ib.batch_no AS batch_no, ib.on_hand_qty AS on_hand_qty,
                               ib.reserved_qty AS reserved_qty, ib.frozen_qty AS frozen_qty,
                               ib.available_qty AS available_qty
                          FROM inventory_balances ib
                         WHERE ib.available_qty <= 0
                         ORDER BY ib.available_qty ASC LIMIT ?
                        """,
                (rs, i) -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("skuId", rs.getLong("sku_id"));
                    m.put("warehouseId", rs.getLong("warehouse_id"));
                    m.put("batchNo", rs.getString("batch_no"));
                    m.put("onHandQty", rs.getLong("on_hand_qty"));
                    m.put("reservedQty", rs.getLong("reserved_qty"));
                    m.put("frozenQty", rs.getLong("frozen_qty"));
                    m.put("availableQty", rs.getLong("available_qty"));
                    return m;
                }, limit);
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
