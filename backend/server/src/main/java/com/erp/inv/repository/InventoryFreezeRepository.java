package com.erp.inv.repository;

import com.erp.inv.domain.FreezeStatus;
import com.erp.inv.domain.InventoryFreeze;
import com.erp.shared.web.PageResult;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** 库存冻结仓储（FR-INV-10）。 */
@Repository
public class InventoryFreezeRepository {

    private static final RowMapper<InventoryFreeze> MAPPER = (rs, i) -> new InventoryFreeze(
            rs.getLong("id"),
            rs.getLong("sku_id"),
            rs.getLong("warehouse_id"),
            rs.getString("batch_no"),
            rs.getInt("qty"),
            rs.getString("reason"),
            FreezeStatus.valueOf(rs.getString("status")),
            rs.getObject("expires_at", OffsetDateTime.class),
            rs.getObject("released_at", OffsetDateTime.class),
            rs.getObject("created_at", OffsetDateTime.class));

    private final JdbcTemplate jdbc;

    public InventoryFreezeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<InventoryFreeze> findById(long id) {
        return jdbc.query("""
                SELECT id, sku_id, warehouse_id, batch_no, qty, reason, status,
                       expires_at, released_at, created_at
                  FROM inventory_freezes WHERE id = ?
                """, MAPPER, id).stream().findFirst();
    }

    public Optional<InventoryFreeze> findByRequestId(String requestId) {
        return jdbc.query("""
                SELECT id, sku_id, warehouse_id, batch_no, qty, reason, status,
                       expires_at, released_at, created_at
                  FROM inventory_freezes WHERE request_id = ?
                """, MAPPER, requestId).stream().findFirst();
    }

    /** 插入冻结记录；同一 requestId 重复提交返回空（INV-9）。 */
    public Optional<Long> insert(long skuId, long warehouseId, String batchNo, int qty,
                                 String reason, LocalDateTime expiresAt, String requestId) {
        List<Long> ids = jdbc.queryForList("""
                INSERT INTO inventory_freezes
                    (sku_id, warehouse_id, batch_no, qty, reason, status, expires_at, request_id)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                ON CONFLICT (request_id) DO NOTHING
                RETURNING id
                """, Long.class, skuId, warehouseId, batchNo, qty, reason, expiresAt, requestId);
        return ids.stream().findFirst();
    }

    /** 人工/超时解冻：ACTIVE → 目标状态，条件更新保证只成功一次。 */
    public boolean markReleased(long id, FreezeStatus target, String releaseRequestId,
                                LocalDateTime releasedAt) {
        return jdbc.update("""
                UPDATE inventory_freezes
                   SET status = ?, release_request_id = ?, released_at = ?, updated_at = now()
                 WHERE id = ? AND status = 'ACTIVE'
                """, target.name(), releaseRequestId, releasedAt, id) == 1;
    }

    public String releaseRequestId(long id) {
        List<String> rows = jdbc.queryForList(
                "SELECT release_request_id FROM inventory_freezes WHERE id = ?", String.class, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 已到期且仍 ACTIVE 的冻结。{@code now} 必须来自 ClockProvider，
     * 否则超时解冻只能等真实时间到来才能验证。
     */
    public List<InventoryFreeze> findOverdueActive(LocalDateTime now) {
        return jdbc.query("""
                SELECT id, sku_id, warehouse_id, batch_no, qty, reason, status,
                       expires_at, released_at, created_at
                  FROM inventory_freezes
                 WHERE status = 'ACTIVE' AND expires_at IS NOT NULL AND expires_at < ?
                 ORDER BY id
                """, MAPPER, now);
    }

    public PageResult<InventoryFreeze> page(FreezeStatus status, Long skuId, Long warehouseId,
                                            int page, int size) {
        int p = PageResult.normalizePage(page);
        int s = PageResult.normalizeSize(size);
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (status != null) {
            where.append(" AND status = ?");
            args.add(status.name());
        }
        if (skuId != null) {
            where.append(" AND sku_id = ?");
            args.add(skuId);
        }
        if (warehouseId != null) {
            where.append(" AND warehouse_id = ?");
            args.add(warehouseId);
        }
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_freezes" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(s);
        pageArgs.add(PageResult.offset(p, s));
        List<InventoryFreeze> items = jdbc.query("""
                SELECT id, sku_id, warehouse_id, batch_no, qty, reason, status,
                       expires_at, released_at, created_at
                  FROM inventory_freezes
                """ + where + " ORDER BY id DESC LIMIT ? OFFSET ?", MAPPER, pageArgs.toArray());
        return new PageResult<>(items, total == null ? 0 : total, p, s);
    }
}
