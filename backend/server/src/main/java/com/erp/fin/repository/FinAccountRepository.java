package com.erp.fin.repository;

import com.erp.fin.domain.AccountCategory;
import com.erp.fin.domain.AccountDirection;
import com.erp.fin.domain.FinAccount;
import com.erp.shared.db.DbErrors;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.tenant.TenantJdbc;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** 会计科目仓储（FR-V3-06）。tenant_id 一律交给 TenantSql 改写，SQL 里不出现它。 */
@Repository
public class FinAccountRepository {

    private static final RowMapper<FinAccount> MAPPER = (rs, i) -> new FinAccount(
            rs.getLong("id"),
            rs.getString("code"),
            rs.getString("name"),
            rs.getObject("parent_id") == null ? null : rs.getLong("parent_id"),
            AccountCategory.valueOf(rs.getString("category")),
            AccountDirection.valueOf(rs.getString("direction")),
            rs.getBoolean("is_leaf"),
            rs.getBigDecimal("init_balance"),
            rs.getBoolean("enabled"),
            rs.getString("remark"));

    private static final String COLUMNS =
            "id, code, name, parent_id, category, direction, is_leaf, init_balance, enabled, remark";

    private final JdbcTemplate jdbc;

    public FinAccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<FinAccount> listAll() {
        return jdbc.query("SELECT " + COLUMNS + " FROM fin_account ORDER BY code", MAPPER);
    }

    public Optional<FinAccount> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM fin_account WHERE id = ?",
                MAPPER, id).stream().findFirst();
    }

    public Optional<FinAccount> findByCode(String code) {
        return jdbc.query("SELECT " + COLUMNS + " FROM fin_account WHERE code = ?",
                MAPPER, code).stream().findFirst();
    }

    public long count() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM fin_account", Long.class);
        return n == null ? 0 : n;
    }

    public long countChildren(long parentId) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fin_account WHERE parent_id = ?", Long.class, parentId);
        return n == null ? 0 : n;
    }

    /**
     * 插入科目。撞本租户编码唯一键（uq_fin_account_code）翻成 409。
     *
     * <p>种子路径与用户建科目路径共用：种子走 {@code ON CONFLICT DO NOTHING} 会绕过
     * 冲突翻译，因此种子插入前应用层已判空（count() == 0），这里保持"撞了就报"。
     */
    public long insert(String code, String name, Long parentId, AccountCategory category,
                       AccountDirection direction, boolean leaf, BigDecimal initBalance,
                       boolean enabled, String remark) {
        try {
            return TenantJdbc.insertReturningId(jdbc,
                    """
                        INSERT INTO fin_account (code, name, parent_id, category, direction,
                                                 is_leaf, init_balance, enabled, remark)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        RETURNING id
                        """,
                    code, name, parentId, category.name(), direction.name(), leaf,
                    initBalance == null ? BigDecimal.ZERO : initBalance, enabled, remark);
        } catch (DataIntegrityViolationException ex) {
            BusinessException dup = DbErrors.translate(ex, "uq_fin_account_code",
                    ErrorCode.FIN_ACCOUNT_DUPLICATED, "科目编码已存在：code=" + code);
            if (dup != null) {
                throw dup;
            }
            throw ex;
        }
    }

    /** 名称/方向/期初/停用等可改，编码与父级创建后不可改（简报口径：编码是凭证锚点）。 */
    public boolean update(long id, String name, AccountDirection direction,
                          BigDecimal initBalance, boolean enabled, String remark) {
        return TenantJdbc.update(jdbc, """
                UPDATE fin_account
                   SET name = ?, direction = ?, init_balance = ?, enabled = ?,
                       remark = ?, updated_at = now()
                 WHERE id = ?
                """, name, direction.name(),
                initBalance == null ? BigDecimal.ZERO : initBalance, enabled, remark, id) == 1;
    }

    /** 父科目降级为非末级（新子科目挂到末级科目下时调用）。 */
    public boolean setLeaf(long id, boolean leaf) {
        return TenantJdbc.update(jdbc, """
                UPDATE fin_account SET is_leaf = ?, updated_at = now() WHERE id = ?
                """, leaf, id) == 1;
    }

    public boolean deleteById(long id) {
        return TenantJdbc.update(jdbc, "DELETE FROM fin_account WHERE id = ?", id) == 1;
    }
}
