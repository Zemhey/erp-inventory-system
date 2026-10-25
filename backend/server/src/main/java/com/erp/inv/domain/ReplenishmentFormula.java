package com.erp.inv.domain;

/**
 * 补货建议公式（FR-INV-13 的定义性规则，答辩口径固化在这里）。
 *
 * <pre>
 *   建议量 = max_level − 可用量          （低于 min_level 才建议，补到 max_level）
 *   可用量 ≥ min_level                  → 不建议
 *   同仓同 SKU 有在途（已下未到 pendingPurchaseQty &gt; 0）→ 不建议（防重复采购）
 *   建议量 ≤ 0（如 max_level 未设置）    → 不建议
 * </pre>
 *
 * <p>返回 {@code null} 表示"该候选不需要出现在建议列表里"。纯函数、不落库，
 * 因此重复调用结果一致（幂等验收点）。
 */
public final class ReplenishmentFormula {

    private ReplenishmentFormula() {
    }

    /**
     * @param availableQty       当前可用量
     * @param minLevel           安全库存下限（skus.min_level，0 = 不设阈值时由上游过滤）
     * @param maxLevel           补货上限（skus.max_level）
     * @param pendingPurchaseQty 同仓同 SKU 已下单未到货数量
     * @return 建议采购量；null = 不出建议
     */
    public static Integer suggest(int availableQty, int minLevel, int maxLevel,
                                  int pendingPurchaseQty) {
        if (pendingPurchaseQty > 0) {
            return null;
        }
        if (availableQty >= minLevel) {
            return null;
        }
        int suggested = maxLevel - availableQty;
        return suggested > 0 ? suggested : null;
    }
}
