package com.erp.inv.repository;

import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 预占记录仓储。
 *
 * <p>幂等性由数据库唯一约束 {@code uq_reservation_request} 保证：
 * 仅凭这一点，"相同 requestId 重复预占"就不可能产生两笔预占。
 */
@Repository
public class InventoryReservationRepository {

    private final JdbcTemplate jdbc;

    public InventoryReservationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Reservation(long id, long skuId, long warehouseId, String batchNo,
                              int reservedQty, String status, String requestId) {
    }

    private static final RowMapper<Reservation> MAPPER = (rs, i) -> new Reservation(
            rs.getLong("id"), rs.getLong("sku_id"), rs.getLong("warehouse_id"),
            rs.getString("batch_no"), rs.getInt("reserved_qty"),
            rs.getString("status"), rs.getString("request_id"));

    public Optional<Reservation> findByRequestId(String requestId) {
        return jdbc.query("""
                SELECT id, sku_id, warehouse_id, batch_no, reserved_qty, status, request_id
                  FROM inventory_reservations WHERE request_id = ?
                """, MAPPER, requestId).stream().findFirst();
    }

    public Optional<Reservation> findById(long id) {
        return jdbc.query("""
                SELECT id, sku_id, warehouse_id, batch_no, reserved_qty, status, request_id
                  FROM inventory_reservations WHERE id = ?
                """, MAPPER, id).stream().findFirst();
    }

    /** 尝试插入预占记录；若 requestId 已存在（唯一约束冲突），返回 false 而不是抛异常。 */
    public boolean tryInsert(long skuId, long warehouseId, String batchNo, int qty,
                             String bizRefNo, String requestId, LocalDateTime expiresAt) {
        int inserted = jdbc.update("""
                INSERT INTO inventory_reservations
                    (sku_id, warehouse_id, batch_no, reserved_qty, status,
                     biz_ref_no, request_id, expires_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, ?)
                ON CONFLICT (request_id) DO NOTHING
                """, skuId, warehouseId, batchNo, qty, bizRefNo, requestId, expiresAt);
        return inserted == 1;
    }

    public boolean markReleased(long id) {
        return jdbc.update("""
                UPDATE inventory_reservations
                   SET status = 'RELEASED', updated_at = now()
                 WHERE id = ? AND status = 'ACTIVE'
                """, id) == 1;
    }
}
