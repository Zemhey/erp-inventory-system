package com.erp.fin.repository;

import com.erp.fin.domain.Invoice;
import com.erp.fin.domain.InvoiceStatus;
import com.erp.fin.domain.InvoiceType;
import com.erp.shared.db.DbErrors;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.tenant.TenantJdbc;
import com.erp.shared.web.PageResult;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** 发票台账仓储（FR-V3-11）。 */
@Repository
public class FinInvoiceRepository {

    private static final RowMapper<Invoice> MAPPER = (rs, i) -> new Invoice(
            rs.getLong("id"), rs.getString("invoice_no"),
            InvoiceType.valueOf(rs.getString("invoice_type")),
            rs.getObject("partner_id") == null ? null : rs.getLong("partner_id"),
            rs.getString("partner_name"),
            rs.getBigDecimal("amount"), rs.getBigDecimal("tax_amount"),
            rs.getBigDecimal("total_amount"),
            rs.getObject("invoice_date", LocalDate.class),
            rs.getString("source_type"),
            rs.getObject("source_id") == null ? null : rs.getLong("source_id"),
            InvoiceStatus.valueOf(rs.getString("status")),
            rs.getString("remark"));

    private static final String COLUMNS = """
            id, invoice_no, invoice_type, partner_id, partner_name, amount, tax_amount,
            total_amount, invoice_date, source_type, source_id, status, remark""";

    private final JdbcTemplate jdbc;

    public FinInvoiceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String invoiceNo, InvoiceType type, Long partnerId, String partnerName,
                       BigDecimal amount, BigDecimal taxAmount, BigDecimal totalAmount,
                       LocalDate invoiceDate, String sourceType, Long sourceId, String remark) {
        try {
            return TenantJdbc.insertReturningId(jdbc, """
                    INSERT INTO fin_invoice (invoice_no, invoice_type, partner_id, partner_name,
                                             amount, tax_amount, total_amount, invoice_date,
                                             source_type, source_id, remark)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    RETURNING id
                    """,
                    invoiceNo, type.name(), partnerId, partnerName, amount, taxAmount,
                    totalAmount, invoiceDate, sourceType, sourceId, remark);
        } catch (DataIntegrityViolationException ex) {
            BusinessException dup = DbErrors.translate(ex, "uq_fin_invoice_no",
                    ErrorCode.SYS_DUPLICATE_REQUEST,
                    "发票号已登记：%s（%s）".formatted(invoiceNo, type));
            if (dup != null) {
                throw dup;
            }
            throw ex;
        }
    }

    public Optional<Invoice> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM fin_invoice WHERE id = ?",
                MAPPER, id).stream().findFirst();
    }

    public boolean update(long id, Long partnerId, String partnerName,
                          BigDecimal amount, BigDecimal taxAmount, BigDecimal totalAmount,
                          LocalDate invoiceDate, String remark) {
        return TenantJdbc.update(jdbc, """
                UPDATE fin_invoice
                   SET partner_id = ?, partner_name = ?, amount = ?, tax_amount = ?,
                       total_amount = ?, invoice_date = ?, remark = ?, updated_at = now()
                 WHERE id = ? AND status = 'NORMAL'
                """, partnerId, partnerName, amount, taxAmount, totalAmount,
                invoiceDate, remark, id) == 1;
    }

    public boolean setStatus(long id, InvoiceStatus from, InvoiceStatus to) {
        return TenantJdbc.update(jdbc, """
                UPDATE fin_invoice SET status = ?, updated_at = now()
                 WHERE id = ? AND status = ?
                """, to.name(), id, from.name()) == 1;
    }

    public boolean deleteById(long id) {
        return TenantJdbc.update(jdbc, "DELETE FROM fin_invoice WHERE id = ?", id) == 1;
    }

    /** 同一源单据已登记的 NORMAL 发票价税合计（超额校验的"已开"部分）。 */
    public BigDecimal invoicedTotalBySource(String sourceType, long sourceId) {
        return jdbc.query("""
                SELECT COALESCE(SUM(total_amount), 0) FROM fin_invoice
                 WHERE source_type = ? AND source_id = ? AND status = 'NORMAL'
                """, (rs, i) -> rs.getBigDecimal(1), sourceType, sourceId)
                .stream().findFirst().orElse(BigDecimal.ZERO);
    }

    public PageResult<Invoice> page(InvoiceType type, InvoiceStatus status, String keyword,
                                    LocalDate from, LocalDate to, int pageNo, int size) {
        int p = PageResult.normalizePage(pageNo);
        int s = PageResult.normalizeSize(size);
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        if (type != null) {
            where.append(" AND invoice_type = ?");
            args.add(type.name());
        }
        if (status != null) {
            where.append(" AND status = ?");
            args.add(status.name());
        }
        if (from != null) {
            where.append(" AND invoice_date >= ?");
            args.add(from);
        }
        if (to != null) {
            where.append(" AND invoice_date <= ?");
            args.add(to);
        }
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (invoice_no ILIKE ? OR partner_name ILIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM fin_invoice" + where,
                Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(s);
        pageArgs.add(PageResult.offset(p, s));
        List<Invoice> items = jdbc.query("SELECT " + COLUMNS + " FROM fin_invoice"
                + where + " ORDER BY invoice_date DESC, id DESC LIMIT ? OFFSET ?",
                MAPPER, pageArgs.toArray());
        return new PageResult<>(items, total == null ? 0 : total, p, s);
    }
}
