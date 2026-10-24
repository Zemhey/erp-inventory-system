package com.erp.inv.domain;

/**
 * 盘点单生命周期（FR-INV-08）。
 *
 * <p>DRAFT 期间可反复核对实盘数；过账（POSTED）后差异已落成调整流水，不可逆。
 */
public enum StocktakeStatus {
    DRAFT,
    POSTED
}
