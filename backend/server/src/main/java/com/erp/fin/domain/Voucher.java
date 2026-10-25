package com.erp.fin.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 会计凭证（头 + 分录，FR-V3-07/08）。 */
public record Voucher(
        long id,
        String voucherNo,
        LocalDate voucherDate,
        String period,
        VoucherSourceType sourceType,
        Long sourceId,
        String sourceNo,
        VoucherStatus status,
        BigDecimal totalDebit,
        BigDecimal totalCredit,
        Long createdBy,
        Long auditedBy,
        LocalDateTime postedAt,
        String remark,
        List<VoucherLine> lines) {

    public Voucher {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /** 装配分录后的副本（仓储"头查询 + 批量分行"两步组装用）。 */
    public Voucher withLines(List<VoucherLine> newLines) {
        return new Voucher(id, voucherNo, voucherDate, period, sourceType, sourceId, sourceNo,
                status, totalDebit, totalCredit, createdBy, auditedBy, postedAt, remark, newLines);
    }
}
