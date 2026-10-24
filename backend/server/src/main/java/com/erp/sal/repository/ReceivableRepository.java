package com.erp.sal.repository;

import com.erp.sal.domain.CustomerReceivable;
import com.erp.sal.domain.CustomerReceipt;
import com.erp.sal.domain.ReceivableSummary;
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

/** 应收/收款仓储（FR-SAL-07/08）。 */
@Repository
public class ReceivableRepository {

    private static final RowMapper<CustomerReceivable> RECEIVABLE_MAPPER = (rs, i) ->
            new CustomerReceivable(
                    rs.getLong("id"),
                    rs.getLong("sales_order_id"),
                    rs.getLong("customer_id"),
                    rs.getObject("biz_date", LocalDate.class),
                    rs.getObject("due_date", LocalDate.class),
                    rs.getLong("shipped_amount"),
                    rs.getLong("returned_amount"),
                    rs.getLong("received_amount"),
                    rs.getLong("balance"),
                    rs.getString("status"),
                    rs.getInt("version"));

    private static final RowMapper<CustomerReceipt> RECEIPT_MAPPER = (rs, i) ->
            new CustomerReceipt(
                    rs.getLong("id"),
                    rs.getString("receipt_no"),
                    rs.getLong("customer_id"),
                    rs.getLong("amount"),
                    rs.getObject("biz_date", LocalDate.class),
                    rs.getString("remark"),
                    List.of());

    private final com.erp.shared.tenant.TenantAwareJdbcTemplate jdbc;

