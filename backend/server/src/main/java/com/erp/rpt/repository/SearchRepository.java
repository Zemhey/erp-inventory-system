package com.erp.rpt.repository;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 全局搜索只读仓储（FR-V3-05）。 */
@Repository
public class SearchRepository {

    private final JdbcTemplate jdbc;

    public SearchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> products(String keyword, int limit) {
        return jdbc.query(
                """
                        SELECT id, code, name FROM products
                         WHERE code ILIKE ? OR name ILIKE ?
                         ORDER BY code LIMIT ?
                        """,
                (rs, i) -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("code", rs.getString("code"));
                    m.put("name", rs.getString("name"));
                    return m;
                }, "%" + keyword + "%", "%" + keyword + "%", limit);
    }

    public List<Map<String, Object>> customers(String keyword, int limit) {
        return jdbc.query(
                """
                        SELECT id, code, name FROM customers
                         WHERE code ILIKE ? OR name ILIKE ?
                         ORDER BY code LIMIT ?
                        """,
                (rs, i) -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("code", rs.getString("code"));
                    m.put("name", rs.getString("name"));
                    return m;
                }, "%" + keyword + "%", "%" + keyword + "%", limit);
    }

    public List<Map<String, Object>> suppliers(String keyword, int limit) {
        return jdbc.query(
                """
                        SELECT id, code, name FROM suppliers
                         WHERE code ILIKE ? OR name ILIKE ?
                         ORDER BY code LIMIT ?
                        """,
                (rs, i) -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("code", rs.getString("code"));
                    m.put("name", rs.getString("name"));
                    return m;
                }, "%" + keyword + "%", "%" + keyword + "%", limit);
    }

    public List<Map<String, Object>> orders(String keyword, int limit) {
        return jdbc.query(
                """
                        SELECT 'purchase' AS type, id, order_no AS no, status FROM purchase_orders
                         WHERE order_no ILIKE ?
                         UNION ALL
                        SELECT 'sales' AS type, id, order_no AS no, status FROM sales_orders
                         WHERE order_no ILIKE ?
                         ORDER BY no LIMIT ?
                        """,
                (rs, i) -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("type", rs.getString("type"));
                    m.put("id", rs.getLong("id"));
                    m.put("no", rs.getString("no"));
                    m.put("status", rs.getString("status"));
                    return m;
                }, "%" + keyword + "%", "%" + keyword + "%", limit);
    }
}
