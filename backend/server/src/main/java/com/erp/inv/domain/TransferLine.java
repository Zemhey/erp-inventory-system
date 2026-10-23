package com.erp.inv.domain;

import java.time.LocalDate;

/** 调拨明细（FR-INV-07）：{@code inTransitQty} 是仍在途的数量，到货后归零。 */
public record TransferLine(
        long id,
        long transferId,
        int lineNo,
        long skuId,
        String batchNo,
        int qty,
        int inTransitQty,
        LocalDate productionDate,
        LocalDate expiryDate,
        Long unitCostAmount) {
}
