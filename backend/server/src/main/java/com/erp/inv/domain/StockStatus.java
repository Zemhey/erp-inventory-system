package com.erp.inv.domain;

/**
 * 五维结存里的"状态"维度（FR-INV-11）。
 *
 * <p>同一余额行按数量拆成三段：
 * {@link #AVAILABLE}（可卖）、{@link #RESERVED}（已预占）、{@link #FROZEN}（已冻结），
 * 三者之和恒等于实存。
 */
public enum StockStatus {
    AVAILABLE,
    RESERVED,
    FROZEN
}
