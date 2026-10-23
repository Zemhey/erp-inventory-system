package com.erp.inv.domain;

/**
 * 调拨单生命周期（FR-INV-07，两段式）。
 *
 * <p>刻意不复用七态单据状态机：调拨没有草稿/审核环节，
 * 建单即发运（{@link #IN_TRANSIT}），到货即完结（{@link #COMPLETED}）。
 * "在途"是库存事实，而不是审批状态。
 */
public enum TransferStatus {
    IN_TRANSIT,
    COMPLETED
}
