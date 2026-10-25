package com.erp.fin.domain;

import java.math.BigDecimal;

/** 会计科目（FR-V3-06）。金额是"元"口径的 BigDecimal（简报 §5：numeric(18,2)，禁 double）。 */
public record FinAccount(
        long id,
        String code,
        String name,
        Long parentId,
        AccountCategory category,
        AccountDirection direction,
        boolean leaf,
        BigDecimal initBalance,
        boolean enabled,
        String remark) {
}
