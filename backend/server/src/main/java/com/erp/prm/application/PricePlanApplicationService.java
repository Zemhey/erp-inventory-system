package com.erp.prm.application;

import com.erp.prm.domain.PricePlan;
import com.erp.prm.domain.PricePlan.PlanType;
import com.erp.prm.repository.PricePlanRepository;
import com.erp.prm.repository.PricePlanRepository.PriceHit;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.time.ClockProvider;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 价格方案应用服务（FR-V3-20）：CRUD + 取价试算。
 *
 * <p><b>取价优先级（最终实现口径，答辩规则）</b>：
 * <pre>
 *   客户协议价 AGREEMENT &gt; 客户专属价 CUSTOMER &gt; 客户等级价 CUSTOMER_LEVEL
 *   &gt; 商品标准售价 skus.retail_price_amount
 * </pre>
 * 同类型内：priority 降序 → 方案 id 升序（保证多次调用结果一致，取价纯查询、幂等）。
 * 明细行 {@code min_qty > 请求量} 时跳过该行（数量阶梯）；
 * 生效价 = price_amount × discount_bp ÷ 10000，四舍五入到分，全程 long。
 *
 * <p>本期未把取价接入销售报价/出货的算价链路（合并风险最小化），
 * {@code GET /api/v1/prm/price} 是独立试算端点 —— 报告中已如实标注。
 */
@Service
public class PricePlanApplicationService {

    /** 取价结果里的来源标识。 */
    public static final String SOURCE_AGREEMENT = "AGREEMENT_PRICE";
    public static final String SOURCE_CUSTOMER = "CUSTOMER_PRICE";
    public static final String SOURCE_LEVEL = "CUSTOMER_LEVEL_PRICE";
    public static final String SOURCE_STANDARD = "STANDARD_PRICE";

    public record PriceResult(String source, String planCode, Long planId, long priceAmount,
                              int discountBp, long qty) {
    }

    private final PricePlanRepository plans;
    private final ClockProvider clock;

    public PricePlanApplicationService(PricePlanRepository plans, ClockProvider clock) {
        this.plans = plans;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<PricePlan> page(String keyword) {
        return plans.page(keyword);
    }

    @Transactional(readOnly = true)
    public PricePlan require(long id) {
        return plans.findById(id).orElseThrow(() -> new BusinessException(
                ErrorCode.PRM_PLAN_NOT_FOUND, "价格方案不存在：id=" + id));
    }

    @Transactional
    public PricePlan create(PricePlan draft) {
        if (plans.existsCode(draft.code())) {
            throw new BusinessException(ErrorCode.PRM_PLAN_CODE_DUPLICATED,
                    "价格方案编码已存在：" + draft.code());
        }
        validate(draft);
        long id = plans.insert(draft);
        return require(id);
    }

    @Transactional
    public PricePlan update(long id, PricePlan draft) {
        require(id);
        validate(draft);
        plans.replace(id, draft);
        return require(id);
    }

    @Transactional
    public PricePlan disable(long id) {
        require(id);
        plans.setEnabled(id, false);
        return require(id);
    }

    /**
     * 取价试算（纯查询）。
     *
     * @param day 生效判断日；由调用方（controller 可选参数）传入，缺省用 ClockProvider 今天。
     */
    @Transactional(readOnly = true)
    public PriceResult resolvePrice(long skuId, Long customerId, long qty, LocalDate day) {
        LocalDate today = day != null ? day : clock.today();
        String level = customerId == null ? null
                : plans.findCustomerLevel(customerId).orElse(null);

        List<PriceHit> hits = new ArrayList<>(plans.findPricedCandidates(skuId, today));
        hits.removeIf(h -> h.minQty() > qty);
        hits.removeIf(h -> switch (h.planType()) {
            // 协议价/专属价必须绑定到本客户
            case AGREEMENT, CUSTOMER -> h.customerId() == null || !h.customerId().equals(customerId);
            // 等级价必须匹配客户等级；无等级客户不命中
            case CUSTOMER_LEVEL -> level == null || h.customerLevel() == null
                    || !h.customerLevel().equals(level);
        });
        hits.sort(Comparator
                .comparingInt((PriceHit h) -> h.planType().rank())
                .thenComparing(h -> h.priority(), Comparator.reverseOrder())
                .thenComparingLong(PriceHit::planId));

        if (!hits.isEmpty()) {
            PriceHit hit = hits.get(0);
            return new PriceResult(sourceOf(hit.planType()), hit.planCode(), hit.planId(),
                    hit.effectivePrice(), hit.discountBp(), qty);
        }
        long standard = plans.findStandardRetail(skuId).orElseThrow(() ->
                new BusinessException(ErrorCode.SYS_NOT_FOUND,
                        "SKU 不存在或已停用，且没有任何可用价格方案：skuId=" + skuId));
        return new PriceResult(SOURCE_STANDARD, null, null, standard, 10000, qty);
    }

    private static String sourceOf(PlanType type) {
        return switch (type) {
            case AGREEMENT -> SOURCE_AGREEMENT;
            case CUSTOMER -> SOURCE_CUSTOMER;
            case CUSTOMER_LEVEL -> SOURCE_LEVEL;
        };
    }

    private static void validate(PricePlan draft) {
        if (draft.items() == null || draft.items().isEmpty()) {
            throw new BusinessException(ErrorCode.PRM_PRICE_PLAN_INVALID, "价格方案至少要有一行明细");
        }
        if (draft.startDate() != null && draft.endDate() != null
                && draft.endDate().isBefore(draft.startDate())) {
            throw new BusinessException(ErrorCode.PRM_PRICE_PLAN_INVALID,
                    "结束日期早于开始日期：%s ~ %s".formatted(draft.startDate(), draft.endDate()));
        }
        if ((draft.planType() == PlanType.AGREEMENT || draft.planType() == PlanType.CUSTOMER)
                && draft.customerId() == null) {
            throw new BusinessException(ErrorCode.PRM_PRICE_PLAN_INVALID,
                    "协议价/客户专属价方案必须绑定客户");
        }
        if (draft.planType() == PlanType.CUSTOMER_LEVEL
                && (draft.customerLevel() == null || draft.customerLevel().isBlank())) {
            throw new BusinessException(ErrorCode.PRM_PRICE_PLAN_INVALID, "等级价方案必须填写客户等级");
        }
        for (PricePlan.Item item : draft.items()) {
            if (item.discountBp() < 0 || item.discountBp() > 10000 || item.priceAmount() < 0
                    || item.minQty() < 0) {
                throw new BusinessException(ErrorCode.PRM_PRICE_PLAN_INVALID,
                        "明细行价格/折扣/起订量非法：skuId=" + item.skuId());
            }
        }
    }
}
