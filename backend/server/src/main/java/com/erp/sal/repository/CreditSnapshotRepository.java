package com.erp.sal.repository;

import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 信用快照仓储（FR-SAL-09）。 */
@Repository
public class CreditSnapshotRepository {

    private final JdbcTemplate jdbc;

    public CreditSnapshotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Snapshot(long id, long customerId, LocalDate snapshotDate, long creditLimit,
                           int creditDays, long exposureAmount, long orderAmount,
                           Long salesOrderId, String requestId) {
    }

    public void insert(long customerId, LocalDate snapshotDate, long creditLimit, int creditDays,
                       long exposureAmount, long orderAmount, Long salesOrderId, String requestId) {
        jdbc.update("""
                INSERT INTO customer_credit_snapshots
                    (customer_id, snapshot_date, credit_limit, credit_days,
                     exposure_amount, order_amount, sales_order_id, request_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, customerId, snapshotDate, creditLimit, creditDays,
                exposureAmount, orderAmount, salesOrderId, requestId);
    }

    public List<Snapshot> findByCustomer(long customerId) {
        return jdbc.query("""
                SELECT id, customer_id, snapshot_date, credit_limit, credit_days,
                       exposure_amount, order_amount, sales_order_id, request_id
                  FROM customer_credit_snapshots
                 WHERE customer_id = ? ORDER BY id DESC
                """, (rs, i) -> new Snapshot(
                        rs.getLong("id"), rs.getLong("customer_id"),
                        rs.getObject("snapshot_date", LocalDate.class),
                        rs.getLong("credit_limit"), rs.getInt("credit_days"),
                        rs.getLong("exposure_amount"), rs.getLong("order_amount"),
                        rs.getObject("sales_order_id", Long.class), rs.getString("request_id")),
                customerId);
    }
}
