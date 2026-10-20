package com.erp.mdm.repository;

import com.erp.mdm.domain.Warehouse;
import com.erp.shared.db.DbErrors;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 仓库仓储（FR-MDM-04）。表在 V1 已存在，M1 补齐 CRUD。
 *
 * <p>仓库列表不分页 —— 一个网店的仓库数量是个位数，
 * 分页只会让前端多写一页逻辑而没有任何收益（契约里 {@code /warehouses} 也确实返回 List）。
 */
@Repository
public class WarehouseRepository {

    private static final RowMapper<Warehouse> MAPPER = (rs, i) -> new Warehouse(
            rs.getLong("id"),
            rs.getString("code"),
            rs.getString("name"),
            rs.getString("address"),
            rs.getBoolean("enabled"));

    private final JdbcTemplate jdbc;

    public WarehouseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String code, String name, String address, boolean enabled) {
        KeyHolder keys = new GeneratedKeyHolder();
        try {
            jdbc.update(con -> {
                var ps = con.prepareStatement("""
                        INSERT INTO warehouses (code, name, address, enabled)
                        VALUES (?, ?, ?, ?)
                        """, new String[]{"id"});
                ps.setString(1, code);
                ps.setString(2, name);
                ps.setString(3, address);
                ps.setBoolean(4, enabled);
                return ps;
            }, keys);
        } catch (DataIntegrityViolationException ex) {
            BusinessException translated = DbErrors.translate(ex, "uq_warehouse_code",
                    ErrorCode.MDM_CODE_DUPLICATED, "仓库编码已存在：code=%s".formatted(code));
            if (translated != null) {
                throw translated;
            }
            throw ex;
        }
        Number id = keys.getKey();
        if (id == null) {
            throw new IllegalStateException("插入仓库后未取得主键：code=" + code);
        }
        return id.longValue();
    }

    public Optional<Warehouse> findById(long id) {
        return jdbc.query("""
                SELECT id, code, name, address, enabled FROM warehouses WHERE id = ?
                """, MAPPER, id).stream().findFirst();
    }

    public List<Warehouse> findAll(boolean onlyEnabled) {
        String sql = """
                SELECT id, code, name, address, enabled
                  FROM warehouses
                """ + (onlyEnabled ? " WHERE enabled" : "") + " ORDER BY id";
        return jdbc.query(sql, MAPPER);
    }

    public boolean update(long id, String name, String address, boolean enabled) {
        return jdbc.update("""
                UPDATE warehouses
                   SET name = ?, address = ?, enabled = ?, updated_at = now()
                 WHERE id = ?
                """, name, address, enabled, id) == 1;
    }

    public boolean disable(long id) {
        return jdbc.update("""
                UPDATE warehouses SET enabled = FALSE, updated_at = now() WHERE id = ?
                """, id) == 1;
    }
}
