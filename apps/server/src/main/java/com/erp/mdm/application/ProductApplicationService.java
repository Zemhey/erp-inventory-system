package com.erp.mdm.application;

import com.erp.mdm.domain.Product;
import com.erp.mdm.repository.ProductRepository;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.web.PageResult;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 商品应用服务（FR-MDM-01）。
 *
 * <p>虽然看起来是纯 CRUD，但这里守着两条业务规则（计划书 §2.1 批次 A 的提醒）：
 * <ol>
 *   <li><b>编码唯一</b>：由数据库约束保证，应用层只做翻译，不做"先查后插"；</li>
 *   <li><b>停用不删</b>：{@link #disable} 只置 {@code enabled = false}。
 *       已产生 SKU 的商品不允许删除，因为 SKU 可能已被库存与历史单据引用。</li>
 * </ol>
 */
@Service
public class ProductApplicationService {

    private final ProductRepository products;

    public ProductApplicationService(ProductRepository products) {
        this.products = products;
    }

    public record CreateCommand(String code, String name, String category, String season,
                                boolean enabled) {
    }

    public record UpdateCommand(String name, String category, String season, boolean enabled) {
    }

    @Transactional
    public Product create(CreateCommand cmd) {
        String code = requireText(cmd.code(), "商品编码");
        String name = requireText(cmd.name(), "商品名称");
        long id = products.insert(code, name, trimToNull(cmd.category()),
                trimToNull(cmd.season()), cmd.enabled());
        return require(id);
    }

    @Transactional(readOnly = true)
    public Product require(long id) {
        return products.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.SYS_NOT_FOUND,
                        "商品不存在：id=%d".formatted(id)));
    }

    @Transactional(readOnly = true)
    public PageResult<Product> page(String keyword, int page, int size) {
        return products.page(keyword, page, size);
    }

    @Transactional
    public Product update(long id, UpdateCommand cmd) {
        require(id);
        String name = requireText(cmd.name(), "商品名称");
        boolean ok = products.update(id, name, trimToNull(cmd.category()),
                trimToNull(cmd.season()), cmd.enabled());
        if (!ok) {
            throw new BusinessException(ErrorCode.SYS_NOT_FOUND,
                    "商品不存在：id=%d".formatted(id));
        }
        return require(id);
    }

    /**
     * 停用商品（软删）。
     *
     * <p>刻意<b>不</b>级联停用其 SKU：SKU 是否可卖是独立的库存/运营决策，
     * 而且在售的 SKU 被商品停用悄悄带走会造成"库存还在但下不了单"的困惑。
     * 需要时由调用方显式对 SKU 调停用接口。
     */
    @Transactional
    public void disable(long id) {
        require(id);
        if (!products.disable(id)) {
            throw new BusinessException(ErrorCode.SYS_NOT_FOUND,
                    "商品不存在：id=%d".formatted(id));
        }
    }

    // ─────────────────────────── 校验辅助 ───────────────────────────

    static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "%s不能为空".formatted(fieldName));
        }
        return value.trim();
    }

    static String trimToNull(String value) {
        return Objects.isNull(value) || value.isBlank() ? null : value.trim();
    }
}
