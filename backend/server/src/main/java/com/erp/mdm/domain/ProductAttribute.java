package com.erp.mdm.domain;

/**
 * 商品级扩展规格属性（键值）。
 *
 * <p>刻意不并入 SKU 的颜色×尺码唯一键：属性是款级描述（面料、版型、执行标准），
 * 不产生新的 SKU 组合，也不替代季节/波段字段。
 */
public record ProductAttribute(String key, String value) {
}
