package com.erp.inv.domain;

/**
 * 补货建议公式（FR-INV-13 的定义性规则，答辩口径固化在这里）。
 *
 * <pre>
 *   建议量 = max_level − 可用量 − 在途量   （低于 min_level 才建议，补到 max_level）
 *   可用量 ≥ min_level                  → 不建议
 *   建议量 ≤ 0（含在途已覆盖缺口、max_level 未设置）→ 不建议
 * </pre>
 *
 * <p>在途按<b>净额扣减</b>而不是"有在途就整条不出"：后者在真实业务里等于永久空页——
 * 已下单未到货是常态，不是"无需补货"的信号。净额口径同样防重复采购，
 * 因为在途覆盖缺口时算出来 ≤ 0，同样不出建议。
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
        if (availableQty >= minLevel) {
            return null;
        }
        // 在途量按净额扣减，而不是"有在途就整条不出建议"。
        // 后者会让任何一个正在正常采购的租户永远看不到补货建议 —— 已下单但还没到货
        // 是常态，不是"无需补货"的信号。净额口径同样防重复采购：在途已覆盖缺口时
        // 算出来 ≤ 0，照样不出建议。
        int suggested = maxLevel - availableQty - pendingPurchaseQty;
        return suggested > 0 ? suggested : null;
    }
}
