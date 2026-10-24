package com.erp.mdm.domain;

/**
 * 客户 / 会员（FR-MDM-03）。
 *
 * <p>{@code discountRate} 是<b>整数万分比</b>（10000 = 不打折，9000 = 9 折）。
 * 全系统金额禁止浮点（ADR-0005），折扣率同理：用浮点 0.9 会在
 * {@code 129 元 × 0.9} 这类运算上产生 116.09999999999999 分这类尾巴。
 *
 * <p>M1 只建字段与校验，真正参与售价计算在 M2 促销（计划书 §7 决策 3）。
 */
public record Customer(long id, String code, String name, String phone,
                       int discountRate, String level, boolean enabled,
                       long creditLimit, int creditDays) {

    /** 兼容 M1/M2 的构造：存量客户默认不设信用额度、账期 0 天。 */
    public Customer(long id, String code, String name, String phone,
                    int discountRate, String level, boolean enabled) {
        this(id, code, name, phone, discountRate, level, enabled, 0L, 0);
    }

    /** 0 = 不设限（避免存量客户/测试被信用校验一刀切拦死）。 */
    public boolean creditControlled() {
        return creditLimit > 0;
    }
}
