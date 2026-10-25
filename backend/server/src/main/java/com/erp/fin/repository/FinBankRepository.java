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
            rs.getBigDecimal("balance"), rs.getBoolean("is_default"), rs.getBoolean("enabled"),
            rs.getString("remark"));

    private static final RowMapper<CashJournalEntry> JOURNAL_MAPPER = (rs, i) -> new CashJournalEntry(
            rs.getLong("id"), rs.getLong("account_id"),
            rs.getObject("biz_date", LocalDate.class),
            AccountDirection.valueOf(rs.getString("direction")),
            rs.getBigDecimal("amount"), rs.getBigDecimal("balance"),
            rs.getString("source_type"),
            rs.getObject("source_id") == null ? null : rs.getLong("source_id"),
            rs.getString("summary"));

    private static final String ACCOUNT_COLUMNS =
            "id, code, name, bank_name, account_no, currency, balance, is_default, enabled, remark";

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
                              String currency, BigDecimal balance, boolean isDefault,
                              boolean enabled, String remark) {
        try {
            return TenantJdbc.insertReturningId(jdbc, """
                    INSERT INTO fin_bank_account (code, name, bank_name, account_no, currency,
                                                  balance, is_default, enabled, remark)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    RETURNING id
                    """,
                    code, name, bankName, accountNo, currency == null ? "CNY" : currency,
                    balance == null ? BigDecimal.ZERO : balance, isDefault, enabled, remark);
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

    public boolean updateAccount(long id, String name, String bankName, String accountNo,
                                 BigDecimal balance, boolean isDefault, boolean enabled,
                                 String remark) {
        return TenantJdbc.update(jdbc, """
                UPDATE fin_bank_account
                   SET name = ?, bank_name = ?, account_no = ?, balance = ?,
                       is_default = ?, enabled = ?, remark = ?, updated_at = now()
                 WHERE id = ?
                """, name, bankName, accountNo, balance, isDefault, enabled, remark, id) == 1;
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

    /** 上一笔的余额（按记账顺序，非业务日期 —— 日记账余额必须逐笔连续）。 */
    public BigDecimal lastBalance(long accountId) {
        return jdbc.query("""
                SELECT balance FROM fin_cash_journal
                 WHERE account_id = ? ORDER BY biz_date DESC, id DESC LIMIT 1
                """, (rs, i) -> rs.getBigDecimal("balance"), accountId)
                .stream().findFirst().orElse(BigDecimal.ZERO);
    }

    public long insertJournal(long accountId, LocalDate bizDate, AccountDirection direction,
                              BigDecimal amount, BigDecimal balance,
                              String sourceType, Long sourceId, String summary) {
        return TenantJdbc.insertReturningId(jdbc, """
                INSERT INTO fin_cash_journal (account_id, biz_date, direction, amount, balance,
                                              source_type, source_id, summary)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """,
                accountId, bizDate, direction.name(), amount, balance,
                sourceType, sourceId, summary);
    }

    public List<CashJournalEntry> journal(Long accountId, LocalDate from, LocalDate to, int limit) {
        List<Object> args = new ArrayList<>();
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
