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

    /**
     * 明细行金额列的两套取法。
     *
     * <p>进货/销售/退货行本身带 {@code unit_price_amount} 与 {@code amount}；调拨行没有这两列，
     * 只有发运时快照的批次成本 {@code unit_cost_amount}（可空），所以按成本口径别名出来 ——
     * 详情抽屉里调拨单的"金额"读作成本金额，不是售价金额。
     */
    private static final String LINE_COLS_STANDARD =
            "l.line_no, l.sku_id, l.qty, l.unit_price_amount, l.amount";

    private static final String LINE_COLS_COST =
            "l.line_no, l.sku_id, l.qty, COALESCE(l.unit_cost_amount, 0) AS unit_price_amount, "
                    + "l.qty * COALESCE(l.unit_cost_amount, 0) AS amount";

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
                    "purchase_order_lines", "order_id",
                    LINE_COLS_STANDARD,
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
                    LINE_COLS_STANDARD,
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
                    LINE_COLS_STANDARD,
                    id);
            case "transfer" -> headerAndLines(
                    type,
                    // inventory_transfers 表上没有 total_qty / total_amount 汇总列（V9 建表就没有，
                    // V16 只补了 tenant_id），所以表头合计只能从明细行现算。
                    """
                            SELECT t.id, t.transfer_no AS order_no, t.status, t.biz_date,
                                   COALESCE(l.total_qty, 0) AS total_qty,
                                   COALESCE(l.total_amount, 0) AS total_amount,
                                   NULL AS party_name, w.name AS warehouse_name
                              FROM inventory_transfers t
                              JOIN warehouses w ON w.id = t.from_warehouse_id
                              LEFT JOIN (
                                    SELECT transfer_id,
                                           SUM(qty)::bigint AS total_qty,
                                           SUM(qty * COALESCE(unit_cost_amount, 0)) AS total_amount
                                      FROM transfer_lines
                                     GROUP BY transfer_id
                                   ) l ON l.transfer_id = t.id
                             WHERE t.id = ?
                            """,
                    "transfer_lines", "transfer_id",
                    LINE_COLS_COST,
                    id);
            default -> throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "不支持的单据类型：" + type);
        };
    }

    private DocDetail headerAndLines(String type, String headerSql, String lineTable,
                                    String lineFk, String lineCols, long id) {
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
                        SELECT %s,
                               p.name AS product_name, s.color, s.size
                          FROM %s l
                          JOIN skus s ON s.id = l.sku_id
                          JOIN products p ON p.id = s.product_id
                         WHERE l.%s = ?
                         ORDER BY l.line_no
                        """.formatted(lineCols, lineTable, lineFk),
                LINE_MAPPER, id);

        return new DocDetail(header.type(), header.id(), header.no(), header.status(),
                header.bizDate(), header.partyName(), header.warehouseName(),
                header.totalQty(), header.totalAmount(), lines);
    }
}
