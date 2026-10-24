package com.erp.sal.application;

import com.erp.inv.application.InventoryApplicationService;
import com.erp.mdm.repository.SkuRepository;
import com.erp.sal.domain.BundleItem;
import com.erp.sal.domain.ProductBundle;
import com.erp.sal.repository.ProductBundleRepository;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.web.PageResult;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 套装应用服务（FR-SAL-10 / INV-16）。
 *
 * <p><b>本类不叫 *ApplicationService</b> 是刻意的：ArchUnit 的“单据模块必须复用
 * 统一状态机”只适用于单据应用服务，套装不是单据、没有状态流转。
 */
@Service
public class ProductBundleService {

    private final ProductBundleRepository bundles;
    private final SkuRepository skus;
    private final InventoryApplicationService inventory;

    public ProductBundleService(ProductBundleRepository bundles, SkuRepository skus,
                                InventoryApplicationService inventory) {
        this.bundles = bundles;
        this.skus = skus;
        this.inventory = inventory;
    }

    public record ItemCommand(long componentSkuId, int ratioQty) {
    }

    public record CreateCommand(String code, String name, long priceAmount, boolean enabled,
                                List<ItemCommand> items) {
    }

    public record UpdateCommand(String name, long priceAmount, boolean enabled,
                                List<ItemCommand> items) {
    }

    public record ComponentAvailability(long componentSkuId, int ratioQty,
                                        int availableQty, int maxBundles) {
    }

    public record BundleAvailability(long bundleId, Long warehouseId, int availableQty,
                                     List<ComponentAvailability> components) {
    }

    @Transactional
    public ProductBundle create(CreateCommand cmd) {
        List<BundleItem> items = validateItems(cmd.items());
        long id = bundles.insertBundle(requireText(cmd.code(), "套装编码"),
                requireText(cmd.name(), "套装名称"), requireNonNegative(cmd.priceAmount(), "套装价格"),
                cmd.enabled());
        for (BundleItem item : items) {
            bundles.insertItem(id, item.componentSkuId(), item.ratioQty());
        }
        return require(id);
    }

    @Transactional
    public ProductBundle update(long id, UpdateCommand cmd) {
        require(id);
        List<BundleItem> items = validateItems(cmd.items());
        if (!bundles.update(id, requireText(cmd.name(), "套装名称"),
                requireNonNegative(cmd.priceAmount(), "套装价格"), cmd.enabled())) {
            throw new BusinessException(ErrorCode.MDM_BUNDLE_NOT_FOUND,
                    "套装不存在：id=%d".formatted(id));
        }
        bundles.replaceItems(id, items);
        return require(id);
    }

    @Transactional
    public ProductBundle disable(long id) {
        require(id);
        if (!bundles.disable(id)) {
            throw new BusinessException(ErrorCode.MDM_BUNDLE_NOT_FOUND,
                    "套装不存在：id=%d".formatted(id));
        }
        return require(id);
    }

    @Transactional(readOnly = true)
    public ProductBundle require(long id) {
        return bundles.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.MDM_BUNDLE_NOT_FOUND,
                        "套装不存在：id=%d".formatted(id)));
    }

    /** 建单/转化时使用：要求存在且启用。 */
    @Transactional(readOnly = true)
    public ProductBundle requireEnabled(long id) {
        ProductBundle bundle = require(id);
        if (!bundle.enabled()) {
            throw new BusinessException(ErrorCode.MDM_BUNDLE_DISABLED,
                    "套装已停用，不能建单：%s".formatted(bundle.code()));
        }
        if (bundle.items().isEmpty()) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "套装没有组件，不能建单：%s".formatted(bundle.code()));
        }
        return bundle;
    }

    @Transactional(readOnly = true)
    public PageResult<ProductBundle> page(String keyword, boolean onlyEnabled, int page, int size) {
        return bundles.page(keyword, onlyEnabled, page, size);
    }

    /**
     * 套装可用量 = min(各组件可用量 ÷ 配比)（INV-16）。
     *
     * @param warehouseId null 表示全仓汇总口径（组件可用量先跨仓求和）
     */
    @Transactional(readOnly = true)
    public BundleAvailability availability(long bundleId, Long warehouseId) {
        ProductBundle bundle = require(bundleId);
        List<ComponentAvailability> components = new ArrayList<>();
        int available = Integer.MAX_VALUE;
        for (BundleItem item : bundle.items()) {
            int componentAvailable = warehouseId == null
                    ? inventory.availableQtyAllWarehouses(item.componentSkuId())
                    : inventory.availableQty(item.componentSkuId(), warehouseId);
            int maxBundles = componentAvailable / item.ratioQty();
            components.add(new ComponentAvailability(item.componentSkuId(), item.ratioQty(),
                    componentAvailable, maxBundles));
            available = Math.min(available, maxBundles);
        }
        return new BundleAvailability(bundleId, warehouseId,
                bundle.items().isEmpty() ? 0 : available, components);
    }

    // ─────────────────────────── 校验辅助 ───────────────────────────

    private List<BundleItem> validateItems(List<ItemCommand> items) {
        if (items == null || items.isEmpty()) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "套装至少需要一个组件");
        }
        Set<Long> seen = new HashSet<>();
        List<BundleItem> result = new ArrayList<>();
        for (ItemCommand item : items) {
            if (!seen.add(item.componentSkuId())) {
                throw new BusinessException(ErrorCode.MDM_BUNDLE_DUPLICATED,
                        "同一组件 SKU 在套装中重复：skuId=%d".formatted(item.componentSkuId()));
            }
            if (item.ratioQty() <= 0) {
                throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                        "组件配比必须为正：skuId=%d, ratio=%d"
                                .formatted(item.componentSkuId(), item.ratioQty()));
            }
            var sku = skus.findById(item.componentSkuId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.MDM_SKU_NOT_FOUND,
                            "组件 SKU 不存在：id=%d".formatted(item.componentSkuId())));
            if (!sku.enabled()) {
                throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                        "组件 SKU 已停用：id=%d".formatted(item.componentSkuId()));
            }
            result.add(new BundleItem(0, item.componentSkuId(), item.ratioQty()));
        }
        return result;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "%s不能为空".formatted(field));
        }
        return value.trim();
    }

    private static long requireNonNegative(long value, String field) {
        if (value < 0) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "%s不能为负：%d".formatted(field, value));
        }
        return value;
    }
}
