package com.erp.mdm.application;

import com.erp.mdm.domain.Sku;
import com.erp.mdm.repository.ProductRepository;
import com.erp.mdm.repository.SkuRepository;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.web.PageResult;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SKU 应用服务（FR-MDM-01）—— 单条创建 + 颜色×尺码矩阵批量生成。
 *
 * <p><b>矩阵生成的语义</b>（这是本类最容易做错的地方，所以写清楚）：
 * <ul>
 *   <li>请求给 {@code colors} × {@code sizes}，服务端做笛卡尔积；</li>
 *   <li>已存在的组合 <b>跳过</b>（{@code skipped}），不报错 ——
 *       这样"补一次颜色"的运营操作可以安全重放；</li>
 *   <li>请求自身出现重复组合（如 colors 里写了两次"白色"），
 *       属于<b>请求有误</b>，直接 {@code MDM_SKU_DUPLICATED} ——
 *       静默去重会让调用方以为"生成了 6 个"而实际 4 个；</li>
 *   <li>颜色或尺码列表为空 → 校验错误，不静默生成 0 条。</li>
 * </ul>
 *
 * <p>整个矩阵在一个事务里生成：要么全部生效，要么一条都不留。
 */
@Service
public class SkuApplicationService {

    private final SkuRepository skus;
    private final ProductRepository products;

    public SkuApplicationService(SkuRepository skus, ProductRepository products) {
        this.skus = skus;
        this.products = products;
    }

    public record CreateCommand(long productId, String color, String size, String barcode,
                                long retailPriceAmount, boolean enabled) {
    }

    /**
     * 矩阵生成结果。
     *
     * @param created 本次新建的 SKU（按生成顺序）
     * @param skipped 已存在而跳过的组合数
     */
    public record MatrixResult(List<Sku> created, int skipped) {
    }

    @Transactional
    public Sku create(CreateCommand cmd) {
        requireProduct(cmd.productId());
        long id = skus.insert(cmd.productId(),
                requireText(cmd.color(), "颜色"),
                requireText(cmd.size(), "尺码"),
                trimToNull(cmd.barcode()),
                requireNonNegative(cmd.retailPriceAmount(), "零售价"),
                cmd.enabled());
        return skus.findById(id).orElseThrow();
    }

    /**
     * 按颜色 × 尺码矩阵批量生成 SKU。
     *
     * @param productId          目标商品
     * @param colors             颜色列表（顺序即生成顺序）
     * @param sizes              尺码列表
     * @param retailPriceAmount  统一零售价（分）
     * @param enabled            是否启用
     */
    @Transactional
    public MatrixResult createMatrix(long productId, List<String> colors, List<String> sizes,
                                     long retailPriceAmount, boolean enabled) {
        requireProduct(productId);
        requireNonNegative(retailPriceAmount, "零售价");

        List<String> colorList = distinctNonBlank(colors, "颜色");
        List<String> sizeList = distinctNonBlank(sizes, "尺码");

        List<Sku> created = new ArrayList<>();
        int skipped = 0;
        for (String color : colorList) {
            for (String size : sizeList) {
                if (skus.findByVariant(productId, color, size).isPresent()) {
                    skipped++;
                    continue;
                }
                long id = skus.insert(productId, color, size, null, retailPriceAmount, enabled);
                created.add(skus.findById(id).orElseThrow());
            }
        }
        return new MatrixResult(List.copyOf(created), skipped);
    }

    @Transactional(readOnly = true)
    public Sku require(long id) {
        return skus.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.MDM_SKU_NOT_FOUND,
                        "SKU 不存在：id=%d".formatted(id)));
    }

    @Transactional(readOnly = true)
    public List<Sku> findByProduct(long productId) {
        requireProduct(productId);
        return skus.findByProduct(productId);
    }

    @Transactional(readOnly = true)
    public PageResult<Sku> page(Long productId, String color, String skuSize, int page, int pageSize) {
        return skus.page(productId, color, skuSize, page, pageSize);
    }

    /**
     * 按 id 批量取 SKU 展示名（{@code 商品名 · 颜色-尺码}，{@code skuId -> 名称}）。
     *
     * <p>单据列表跨模块展示 SKU 时的取名出口：一次查询拿齐，
     * 调用方不需要再为每个 SKU 反查商品（那会把 products 也 N+1 一遍）。
     */
    @Transactional(readOnly = true)
    public Map<Long, String> findDisplayNamesByIds(Collection<Long> ids) {
        return skus.findDisplayNamesByIds(ids);
    }

    @Transactional
    public Sku update(long id, String barcode, long retailPriceAmount, boolean enabled) {
        require(id);
        requireNonNegative(retailPriceAmount, "零售价");
        boolean ok = skus.update(id, trimToNull(barcode), retailPriceAmount, enabled);
        if (!ok) {
            throw new BusinessException(ErrorCode.MDM_SKU_NOT_FOUND,
                    "SKU 不存在：id=%d".formatted(id));
        }
        return require(id);
    }

    /**
     * 停用 SKU（软删）。
     *
     * <p>停用的 SKU 不允许再出现在新单据的明细里（由 PUR/SAL 的建单服务校验），
     * 但历史单据与库存余额仍完整保留。
     */
    @Transactional
    public void disable(long id) {
        require(id);
        if (!skus.disable(id)) {
            throw new BusinessException(ErrorCode.MDM_SKU_NOT_FOUND,
                    "SKU 不存在：id=%d".formatted(id));
        }
    }

    // ─────────────────────────── 校验辅助 ───────────────────────────

    private void requireProduct(long productId) {
        if (products.findById(productId).isEmpty()) {
            throw new BusinessException(ErrorCode.SYS_NOT_FOUND,
                    "商品不存在：productId=%d".formatted(productId));
        }
    }

    /**
     * 去空白、去重复，并在发现重复时<b>报错</b>而不是静默去重。
     */
    private static List<String> distinctNonBlank(List<String> values, String fieldName) {
        if (values == null || values.isEmpty()) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "%s列表不能为空".formatted(fieldName));
        }
        List<String> trimmed = values.stream()
                .map(v -> v == null ? "" : v.trim())
                .toList();
        if (trimmed.stream().anyMatch(String::isEmpty)) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "%s列表不允许包含空值".formatted(fieldName));
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String v : trimmed) {
            if (!seen.add(v)) {
                throw new BusinessException(ErrorCode.MDM_SKU_DUPLICATED,
                        "%s列表存在重复项：'%s' —— SKU 矩阵不允许自冲突".formatted(fieldName, v));
            }
        }
        return List.copyOf(seen);
    }

    private static long requireNonNegative(long value, String fieldName) {
        if (value < 0) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "%s不能为负：%d".formatted(fieldName, value));
        }
        return value;
    }

    private static String requireText(String value, String fieldName) {
        return ProductApplicationService.requireText(value, fieldName);
    }

    private static String trimToNull(String value) {
        return ProductApplicationService.trimToNull(value);
    }
}
