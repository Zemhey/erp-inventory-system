package com.erp.mdm.domain;

/**
 * 供应商（FR-MDM-02）。编码唯一；停用不删，历史进货单仍可追溯。
 */
public record Supplier(long id, String code, String name, String contactName,
                       String contactPhone, String address, boolean enabled) {
}
