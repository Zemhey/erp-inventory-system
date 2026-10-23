package com.erp.inv.repository;

import com.erp.inv.domain.InventoryAdjustment;
import com.erp.shared.web.PageResult;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** 库存调整仓储（FR-INV-09）。 */
@Repository
public class InventoryAdjustmentRepository {

    private static final RowMapper<InventoryAdjustment> MAPPER = (rs, i) -> new InventoryAdjustment(
            rs.getLong("id"),
            rs.getString("adjust_no"),
            rs.getLong("sku_id"),
            rs.getLong("warehouse_id"),
            rs.getString("batch_no"),
            rs.getInt("change_qty"),
            rs.getString("reason"),
            rs.getObject("created_at", OffsetDateTime.class));

    private final JdbcTemplate jdbc;

    public InventoryAdjustmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<InventoryAdjustment> findByRequestId(String requestId) {
        return jdbc.query("""
                SELECT id, adjust_no, sku_id, warehouse_id, batch_no, change_qty, reason, created_at
                  FROM inventory_adjustments WHERE request_id = ?
                """, MAPPER, requestId).stream().findFirst();
    }

    /** 插入调整单；同一 requestId 重复提交返回空（INV-9）。 */
    public Optional<Long> insert(String adjustNo, long skuId, long warehouseId, String batchNo,
                                 int changeQty, String reason, String requestId) {
        List<Long> ids = jdbc.queryForList("""
                INSERT INTO inventory_adjustments
                    (adjust_no, sku_id, warehouse_id, batch_no, change_qty, reason, request_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (request_id) DO NOTHING
                RETURNING id
                """, Long.class, adjustNo, skuId, warehouseId, batchNo, changeQty, reason, requestId);
        return ids.stream().findFirst();
    }

    public PageResult<InventoryAdjustment> page(Long skuId, Long warehouseId, int page, int size) {
        int p = PageResult.normalizePage(page);
        int s = PageResult.normalizeSize(size);
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (skuId != null) {
            where.append(" AND sku_id = ?");
            args.add(skuId);
        }
        if (warehouseId != null) {
            where.append(" AND warehouse_id = ?");
            args.add(warehouseId);
        }
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_adjustments" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(s);
        pageArgs.add(PageResult.offset(p, s));
        List<InventoryAdjustment> items = jdbc.query("""
                SELECT id, adjust_no, sku_id, warehouse_id, batch_no, change_qty, reason, created_at
                  FROM inventory_adjustments
                """ + where + " ORDER BY id DESC LIMIT ? OFFSET ?", MAPPER, pageArgs.toArray());
        return new PageResult<>(items, total == null ? 0 : total, p, s);
    }
}
