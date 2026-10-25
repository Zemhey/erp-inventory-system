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

    /**
     * 插入供应商，可带建单幂等键（V2-C1）。
     *
     * <p>{@code uq_supplier_create_request} 是幂等的最终保证；
     * 分工与冲突翻译理由见 {@code WarehouseRepository.insert}。
     */
    public long insert(String code, String name, String contactName, String contactPhone,
                       String address, boolean enabled, String requestId) {
        try {
            return com.erp.shared.tenant.TenantJdbc.insertReturningId(jdbc,
                    """
                        INSERT INTO suppliers (code, name, contact_name, contact_phone, address,
                                               enabled, create_request_id)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        RETURNING id
                        """,
                    new Object[]{code, name, contactName, contactPhone, address, enabled, requestId});
        } catch (DataIntegrityViolationException ex) {
            BusinessException duplicatedCode = DbErrors.translate(ex, "uq_supplier_code",
                    ErrorCode.MDM_CODE_DUPLICATED, "供应商编码已存在：code=%s".formatted(code));
            if (duplicatedCode != null) {
                throw duplicatedCode;
            }
            BusinessException duplicatedRequest = DbErrors.translate(ex, "uq_supplier_create_request",
                    ErrorCode.SYS_DUPLICATE_REQUEST,
                    "幂等键已用于另一次建供应商：requestId=%s".formatted(requestId));
            if (duplicatedRequest != null) {
                throw duplicatedRequest;
            }
            throw ex;
        }
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

    // ─────────────────────────── 幂等键（V2-C1，见 V15） ───────────────────────────

    /**
     * 按建供应商的幂等键回查。
     *
     * <p>null 键走 {@code = NULL} 天然返回空集，不需要判空分支（与
     * {@code WarehouseRepository.findByRequestId} 同一理由）。
     */
    public Optional<Supplier> findByRequestId(String requestId) {
        return jdbc.query("""
                SELECT id, code, name, contact_name, contact_phone, address, enabled
                  FROM suppliers WHERE create_request_id = ?
                """, MAPPER, requestId).stream().findFirst();
    }

    /**
     * 带幂等键停用。
     *
     * <p>谓词与冲突翻译的分工见 {@code WarehouseRepository.disable(long, String)}：
     * {@code AND enabled} 让"已是停用态"自己完成幂等（0 行 = 无可改）；
     * 键落在另一行上时由唯一索引拦下并翻成 409。
     *
     * @return true 表示本次调用真的改了状态
     */
    public boolean disable(long id, String requestId) {
        try {
            return jdbc.update("""
                    UPDATE suppliers
                       SET enabled = FALSE, disable_request_id = ?, updated_at = now()
                     WHERE id = ? AND enabled
                    """, requestId, id) == 1;
        } catch (DataIntegrityViolationException ex) {
            BusinessException translated = DbErrors.translate(ex, "uq_supplier_disable_request",
                    ErrorCode.SYS_DUPLICATE_REQUEST,
                    "幂等键已用于另一次停用：requestId=%s".formatted(requestId));
            if (translated != null) {
                throw translated;
            }
            throw ex;
        }
    }
}
