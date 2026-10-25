package com.erp.fin.repository;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 账目聚合只读仓储（FR-V3-12 报表口径）。
 *
 * <p>口径（简报 §4 FR-V3-12）：
 * 科目余额 = {@code init_balance + Σ(借方) - Σ(贷方)}，按 direction 决定正负；
 * 只统计 POSTED 凭证 —— 未过账的草稿不该出现在法定报表里。
 *
 * <p>与 {@code rpt} 模块同一"只读泳道"纪律：这里没有任何写语句。
 */
@Repository
public class FinReportRepository {

    /** 一个科目在某统计范围内的发生额合计。 */
    public record Movement(long accountId, String accountCode, String accountName,
                           String category, String direction,
                           BigDecimal initBalance, BigDecimal sumDebit, BigDecimal sumCredit) {
    }

    private final JdbcTemplate jdbc;

    public FinReportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 截至某账期（含）每个科目的借/贷累计发生额 —— 资产负债表用。
     *
     * <p>以科目表为主体 LEFT JOIN 分录聚合：没有发生额的科目也要出现在报表里
     * （它的期初余额也是报表数字）。
     */
    public List<Movement> movementsUpTo(String period) {
        return jdbc.query("""
                SELECT a.id, a.code, a.name, a.category, a.direction, a.init_balance,
                       COALESCE(m.sum_debit, 0) AS sum_debit,
                       COALESCE(m.sum_credit, 0) AS sum_credit
                  FROM fin_account a
                  LEFT JOIN (SELECT l.account_id,
                                    SUM(l.debit) AS sum_debit, SUM(l.credit) AS sum_credit
                               FROM fin_voucher_line l
                               JOIN fin_voucher v ON v.id = l.voucher_id
                              WHERE v.status = 'POSTED' AND v.period <= ?
                              GROUP BY l.account_id) m
                    ON m.account_id = a.id
                 ORDER BY a.code
                """, (rs, i) -> new Movement(rs.getLong("id"), rs.getString("code"),
                rs.getString("name"), rs.getString("category"), rs.getString("direction"),
                rs.getBigDecimal("init_balance"), rs.getBigDecimal("sum_debit"),
                rs.getBigDecimal("sum_credit")), period);
    }

    /**
     * 单个账期内的发生额 —— 利润表用。
     *
     * <p>刻意不带 init_balance：本期利润只由本期发生额决定
     * （简化口径：不做期初结转，损益结转到权益的动作留给"年末结转"下期能力）。
     */
    public List<Movement> movementsInPeriod(String period) {
        return jdbc.query("""
                SELECT a.id, a.code, a.name, a.category, a.direction, a.init_balance,
                       COALESCE(m.sum_debit, 0) AS sum_debit,
                       COALESCE(m.sum_credit, 0) AS sum_credit
                  FROM fin_account a
                  JOIN (SELECT l.account_id,
                               SUM(l.debit) AS sum_debit, SUM(l.credit) AS sum_credit
                          FROM fin_voucher_line l
                          JOIN fin_voucher v ON v.id = l.voucher_id
                         WHERE v.status = 'POSTED' AND v.period = ?
                         GROUP BY l.account_id) m
                    ON m.account_id = a.id
                 ORDER BY a.code
                """, (rs, i) -> new Movement(rs.getLong("id"), rs.getString("code"),
                rs.getString("name"), rs.getString("category"), rs.getString("direction"),
                rs.getBigDecimal("init_balance"), rs.getBigDecimal("sum_debit"),
                rs.getBigDecimal("sum_credit")), period);
    }

    /** 某期间 POSTED 凭证数（总览"本月"卡片与报表说明用）。 */
    public long postedVoucherCount(String period) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fin_voucher WHERE status = 'POSTED' AND period = ?",
                Long.class, period);
        return n == null ? 0 : n;
    }

    /** 全部 POSTED 凭证数（总览"凭证总数"用）。 */
    public long allPostedVoucherCount() {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fin_voucher WHERE status = 'POSTED'", Long.class);
        return n == null ? 0 : n;
    }
}
