package com.erp.mdm.domain;

/**
 * 客户收货地址（一对多）。
 *
 * <p>不提供物理删除：停用只置 {@code enabled=false}，历史出货单仍通过
 * {@code customer_id} 指向客户，不会因地址变更而断链。
 *
 * <p>{@code addressKey} 是客户内的业务键（如 HOME / OFFICE），创建后不可改；
 * 同一客户最多一个 {@code isDefault=true} 的地址，由数据库部分唯一索引兜底。
 */
public record CustomerAddress(long id, long customerId, String addressKey,
                              String recipientName, String phone,
                              String province, String city, String district,
                              String detailAddress, boolean isDefault, boolean enabled) {
}
