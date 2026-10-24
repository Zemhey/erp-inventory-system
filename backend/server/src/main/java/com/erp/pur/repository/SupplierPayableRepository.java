package com.erp.pur.repository;

import com.erp.pur.domain.SupplierPayable;
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

/**
 * 供应商应付仓储（FR-PUR-07）。
 *
 * <p>余额是数据库生成列，本类只读它、从不写入；
 * 付款与退货都通过<b>条件 UPDATE</b> 增加 {@code paid_amount / returned_amount}，
 * 条件不满足返回 0 行，由应用层翻译成业务错误。
 */
@Repository
public class SupplierPayableRepository {

    private static final RowMapper<SupplierPayable> MAPPER = (rs, i) -> new SupplierPayable(
            rs.getLong("id"),
            rs.getString("payable_no"),
            rs.getLong("supplier_id"),
            rs.getLong("purchase_order_id"),
            rs.getString("order_no"),
            rs.getLong("receipt_id"),
            rs.getObject("biz_date", LocalDate.class),
            rs.getString("period"),
            rs.getLong("original_amount"),
            rs.getLong("returned_amount"),
            rs.getLong("paid_amount"),
            rs.getLong("balance_amount"),
            rs.getInt("version"),
            List.of());

    private static final String SELECT = """
            SELECT sp.id, sp.payable_no, sp.supplier_id, sp.purchase_order_id,
                   po.order_no, sp.receipt_id, sp.biz_date, sp.period,
                   sp.original_amount, sp.returned_amount, sp.paid_amount,
                   sp.balance_amount, sp.version
              FROM supplier_payables sp
              JOIN purchase_orders po ON po.id = sp.purchase_order_id
            """;

    private final JdbcTemplate jdbc;

    public SupplierPayableRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String payableNo, long supplierId, long purchaseOrderId, long receiptId,
                       LocalDate bizDate, String period, long originalAmount) {
        return com.erp.shared.tenant.TenantJdbc.insertReturningId(jdbc,
                """
                    INSERT INTO supplier_payables
                        (payable_no, supplier_id, purchase_order_id, receipt_id,
                         biz_date, period, original_amount)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    RETURNING id
                    """,
                new Object[]{payableNo, supplierId, purchaseOrderId, receiptId, bizDate, period, originalAmount});
    }

    public Optional<SupplierPayable> findById(long id) {
        return jdbc.query(SELECT + " WHERE sp.id = ?", MAPPER, id).stream().findFirst();
    }

    public Optional<SupplierPayable> findByReceiptId(long receiptId) {
        return jdbc.query(SELECT + " WHERE sp.receipt_id = ?", MAPPER, receiptId)
                .stream().findFirst();
    }

    /**
     * 付款核销：条件 UPDATE 保证"核销额不超过当前余额"。
     *
     * @return false = 余额不足或应付不存在（应用层 → 409 PUR_PAYMENT_EXCEED_PAYABLE）
     */
    public boolean increasePaid(long payableId, long amount) {
        return jdbc.update("""
                UPDATE supplier_payables
                   SET paid_amount = paid_amount + ?,
                       version = version + 1, updated_at = now()
                 WHERE id = ?
                   AND original_amount - returned_amount - paid_amount >= ?
                """, amount, payableId, amount) == 1;
    }

    /**
     * 采购退货冲减应付：条件 UPDATE 保证余额不为负。
     *
     * @return false = 余额不足（已付款部分不能被退货冲掉）
     */
    public boolean increaseReturned(long payableId, long amount) {
        return jdbc.update("""
                UPDATE supplier_payables
                   SET returned_amount = returned_amount + ?,
                       version = version + 1, updated_at = now()
                 WHERE id = ?
                   AND original_amount - returned_amount - paid_amount >= ?
                """, amount, payableId, amount) == 1;
    }

    public PageResult<SupplierPayable> page(Long supplierId, String status, int page, int size) {
        int p = PageResult.normalizePage(page);
        int s = PageResult.normalizeSize(size);
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        if (supplierId != null) {
            where.append(" AND sp.supplier_id = ?");
            args.add(supplierId);
        }
        if (status != null && !status.isBlank()) {
            where.append("""
                     AND (CASE
                            WHEN sp.balance_amount = 0 THEN 'SETTLED'
                            WHEN sp.paid_amount = 0 AND sp.returned_amount = 0 THEN 'OPEN'
                            ELSE 'PARTIAL'
                          END) = ?
                    """);
            args.add(status);
        }
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM supplier_payables sp" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(s);
        pageArgs.add(PageResult.offset(p, s));
        List<SupplierPayable> items = jdbc.query(SELECT + where
                + " ORDER BY sp.id DESC LIMIT ? OFFSET ?", MAPPER, pageArgs.toArray());
        return new PageResult<>(items, total == null ? 0 : total, p, s);
    }

    /** 供应商维度汇总：Σ原值 / Σ退货 / Σ付款 / Σ余额。 */
    public PayableTotals totals(Long supplierId) {
        String where = supplierId == null ? "" : " WHERE supplier_id = ?";
        Object[] args = supplierId == null ? new Object[0] : new Object[]{supplierId};
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(original_amount), 0) AS original_total,
                       COALESCE(SUM(returned_amount), 0) AS returned_total,
                       COALESCE(SUM(paid_amount), 0) AS paid_total,
                       COALESCE(SUM(balance_amount), 0) AS balance_total
                  FROM supplier_payables
                """ + where, (rs, i) -> new PayableTotals(
                        rs.getLong("original_total"), rs.getLong("returned_total"),
                        rs.getLong("paid_total"), rs.getLong("balance_total")), args);
    }

    public record PayableTotals(long originalTotal, long returnedTotal, long paidTotal,
                                long balanceTotal) {
    }
}
