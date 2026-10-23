package com.erp.inv.repository;

import java.time.LocalDateTime;
import java.util.List;
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

    /** 标记为已消费（预占转为实际出库）。出货确认后调用。 */
    public boolean markConsumed(long id) {
        return jdbc.update("""
                UPDATE inventory_reservations
                   SET status = 'CONSUMED', updated_at = now()
                 WHERE id = ? AND status = 'ACTIVE'
                """, id) == 1;
    }

    /**
     * 查出<b>已到期且仍 ACTIVE</b> 的预占（BR-INV-05）。
     *
     * <p>{@code now} 必须来自注入的 {@code ClockProvider}，不能由数据库的
     * {@code now()} 代劳 —— 否则测试无法用可控时钟验证"到期/未到期"边界。
     *
     * <p>边界为<b>严格小于</b>：{@code expires_at == now} 时算未到期，留给下一轮。
     * {@code expires_at IS NULL} 表示该预占不设超时，天然被排除（NULL 比较不为真）。
     *
     * <p>按 id 升序返回，让处理顺序确定 —— 并发下两个调度实例同时扫到同一批时，
     * 相同顺序 + 条件更新可避免交叉等待。
     */
    public List<Reservation> findOverdueActive(LocalDateTime now) {
        return jdbc.query("""
                SELECT id, sku_id, warehouse_id, batch_no, reserved_qty, status, request_id
                  FROM inventory_reservations
                 WHERE status = 'ACTIVE' AND expires_at < ?
                 ORDER BY id
                """, MAPPER, now);
    }

    /**
     * 标记为已超时（ACTIVE → EXPIRED）。
     *
     * <p>与 {@link #markReleased}、{@link #markConsumed} 同样是<b>条件更新</b>：
     * 只有当前仍是 ACTIVE 才生效，因此重复执行过期任务不会二次置位，
     * 也不会与并发的取消/出货互相覆盖（TC-INV-015）。
     */
    public boolean markExpired(long id) {
        return jdbc.update("""
                UPDATE inventory_reservations
                   SET status = 'EXPIRED', updated_at = now()
                 WHERE id = ? AND status = 'ACTIVE'
                """, id) == 1;
    }

    /** 某 SKU 在某仓库下仍处于 ACTIVE 的预占总量。 */
    public int sumActiveReservedQty(long skuId, long warehouseId) {
        Integer v = jdbc.queryForObject("""
                SELECT COALESCE(SUM(reserved_qty), 0) FROM inventory_reservations
                 WHERE sku_id = ? AND warehouse_id = ? AND status = 'ACTIVE'
                """, Integer.class, skuId, warehouseId);
        return v == null ? 0 : v;
    }

    /** 按 id 批量取仍为 ACTIVE 的预占（发货确认按预占分段消费）。 */
    public List<Reservation> findActiveByIds(java.util.Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        return jdbc.query("""
                SELECT id, sku_id, warehouse_id, batch_no, reserved_qty, status, request_id
                  FROM inventory_reservations
                 WHERE status = 'ACTIVE' AND id IN (""" + placeholders + ") ORDER BY id",
                MAPPER, ids.toArray());
    }
}
