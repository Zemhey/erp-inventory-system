package com.erp.mdm.repository;

import com.erp.mdm.domain.WarehouseLocation;
import com.erp.shared.db.DbErrors;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 仓库库位仓储（V18）。
 *
 * <p>容量合计只统计启用库位：停用意味着“本库位不再分配容量”。
 * 唯一的租户改写入口仍是 {@code TenantSql}。
 */
@Repository
public class WarehouseLocationRepository {

    private static final RowMapper<WarehouseLocation> MAPPER = (rs, i) -> new WarehouseLocation(
            rs.getLong("id"),
            rs.getLong("warehouse_id"),
            rs.getString("location_code"),
            rs.getString("name"),
            rs.getLong("capacity_qty"),
            rs.getBoolean("enabled"));

    private final JdbcTemplate jdbc;

    public WarehouseLocationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long warehouseId, String locationCode, String name, long capacityQty,
                       boolean enabled, String requestId) {
        try {
            return com.erp.shared.tenant.TenantJdbc.insertReturningId(jdbc,
                    """
                        INSERT INTO warehouse_locations
                            (warehouse_id, location_code, name, capacity_qty, enabled,
                             create_request_id)
                        VALUES (?, ?, ?, ?, ?, ?)
                        RETURNING id
                        """,
                    warehouseId, locationCode, name, capacityQty, enabled, requestId);
        } catch (DataIntegrityViolationException ex) {
            BusinessException duplicated = DbErrors.translate(ex, "uq_warehouse_location_code",
                    ErrorCode.MDM_LOCATION_DUPLICATED,
                    "库位编码在仓库内已存在：locationCode=%s".formatted(locationCode));
            if (duplicated != null) {
                throw duplicated;
            }
            BusinessException duplicatedRequest = DbErrors.translate(ex,
                    "uq_warehouse_location_create_request", ErrorCode.SYS_DUPLICATE_REQUEST,
                    "幂等键已用于另一次建库位：requestId=%s".formatted(requestId));
            if (duplicatedRequest != null) {
                throw duplicatedRequest;
            }
            throw ex;
        }
    }

    public Optional<WarehouseLocation> findById(long warehouseId, long id) {
        return jdbc.query("""
                SELECT id, warehouse_id, location_code, name, capacity_qty, enabled
                  FROM warehouse_locations
                 WHERE warehouse_id = ? AND id = ?
                """, MAPPER, warehouseId, id).stream().findFirst();
    }

    public Optional<WarehouseLocation> findByRequestId(String requestId) {
        return jdbc.query("""
                SELECT id, warehouse_id, location_code, name, capacity_qty, enabled
                  FROM warehouse_locations
                 WHERE create_request_id = ?
                """, MAPPER, requestId).stream().findFirst();
    }

    public List<WarehouseLocation> findAll(long warehouseId, boolean onlyEnabled) {
        String sql = """
                SELECT id, warehouse_id, location_code, name, capacity_qty, enabled
                  FROM warehouse_locations
                 WHERE warehouse_id = ?
                """ + (onlyEnabled ? " AND enabled" : "") + " ORDER BY enabled DESC, id";
        return jdbc.query(sql, MAPPER, warehouseId);
    }

    /** 启用库位容量合计；{@code excludeId} 用于更新/启用时排除自身。 */
    public long sumEnabledCapacity(long warehouseId, long excludeId) {
        Long sum = jdbc.queryForObject("""
                SELECT COALESCE(SUM(capacity_qty), 0)
                  FROM warehouse_locations
                 WHERE warehouse_id = ? AND enabled AND id <> ?
                """, Long.class, warehouseId, excludeId);
        return sum == null ? 0 : sum;
    }

    public int count(long warehouseId, boolean onlyEnabled) {
        String sql = "SELECT COUNT(*) FROM warehouse_locations WHERE warehouse_id = ?"
                + (onlyEnabled ? " AND enabled" : "");
        Integer n = jdbc.queryForObject(sql, Integer.class, warehouseId);
        return n == null ? 0 : n;
    }

    public boolean update(long warehouseId, long id, String name, long capacityQty) {
        return jdbc.update("""
                UPDATE warehouse_locations
                   SET name = ?, capacity_qty = ?, updated_at = now()
                 WHERE warehouse_id = ? AND id = ?
                """, name, capacityQty, warehouseId, id) == 1;
    }

    public boolean disable(long warehouseId, long id) {
        return jdbc.update("""
                UPDATE warehouse_locations
                   SET enabled = FALSE, updated_at = now()
                 WHERE warehouse_id = ? AND id = ?
                """, warehouseId, id) == 1;
    }

    public boolean enable(long warehouseId, long id) {
        return jdbc.update("""
                UPDATE warehouse_locations
                   SET enabled = TRUE, updated_at = now()
                 WHERE warehouse_id = ? AND id = ?
                """, warehouseId, id) == 1;
    }
}
