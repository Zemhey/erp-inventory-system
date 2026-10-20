package com.erp.mdm.domain;

/**
 * 商品（款式级）。不参与库存 —— 库存挂在 SKU 上（docs/02-领域模型 §1）。
 *
 * <p>{@code enabled} 而不是物理删除：商品一旦出现在历史单据里就必须永远可查，
 * 因此"下架"只能表现为停用。这条规则有测试（TC-MDM-004）。
 *
 * @param id       主键
 * @param code     商品编码，全局唯一
 * @param name     商品名称
 * @param category 品类（可为空）
 * @param season   季节（可为空）
 * @param enabled  是否启用
 */
public record Product(long id, String code, String name, String category, String season,
                      boolean enabled) {
}
