package com.erp.prm.domain;

import java.time.LocalDate;
import java.util.List;

/**
 * 价格方案（FR-V3-20）：纯 POJO，不依赖 Spring/JDBC（ArchUnit 规则 3）。
 *
 * <p><b>取价优先级（答辩口径，实现在 PricePlanApplicationService#resolvePrice）</b>：
 * <ol>
 *   <li>{@code AGREEMENT} 客户协议价（绑定具体客户）；</li>
 *   <li>{@code CUSTOMER} 客户专属价（同样绑定客户，语义轻一档，优先级次于协议）；</li>
 *   <li>{@code CUSTOMER_LEVEL} 客户等级价（按 customers.level 匹配）；</li>
 *   <li>以上全部未命中 → 商品标准售价 {@code skus.retail_price_amount}。</li>
 * </ol>
 * 同一类型内部按 {@code priority} 降序、再按 id 升序取第一条命中。
 */
public record PricePlan(
        Long id,
        String code,
        String name,
        PlanType planType,
        Long customerId,
        String customerLevel,
        int priority,
        LocalDate startDate,
        LocalDate endDate,
        boolean enabled,
        String remark,
        List<Item> items) {

    /** 方案类型；排序权重即声明顺序（序号越小越优先）。 */
    public enum PlanType {
        AGREEMENT, CUSTOMER, CUSTOMER_LEVEL;

        /** 类型优先级：0 = 最高。STANDARD（标准售价）视为 99。 */
        public int rank() {
            return ordinal();
        }
    }

    /**
     * 方案明细行。
     *
     * <p><b>金额口径</b>：{@code priceAmount} 单位是<b>分</b>（对齐 sal 报价/出货
     * 的 unitPriceAmount 与 skus.retailPriceAmount）；{@code discountBp} 是<b>万分比</b>
     * （10000 = 不打折，对齐 customers.discount_rate）。两者都禁止 double。
     */
    public record Item(Long id, long skuId, long priceAmount, int discountBp, int minQty) {

        /** 生效单价（分）：price × discount ÷ 10000，四舍五入到分；全程 long 运算，禁用 double。 */
        public long effectivePrice() {
            return (Math.multiplyExact(priceAmount, discountBp) + 5000) / 10000;
        }
    }

    public boolean inWindow(LocalDate day) {
        if (!enabled) {
            return false;
        }
        if (startDate != null && day.isBefore(startDate)) {
            return false;
        }
        return endDate == null || !day.isAfter(endDate);
    }
}
