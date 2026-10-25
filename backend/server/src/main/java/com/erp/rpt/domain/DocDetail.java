package com.erp.rpt.domain;

import java.time.LocalDate;
import java.util.List;

/** 单据详情（FR-V3-04）：按类型返回表头 + 明细行。金额单位：分。 */
public record DocDetail(
        String type,
        long id,
        String no,
        String status,
        LocalDate bizDate,
        String partyName,
        String warehouseName,
        long totalQty,
        long totalAmount,
        List<DocLine> lines) {
}
