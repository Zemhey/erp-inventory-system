package com.erp.fin.application;

import com.erp.fin.domain.AccountDirection;
import com.erp.fin.domain.BankAccount;
import com.erp.fin.domain.CashJournalEntry;
import com.erp.fin.repository.FinBankRepository;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.time.ClockProvider;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 银行账户 + 现金日记账（FR-V3-09）。 */
@Service
public class FinBankApplicationService {

    public record AccountCommand(String code, String name, String bankName, String accountNo,
                                 String currency, BigDecimal initBalance, Boolean isDefault,
                                 Boolean enabled, String remark) {
    }

    /** 手工日记账补录（银行间调拨/利息等场景；金额单位：元）。 */
    public record JournalCommand(Long accountId, LocalDate bizDate, String direction,
                                 BigDecimal amount, String summary) {
    }

    private final FinBankRepository bank;
    private final ClockProvider clock;

    public FinBankApplicationService(FinBankRepository bank, ClockProvider clock) {
        this.bank = bank;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<BankAccount> listAccounts() {
        return bank.listAccounts();
    }

    @Transactional
    public BankAccount createAccount(AccountCommand cmd) {
        if (cmd.code() == null || cmd.code().isBlank()) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR, "账户编码必填");
        }
        if (cmd.name() == null || cmd.name().isBlank()) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR, "账户名称必填");
        }
        BigDecimal init = cmd.initBalance() == null ? BigDecimal.ZERO
                : cmd.initBalance().setScale(2, java.math.RoundingMode.HALF_UP);
        if (init.signum() < 0) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR, "期初余额不能为负");
        }
        boolean isDefault = Boolean.TRUE.equals(cmd.isDefault());
        // 本租户还没有任何账户时，第一个默认成为默认账户（收付款自动日记账需要落点）
        boolean first = bank.listAccounts().isEmpty();
        long id = bank.insertAccount(cmd.code().trim(), cmd.name().trim(), trim(cmd.bankName()),
                trim(cmd.accountNo()), cmd.currency(), init, isDefault || first,
                cmd.enabled() == null || cmd.enabled(), trim(cmd.remark()));
        return bank.findAccount(id).orElseThrow();
    }

    @Transactional
    public BankAccount updateAccount(long id, AccountCommand cmd) {
        BankAccount existing = bank.findAccount(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.FIN_BANK_ACCOUNT_NOT_FOUND,
                        "银行账户不存在：id=" + id));
        BigDecimal balance = cmd.initBalance() == null ? existing.balance()
                : cmd.initBalance().setScale(2, java.math.RoundingMode.HALF_UP);
        boolean isDefault = cmd.isDefault() == null ? existing.isDefault() : cmd.isDefault();
        if (!bank.updateAccount(id,
                cmd.name() == null ? existing.name() : cmd.name().trim(),
                trim(cmd.bankName()), trim(cmd.accountNo()), balance, isDefault,
                cmd.enabled() == null ? existing.enabled() : cmd.enabled(),
                trim(cmd.remark()))) {
            throw new BusinessException(ErrorCode.FIN_BANK_ACCOUNT_NOT_FOUND,
                    "银行账户不存在：id=" + id);
        }
        return bank.findAccount(id).orElseThrow();
    }

    @Transactional
    public void deleteAccount(long id) {
        bank.findAccount(id).orElseThrow(() ->
                new BusinessException(ErrorCode.FIN_BANK_ACCOUNT_NOT_FOUND,
                        "银行账户不存在：id=" + id));
        bank.deleteAccount(id);
    }

    /** 设为默认账户：同租户其余账户取消默认（部分唯一索引只兜并发，这里先显式清）。 */
    @Transactional
    public BankAccount setDefault(long id) {
        BankAccount target = bank.findAccount(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.FIN_BANK_ACCOUNT_NOT_FOUND,
                        "银行账户不存在：id=" + id));
        for (BankAccount a : bank.listAccounts()) {
            if (a.isDefault() && a.id() != id) {
                bank.updateAccount(a.id(), a.name(), a.bankName(), a.accountNo(), a.balance(),
                        false, a.enabled(), a.remark());
            }
        }
        bank.updateAccount(target.id(), target.name(), target.bankName(), target.accountNo(),
                target.balance(), true, target.enabled(), target.remark());
        return bank.findAccount(id).orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<CashJournalEntry> journal(Long accountId, LocalDate from, LocalDate to) {
        return bank.journal(accountId, from, to, 500);
    }

    /** 手工补录日记账（余额链延续 FinVoucherApplicationService.writeJournal 同一算法）。 */
    @Transactional
    public CashJournalEntry addJournalEntry(JournalCommand cmd) {
        BankAccount account = bank.findAccount(requireAccountId(cmd))
                .orElseThrow(() -> new BusinessException(ErrorCode.FIN_BANK_ACCOUNT_NOT_FOUND,
                        "银行账户不存在"));
        AccountDirection direction;
        try {
            direction = AccountDirection.valueOf(
                    (cmd.direction() == null ? "" : cmd.direction().trim().toUpperCase(
                            java.util.Locale.ROOT)));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "direction 取值非法：DEBIT（收）/ CREDIT（支）");
        }
        if (cmd.amount() == null || cmd.amount().signum() <= 0) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR, "日记账金额必须为正");
        }
        LocalDate date = cmd.bizDate() == null ? clock.today() : cmd.bizDate();
        BigDecimal amount = cmd.amount().setScale(2, java.math.RoundingMode.HALF_UP);
        BigDecimal opening = bank.lastBalance(account.id());
        BigDecimal balance = direction == AccountDirection.DEBIT
                ? opening.add(amount) : opening.subtract(amount);
        long id = bank.insertJournal(account.id(), date, direction, amount, balance,
                "MANUAL", null, cmd.summary() == null || cmd.summary().isBlank()
                        ? "手工记账" : cmd.summary().trim());
        bank.updateAccount(account.id(), account.name(), account.bankName(), account.accountNo(),
                balance, account.isDefault(), account.enabled(), account.remark());
        return new CashJournalEntry(id, account.id(), date, direction, amount, balance,
                "MANUAL", null, cmd.summary());
    }

    private static long requireAccountId(JournalCommand cmd) {
        if (cmd.accountId() == null) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR, "必须选择银行账户");
        }
        return cmd.accountId();
    }

    @Transactional(readOnly = true)
    public Optional<BankAccount> defaultAccount() {
        return bank.defaultAccount();
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
