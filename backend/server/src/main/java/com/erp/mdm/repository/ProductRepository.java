package com.erp.mdm.repository;

import com.erp.mdm.domain.Product;
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
 * 商品仓储（FR-MDM-01）。
 *
 * <p>唯一性由数据库 {@code uq_product_code} 兜底，应用层只负责把它翻译成
 * {@code MDM_CODE_DUPLICATED}。刻意<b>不做</b>"先 SELECT 查重再 INSERT" ——
 * 那种写法在并发下仍会漏，多一条查询只是增加了竞态窗口的宽度。
 */
@Repository
public class ProductRepository {

    private static final RowMapper<Product> MAPPER = (rs, i) -> new Product(
            rs.getLong("id"),
            rs.getString("code"),
            rs.getString("name"),
            rs.getString("category"),
            rs.getString("season"),
            rs.getBoolean("enabled"));

    private final JdbcTemplate jdbc;

    public ProductRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String code, String name, String category, String season, boolean enabled) {
        KeyHolder keys = new GeneratedKeyHolder();
        try {
            jdbc.update(con -> {
                var ps = con.prepareStatement("""
                        INSERT INTO products (code, name, category, season, enabled)
                        VALUES (?, ?, ?, ?, ?)
                        """, new String[]{"id"});
                ps.setString(1, code);
                ps.setString(2, name);
                ps.setString(3, category);
                ps.setString(4, season);
                ps.setBoolean(5, enabled);
                return ps;
            }, keys);
        } catch (DataIntegrityViolationException ex) {
            BusinessException translated = DbErrors.translate(ex, "uq_product_code",
                    ErrorCode.MDM_CODE_DUPLICATED, "商品编码已存在：code=%s".formatted(code));
            if (translated != null) {
                throw translated;
            }
            throw ex;
        }
        Number id = keys.getKey();
        if (id == null) {
            throw new IllegalStateException("插入商品后未取得主键：code=" + code);
        }
        return id.longValue();
    }

    public Optional<Product> findById(long id) {
        return jdbc.query("""
                SELECT id, code, name, category, season, enabled
                  FROM products WHERE id = ?
                """, MAPPER, id).stream().findFirst();
    }

    public Optional<Product> findByCode(String code) {
        return jdbc.query("""
                SELECT id, code, name, category, season, enabled
                  FROM products WHERE code = ?
                """, MAPPER, code).stream().findFirst();
    }

    /**
     * 分页查询。{@code keyword} 同时匹配编码与名称（模糊），为空则不过滤。
     *
     * <p>先用 count 再取页：契约要求返回 total，而窗口函数 {@code count(*) over()}
     * 在空页时拿不到总数，会让"第 5 页但只有 2 页数据"的响应失去 total。
     */
    public PageResult<Product> page(String keyword, int page, int size) {
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
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM products" + where,
                Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(s);
        pageArgs.add(PageResult.offset(p, s));
        List<Product> items = jdbc.query("""
                SELECT id, code, name, category, season, enabled
                  FROM products
                """ + where + " ORDER BY id LIMIT ? OFFSET ?", MAPPER, pageArgs.toArray());
        return new PageResult<>(items, total == null ? 0 : total, p, s);
    }

    /** 更新基础字段（编码不可改：改了历史单据的可读性就断了）。 */
    public boolean update(long id, String name, String category, String season, boolean enabled) {
        return jdbc.update("""
                UPDATE products
                   SET name = ?, category = ?, season = ?, enabled = ?, updated_at = now()
                 WHERE id = ?
                """, name, category, season, enabled, id) == 1;
    }

    /** 停用（软删）：商品一旦被单据引用就不可物理删除。 */
    public boolean disable(long id) {
        return jdbc.update("""
                UPDATE products SET enabled = FALSE, updated_at = now() WHERE id = ?
                """, id) == 1;
    }
}
