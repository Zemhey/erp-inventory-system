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

    /**
     * 插入商品，可带建单幂等键（V2-C1）。
     *
     * <p>{@code uq_product_create_request} 是幂等的最终保证；
     * 分工与冲突翻译理由见 {@code WarehouseRepository.insert}。
     */
    public long insert(String code, String name, String category, String season, boolean enabled,
                       String requestId) {
        try {
            return com.erp.shared.tenant.TenantJdbc.insertReturningId(jdbc,
                    """
                        INSERT INTO products (code, name, category, season, enabled,
                                              create_request_id)
                        VALUES (?, ?, ?, ?, ?, ?)
                        RETURNING id
                        """,
                    new Object[]{code, name, category, season, enabled, requestId});
        } catch (DataIntegrityViolationException ex) {
            BusinessException duplicatedCode = DbErrors.translate(ex, "uq_product_code",
                    ErrorCode.MDM_CODE_DUPLICATED, "商品编码已存在：code=%s".formatted(code));
            if (duplicatedCode != null) {
                throw duplicatedCode;
            }
            BusinessException duplicatedRequest = DbErrors.translate(ex, "uq_product_create_request",
                    ErrorCode.SYS_DUPLICATE_REQUEST,
                    "幂等键已用于另一次建商品：requestId=%s".formatted(requestId));
            if (duplicatedRequest != null) {
                throw duplicatedRequest;
            }
            throw ex;
        }
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

    /**
     * 按建商品的幂等键回查。
     *
     * <p>null 键走 {@code = NULL} 天然返回空集，不需要判空分支（与
     * {@code WarehouseRepository.findByRequestId} 同一理由）。
     */
    public Optional<Product> findByRequestId(String requestId) {
        return jdbc.query("""
                SELECT id, code, name, category, season, enabled
                  FROM products WHERE create_request_id = ?
                """, MAPPER, requestId).stream().findFirst();
    }
}
