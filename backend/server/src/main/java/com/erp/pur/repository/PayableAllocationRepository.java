package com.erp.pur.repository;

import com.erp.pur.domain.PayableAllocation;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 付款核销明细仓储（FR-PUR-08）。
 *
 * <p>同一次付款的多条核销共用 {@code payment_no} 与 {@code payment_amount}；
 * 守恒（Σ明细 == 付款单金额）由应用层写入前校验，并由
 * {@link #conservation(Long)} 提供可重复的数据库断言。
 */
@Repository
public class PayableAllocationRepository {

    private static final RowMapper<PayableAllocation> MAPPER = (rs, i) -> new PayableAllocation(
            rs.getLong("id"),
            rs.getString("payment_no"),
            rs.getLong("supplier_id"),
            rs.getLong("payable_id"),
            rs.getLong("amount"),
            rs.getLong("payment_amount"),
            rs.getObject("biz_date", LocalDate.class),
            rs.getString("remark"),
            rs.getObject("created_at", OffsetDateTime.class));

    private final JdbcTemplate jdbc;

    public PayableAllocationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入一条核销明细并返回其 id。
     *
     * <p>★ V3-2 钩子点（纯追加式改造：签名 void→long，调用方不受影响）：
     * 付款凭证的来源 id 约定为"该笔付款的第一条核销明细 id"，
     * 财务域顺着它反查整笔付款（见 FinSourceRepository.payment）。
     */
    public long insert(String paymentNo, long supplierId, long payableId, long amount,
                       long paymentAmount, LocalDate bizDate, String period, String remark,
                       String requestId) {
        return com.erp.shared.tenant.TenantJdbc.insertReturningId(jdbc, """
                INSERT INTO payable_allocations
                    (payment_no, supplier_id, payable_id, amount, payment_amount,
                     biz_date, period, remark, request_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """, paymentNo, supplierId, payableId, amount, paymentAmount,
                bizDate, period, remark, requestId);
    }

    public List<PayableAllocation> findByPayableId(long payableId) {
        return jdbc.query("""
                SELECT id, payment_no, supplier_id, payable_id, amount, payment_amount,
                       biz_date, remark, created_at
                  FROM payable_allocations WHERE payable_id = ? ORDER BY id
                """, MAPPER, payableId);
    }

    public List<PayableAllocation> findByRequestId(String requestId) {
        return jdbc.query("""
                SELECT id, payment_no, supplier_id, payable_id, amount, payment_amount,
                       biz_date, remark, created_at
                  FROM payable_allocations WHERE request_id = ? ORDER BY payable_id
                """, MAPPER, requestId);
    }

    /**
     * 核销守恒检查：按付款单分组，明细之和必须等于付款单金额，且同组金额一致。
     *
     * @param supplierId null = 全部供应商
     */
    public PaymentTotals conservation(Long supplierId) {
        String where = supplierId == null ? "" : " WHERE supplier_id = ?";
        Object[] args = supplierId == null ? new Object[0] : new Object[]{supplierId};
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(payment_amount), 0) AS payment_total,
                       COALESCE(SUM(allocated), 0) AS allocation_total,
                       COALESCE(SUM(CASE WHEN allocated <> payment_amount
                                           OR max_payment <> min_payment THEN 1 ELSE 0 END), 0)
                           AS drift_count
                  FROM (
                        SELECT payment_no,
                               MIN(payment_amount) AS payment_amount,
                               MAX(payment_amount) AS max_payment,
                               MIN(payment_amount) AS min_payment,
                               SUM(amount) AS allocated
                          FROM payable_allocations
                        """ + where + """
                         GROUP BY payment_no
                  ) t
                """, (rs, i) -> new PaymentTotals(
                        rs.getLong("payment_total"), rs.getLong("allocation_total"),
                        rs.getInt("drift_count")), args);
    }

    public record PaymentTotals(long paymentTotal, long allocationTotal, int driftCount) {
    }
}
