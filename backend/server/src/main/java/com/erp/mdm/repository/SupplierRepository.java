package com.erp.mdm.repository;

import com.erp.mdm.domain.Supplier;
import com.erp.shared.db.DbErrors;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.web.PageResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 供应商仓储（FR-MDM-02）。编码唯一由 {@code uq_supplier_code} 兜底。
 */
@Repository
public class SupplierRepository {

    private static final RowMapper<Supplier> MAPPER = (rs, i) -> new Supplier(
            rs.getLong("id"),
            rs.getString("code"),
            rs.getString("name"),
            rs.getString("contact_name"),
            rs.getString("contact_phone"),
            rs.getString("address"),
            rs.getBoolean("enabled"));

    private final JdbcTemplate jdbc;

    public SupplierRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String code, String name, String contactName, String contactPhone,
                       String address, boolean enabled) {
        KeyHolder keys = new GeneratedKeyHolder();
        try {
            jdbc.update(con -> {
                var ps = con.prepareStatement("""
                        INSERT INTO suppliers (code, name, contact_name, contact_phone, address, enabled)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """, new String[]{"id"});
                ps.setString(1, code);
                ps.setString(2, name);
                ps.setString(3, contactName);
                ps.setString(4, contactPhone);
                ps.setString(5, address);
                ps.setBoolean(6, enabled);
                return ps;
            }, keys);
        } catch (DataIntegrityViolationException ex) {
            BusinessException translated = DbErrors.translate(ex, "uq_supplier_code",
                    ErrorCode.MDM_CODE_DUPLICATED, "供应商编码已存在：code=%s".formatted(code));
            if (translated != null) {
                throw translated;
            }
            throw ex;
        }
        Number id = keys.getKey();
        if (id == null) {
            throw new IllegalStateException("插入供应商后未取得主键：code=" + code);
        }
        return id.longValue();
    }

    public Optional<Supplier> findById(long id) {
        return jdbc.query("""
                SELECT id, code, name, contact_name, contact_phone, address, enabled
                  FROM suppliers WHERE id = ?
                """, MAPPER, id).stream().findFirst();
    }

    public Optional<Supplier> findByCode(String code) {
        return jdbc.query("""
                SELECT id, code, name, contact_name, contact_phone, address, enabled
                  FROM suppliers WHERE code = ?
                """, MAPPER, code).stream().findFirst();
    }

    public PageResult<Supplier> page(String keyword, int page, int size) {
        int p = PageResult.normalizePage(page);
        int s = PageResult.normalizeSize(size);
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (code ILIKE ? OR name ILIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM suppliers" + where,
                Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(s);
        pageArgs.add(PageResult.offset(p, s));
        List<Supplier> items = jdbc.query("""
                SELECT id, code, name, contact_name, contact_phone, address, enabled
                  FROM suppliers
                """ + where + " ORDER BY id LIMIT ? OFFSET ?", MAPPER, pageArgs.toArray());
        return new PageResult<>(items, total == null ? 0 : total, p, s);
    }

    public boolean update(long id, String name, String contactName, String contactPhone,
                          String address, boolean enabled) {
        return jdbc.update("""
                UPDATE suppliers
                   SET name = ?, contact_name = ?, contact_phone = ?, address = ?,
                       enabled = ?, updated_at = now()
                 WHERE id = ?
                """, name, contactName, contactPhone, address, enabled, id) == 1;
    }

    public boolean disable(long id) {
        return jdbc.update("""
                UPDATE suppliers SET enabled = FALSE, updated_at = now() WHERE id = ?
                """, id) == 1;
    }
}
