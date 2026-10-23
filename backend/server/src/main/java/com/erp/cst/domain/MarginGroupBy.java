package com.erp.cst.domain;

import java.util.Locale;

/** 毛利分析维度（契约 {@code groupBy}）。 */
public enum MarginGroupBy {
    PRODUCT,
    CUSTOMER,
    PERIOD;

    public static MarginGroupBy parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return PRODUCT;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "groupBy 取值非法：%s（允许 product | customer | period）".formatted(raw));
        }
    }
}
