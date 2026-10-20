package com.erp.mdm.repository;

import com.erp.mdm.domain.Sku;
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
 * SKU 仓储（FR-MDM-01）。
 *
 * <p><b>唯一性语义</b>：{@code (product_id, color, size)} 唯一（{@code uq_sku_variant}）。
 * 这条约束是"SKU 矩阵生成"能否安全重复执行的基础 ——
 * 同一商品再次生成同样的颜色×尺码矩阵时，已存在的组合应被<b>跳过</b>而不是报错，
 * 但若请求里本身写了重复组合（矩阵自冲突），必须报 {@code MDM_SKU_DUPLICATED}。
 * 两者的区分在应用服务里做，仓储层只负责忠实映射数据库结论。
 */
@Repository
public class SkuRepository {

    private static final RowMapper<Sku> MAPPER = (rs, i) -> new Sku(
            rs.getLong("id"),
            rs.getLong("product_id"),
            rs.getString("color"),
            rs.getString("size"),
            rs.getString("barcode"),
            rs.getLong("retail_price_amount"),
            rs.getBoolean("enabled"));

    private final JdbcTemplate jdbc;

    public SkuRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long productId, String color, String size, String barcode,
                       long retailPriceAmount, boolean enabled) {
        KeyHolder keys = new GeneratedKeyHolder();
        try {
            jdbc.update(con -> {
                var ps = con.prepareStatement("""
                        INSERT INTO skus (product_id, color, size, barcode, retail_price_amount, enabled)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """, new String[]{"id"});
                ps.setLong(1, productId);
                ps.setString(2, color);
                ps.setString(3, size);
                ps.setString(4, barcode);
                ps.setLong(5, retailPriceAmount);
                ps.setBoolean(6, enabled);
                return ps;
            }, keys);
        } catch (DataIntegrityViolationException ex) {
            BusinessException translated = DbErrors.translate(ex, "uq_sku_variant",
                    ErrorCode.MDM_SKU_DUPLICATED,
                    "SKU 重复：商品 %d 下已存在 %s / %s 组合"
                            .formatted(productId, color, size));
            if (translated != null) {
                throw translated;
            }
            if (DbErrors.isSqlState(ex, DbErrors.FOREIGN_KEY_VIOLATION)) {
                throw new BusinessException(ErrorCode.SYS_NOT_FOUND,
                        "商品不存在：productId=%d".formatted(productId));
            }
            throw ex;
        }
        Number id = keys.getKey();
        if (id == null) {
            throw new IllegalStateException("插入 SKU 后未取得主键：productId=" + productId);
        }
        return id.longValue();
    }

    public Optional<Sku> findById(long id) {
        return jdbc.query("""
                SELECT id, product_id, color, size, barcode, retail_price_amount, enabled
                  FROM skus WHERE id = ?
                """, MAPPER, id).stream().findFirst();
    }

    public Optional<Sku> findByVariant(long productId, String color, String size) {
        return jdbc.query("""
                SELECT id, product_id, color, size, barcode, retail_price_amount, enabled
                  FROM skus WHERE product_id = ? AND color = ? AND size = ?
                """, MAPPER, productId, color, size).stream().findFirst();
    }

    public List<Sku> findByProduct(long productId) {
        return jdbc.query("""
                SELECT id, product_id, color, size, barcode, retail_price_amount, enabled
                  FROM skus WHERE product_id = ?
                 ORDER BY color, size
                """, MAPPER, productId);
    }

    public PageResult<Sku> page(Long productId, String color, String size, int page, int sizeRows) {
        int p = PageResult.normalizePage(page);
        int s = PageResult.normalizeSize(sizeRows);
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        if (productId != null) {
            where.append(" AND product_id = ?");
            args.add(productId);
        }
        if (color != null && !color.isBlank()) {
            where.append(" AND color = ?");
            args.add(color);
        }
        if (size != null && !size.isBlank()) {
            where.append(" AND size = ?");
            args.add(size);
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM skus" + where,
                Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(s);
        pageArgs.add(PageResult.offset(p, s));
        List<Sku> items = jdbc.query("""
                SELECT id, product_id, color, size, barcode, retail_price_amount, enabled
                  FROM skus
                """ + where + " ORDER BY product_id, color, size LIMIT ? OFFSET ?",
                MAPPER, pageArgs.toArray());
        return new PageResult<>(items, total == null ? 0 : total, p, s);
    }

    public boolean update(long id, String barcode, long retailPriceAmount, boolean enabled) {
        return jdbc.update("""
                UPDATE skus
                   SET barcode = ?, retail_price_amount = ?, enabled = ?, updated_at = now()
                 WHERE id = ?
                """, barcode, retailPriceAmount, enabled, id) == 1;
    }

    /** 停用（软删）。停用的 SKU 不允许再建单，但历史单据仍可查阅。 */
    public boolean disable(long id) {
        return jdbc.update("""
                UPDATE skus SET enabled = FALSE, updated_at = now() WHERE id = ?
                """, id) == 1;
    }

    public boolean existsByProduct(long productId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM skus WHERE product_id = ?",
                Integer.class, productId);
        return n != null && n > 0;
    }
}
