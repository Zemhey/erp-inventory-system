package com.erp.mdm.domain;

/**
 * 仓库（FR-MDM-04）。
 *
 * <p>M1 只做基础 CRUD；"库位"（库位编码）留待 P1 的调拨/盘点再做 ——
 * 当前库存余额的维度是 {@code (SKU, 仓库, 批次)}，没有库位维度。
 * 提前引入库位会让每一条库存 SQL 都要多一个维度，而 M1 用不上。
 */
public record Warehouse(long id, String code, String name, String address, boolean enabled,
                        int priority) {

    public Warehouse(long id, String code, String name, String address, boolean enabled) {
        this(id, code, name, address, enabled, 100);
    }
}
