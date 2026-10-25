package com.erp.mdm.repository;

import com.erp.mdm.domain.Sku;
import com.erp.mdm.domain.SkuFilter;
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
            rs.getBoolean("enabled"),
            rs.getString("season_code"),
            rs.getString("band_code"),
            rs.getObject("launch_date", java.time.LocalDate.class));

    private final JdbcTemplate jdbc;

    public SkuRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long productId, String color, String size, String barcode,
                       long retailPriceAmount, boolean enabled) {
        return insert(productId, color, size, barcode, retailPriceAmount, enabled,
                null, null, null, null);
    }

    /**
     * 插入 SKU，可带建单幂等键（V2-C1）。
     *
     * <p>与 {@code uq_sku_variant}（商品下颜色×尺码唯一）是两层互不相干的保护：
     * 变体冲突翻成 {@code MDM_SKU_DUPLICATED}，幂等键冲突翻成 {@code SYS_DUPLICATE_REQUEST}。
     * 分工与冲突翻译理由见 {@code WarehouseRepository.insert}。
     */
    public long insert(long productId, String color, String size, String barcode,
                       long retailPriceAmount, boolean enabled,
                       String seasonCode, String bandCode, java.time.LocalDate launchDate,
                       String requestId) {
        try {
            return com.erp.shared.tenant.TenantJdbc.insertReturningId(jdbc,
                    """
                        INSERT INTO skus (product_id, color, size, barcode, retail_price_amount,
                                          enabled, season_code, band_code, launch_date,
                                          create_request_id)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        RETURNING id
                        """,
                    new Object[]{productId, color, size, barcode, retailPriceAmount, enabled, seasonCode, bandCode, launchDate, requestId});
        } catch (DataIntegrityViolationException ex) {
            BusinessException duplicatedVariant = DbErrors.translate(ex, "uq_sku_variant",
                    ErrorCode.MDM_SKU_DUPLICATED,
                    "SKU 重复：商品 %d 下已存在 %s / %s 组合"
                            .formatted(productId, color, size));
            if (duplicatedVariant != null) {
                throw duplicatedVariant;
            }
            BusinessException duplicatedRequest = DbErrors.translate(ex, "uq_sku_create_request",
                    ErrorCode.SYS_DUPLICATE_REQUEST,
                    "幂等键已用于另一次建 SKU：requestId=%s".formatted(requestId));
            if (duplicatedRequest != null) {
                throw duplicatedRequest;
            }
            if (DbErrors.isSqlState(ex, DbErrors.FOREIGN_KEY_VIOLATION)) {
                throw new BusinessException(ErrorCode.SYS_NOT_FOUND,
                        "商品不存在：productId=%d".formatted(productId));
            }
            throw ex;
        }
    }

    public Optional<Sku> findById(long id) {
        return jdbc.query("""
                SELECT id, product_id, color, size, barcode, retail_price_amount, enabled,
                       season_code, band_code, launch_date
                  FROM skus WHERE id = ?
                """, MAPPER, id).stream().findFirst();
    }

    public Optional<Sku> findByVariant(long productId, String color, String size) {
        return jdbc.query("""
                SELECT id, product_id, color, size, barcode, retail_price_amount, enabled,
                       season_code, band_code, launch_date
                  FROM skus WHERE product_id = ? AND color = ? AND size = ?
                """, MAPPER, productId, color, size).stream().findFirst();
    }

    public List<Sku> findByProduct(long productId) {
        return jdbc.query("""
                SELECT id, product_id, color, size, barcode, retail_price_amount, enabled,
                       season_code, band_code, launch_date
                  FROM skus WHERE product_id = ?
                 ORDER BY color, size
                """, MAPPER, productId);
    }

    /**
     * 按 id 批量取 SKU 展示名：{@code 商品名 · 颜色-尺码}。
     *
     * <p>SKU 本身没有独立编码列（V1：SKU = 商品 + 颜色 + 尺码），只给颜色尺码会撞名
     * （多个商品都有"白色-L"），所以展示名必须带商品名 —— 与主数据页、单据页同一口径。
     * 组成写在 SQL 里是为了<b>一条查询</b>拿齐：跨模块取名不应把 products 也 N+1 一遍。
     *
     * @return {@code skuId -> 展示名}；不存在的 id 不出现在结果里
     */
    public Map<Long, String> findDisplayNamesByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        List<Long> list = new ArrayList<>(ids);
        String placeholders = String.join(",", Collections.nCopies(list.size(), "?"));
        Map<Long, String> names = new LinkedHashMap<>();
        jdbc.query("""
                SELECT k.id, pr.name || ' · ' || k.color || '-' || k.size AS display_name
                  FROM skus k
                  JOIN products pr ON pr.id = k.product_id
                 WHERE k.id IN (""" + placeholders + ")",
                rs -> {
                    names.put(rs.getLong("id"), rs.getString("display_name"));
                },
                list.toArray());
        return names;
    }

    public PageResult<Sku> page(Long productId, String color, String skuSize, int page, int sizeRows) {
        return page(new SkuFilter(productId, color, skuSize, null, null, null, null, null),
                page, sizeRows);
    }

    public PageResult<Sku> page(SkuFilter filter, int page, int sizeRows) {
        int p = PageResult.normalizePage(page);
        int s = PageResult.normalizeSize(sizeRows);
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        if (filter.productId() != null) {
            where.append(" AND product_id = ?");
            args.add(filter.productId());
        }
        if (filter.color() != null && !filter.color().isBlank()) {
            where.append(" AND color = ?");
            args.add(filter.color());
        }
        if (filter.skuSize() != null && !filter.skuSize().isBlank()) {
            where.append(" AND size = ?");
            args.add(filter.skuSize());
        }
        if (filter.seasonCode() != null && !filter.seasonCode().isBlank()) {
            where.append(" AND season_code = ?");
            args.add(filter.seasonCode().trim());
        }
        if (filter.bandCode() != null && !filter.bandCode().isBlank()) {
            where.append(" AND band_code = ?");
            args.add(filter.bandCode().trim());
        }
        if (filter.launchDateFrom() != null) {
            where.append(" AND launch_date >= ?");
            args.add(filter.launchDateFrom());
        }
        if (filter.launchDateTo() != null) {
            where.append(" AND launch_date <= ?");
            args.add(filter.launchDateTo());
        }
        if (filter.seasonYear() != null) {
            where.append(" AND EXTRACT(YEAR FROM launch_date) = ?");
            args.add(filter.seasonYear());
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM skus" + where,
                Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(s);
        pageArgs.add(PageResult.offset(p, s));
        List<Sku> items = jdbc.query("""
                SELECT id, product_id, color, size, barcode, retail_price_amount, enabled,
                       season_code, band_code, launch_date
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

    /** 更新 SKU 的季节/年份/波段维度（FR-MDM-08）。 */
    public boolean updateDimensions(long id, String seasonCode, String bandCode,
                                    java.time.LocalDate launchDate) {
        return jdbc.update("""
                UPDATE skus
                   SET season_code = ?, band_code = ?, launch_date = ?, updated_at = now()
                 WHERE id = ?
                """, seasonCode, bandCode, launchDate, id) == 1;
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

    // ─────────────────────────── 幂等键（V2-C1，见 V15） ───────────────────────────

    /**
     * 按建 SKU 的幂等键回查。
     *
     * <p>null 键走 {@code = NULL} 天然返回空集，不需要判空分支（与
     * {@code WarehouseRepository.findByRequestId} 同一理由）。
     */
    public Optional<Sku> findByRequestId(String requestId) {
        return jdbc.query("""
                SELECT id, product_id, color, size, barcode, retail_price_amount, enabled,
                       season_code, band_code, launch_date
                  FROM skus WHERE create_request_id = ?
                """, MAPPER, requestId).stream().findFirst();
    }
}
