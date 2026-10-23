package com.erp.inv.domain;

/** 库存预警类型（FR-INV-12）。 */
public enum AlertType {
    /** 可用量低于 skus.min_level。 */
    SAFETY,
    /** 超过 N 天没有出库。 */
    SLOW_MOVING,
    /** 效期在 N 天内（含已过期）。 */
    EXPIRING
}
