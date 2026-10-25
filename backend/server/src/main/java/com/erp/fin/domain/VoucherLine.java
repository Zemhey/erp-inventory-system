package com.erp.fin.domain;

import java.math.BigDecimal;

/**
 * 凭证分录行。
 *
 * <p>{@code accountCode} 是冗余科目编码（简报 §3）：报表按编码归组，
 * 科目将来改名/停用都不会让历史凭证"看起来变了"。
 * 借贷单边非零由数据库 CHECK 兜底（ck_fin_voucher_line_side）。
 */
public record VoucherLine(
        long id,
        int lineNo,
        long accountId,
        String accountCode,
        String summary,
        BigDecimal debit,
        BigDecimal credit) {
}
