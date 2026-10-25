package com.erp.mdm.repository;

import com.erp.mdm.domain.Customer;
import com.erp.shared.db.DbErrors;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.web.PageResult;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 客户/会员仓储（FR-MDM-03）。
 *
 * <p>折扣率是整数万分比，合法性（0 &lt; rate ≤ 10000）由应用层校验并给出业务消息，
 * 数据库 CHECK 约束作为最后兜底。
 */
@Repository
public class CustomerRepository {

    private static final RowMapper<Customer> MAPPER = (rs, i) -> new Customer(
            rs.getLong("id"),
            rs.getString("code"),
            rs.getString("name"),
            rs.getString("phone"),
            rs.getInt("discount_rate"),
            rs.getString("level"),
            rs.getBoolean("enabled"),
            rs.getLong("credit_limit"),
            rs.getInt("credit_days"));

    private final JdbcTemplate jdbc;

    public CustomerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入客户，可带建单幂等键（V2-C1）。
     *
     * <p>{@code uq_customer_create_request} 是幂等的最终保证；
     * 分工与冲突翻译理由见 {@code WarehouseRepository.insert}。
     */
    public long insert(String code, String name, String phone, int discountRate,
                       String level, boolean enabled, String requestId) {
        try {
            return com.erp.shared.tenant.TenantJdbc.insertReturningId(jdbc,
                    """
                        INSERT INTO customers (code, name, phone, discount_rate, level, enabled,
                                               create_request_id)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        RETURNING id
                        """,
                    new Object[]{code, name, phone, discountRate, level, enabled, requestId});
        } catch (DataIntegrityViolationException ex) {
            BusinessException duplicatedCode = DbErrors.translate(ex, "uq_customer_code",
                    ErrorCode.MDM_CODE_DUPLICATED, "客户编码已存在：code=%s".formatted(code));
            if (duplicatedCode != null) {
                throw duplicatedCode;
            }
            BusinessException duplicatedRequest = DbErrors.translate(ex, "uq_customer_create_request",
                    ErrorCode.SYS_DUPLICATE_REQUEST,
                    "幂等键已用于另一次建客户：requestId=%s".formatted(requestId));
            if (duplicatedRequest != null) {
                throw duplicatedRequest;
            }
            throw ex;
        }
    }

    public Optional<Customer> findById(long id) {
        return jdbc.query("""
                SELECT id, code, name, phone, discount_rate, level, enabled,
                       credit_limit, credit_days
                  FROM customers WHERE id = ?
                """, MAPPER, id).stream().findFirst();
    }

    public Optional<Customer> findByCode(String code) {
        return jdbc.query("""
                SELECT id, code, name, phone, discount_rate, level, enabled,
                       credit_limit, credit_days
                  FROM customers WHERE code = ?
                """, MAPPER, code).stream().findFirst();
    }

    /**
     * 按 id 批量取客户名。
     *
     * <p><b>为什么单独开这个口子</b>：单据列表要显示"是谁的单"时，没有批量取名入口的调用方
     * 只有两条坏路 —— 循环里逐条查（N+1），或"先把主数据前 200 条拉成前端字典"
     * （第 201 个客户就显示成 {@code 客户#201}，计划书 §6 坑 14）。
     * 一条 IN 查询两条都躲开了。
     *
     * @return {@code id -> name}；查不到的 id 不会出现在结果里（调用方自行给兜底文案）
     */
    public Map<Long, String> findNamesByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        List<Long> list = new ArrayList<>(ids);
        String placeholders = String.join(",", Collections.nCopies(list.size(), "?"));
        Map<Long, String> names = new LinkedHashMap<>();
        jdbc.query("SELECT id, name FROM customers WHERE id IN (" + placeholders + ")",
                rs -> {
                    names.put(rs.getLong("id"), rs.getString("name"));
                },
                list.toArray());
        return names;
    }

    public PageResult<Customer> page(String keyword, int page, int size) {
        int p = PageResult.normalizePage(page);
        int s = PageResult.normalizeSize(size);
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (code ILIKE ? OR name ILIKE ? OR phone ILIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM customers" + where,
                Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(s);
        pageArgs.add(PageResult.offset(p, s));
        List<Customer> items = jdbc.query("""
                SELECT id, code, name, phone, discount_rate, level, enabled,
                       credit_limit, credit_days
                  FROM customers
                """ + where + " ORDER BY id LIMIT ? OFFSET ?", MAPPER, pageArgs.toArray());
        return new PageResult<>(items, total == null ? 0 : total, p, s);
    }

    public boolean update(long id, String name, String phone, int discountRate,
                          String level, boolean enabled) {
        return jdbc.update("""
                UPDATE customers
                   SET name = ?, phone = ?, discount_rate = ?, level = ?,
                       enabled = ?, updated_at = now()
                 WHERE id = ?
                """, name, phone, discountRate, level, enabled, id) == 1;
    }

    /**
     * 锁客户行并读取（FR-SAL-09 信用校验）。
     *
     * <p>并发建单时，两个请求都可能先读到"还有额度"；把客户行锁进事务后，
     * 授信判断与建单在同一把行锁下串行化，超额的那一单会看到已占用的敞口。
     */
    public Optional<Customer> findByIdForUpdate(long id) {
        return jdbc.query("""
                SELECT id, code, name, phone, discount_rate, level, enabled,
                       credit_limit, credit_days
                  FROM customers WHERE id = ? FOR UPDATE
                """, MAPPER, id).stream().findFirst();
    }

    /** 更新信用额度与账期（FR-SAL-09）。 */
    public boolean updateCredit(long id, long creditLimit, int creditDays) {
        return jdbc.update("""
                UPDATE customers
                   SET credit_limit = ?, credit_days = ?, updated_at = now()
                 WHERE id = ?
                """, creditLimit, creditDays, id) == 1;
    }

    public boolean disable(long id) {
        return jdbc.update("""
                UPDATE customers SET enabled = FALSE, updated_at = now() WHERE id = ?
                """, id) == 1;
    }

    // ─────────────────────────── 幂等键（V2-C1，见 V15） ───────────────────────────

    /**
     * 按建客户的幂等键回查。
     *
     * <p>null 键走 {@code = NULL} 天然返回空集，不需要判空分支（与
     * {@code WarehouseRepository.findByRequestId} 同一理由）。
     */
    public Optional<Customer> findByRequestId(String requestId) {
        return jdbc.query("""
                SELECT id, code, name, phone, discount_rate, level, enabled,
                       credit_limit, credit_days
                  FROM customers WHERE create_request_id = ?
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
                    UPDATE customers
                       SET enabled = FALSE, disable_request_id = ?, updated_at = now()
                     WHERE id = ? AND enabled
                    """, requestId, id) == 1;
        } catch (DataIntegrityViolationException ex) {
            BusinessException translated = DbErrors.translate(ex, "uq_customer_disable_request",
                    ErrorCode.SYS_DUPLICATE_REQUEST,
                    "幂等键已用于另一次停用：requestId=%s".formatted(requestId));
            if (translated != null) {
                throw translated;
            }
            throw ex;
        }
    }
}
