package com.erp.mdm.domain;

/**
 * 最小库存单元 = 商品 + 颜色 + 尺码（docs/02-领域模型 §1）。
 *
 * <p><b>唯一性语义</b>：同一商品下 {@code (color, size)} 不可重复（对应错误码
 * {@code MDM_SKU_DUPLICATED}）。不同商品可以拥有相同颜色尺码组合。
 *
 * @param id                主键
 * @param productId         所属商品
 * @param color             颜色
 * @param size              尺码
 * @param barcode           条码（可为空）
 * @param retailPriceAmount 零售价，<b>整数分</b>（ADR-0005）
 * @param enabled           是否启用
 * @param minLevel          安全库存下限，<b>整数件（不是分）</b>；0 = 未设阈值
 * @param maxLevel          补货目标上限，<b>整数件（不是分）</b>；0 = 未设（见下）
 *
 * <p><b>阈值的口径</b>（与库里的 CHECK 约束一致，不要自创更严的规则）：
 * <ul>
 *   <li>{@code min_level = 0} —— 该 SKU 不参与安全库存预警
 *       （{@code InventoryInsightRepository.safetyAlerts} 的
 *        {@code AND s.min_level > 0} 就是这么筛的）；</li>
 *   <li>{@code max_level = 0} —— 该 SKU 不参与补货建议
 *       （{@code replenishmentCandidates} 的 {@code AND s.max_level > 0}）；
 *       这也是约束 {@code ck_sku_level_order} 允许 {@code max < min} 的唯一情形，
 *       所以"下限 50、上限留空(0)"是<b>合法</b>的组合（只预警、不建议补货）。</li>
 * </ul>
 * 单价列（{@code retailPriceAmount}）单位是分，阈值列单位是件，两者不可混用。
 */
public record Sku(long id, long productId, String color, String size, String barcode,
                  long retailPriceAmount, boolean enabled,
                  String seasonCode, String bandCode, java.time.LocalDate launchDate,
                  int minLevel, int maxLevel) {

    /** 兼容 M1 的构造：未填写季节/波段/上市日期，阈值取库默认 0（未设）。 */
    public Sku(long id, long productId, String color, String size, String barcode,
               long retailPriceAmount, boolean enabled) {
        this(id, productId, color, size, barcode, retailPriceAmount, enabled, null, null, null, 0, 0);
    }

    /** 兼容 M3 的构造：带季节维度，阈值取库默认 0（未设）。 */
    public Sku(long id, long productId, String color, String size, String barcode,
               long retailPriceAmount, boolean enabled,
               String seasonCode, String bandCode, java.time.LocalDate launchDate) {
        this(id, productId, color, size, barcode, retailPriceAmount, enabled,
                seasonCode, bandCode, launchDate, 0, 0);
    }

    /** 年份维度由上市日期派生（FR-MDM-08 不另存冗余列）。 */
    public Integer seasonYear() {
        return launchDate == null ? null : launchDate.getYear();
    }
}
