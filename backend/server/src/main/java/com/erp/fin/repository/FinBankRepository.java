package com.erp.fin.repository;

import com.erp.fin.domain.AccountDirection;
import com.erp.fin.domain.BankAccount;
import com.erp.fin.domain.CashJournalEntry;
import com.erp.shared.db.DbErrors;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.tenant.TenantJdbc;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** 银行账户 + 现金日记账仓储（FR-V3-09）。 */
@Repository
public class FinBankRepository {

    private static final RowMapper<BankAccount> ACCOUNT_MAPPER = (rs, i) -> new BankAccount(
            rs.getLong("id"), rs.getString("code"), rs.getString("name"),
            rs.getString("bank_name"), rs.getString("account_no"), rs.getString("currency"),
            rs.getBigDecimal("init_balance"), rs.getBigDecimal("balance"),
            rs.getBoolean("is_default"), rs.getBoolean("enabled"), rs.getString("remark"));

    private static final RowMapper<CashJournalEntry> JOURNAL_MAPPER = (rs, i) -> new CashJournalEntry(
            rs.getLong("id"), rs.getLong("account_id"),
            rs.getObject("biz_date", LocalDate.class),
            AccountDirection.valueOf(rs.getString("direction")),
            rs.getBigDecimal("amount"), rs.getBigDecimal("balance"),
            rs.getString("source_type"),
            rs.getObject("source_id") == null ? null : rs.getLong("source_id"),
            rs.getString("summary"));

    private static final String ACCOUNT_COLUMNS =
            "id, code, name, bank_name, account_no, currency, init_balance, balance,"
                    + " is_default, enabled, remark";

    private final JdbcTemplate jdbc;

    public FinBankRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<BankAccount> listAccounts() {
        return jdbc.query("SELECT " + ACCOUNT_COLUMNS + " FROM fin_bank_account"
                + " ORDER BY is_default DESC, id", ACCOUNT_MAPPER);
    }

    public Optional<BankAccount> findAccount(long id) {
        return jdbc.query("SELECT " + ACCOUNT_COLUMNS + " FROM fin_bank_account WHERE id = ?",
                ACCOUNT_MAPPER, id).stream().findFirst();
    }

    /**
     * 默认入账账户：is_default 优先，其次最早建的启用账户。
     *
     * <p>收付款自动日记账必须有一个确定的落点；"用户还没建账户"不是拒绝业务确认的理由，
     * 因此本方法返回空时调用方跳过日记账（凭证照出），报告里写明这一口径。
     */
    public Optional<BankAccount> defaultAccount() {
        return jdbc.query("SELECT " + ACCOUNT_COLUMNS + " FROM fin_bank_account"
                        + " WHERE enabled ORDER BY is_default DESC, id LIMIT 1",
                ACCOUNT_MAPPER).stream().findFirst();
    }

    public long insertAccount(String code, String name, String bankName, String accountNo,
                              String currency, BigDecimal initBalance, boolean isDefault,
                              boolean enabled, String remark) {
        BigDecimal init = initBalance == null ? BigDecimal.ZERO : initBalance;
        try {
            // balance 与 init_balance 建户时相同：此刻还没有流水，现余额就等于期初。
            // 之后 balance 只由 recomputeChainAndBalance 派生，任何外部写入都不认。
            return TenantJdbc.insertReturningId(jdbc, """
                    INSERT INTO fin_bank_account (code, name, bank_name, account_no, currency,
                                                  balance, init_balance, is_default, enabled, remark)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    RETURNING id
                    """,
                    code, name, bankName, accountNo, currency == null ? "CNY" : currency,
                    init, init, isDefault, enabled, remark);
        } catch (DataIntegrityViolationException ex) {
            BusinessException dup = DbErrors.translate(ex, "uq_fin_bank_account_code",
                    ErrorCode.MDM_CODE_DUPLICATED, "银行账户编码已存在：code=" + code);
            if (dup != null) {
                throw dup;
            }
            // 部分唯一索引 uq_fin_bank_account_default：同租户第二个默认账户
            BusinessException defaultDup = DbErrors.translate(ex, "uq_fin_bank_account_default",
                    ErrorCode.SYS_VALIDATION_ERROR, "本租户已存在默认账户（至多一个 is_default）");
            if (defaultDup != null) {
                throw defaultDup;
            }
            throw ex;
        }
    }

    /**
     * 改账户资料与期初。传 null 的期初表示"不动期初"。
     *
     * <p>★ 这里<b>不接受</b>直接设置现余额：现余额是 期初 + 流水净额 的派生值，
     * 老写法把它当普通字段覆盖，于是"编辑一次账户"就能把已发生的流水抹平（V24）。
     */
    public boolean updateAccount(long id, String name, String bankName, String accountNo,
                                 BigDecimal initBalance, boolean isDefault, boolean enabled,
                                 String remark) {
        boolean updated = TenantJdbc.update(jdbc, """
                UPDATE fin_bank_account
                   SET name = ?, bank_name = ?, account_no = ?,
                       init_balance = COALESCE(?::numeric, init_balance),
                       is_default = ?, enabled = ?, remark = ?, updated_at = now()
                 WHERE id = ?
                """, name, bankName, accountNo, initBalance, isDefault, enabled, remark, id) == 1;
        recomputeChainAndBalance(id);
        return updated;
    }

    /** 只翻"默认账户"标志，不碰金额（老写法要绕 updateAccount 的八个参数）。 */
    public boolean setAccountDefault(long id, boolean isDefault) {
        return TenantJdbc.update(jdbc, """
                UPDATE fin_bank_account SET is_default = ?, updated_at = now() WHERE id = ?
                """, isDefault, id) == 1;
    }

