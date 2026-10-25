package com.erp.fin.repository;

import com.erp.fin.domain.Expense;
import com.erp.fin.domain.ExpenseLine;
import com.erp.fin.domain.ExpenseStatus;
import com.erp.shared.db.DbErrors;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.tenant.TenantJdbc;
import com.erp.shared.web.PageResult;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** 费用报销仓储（FR-V3-10）。 */
@Repository
public class FinExpenseRepository {

    private static final RowMapper<Expense> HEAD_MAPPER = (rs, i) -> new Expense(
            rs.getLong("id"), rs.getString("expense_no"),
            rs.getObject("applicant_id") == null ? null : rs.getLong("applicant_id"),
            rs.getString("applicant_name"),
            rs.getObject("department_id") == null ? null : rs.getLong("department_id"),
            rs.getString("department_name"),
            rs.getString("expense_type"), rs.getBigDecimal("amount"),
            ExpenseStatus.valueOf(rs.getString("status")),
            rs.getObject("apply_date", LocalDate.class),
            rs.getString("reject_reason"),
            toLocal(rs.getObject("paid_at", java.time.OffsetDateTime.class)),
            rs.getString("remark"), List.of());

    private static final RowMapper<ExpenseLine> LINE_MAPPER = (rs, i) -> new ExpenseLine(
            rs.getLong("id"), rs.getInt("line_no"), rs.getString("expense_item"),
            rs.getBigDecimal("amount"), rs.getString("invoice_no"), rs.getString("remark"));

    private static final String HEAD_COLUMNS = """
            id, expense_no, applicant_id, applicant_name, department_id, department_name,
            expense_type, amount, status, apply_date, reject_reason, paid_at, remark""";

    /** timestamptz → 应用层 LocalDateTime（pgjdbc 不支持直接按 LocalDateTime 取带时区列）。 */
    private static LocalDateTime toLocal(java.time.OffsetDateTime value) {
        return value == null ? null : value.toLocalDateTime();
    }

    private final JdbcTemplate jdbc;

    public FinExpenseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String expenseNo, Long applicantId, String applicantName,
                      Long departmentId, String departmentName, String expenseType,
                      BigDecimal amount, LocalDate applyDate, String remark) {
        try {
            return TenantJdbc.insertReturningId(jdbc, """
                    INSERT INTO fin_expense (expense_no, applicant_id, applicant_name,
                                             department_id, department_name, expense_type,
                                             amount, apply_date, remark)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    RETURNING id
                    """,
                    expenseNo, applicantId, applicantName, departmentId, departmentName,
                    expenseType, amount, applyDate, remark);
        } catch (DataIntegrityViolationException ex) {
            BusinessException dup = DbErrors.translate(ex, "uq_fin_expense_no",
                    ErrorCode.SYS_DUPLICATE_REQUEST, "报销单号重复：" + expenseNo);
            if (dup != null) {
                throw dup;
            }
            throw ex;
        }
    }

    public void insertLine(long expenseId, int lineNo, String item, BigDecimal amount,
                           String invoiceNo, String remark) {
        TenantJdbc.insertReturningId(jdbc, """
                INSERT INTO fin_expense_line (expense_id, line_no, expense_item, amount,
                                              invoice_no, remark)
                VALUES (?, ?, ?, ?, ?, ?)
                RETURNING id
                """, expenseId, lineNo, item, amount, invoiceNo, remark);
    }

    public void replaceLines(long expenseId, List<ExpenseLine> lines) {
        jdbc.update("DELETE FROM fin_expense_line WHERE expense_id = ?", expenseId);
        int lineNo = 1;
        for (ExpenseLine line : lines) {
            insertLine(expenseId, lineNo++, line.expenseItem(), line.amount(),
                    line.invoiceNo(), line.remark());
        }
    }

    public Optional<Expense> findById(long id) {
        return jdbc.query("SELECT " + HEAD_COLUMNS + " FROM fin_expense WHERE id = ?",
                HEAD_MAPPER, id).stream().findFirst().map(head ->
                        head.withLines(jdbc.query("""
                                SELECT id, line_no, expense_item, amount, invoice_no, remark
                                  FROM fin_expense_line WHERE expense_id = ? ORDER BY line_no
                                """, LINE_MAPPER, head.id())));
    }

    public boolean updateDraft(long id, String expenseType, BigDecimal amount,
                               LocalDate applyDate, String remark) {
        return TenantJdbc.update(jdbc, """
                UPDATE fin_expense
                   SET expense_type = ?, amount = ?, apply_date = ?, remark = ?,
                       updated_at = now()
                 WHERE id = ? AND status IN ('DRAFT', 'REJECTED')
                """, expenseType, amount, applyDate, remark, id) == 1;
    }

    /** 状态转移（条件 UPDATE；PAID 落 paid_at；驳回原因经 reject_reason 保留）。 */
    public boolean transition(long id, ExpenseStatus from, ExpenseStatus to,
                              String rejectReason, LocalDateTime paidAt) {
        return TenantJdbc.update(jdbc, """
                UPDATE fin_expense
                   SET status = ?, updated_at = now(),
                       reject_reason = COALESCE(?, reject_reason),
                       paid_at = COALESCE(?, paid_at)
                 WHERE id = ? AND status = ?
                """, to.name(), rejectReason, paidAt, id, from.name()) == 1;
    }

    public boolean deleteById(long id) {
        jdbc.update("DELETE FROM fin_expense_line WHERE expense_id = ?", id);
        return TenantJdbc.update(jdbc,
                "DELETE FROM fin_expense WHERE id = ? AND status = 'DRAFT'", id) == 1;
    }

    /** 已生成付款凭证的报销单 id（幂等兜底查询走 fin_voucher，这里只提供列表）。 */
    public PageResult<Expense> page(ExpenseStatus status, String keyword, int pageNo, int size) {
        int p = PageResult.normalizePage(pageNo);
        int s = PageResult.normalizeSize(size);
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        if (status != null) {
            where.append(" AND status = ?");
            args.add(status.name());
        }
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (expense_no ILIKE ? OR applicant_name ILIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM fin_expense" + where,
                Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(s);
        pageArgs.add(PageResult.offset(p, s));
        List<Expense> heads = jdbc.query("SELECT " + HEAD_COLUMNS + " FROM fin_expense"
                + where + " ORDER BY apply_date DESC, id DESC LIMIT ? OFFSET ?",
                HEAD_MAPPER, pageArgs.toArray());
        List<Expense> items = new ArrayList<>(heads.size());
        for (Expense head : heads) {
            items.add(head.withLines(jdbc.query("""
                    SELECT id, line_no, expense_item, amount, invoice_no, remark
                      FROM fin_expense_line WHERE expense_id = ? ORDER BY line_no
                    """, LINE_MAPPER, head.id())));
        }
        return new PageResult<>(items, total == null ? 0 : total, p, s);
    }
}
