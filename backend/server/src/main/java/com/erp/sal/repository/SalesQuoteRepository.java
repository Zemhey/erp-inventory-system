package com.erp.sal.repository;

import com.erp.sal.domain.SalesQuote;
import com.erp.sal.domain.SalesQuoteLine;
import com.erp.shared.document.DocumentStatus;
import com.erp.shared.web.PageResult;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** 报价单仓储（FR-SAL-05）。 */
@Repository
public class SalesQuoteRepository {

    private static final RowMapper<SalesQuote> HEADER_MAPPER = (rs, i) -> new SalesQuote(
            rs.getLong("id"),
            rs.getString("quote_no"),
            DocumentStatus.valueOf(rs.getString("status")),
            rs.getLong("customer_id"),
            rs.getObject("biz_date", LocalDate.class),
            rs.getObject("valid_until", LocalDate.class),
            rs.getLong("total_amount"),
            rs.getInt("total_qty"),
            rs.getString("remark"),
            rs.getString("convert_request_id"),
            rs.getObject("converted_order_id", Long.class),
            rs.getInt("version"),
            List.of());

    private static final RowMapper<SalesQuoteLine> LINE_MAPPER = (rs, i) -> new SalesQuoteLine(
            rs.getLong("id"),
            rs.getInt("line_no"),
            rs.getLong("sku_id"),
            rs.getInt("qty"),
            rs.getLong("unit_price_amount"),
            rs.getInt("discount_rate"),
            rs.getLong("amount"));

    private static final String HEADER_COLUMNS = """
            id, quote_no, status, customer_id, biz_date, valid_until,
            total_amount, total_qty, remark, convert_request_id, converted_order_id, version
            """;

    private final JdbcTemplate jdbc;

    public SalesQuoteRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insertHeader(String quoteNo, long customerId, LocalDate bizDate,
                             LocalDate validUntil, long totalAmount, int totalQty,
                             String remark, String requestId) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO sales_quotes
                        (quote_no, status, customer_id, biz_date, valid_until,
                         total_amount, total_qty, remark, request_id)
                    VALUES (?, 'DRAFT', ?, ?, ?, ?, ?, ?, ?)
                    """, new String[]{"id"});
            ps.setString(1, quoteNo);
            ps.setLong(2, customerId);
            ps.setObject(3, bizDate);
            ps.setObject(4, validUntil);
            ps.setLong(5, totalAmount);
            ps.setInt(6, totalQty);
            ps.setString(7, remark);
            ps.setString(8, requestId);
            return ps;
        }, keys);
        Number id = keys.getKey();
        if (id == null) {
            throw new IllegalStateException("插入报价单后未取得主键：" + quoteNo);
        }
        return id.longValue();
    }

    public void insertLine(long quoteId, int lineNo, long skuId, int qty,
                           long unitPriceAmount, int discountRate, long amount) {
        jdbc.update("""
                INSERT INTO sales_quote_lines
                    (quote_id, line_no, sku_id, qty, unit_price_amount, discount_rate, amount)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, quoteId, lineNo, skuId, qty, unitPriceAmount, discountRate, amount);
    }

    public List<SalesQuoteLine> findLines(long quoteId) {
        return jdbc.query("""
                SELECT id, line_no, sku_id, qty, unit_price_amount, discount_rate, amount
                  FROM sales_quote_lines WHERE quote_id = ? ORDER BY line_no
                """, LINE_MAPPER, quoteId);
    }

    public Optional<SalesQuote> findById(long id) {
        return jdbc.query("SELECT " + HEADER_COLUMNS + " FROM sales_quotes WHERE id = ?",
                HEADER_MAPPER, id).stream().findFirst()
                .map(h -> withLines(h));
    }

    public Optional<SalesQuote> findByIdForUpdate(long id) {
        return jdbc.query("SELECT " + HEADER_COLUMNS
                + " FROM sales_quotes WHERE id = ? FOR UPDATE", HEADER_MAPPER, id).stream()
                .findFirst().map(this::withLines);
    }

    public Optional<SalesQuote> findByRequestId(String requestId) {
        return jdbc.query("SELECT " + HEADER_COLUMNS
                + " FROM sales_quotes WHERE request_id = ?", HEADER_MAPPER, requestId).stream()
                .findFirst().map(this::withLines);
    }

    private SalesQuote withLines(SalesQuote h) {
        return new SalesQuote(h.id(), h.quoteNo(), h.status(), h.customerId(), h.bizDate(),
                h.validUntil(), h.totalAmount(), h.totalQty(), h.remark(),
                h.convertRequestId(), h.convertedOrderId(), h.version(), findLines(h.id()));
    }

    public boolean updateStatus(long id, DocumentStatus from, DocumentStatus to,
                                int expectedVersion) {
        return jdbc.update("""
                UPDATE sales_quotes
                   SET status = ?, version = version + 1, updated_at = now()
                 WHERE id = ? AND status = ? AND version = ?
                """, to.name(), id, from.name(), expectedVersion) == 1;
    }

    public boolean markConverted(long id, DocumentStatus from, int expectedVersion,
                                 String requestId, long salesOrderId) {
        return jdbc.update("""
                UPDATE sales_quotes
                   SET status = 'COMPLETED', convert_request_id = ?, converted_order_id = ?,
                       version = version + 1, updated_at = now()
                 WHERE id = ? AND status = ? AND version = ?
                """, requestId, salesOrderId, id, from.name(), expectedVersion) == 1;
    }

    public PageResult<SalesQuote> page(DocumentStatus status, Long customerId, int page, int size) {
        int p = PageResult.normalizePage(page);
        int s = PageResult.normalizeSize(size);
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        if (status != null) {
            where.append(" AND status = ?");
            args.add(status.name());
        }
        if (customerId != null) {
            where.append(" AND customer_id = ?");
            args.add(customerId);
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM sales_quotes" + where,
                Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(s);
        pageArgs.add(PageResult.offset(p, s));
        List<SalesQuote> items = jdbc.query("SELECT " + HEADER_COLUMNS
                + " FROM sales_quotes" + where + " ORDER BY id DESC LIMIT ? OFFSET ?",
                HEADER_MAPPER, pageArgs.toArray()).stream().map(this::withLines).toList();
        return new PageResult<>(items, total == null ? 0 : total, p, s);
    }
}