    public boolean deleteAccount(long id) {
        Long refs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fin_cash_journal WHERE account_id = ?", Long.class, id);
        if (refs != null && refs > 0) {
            throw new BusinessException(ErrorCode.FIN_ACCOUNT_IN_USE,
                    "银行账户已有 %d 条日记账流水，不能删除（请停用而不是删除）".formatted(refs));
        }
        return TenantJdbc.update(jdbc, "DELETE FROM fin_bank_account WHERE id = ?", id) == 1;
    }

    /**
     * 追加一笔日记账，然后<b>整链重算</b>该账户的逐笔余额与账户现余额。
     *
     * <p>★ 为什么不在插入时算：老写法用"按 biz_date 取最新那一笔的 balance ± 本笔金额"
     * 同时填新行和账户余额。补录一笔过去日期的分录时，链尾根本不是那一笔，
     * 于是新行余额、账户余额、以及它后面所有行的余额<b>三处一起错位</b>
     * （实测：演示库招行户 500,000 期初在第一笔收款后就被刷成 1,240.92）。
     * 现在插入只管落行，余额一律由 {@link #recomputeChainAndBalance} 派生。
     */
    public long appendJournal(long accountId, LocalDate bizDate, AccountDirection direction,
                              BigDecimal amount, String sourceType, Long sourceId, String summary) {
        long id = TenantJdbc.insertReturningId(jdbc, """
                INSERT INTO fin_cash_journal (account_id, biz_date, direction, amount, balance,
                                              source_type, source_id, summary)
                VALUES (?, ?, ?, ?, 0, ?, ?, ?)
                RETURNING id
                """,
                accountId, bizDate, direction.name(), amount, sourceType, sourceId, summary);
        recomputeChainAndBalance(accountId);
        return id;
    }

    /**
     * 期初 + 累计净额，按 (biz_date, id) 逐笔重算；再把账户现余额刷成链尾值。
     *
     * <p>单账户的流水是百量级，全量重算比"猜插入点"更便宜也更稳。
     */
    public void recomputeChainAndBalance(long accountId) {
        jdbc.update("""
                UPDATE fin_cash_journal j
                   SET balance = a.init_balance + c.signed_run
                  FROM (SELECT id,
                               account_id,
                               SUM(CASE WHEN direction = 'DEBIT' THEN amount ELSE -amount END)
                                   OVER (PARTITION BY account_id ORDER BY biz_date, id
                                         ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW)
                                   AS signed_run
                          FROM fin_cash_journal
                         WHERE account_id = ?) c
                  JOIN fin_bank_account a ON a.id = c.account_id
                 WHERE j.id = c.id
                """, accountId);
        jdbc.update("""
                UPDATE fin_bank_account
                   SET balance = init_balance + COALESCE(
                           (SELECT SUM(CASE WHEN direction = 'DEBIT' THEN amount ELSE -amount END)
                              FROM fin_cash_journal
                             WHERE fin_cash_journal.account_id = fin_bank_account.id), 0),
                       updated_at = now()
                 WHERE id = ?
                """, accountId);
    }

    /** 刚写入的那一行（余额是整链重算后的值，回读比推算可靠）。 */
    public Optional<CashJournalEntry> findJournal(long id) {
        return jdbc.query("SELECT id, account_id, biz_date, direction, amount, balance,"
                        + " source_type, source_id, summary FROM fin_cash_journal WHERE id = ?",
                JOURNAL_MAPPER, id).stream().findFirst();
    }

    public List<CashJournalEntry> journal(Long accountId, LocalDate from, LocalDate to, int limit) {        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        if (accountId != null) {
            where.append(" AND account_id = ?");
            args.add(accountId);
        }
        if (from != null) {
            where.append(" AND biz_date >= ?");
            args.add(from);
        }
        if (to != null) {
            where.append(" AND biz_date <= ?");
            args.add(to);
        }
        args.add(limit);
        return jdbc.query("SELECT id, account_id, biz_date, direction, amount, balance,"
                        + " source_type, source_id, summary FROM fin_cash_journal"
                        + where + " ORDER BY biz_date DESC, id DESC LIMIT ?",
                JOURNAL_MAPPER, args.toArray());
    }

    /** 资金余额卡：所有启用账户余额合计。 */
    public BigDecimal totalBalance() {
        BigDecimal sum = jdbc.query("SELECT COALESCE(SUM(balance), 0) FROM fin_bank_account"
                        + " WHERE enabled",
                (rs, i) -> rs.getBigDecimal(1)).stream().findFirst().orElse(BigDecimal.ZERO);
        return sum;
    }

    /** 近 N 个自然月的日记账收支（现金流图，按业务日期归月）。direction: DEBIT 收 CREDIT 支。 */
    public List<java.util.Map<String, Object>> monthlyCashFlow(String fromPeriod, String toPeriod) {
        return jdbc.query("""
                SELECT to_char(biz_date, 'YYYY-MM') AS period,
                       COALESCE(SUM(debit), 0) AS inflow, COALESCE(SUM(credit), 0) AS outflow
                  FROM (SELECT biz_date,
                               CASE WHEN direction = 'DEBIT' THEN amount ELSE 0 END AS debit,
                               CASE WHEN direction = 'CREDIT' THEN amount ELSE 0 END AS credit
                          FROM fin_cash_journal) j
                 WHERE to_char(biz_date, 'YYYY-MM') >= ? AND to_char(biz_date, 'YYYY-MM') <= ?
                 GROUP BY 1 ORDER BY 1
                """, (rs, i) -> {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("period", rs.getString("period"));
            m.put("inflow", rs.getBigDecimal("inflow"));
            m.put("outflow", rs.getBigDecimal("outflow"));
            return m;
        }, fromPeriod, toPeriod);
    }
}
