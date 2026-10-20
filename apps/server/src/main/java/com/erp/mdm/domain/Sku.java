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
 */
public record Sku(long id, long productId, String color, String size, String barcode,
                  long retailPriceAmount, boolean enabled) {
}