    public ReceivableRepository(com.erp.shared.tenant.TenantAwareJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ─────────────────────────── 应收 ───────────────────────────

    /** @return 新建 id；空 = 并发/重放已被另一请求创建（调用方回读后做增量） */
    public Optional<Long> tryInsertReceivable(long salesOrderId, long customerId,
                                               LocalDate bizDate, LocalDate dueDate) {
        return jdbc.query("""
                INSERT INTO customer_receivables
                    (sales_order_id, customer_id, biz_date, due_date,
                     shipped_amount, returned_amount, received_amount, balance, status)
                VALUES (?, ?, ?, ?, 0, 0, 0, 0, 'OPEN')
                ON CONFLICT (sales_order_id) DO NOTHING
                RETURNING id
                """, (rs, i) -> rs.getLong("id"), salesOrderId, customerId, bizDate, dueDate)
                .stream().findFirst();
    }

    public Optional<CustomerReceivable> findByOrderId(long salesOrderId) {
        return jdbc.query("""
                SELECT id, sales_order_id, customer_id, biz_date, due_date,
                       shipped_amount, returned_amount, received_amount, balance, status, version
                  FROM customer_receivables WHERE sales_order_id = ?
                """, RECEIVABLE_MAPPER, salesOrderId).stream().findFirst();
    }

    public Optional<CustomerReceivable> findById(long id) {
        return jdbc.query("""
                SELECT id, sales_order_id, customer_id, biz_date, due_date,
                       shipped_amount, returned_amount, received_amount, balance, status, version
                  FROM customer_receivables WHERE id = ?
                """, RECEIVABLE_MAPPER, id).stream().findFirst();
    }

    public Optional<CustomerReceivable> findByIdForUpdate(long id) {
        return jdbc.query("""
                SELECT id, sales_order_id, customer_id, biz_date, due_date,
                       shipped_amount, returned_amount, received_amount, balance, status, version
                  FROM customer_receivables WHERE id = ? FOR UPDATE
                """, RECEIVABLE_MAPPER, id).stream().findFirst();
    }

    /** 出货增量：余额同步增加；已结清/已作废的应收按新余额重算状态。 */
    public boolean addShipped(long id, long amount) {
        return jdbc.update("""
                UPDATE customer_receivables
                   SET shipped_amount = shipped_amount + ?,
                       balance = balance + ?,
                       status = CASE WHEN status = 'VOIDED' THEN 'VOIDED'
                                     WHEN balance + ? > 0 THEN 'OPEN'
                                     ELSE 'SETTLED' END,
                       version = version + 1, updated_at = now()
                 WHERE id = ? AND status <> 'VOIDED'
                """, amount, amount, amount, id) == 1;
    }

    /** 退货增量：余额同步减少。 */
    public boolean addReturned(long id, long amount) {
        return jdbc.update("""
                UPDATE customer_receivables
                   SET returned_amount = returned_amount + ?,
                       balance = balance - ?,
                       status = CASE WHEN status = 'VOIDED' THEN 'VOIDED'
                                     WHEN balance - ? > 0 THEN 'OPEN'
                                     ELSE 'SETTLED' END,
                       version = version + 1, updated_at = now()
                 WHERE id = ? AND status <> 'VOIDED'
                """, amount, amount, amount, id) == 1;
    }

    /**
     * 核销：条件 UPDATE 同时守住「余额足够」与「未作废」。
     *
     * @return false = 余额不足 / 应收已作废（调用方翻译成超额拒绝）
     */
    public boolean applyAllocation(long receivableId, long amount) {
        return jdbc.update("""
                UPDATE customer_receivables
                   SET received_amount = received_amount + ?,
                       balance = balance - ?,
                       status = CASE WHEN balance - ? <= 0 THEN 'SETTLED' ELSE 'OPEN' END,
                       version = version + 1, updated_at = now()
                 WHERE id = ? AND status = 'OPEN' AND balance >= ?
                """, amount, amount, amount, receivableId, amount) == 1;
    }

    public boolean markVoided(long id) {
        return jdbc.update("""
                UPDATE customer_receivables
                   SET shipped_amount = 0, returned_amount = 0, received_amount = 0,
                       balance = 0, status = 'VOIDED', version = version + 1, updated_at = now()
                 WHERE id = ?
                """, id) == 1;
    }

    public PageResult<CustomerReceivable> page(Long customerId, String status, Boolean overdue,
                                               LocalDate today, int page, int size) {
        int p = PageResult.normalizePage(page);
        int s = PageResult.normalizeSize(size);
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        if (customerId != null) {
            where.append(" AND customer_id = ?");
            args.add(customerId);
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND status = ?");
            args.add(status);
        }
        if (Boolean.TRUE.equals(overdue)) {
            where.append(" AND status = 'OPEN' AND balance > 0 AND due_date < ?");
            args.add(today);
        } else if (Boolean.FALSE.equals(overdue)) {
            where.append(" AND (due_date IS NULL OR due_date >= ? OR balance <= 0)");
            args.add(today);
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM customer_receivables" + where,
                Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(s);
        pageArgs.add(PageResult.offset(p, s));
        List<CustomerReceivable> items = jdbc.query("""
                SELECT id, sales_order_id, customer_id, biz_date, due_date,
                       shipped_amount, returned_amount, received_amount, balance, status, version
                  FROM customer_receivables
                """ + where + " ORDER BY id DESC LIMIT ? OFFSET ?",
                RECEIVABLE_MAPPER, pageArgs.toArray());
        return new PageResult<>(items, total == null ? 0 : total, p, s);
    }

    public ReceivableSummary summary(long customerId, LocalDate today) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(shipped_amount), 0) AS shipped,
                       COALESCE(SUM(returned_amount), 0) AS returned,
                       COALESCE(SUM(received_amount), 0) AS received,
                       COALESCE(SUM(balance), 0) AS balance,
                       COALESCE(SUM(CASE WHEN status = 'OPEN' AND balance > 0
                                           AND due_date < ? THEN balance ELSE 0 END), 0)
                           AS overdue
                  FROM customer_receivables
                 WHERE customer_id = ? AND status <> 'VOIDED'
                """, (rs, i) -> new ReceivableSummary(rs.getLong("shipped"),
                        rs.getLong("returned"), rs.getLong("received"),
                        rs.getLong("balance"), rs.getLong("overdue")),
                today, customerId);
    }

    /** 信用敞口中的「已出货未收」部分。 */
    public long openBalance(long customerId) {
        Long v = jdbc.queryForObject("""
                SELECT COALESCE(SUM(balance), 0) FROM customer_receivables
                 WHERE customer_id = ? AND status <> 'VOIDED'
                """, Long.class, customerId);
        return v == null ? 0 : v;
    }

    // ─────────────────────────── 收款 ───────────────────────────

    public long insertReceipt(String receiptNo, long customerId, long amount, LocalDate bizDate,
                              String remark, String requestId) {
        return com.erp.shared.tenant.TenantJdbc.insertReturningId(jdbc,
                """
                    INSERT INTO customer_receipts
                        (receipt_no, customer_id, amount, biz_date, remark, request_id)
                    VALUES (?, ?, ?, ?, ?, ?)
                    RETURNING id
                    """,
                new Object[]{receiptNo, customerId, amount, bizDate, remark, requestId});
    }

    public void insertAllocation(long receiptId, long receivableId, long amount) {
        jdbc.update("""
                INSERT INTO receivable_allocations (receipt_id, receivable_id, amount)
                VALUES (?, ?, ?)
                """, receiptId, receivableId, amount);
    }

    public Optional<CustomerReceipt> findReceiptByRequestId(String requestId) {
        return jdbc.query("""
                SELECT id, receipt_no, customer_id, amount, biz_date, remark
                  FROM customer_receipts WHERE request_id = ?
                """, RECEIPT_MAPPER, requestId).stream().findFirst()
                .map(r -> withAllocations(r));
    }

    public Optional<CustomerReceipt> findReceiptById(long id) {
        return jdbc.query("""
                SELECT id, receipt_no, customer_id, amount, biz_date, remark
                  FROM customer_receipts WHERE id = ?
                """, RECEIPT_MAPPER, id).stream().findFirst()
                .map(r -> withAllocations(r));
    }

    private CustomerReceipt withAllocations(CustomerReceipt receipt) {
        List<CustomerReceipt.Allocation> allocations = jdbc.query("""
                SELECT id, receivable_id, amount FROM receivable_allocations
                 WHERE receipt_id = ? ORDER BY id
                """, (rs, i) -> new CustomerReceipt.Allocation(
                        rs.getLong("id"), rs.getLong("receivable_id"), rs.getLong("amount")),
                receipt.id());
        return new CustomerReceipt(receipt.id(), receipt.receiptNo(), receipt.customerId(),
                receipt.amount(), receipt.bizDate(), receipt.remark(), allocations);
    }

    public PageResult<CustomerReceipt> pageReceipts(Long customerId, int page, int size) {
        int p = PageResult.normalizePage(page);
        int s = PageResult.normalizeSize(size);
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        if (customerId != null) {
            where.append(" AND customer_id = ?");
            args.add(customerId);
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM customer_receipts" + where,
                Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(s);
        pageArgs.add(PageResult.offset(p, s));
        List<CustomerReceipt> items = jdbc.query("""
                SELECT id, receipt_no, customer_id, amount, biz_date, remark
                  FROM customer_receipts
                """ + where + " ORDER BY id DESC LIMIT ? OFFSET ?",
                RECEIPT_MAPPER, pageArgs.toArray()).stream()
                .map(this::withAllocations)
                .toList();
        return new PageResult<>(items, total == null ? 0 : total, p, s);
    }
}
