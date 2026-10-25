package com.erp.fin.repository;

import com.erp.fin.domain.Voucher;
import com.erp.fin.domain.VoucherLine;
import com.erp.fin.domain.VoucherSourceType;
import com.erp.fin.domain.VoucherStatus;
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

/**
 * 凭证仓储（FR-V3-07/08）。
 *
 * <p>分录入库走"先删后插"的整单替换：凭证最多几十行，替换比逐行 diff 简单，
 * 而且"分录行号连续"这条显示口径天然成立。只允许在 DRAFT 上替换（应用层把关）。
 */
@Repository
public class FinVoucherRepository {

    private static final RowMapper<Voucher> HEAD_MAPPER = (rs, i) -> new Voucher(
            rs.getLong("id"),
            rs.getString("voucher_no"),
            rs.getObject("voucher_date", LocalDate.class),
            rs.getString("period"),
            VoucherSourceType.valueOf(rs.getString("source_type")),
            rs.getObject("source_id") == null ? null : rs.getLong("source_id"),
            rs.getString("source_no"),
            VoucherStatus.valueOf(rs.getString("status")),
            rs.getBigDecimal("total_debit"),
            rs.getBigDecimal("total_credit"),
            rs.getObject("created_by") == null ? null : rs.getLong("created_by"),
            rs.getObject("audited_by") == null ? null : rs.getLong("audited_by"),
            rs.getObject("posted_at", LocalDateTime.class),
            rs.getString("remark"),
            List.of());

    private static final RowMapper<VoucherLine> LINE_MAPPER = (rs, i) -> new VoucherLine(
            rs.getLong("id"),
            rs.getInt("line_no"),
            rs.getLong("account_id"),
            rs.getString("account_code"),
            rs.getString("summary"),
            rs.getBigDecimal("debit"),
            rs.getBigDecimal("credit"));

    private static final String HEAD_COLUMNS = """
            id, voucher_no, voucher_date, period, source_type, source_id, source_no,
            status, total_debit, total_credit, created_by, audited_by, posted_at, remark""";

    private final JdbcTemplate jdbc;

    public FinVoucherRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入凭证头，返回 id。
     *
     * <p>撞来源唯一键（uq_fin_voucher_source）= 同一业务单据并发生成第二张凭证，
     * 翻成 SYS_DUPLICATE_REQUEST；调用方（自动生成路径）应先查后插，这里是并发兜底。
     */
    public long insertHeader(String voucherNo, LocalDate voucherDate, String period,
                             VoucherSourceType sourceType,
                             Long sourceId, String sourceNo, VoucherStatus status,
                             BigDecimal totalDebit, BigDecimal totalCredit,
                             Long createdBy, String remark, LocalDateTime postedAt) {
        try {
            return TenantJdbc.insertReturningId(jdbc, """
                    INSERT INTO fin_voucher (voucher_no, voucher_date, period, source_type,
                                             source_id, source_no, status, total_debit,
                                             total_credit, created_by, remark, posted_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    RETURNING id
                    """,
                    voucherNo, voucherDate, period, sourceType.name(), sourceId, sourceNo,
                    status.name(), totalDebit, totalCredit, createdBy, remark, postedAt);
        } catch (DataIntegrityViolationException ex) {
            BusinessException dup = DbErrors.translate(ex, "uq_fin_voucher_source",
                    ErrorCode.SYS_DUPLICATE_REQUEST,
                    "该业务单据的凭证已存在（并发生成冲突）：%s sourceId=%d"
                            .formatted(sourceType, sourceId));
            if (dup != null) {
                throw dup;
            }
            throw ex;
        }
    }

    public void insertLine(long voucherId, int lineNo, long accountId, String accountCode,
                           String summary, BigDecimal debit, BigDecimal credit) {
        TenantJdbc.insertReturningId(jdbc, """
                INSERT INTO fin_voucher_line (voucher_id, line_no, account_id, account_code,
                                              summary, debit, credit)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """,
                voucherId, lineNo, accountId, accountCode, summary, debit, credit);
    }

    /** 整单替换分录（仅 DRAFT，应用层把关状态）。 */
    public void replaceLines(long voucherId, List<VoucherLine> lines) {
        jdbc.update("DELETE FROM fin_voucher_line WHERE voucher_id = ?", voucherId);
        int lineNo = 1;
        for (VoucherLine line : lines) {
            insertLine(voucherId, lineNo++, line.accountId(), line.accountCode(),
                    line.summary(), line.debit(), line.credit());
        }
    }

    public Optional<Voucher> findById(long id) {
        return jdbc.query("SELECT " + HEAD_COLUMNS + " FROM fin_voucher WHERE id = ?",
                HEAD_MAPPER, id).stream().findFirst().map(head ->
                        head.withLines(linesOf(id)));
    }

    public Optional<Voucher> findBySource(VoucherSourceType sourceType, long sourceId) {
        return jdbc.query("""
                SELECT id, voucher_no, voucher_date, period, source_type, source_id, source_no,
                       status, total_debit, total_credit, created_by, audited_by, posted_at, remark
                  FROM fin_voucher WHERE source_type = ? AND source_id = ?
                """, HEAD_MAPPER, sourceType.name(), sourceId)
                .stream().findFirst().map(head -> head.withLines(linesOf(head.id())));
    }

