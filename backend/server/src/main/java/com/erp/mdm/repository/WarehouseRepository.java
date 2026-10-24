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
            rs.getBoolean("enabled"),
            rs.getInt("priority"));

    private final com.erp.shared.tenant.TenantAwareJdbcTemplate jdbc;

    public WarehouseRepository(com.erp.shared.tenant.TenantAwareJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入仓库，可带建单幂等键（V2-C1）。
     *
     * <p>{@code create_request_id} 上的局部唯一索引 {@code uq_warehouse_create_request}
     * 是幂等的<b>最终保证</b>：应用层的"先按键回查"只负责把重放变成友好返回，
     * 并发下同键的两个请求里必有一个在这里被数据库拦住（与 {@code uq_po_request} 同一分工）。
     */
    public long insert(String code, String name, String address, boolean enabled, int priority,
                       String requestId) {
        try {
            return com.erp.shared.tenant.TenantJdbc.insertReturningId(jdbc,
                    """
                        INSERT INTO warehouses (code, name, address, enabled, priority,
                                                create_request_id)
                        VALUES (?, ?, ?, ?, ?, ?)
                        RETURNING id
                        """,
                    new Object[]{code, name, address, enabled, priority, requestId});
        } catch (DataIntegrityViolationException ex) {
            BusinessException duplicatedCode = DbErrors.translate(ex, "uq_warehouse_code",
                    ErrorCode.MDM_CODE_DUPLICATED, "仓库编码已存在：code=%s".formatted(code));
            if (duplicatedCode != null) {
                throw duplicatedCode;
            }
            BusinessException duplicatedRequest = DbErrors.translate(ex, "uq_warehouse_create_request",
                    ErrorCode.SYS_DUPLICATE_REQUEST,
                    "幂等键已用于另一次建仓库：requestId=%s".formatted(requestId));
            if (duplicatedRequest != null) {
                throw duplicatedRequest;
            }
            throw ex;
        }
    }

    /**
     * 按建仓库的幂等键回查。
     *
     * <p>{@code requestId} 为 null 时条件变成 {@code = NULL}，SQL 语义天然返回空集，
     * 因此不需要额外的判空分支（与 {@code PurchaseRequisitionRepository.findByRequestId} 一致）。
     */
    public Optional<Warehouse> findByRequestId(String requestId) {
        return jdbc.query("""
                SELECT id, code, name, address, enabled, priority
                  FROM warehouses WHERE create_request_id = ?
                """, MAPPER, requestId).stream().findFirst();
    }

    public Optional<Warehouse> findById(long id) {
        return jdbc.query("""
                SELECT id, code, name, address, enabled, priority
                  FROM warehouses WHERE id = ?
                """, MAPPER, id).stream().findFirst();
    }

    public List<Warehouse> findAll(boolean onlyEnabled) {
        String sql = """
                SELECT id, code, name, address, enabled, priority
                  FROM warehouses
                """ + (onlyEnabled ? " WHERE enabled" : "") + " ORDER BY priority DESC, id";
        return jdbc.query(sql, MAPPER);
    }

    public boolean update(long id, String name, String address, boolean enabled) {
        return jdbc.update("""
                UPDATE warehouses
                   SET name = ?, address = ?, enabled = ?, updated_at = now()
                 WHERE id = ?
                """, name, address, enabled, id) == 1;
    }

    public boolean update(long id, String name, String address, boolean enabled, int priority) {
        return jdbc.update("""
                UPDATE warehouses
                   SET name = ?, address = ?, enabled = ?, priority = ?, updated_at = now()
                 WHERE id = ?
                """, name, address, enabled, priority, id) == 1;
    }

    /** 启用的仓库按发货优先级排序（FR-SAL-11）：与 V9 一致，数值越大越优先。 */
    public List<Warehouse> findEnabledByPriority() {
        return jdbc.query("""
                SELECT id, code, name, address, enabled, priority
                  FROM warehouses WHERE enabled
                 ORDER BY priority DESC, id
                """, MAPPER);
    }

    public boolean disable(long id) {
        return jdbc.update("""
                UPDATE warehouses SET enabled = FALSE, updated_at = now() WHERE id = ?
                """, id) == 1;
    }

    /**
     * 带幂等键停用（V2-C1）。
     *
     * <p>谓词 {@code AND enabled} 让"已经是停用态"这一事实自己完成幂等：
     * 重复点击不再写第二行、不再推后 {@code updated_at}，应用服务据 0 行判为重放。
     * 而"停用后被重新启用、再用原键停一次"会真的生效 —— 那是一次真实的状态变更，
     * 不该因为键见过就被吞掉。
     *
     * <p>键被用在<b>另一行</b>上时（客户端把同一个键打到两个资源），
     * 局部唯一索引 {@code uq_warehouse_disable_request} 会拦住并翻成 409 ——
     * 那种情况返回"已停用"是错的，调用方需要知道自己用错了键。
     *
     * @return true 表示本次调用真的把启用态改成了停用；false 表示没有可改的（重放）
     */
    public boolean disable(long id, String requestId) {
        try {
            return jdbc.update("""
                    UPDATE warehouses
                       SET enabled = FALSE, disable_request_id = ?, updated_at = now()
                     WHERE id = ? AND enabled
                    """, requestId, id) == 1;
        } catch (DataIntegrityViolationException ex) {
            BusinessException translated = DbErrors.translate(ex, "uq_warehouse_disable_request",
                    ErrorCode.SYS_DUPLICATE_REQUEST,
                    "幂等键已用于另一次停用：requestId=%s".formatted(requestId));
            if (translated != null) {
                throw translated;
            }
            throw ex;
        }
    }
}
