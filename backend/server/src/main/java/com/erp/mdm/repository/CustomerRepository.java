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
            rs.getBoolean("enabled"));

    private final JdbcTemplate jdbc;

    public CustomerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String code, String name, String phone, int discountRate,
                       String level, boolean enabled) {
        KeyHolder keys = new GeneratedKeyHolder();
        try {
            jdbc.update(con -> {
                var ps = con.prepareStatement("""
                        INSERT INTO customers (code, name, phone, discount_rate, level, enabled)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """, new String[]{"id"});
                ps.setString(1, code);
                ps.setString(2, name);
                ps.setString(3, phone);
                ps.setInt(4, discountRate);
                ps.setString(5, level);
                ps.setBoolean(6, enabled);
                return ps;
            }, keys);
        } catch (DataIntegrityViolationException ex) {
            BusinessException translated = DbErrors.translate(ex, "uq_customer_code",
                    ErrorCode.MDM_CODE_DUPLICATED, "客户编码已存在：code=%s".formatted(code));
            if (translated != null) {
                throw translated;
            }
            throw ex;
        }
        Number id = keys.getKey();
        if (id == null) {
            throw new IllegalStateException("插入客户后未取得主键：code=" + code);
        }
        return id.longValue();
    }

    public Optional<Customer> findById(long id) {
        return jdbc.query("""
                SELECT id, code, name, phone, discount_rate, level, enabled
                  FROM customers WHERE id = ?
                """, MAPPER, id).stream().findFirst();
    }

    public Optional<Customer> findByCode(String code) {
        return jdbc.query("""
                SELECT id, code, name, phone, discount_rate, level, enabled
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
                SELECT id, code, name, phone, discount_rate, level, enabled
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

    public boolean disable(long id) {
        return jdbc.update("""
                UPDATE customers SET enabled = FALSE, updated_at = now() WHERE id = ?
                """, id) == 1;
    }
}
