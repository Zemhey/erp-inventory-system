package com.erp.rpt.repository;

import com.erp.rpt.domain.DocDetail;
import com.erp.rpt.domain.DocLine;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 单据详情只读仓储（FR-V3-04）：进货 / 出货 / 退货 / 调拨。
 *
 * <p>rpt 只读泳道：直接读各业务表与明细表，不依赖任何业务模块的应用服务。
 */
@Repository
public class DocumentRepository {

    private final JdbcTemplate jdbc;

    public DocumentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<DocLine> LINE_MAPPER = (rs, i) -> {
        String name = trimName(rs.getString("product_name"),
                rs.getString("color"), rs.getString("size"));
        return new DocLine(
                rs.getInt("line_no"),
                rs.getLong("sku_id"),
                name,
                rs.getLong("qty"),
                rs.getLong("unit_price_amount"),
                rs.getLong("amount"));
    };

    private static String trimName(String productName, String color, String size) {
        StringBuilder sb = new StringBuilder();
        if (productName != null) sb.append(productName);
        if (color != null && !color.isBlank()) sb.append(' ').append(color);
        if (size != null && !size.isBlank()) sb.append(' ').append(size);
        return sb.toString().trim();
    }

    public DocDetail getDetail(String type, long id) {
        return switch (type) {
            case "purchase" -> headerAndLines(
                    type,
                    """
                            SELECT po.id, po.order_no, po.status, po.biz_date, po.total_qty,
                                   po.total_amount, s.name AS party_name, w.name AS warehouse_name
                              FROM purchase_orders po
                              JOIN suppliers s ON s.id = po.supplier_id
                              JOIN warehouses w ON w.id = po.warehouse_id
                             WHERE po.id = ?
                            """,
                    "purchase_order_lines", "purchase_order_id",
                    id);
            case "sales" -> headerAndLines(
                    type,
                    """
                            SELECT so.id, so.order_no, so.status, so.biz_date, so.total_qty,
                                   so.total_amount, c.name AS party_name, w.name AS warehouse_name
                              FROM sales_orders so
                              JOIN customers c ON c.id = so.customer_id
                              JOIN warehouses w ON w.id = so.warehouse_id
                             WHERE so.id = ?
                            """,
                    "sales_order_lines", "order_id",
                    id);
            case "return" -> headerAndLines(
                    type,
                    """
                            SELECT ro.id, ro.order_no, ro.status, ro.biz_date, ro.total_qty,
                                   ro.total_amount, c.name AS party_name, w.name AS warehouse_name
                              FROM return_orders ro
                              JOIN customers c ON c.id = ro.customer_id
                              JOIN warehouses w ON w.id = ro.warehouse_id
                             WHERE ro.id = ?
                            """,
                    "return_order_lines", "order_id",
                    id);
            case "transfer" -> headerAndLines(
                    type,
                    """
                            SELECT t.id, t.transfer_no AS order_no, t.status, t.biz_date,
                                   COALESCE(t.total_qty, 0) AS total_qty,
                                   COALESCE(t.total_amount, 0) AS total_amount,
                                   NULL AS party_name, w.name AS warehouse_name
                              FROM inventory_transfers t
                              JOIN warehouses w ON w.id = t.from_warehouse_id
                             WHERE t.id = ?
                            """,
                    "transfer_lines", "transfer_id",
                    id);
            default -> throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "不支持的单据类型：" + type);
        };
    }

    private DocDetail headerAndLines(String type, String headerSql, String lineTable,
                                    String lineFk, long id) {
        DocDetail header = jdbc.query(headerSql, (rs, i) -> new DocDetail(
                type,
                rs.getLong("id"),
                rs.getString("order_no"),
                rs.getString("status"),
                rs.getDate("biz_date").toLocalDate(),
                rs.getString("party_name"),
                rs.getString("warehouse_name"),
                rs.getLong("total_qty"),
                rs.getLong("total_amount"),
                List.of()), id).stream().findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                        "单据不存在：type=" + type + " id=" + id));

        List<DocLine> lines = jdbc.query(
                """
                        SELECT l.line_no, l.sku_id, l.qty, l.unit_price_amount, l.amount,
                               p.name AS product_name, s.color, s.size
                          FROM %s l
                          JOIN skus s ON s.id = l.sku_id
                          JOIN products p ON p.id = s.product_id
                         WHERE l.%s = ?
                         ORDER BY l.line_no
                        """.formatted(lineTable, lineFk),
                LINE_MAPPER, id);

        return new DocDetail(header.type(), header.id(), header.no(), header.status(),
                header.bizDate(), header.partyName(), header.warehouseName(),
                header.totalQty(), header.totalAmount(), lines);
    }
}