    public List<VoucherLine> linesOf(long voucherId) {
        return jdbc.query("""
                SELECT id, line_no, account_id, account_code, summary, debit, credit
                  FROM fin_voucher_line WHERE voucher_id = ? ORDER BY line_no
                """, LINE_MAPPER, voucherId);
    }

    /** 科目是否被任何分录引用（FR-V3-06 验收：被引用不许删，409）。 */
    public boolean accountReferenced(long accountId) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fin_voucher_line WHERE account_id = ?", Long.class, accountId);
        return n != null && n > 0;
    }

    /** 状态转移（条件 UPDATE：非法状态自然 0 行，由调用方回查给出具体错误）。 */
    public boolean transition(long id, VoucherStatus from, VoucherStatus to,
                              Long actor, LocalDateTime postedAt) {
        return TenantJdbc.update(jdbc, """
                UPDATE fin_voucher
                   SET status = ?, updated_at = now(),
                       audited_by = COALESCE(?, audited_by),
                       posted_at = COALESCE(?, posted_at)
                 WHERE id = ? AND status = ?
                """, to.name(), actor, postedAt, id, from.name()) == 1;
    }

    /** 更新草稿单的头字段 + 合计（整单替换分录后调用）。 */
    public boolean updateDraftHead(long id, LocalDate voucherDate, String period,
                                   BigDecimal totalDebit, BigDecimal totalCredit, String remark) {
        return TenantJdbc.update(jdbc, """
                UPDATE fin_voucher
                   SET voucher_date = ?, period = ?, total_debit = ?, total_credit = ?,
                       remark = ?, updated_at = now()
                 WHERE id = ? AND status = 'DRAFT'
                """, voucherDate, period, totalDebit, totalCredit, remark, id) == 1;
    }

    public PageResult<Voucher> page(VoucherStatus status, VoucherSourceType sourceType,
                                    String period, LocalDate from, LocalDate to, String keyword,
                                    int pageNo, int size) {
        int p = PageResult.normalizePage(pageNo);
        int s = PageResult.normalizeSize(size);
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        if (status != null) {
            where.append(" AND v.status = ?");
            args.add(status.name());
        }
        if (sourceType != null) {
            where.append(" AND v.source_type = ?");
            args.add(sourceType.name());
        }
        if (period != null && !period.isBlank()) {
            where.append(" AND v.period = ?");
            args.add(period.trim());
        }
        if (from != null) {
            where.append(" AND v.voucher_date >= ?");
            args.add(from);
        }
        if (to != null) {
            where.append(" AND v.voucher_date <= ?");
            args.add(to);
        }
        if (keyword != null && !keyword.isBlank()) {
            where.append("""
                   AND (v.voucher_no ILIKE ? OR v.source_no ILIKE ?
                        OR EXISTS (SELECT 1 FROM fin_voucher_line l
                                    WHERE l.voucher_id = v.id AND l.summary ILIKE ?))""");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM fin_voucher v" + where,
                Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(s);
        pageArgs.add(PageResult.offset(p, s));
        List<Voucher> heads = jdbc.query("""
                SELECT v.id, v.voucher_no, v.voucher_date, v.period, v.source_type, v.source_id,
                       v.source_no, v.status, v.total_debit, v.total_credit, v.created_by,
                       v.audited_by, v.posted_at, v.remark
                  FROM fin_voucher v""" + where
                + " ORDER BY v.voucher_date DESC, v.id DESC LIMIT ? OFFSET ?",
                HEAD_MAPPER, pageArgs.toArray());
        // 列表页也要摘要与借贷合计：一次 IN 查询取回本页全部分录再组装，避免 N+1。
        List<Voucher> items = attachLines(heads);
        return new PageResult<>(items, total == null ? 0 : total, p, s);
    }

    /** 批量装配分录（一次查询覆盖整页，列表页与详情共用，避免 N+1）。 */
    public List<Voucher> attachLines(List<Voucher> heads) {
        if (heads.isEmpty()) {
            return List.of();
        }
        List<Long> ids = heads.stream().map(Voucher::id).toList();
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        java.util.Map<Long, List<VoucherLine>> grouped = new java.util.LinkedHashMap<>();
        ids.forEach(id -> grouped.put(id, new ArrayList<>()));
        jdbc.query("""
                SELECT voucher_id, line_no, account_id, account_code, summary, debit, credit
                  FROM fin_voucher_line
                 WHERE voucher_id IN (""" + placeholders + ") ORDER BY voucher_id, line_no",
                rs -> {
                    grouped.computeIfAbsent(rs.getLong("voucher_id"),
                            k -> new ArrayList<>())
                            .add(new VoucherLine(0, rs.getInt("line_no"),
                                    rs.getLong("account_id"), rs.getString("account_code"),
                                    rs.getString("summary"), rs.getBigDecimal("debit"),
                                    rs.getBigDecimal("credit")));
                },
                ids.toArray());
        List<Voucher> out = new ArrayList<>(heads.size());
        for (Voucher head : heads) {
            out.add(head.withLines(grouped.getOrDefault(head.id(), List.of())));
        }
        return out;
    }
}
