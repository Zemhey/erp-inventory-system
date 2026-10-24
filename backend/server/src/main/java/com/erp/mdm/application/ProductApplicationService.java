package com.erp.mdm.application;

import com.erp.mdm.domain.Product;
import com.erp.mdm.repository.ProductRepository;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.idem.IdempotencyReplay;
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
 *
 * <p>★ 第 1 条<b>不适用于</b>幂等键（{@link #create} 里的 {@code requestId}）：
 * 编码冲突是两个不同请求撞了同一个业务标识，只能由数据库裁决并翻译成 409；
 * 幂等键冲突是同一个请求到了两次，正确响应是"把首次的结果给我"，所以必须回查。
 * 把这两件事混为一谈会让重放变成报错，客户端重试反而放大故障。
 */
@Service
public class ProductApplicationService {

    private final ProductRepository products;

    public ProductApplicationService(ProductRepository products) {
        this.products = products;
    }

    public record CreateCommand(String code, String name, String category, String season,
                                boolean enabled, String requestId) {

        public CreateCommand(String code, String name, String category, String season,
                             boolean enabled) {
            this(code, name, category, season, enabled, null);
        }
    }

    public record UpdateCommand(String name, String category, String season, boolean enabled) {
    }

    /**
     * 建商品（FR-MDM-01 + V2-C1 幂等）。
     *
     * <p>回查-插入-并发兜底三步与 {@link WarehouseApplicationService#create} 同构。
     */
    @Transactional
    public Product create(CreateCommand cmd) {
        var replay = products.findByRequestId(cmd.requestId());
        if (replay.isPresent()) {
            IdempotencyReplay.markCurrentRequest();
            return replay.get();
        }
        String code = requireText(cmd.code(), "商品编码");
        String name = requireText(cmd.name(), "商品名称");
        long id = products.insert(code, name, trimToNull(cmd.category()),
                trimToNull(cmd.season()), cmd.enabled(), cmd.requestId());
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
