package com.erp.mdm.application;

import com.erp.mdm.domain.Sku;
import com.erp.mdm.domain.SkuFilter;
import com.erp.mdm.repository.ProductRepository;
import com.erp.mdm.repository.SkuRepository;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.idem.IdempotencyReplay;
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
                                long retailPriceAmount, boolean enabled,
                                String seasonCode, String bandCode,
                                java.time.LocalDate launchDate,
                                Integer minLevel, Integer maxLevel, String requestId) {

        public CreateCommand(long productId, String color, String size, String barcode,
                             long retailPriceAmount, boolean enabled) {
            this(productId, color, size, barcode, retailPriceAmount, enabled,
                    null, null, null, null, null, null);
        }

        public CreateCommand(long productId, String color, String size, String barcode,
                             long retailPriceAmount, boolean enabled,
                             String seasonCode, String bandCode, java.time.LocalDate launchDate) {
            this(productId, color, size, barcode, retailPriceAmount, enabled,
                    seasonCode, bandCode, launchDate, null, null, null);
        }

        /** 兼容 V2-C1 刚落地时的 10 参数调用（阈值未填 = 未设）。 */
        public CreateCommand(long productId, String color, String size, String barcode,
                             long retailPriceAmount, boolean enabled,
                             String seasonCode, String bandCode, java.time.LocalDate launchDate,
                             String requestId) {
            this(productId, color, size, barcode, retailPriceAmount, enabled,
                    seasonCode, bandCode, launchDate, null, null, requestId);
        }
    }

    /** 季节/年份/波段维度更新（FR-MDM-08）；null 表示保持原值。 */
    public record DimensionsCommand(String seasonCode, String bandCode,
                                    java.time.LocalDate launchDate) {
    }

    /**
     * 全量更新：基础字段 + 季节/年份/波段 + 安全库存阈值；维度与阈值省略（null）表示保持原值。
     *
     * <p>{@code minLevel}/{@code maxLevel} 用包装类型而非基本类型，是为了区分
     * "没传这个字段"（保持原值）和"传了 0"（显式清除阈值）——
     * 界面上调阈值时不该被要求每次都填回另一个字段。
     */
    public record UpdateCommand(String barcode, Long retailPriceAmount, Boolean enabled,
                                String seasonCode, String bandCode,
                                java.time.LocalDate launchDate,
                                Integer minLevel, Integer maxLevel) {
    }

    /**
     * 矩阵生成结果。
     *
     * @param created 本次新建的 SKU（按生成顺序）
     * @param skipped 已存在而跳过的组合数
     */
    public record MatrixResult(List<Sku> created, int skipped) {
    }

    /**
     * 建单条 SKU（FR-MDM-01 + V2-C1 幂等）。
     *
     * <p>回查-插入-并发兜底三步与 {@link WarehouseApplicationService#create} 同构。
     * 幂等键与 {@code uq_sku_variant} 是两层互不相干的保护：前者管"同一个请求到了几次"，
     * 后者管"同一个商品下颜色×尺码是否重复"。
     */
    @Transactional
    public Sku create(CreateCommand cmd) {
        var replay = skus.findByRequestId(cmd.requestId());
        if (replay.isPresent()) {
            IdempotencyReplay.markCurrentRequest();
            return replay.get();
        }
        requireProduct(cmd.productId());
        int minLevel = requireLevel(cmd.minLevel(), "安全库存下限", 0);
        int maxLevel = requireLevel(cmd.maxLevel(), "补货上限", 0);
        requireLevelOrder(minLevel, maxLevel);
        long id = skus.insert(cmd.productId(),
                requireText(cmd.color(), "颜色"),
                requireText(cmd.size(), "尺码"),
                trimToNull(cmd.barcode()),
                requireNonNegative(cmd.retailPriceAmount(), "零售价"),
                cmd.enabled(),
                trimToNull(cmd.seasonCode()),
                trimToNull(cmd.bandCode()),
                cmd.launchDate(),
                minLevel, maxLevel,
                cmd.requestId());
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

    /** 季节/年份/波段筛选（FR-MDM-08）。 */
    @Transactional(readOnly = true)
    public PageResult<Sku> page(SkuFilter filter, int page, int pageSize) {
        return skus.page(filter, page, pageSize);
    }

    /** 更新 SKU 的季节/波段/上市日期；null 表示保持原值。 */
    @Transactional
    public Sku updateDimensions(long id, DimensionsCommand cmd) {
        Sku current = require(id);
        boolean ok = skus.updateDimensions(id,
                cmd.seasonCode() == null ? current.seasonCode() : trimToNull(cmd.seasonCode()),
                cmd.bandCode() == null ? current.bandCode() : trimToNull(cmd.bandCode()),
                cmd.launchDate() == null ? current.launchDate() : cmd.launchDate());
        if (!ok) {
            throw new BusinessException(ErrorCode.MDM_SKU_NOT_FOUND,
                    "SKU 不存在：id=%d".formatted(id));
        }
        return require(id);
    }

    /** 基础字段 + 维度 + 阈值一次性更新，避免控制器里两次事务造成半更新。 */
    @Transactional
    public Sku update(long id, UpdateCommand cmd) {
        Sku current = require(id);
        long retail = cmd.retailPriceAmount() == null
                ? current.retailPriceAmount() : cmd.retailPriceAmount();
        requireNonNegative(retail, "零售价");
        // 阈值：省略 = 保持原值；显式 0 = 清除阈值（预警/补货建议不再覆盖该 SKU）。
        int minLevel = requireLevel(cmd.minLevel(), "安全库存下限", current.minLevel());
        int maxLevel = requireLevel(cmd.maxLevel(), "补货上限", current.maxLevel());
        requireLevelOrder(minLevel, maxLevel);
        boolean ok = skus.update(id, trimToNull(cmd.barcode()), retail,
                cmd.enabled() == null || cmd.enabled());
        if (!ok) {
            throw new BusinessException(ErrorCode.MDM_SKU_NOT_FOUND,
                    "SKU 不存在：id=%d".formatted(id));
        }
        skus.updateDimensions(id,
                cmd.seasonCode() == null ? current.seasonCode() : trimToNull(cmd.seasonCode()),
                cmd.bandCode() == null ? current.bandCode() : trimToNull(cmd.bandCode()),
                cmd.launchDate() == null ? current.launchDate() : cmd.launchDate());
        skus.updateLevels(id, minLevel, maxLevel);
        return require(id);
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

    /**
     * 阈值归一：{@code null}（请求没带这个字段）取 {@code fallback}，带值则必须非负。
     *
     * <p>阈值是<b>件数</b>（整数），单位与 {@code retailPriceAmount} 的"分"无关。
     */
    private static int requireLevel(Integer value, String fieldName, int fallback) {
        if (value == null) {
            return fallback;
        }
        if (value < 0) {
            throw new BusinessException(ErrorCode.MDM_SKU_LEVEL_INVALID,
                    "%s不能为负：%d（单位是件，0 表示未设）".formatted(fieldName, value));
        }
        return value;
    }

    /**
     * 阈值顺序校验，口径与库里的 CHECK 约束 {@code ck_sku_level_order} <b>完全一致</b>：
     * {@code max_level = 0 OR max_level >= min_level}。
     *
     * <p>为什么不是无条件 {@code min <= max}：{@code max = 0} 在项目里是"未设上限"的哨兵值
     * （{@code InventoryInsightRepository.replenishmentCandidates} 就靠
     * {@code AND s.max_level > 0} 过滤），若在这里禁止 {@code min=50, max=0}，
     * 用户将无法只设预警下限。前端与后端都按同一条规则给提示，
     * 免得界面放行、库里翻 CHECK 变成 500。
     */
    private static void requireLevelOrder(int minLevel, int maxLevel) {
        if (maxLevel != 0 && maxLevel < minLevel) {
            throw new BusinessException(ErrorCode.MDM_SKU_LEVEL_INVALID,
                    "补货上限不能小于安全库存下限：minLevel=%d、maxLevel=%d（上限留空/0 表示只预警）"
                            .formatted(minLevel, maxLevel));
        }
    }

    private static String requireText(String value, String fieldName) {
        return ProductApplicationService.requireText(value, fieldName);
    }

    private static String trimToNull(String value) {
        return ProductApplicationService.trimToNull(value);
    }
}
