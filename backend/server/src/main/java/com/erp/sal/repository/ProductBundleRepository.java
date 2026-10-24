package com.erp.sal.repository;

import com.erp.sal.domain.BundleItem;
import com.erp.sal.domain.ProductBundle;
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

/** 套装仓储（FR-SAL-10）。 */
@Repository
public class ProductBundleRepository {

    private static final RowMapper<ProductBundle> MAPPER = (rs, i) -> new ProductBundle(
            rs.getLong("id"),
            rs.getString("code"),
            rs.getString("name"),
            rs.getLong("price_amount"),
            rs.getBoolean("enabled"),
            List.of());

    private final JdbcTemplate jdbc;

    public ProductBundleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insertBundle(String code, String name, long priceAmount, boolean enabled) {
        KeyHolder keys = new GeneratedKeyHolder();
        try {
            jdbc.update(con -> {
                var ps = con.prepareStatement("""
                        INSERT INTO product_bundles (code, name, price_amount, enabled)
                        VALUES (?, ?, ?, ?)
                        """, new String[]{"id"});
                ps.setString(1, code);
                ps.setString(2, name);
                ps.setLong(3, priceAmount);
                ps.setBoolean(4, enabled);
                return ps;
            }, keys);
        } catch (DataIntegrityViolationException ex) {
            BusinessException translated = DbErrors.translate(ex, "uq_bundle_code",
                    ErrorCode.MDM_BUNDLE_DUPLICATED, "套装编码已存在：code=%s".formatted(code));
            if (translated != null) {
                throw translated;
            }
            throw ex;
        }
        Number id = keys.getKey();
        if (id == null) {
            throw new IllegalStateException("插入套装后未取得主键：code=" + code);
        }
        return id.longValue();
    }

    public void insertItem(long bundleId, long componentSkuId, int ratioQty) {
        jdbc.update("""
                INSERT INTO bundle_items (bundle_id, component_sku_id, ratio_qty)
                VALUES (?, ?, ?)
                """, bundleId, componentSkuId, ratioQty);
    }

    public void replaceItems(long bundleId, List<BundleItem> items) {
        jdbc.update("DELETE FROM bundle_items WHERE bundle_id = ?", bundleId);
        for (BundleItem item : items) {
            insertItem(bundleId, item.componentSkuId(), item.ratioQty());
        }
    }

    public Optional<ProductBundle> findHeaderById(long id) {
        return jdbc.query("""
                SELECT id, code, name, price_amount, enabled
                  FROM product_bundles WHERE id = ?
                """, MAPPER, id).stream().findFirst();
    }

    public Optional<ProductBundle> findByCode(String code) {
        return jdbc.query("""
                SELECT id, code, name, price_amount, enabled
                  FROM product_bundles WHERE code = ?
                """, MAPPER, code).stream().findFirst();
    }

    public List<BundleItem> findItems(long bundleId) {
        return jdbc.query("""
                SELECT id, component_sku_id, ratio_qty
                  FROM bundle_items WHERE bundle_id = ? ORDER BY id
                """, (rs, i) -> new BundleItem(
                        rs.getLong("id"), rs.getLong("component_sku_id"),
                        rs.getInt("ratio_qty")), bundleId);
    }

    public Optional<ProductBundle> findById(long id) {
        return findHeaderById(id).map(b -> new ProductBundle(
                b.id(), b.code(), b.name(), b.priceAmount(), b.enabled(), findItems(id)));
    }

    public boolean update(long id, String name, long priceAmount, boolean enabled) {
        return jdbc.update("""
                UPDATE product_bundles
                   SET name = ?, price_amount = ?, enabled = ?, updated_at = now()
                 WHERE id = ?
                """, name, priceAmount, enabled, id) == 1;
    }

    public boolean disable(long id) {
        return jdbc.update("""
                UPDATE product_bundles SET enabled = FALSE, updated_at = now() WHERE id = ?
                """, id) == 1;
    }

    public PageResult<ProductBundle> page(String keyword, boolean onlyEnabled, int page, int size) {
        int p = PageResult.normalizePage(page);
        int s = PageResult.normalizeSize(size);
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        if (onlyEnabled) {
            where.append(" AND enabled");
        }
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (code ILIKE ? OR name ILIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM product_bundles" + where,
                Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(s);
        pageArgs.add(PageResult.offset(p, s));
        List<ProductBundle> items = jdbc.query("""
                SELECT id, code, name, price_amount, enabled
                  FROM product_bundles
                """ + where + " ORDER BY id LIMIT ? OFFSET ?", MAPPER, pageArgs.toArray()).stream()
                .map(b -> new ProductBundle(b.id(), b.code(), b.name(), b.priceAmount(),
                        b.enabled(), findItems(b.id())))
                .toList();
        return new PageResult<>(items, total == null ? 0 : total, p, s);
    }
}
