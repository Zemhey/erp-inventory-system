package com.erp.sal.domain;

import com.erp.shared.document.DocumentStatus;
import java.time.LocalDate;
import java.util.List;

/**
 * 报价单（FR-SAL-05）。
 *
 * <p>报价单不是出货单：它不占用库存、不进入应收。只有审核通过后转化，
 * 才调用出货建单（此时才预占、才做信用校验）。
 */
public record SalesQuote(long id, String quoteNo, DocumentStatus status, long customerId,
                         LocalDate bizDate, LocalDate validUntil, long totalAmount, int totalQty,
                         String remark, String convertRequestId, Long convertedOrderId, int version,
                         List<SalesQuoteLine> lines) {

    public SalesQuote {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    public boolean converted() {
        return status == DocumentStatus.COMPLETED && convertedOrderId != null;
    }

    public boolean expired(LocalDate today) {
        return validUntil != null && validUntil.isBefore(today);
    }
}
