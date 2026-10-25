package com.erp.fin.api;

import com.erp.fin.application.FinBankApplicationService;
import com.erp.fin.domain.BankAccount;
import com.erp.fin.domain.CashJournalEntry;
import com.erp.iam.security.RequirePermission;
import com.erp.shared.web.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 银行账户 + 现金日记账接口（FR-V3-09，契约 tag：fin）。 */
@RestController
@RequestMapping("/api/v1/fin")
public class FinBankController {

    public record AccountRequest(@NotBlank String code, @NotBlank String name, String bankName,
                                 String accountNo, String currency, BigDecimal initBalance,
                                 Boolean isDefault, Boolean enabled, String remark) {
    }

    public record JournalRequest(@NotNull Long accountId, LocalDate bizDate,
                                 @NotBlank String direction, @NotNull BigDecimal amount,
                                 String summary) {
    }

    private final FinBankApplicationService bank;

    public FinBankController(FinBankApplicationService bank) {
        this.bank = bank;
    }

    @GetMapping("/bank-accounts")
    @RequirePermission("fin:read")
    public ResponseEntity<Map<String, Object>> listAccounts() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (BankAccount a : bank.listAccounts()) {
            items.add(toAccountMap(a));
        }
        return ResponseEntity.ok(ApiResponses.ok(Map.of("items", items)));
    }

    @PostMapping("/bank-accounts")
    @RequirePermission("fin:bank:write")
    public ResponseEntity<Map<String, Object>> createAccount(
            @RequestHeader("X-Request-Id") @NotBlank String requestId,
            @Valid @RequestBody AccountRequest req) {
        BankAccount created = bank.createAccount(toAccountCommand(req));
        return ResponseEntity.ok(ApiResponses.ok(toAccountMap(created)));
    }

    @PutMapping("/bank-accounts/{id}")
    @RequirePermission("fin:bank:write")
    public ResponseEntity<Map<String, Object>> updateAccount(
            @RequestHeader("X-Request-Id") @NotBlank String requestId,
            @PathVariable long id,
            @Valid @RequestBody AccountRequest req) {
        return ResponseEntity.ok(ApiResponses.ok(toAccountMap(
                bank.updateAccount(id, toAccountCommand(req)))));
    }

    @DeleteMapping("/bank-accounts/{id}")
    @RequirePermission("fin:bank:write")
    public ResponseEntity<Map<String, Object>> deleteAccount(
            @RequestHeader("X-Request-Id") @NotBlank String requestId,
            @PathVariable long id) {
        bank.deleteAccount(id);
        return ResponseEntity.ok(ApiResponses.ok(Map.of("deleted", id)));
    }

    @PostMapping("/bank-accounts/{id}/default")
    @RequirePermission("fin:bank:write")
    public ResponseEntity<Map<String, Object>> setDefault(
            @RequestHeader("X-Request-Id") @NotBlank String requestId,
            @PathVariable long id) {
        return ResponseEntity.ok(ApiResponses.ok(toAccountMap(bank.setDefault(id))));
    }

    @GetMapping("/cash-journal")
    @RequirePermission("fin:read")
    public ResponseEntity<Map<String, Object>> journal(
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (CashJournalEntry e : bank.journal(accountId, from, to)) {
            items.add(toJournalMap(e));
        }
        return ResponseEntity.ok(ApiResponses.ok(Map.of("items", items)));
    }

    @PostMapping("/cash-journal")
    @RequirePermission("fin:bank:write")
    public ResponseEntity<Map<String, Object>> addJournal(
            @RequestHeader("X-Request-Id") @NotBlank String requestId,
            @Valid @RequestBody JournalRequest req) {
        CashJournalEntry entry = bank.addJournalEntry(
                new FinBankApplicationService.JournalCommand(req.accountId(), req.bizDate(),
                        req.direction(), req.amount(), req.summary()));
        return ResponseEntity.ok(ApiResponses.ok(toJournalMap(entry)));
    }

    private static FinBankApplicationService.AccountCommand toAccountCommand(AccountRequest req) {
        return new FinBankApplicationService.AccountCommand(req.code(), req.name(),
                req.bankName(), req.accountNo(), req.currency(), req.initBalance(),
                req.isDefault(), req.enabled(), req.remark());
    }

    private static Map<String, Object> toAccountMap(BankAccount a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.id());
        m.put("code", a.code());
        m.put("name", a.name());
        m.put("bankName", a.bankName());
        m.put("accountNo", a.accountNo());
        m.put("currency", a.currency());
        m.put("balance", a.balance());
        m.put("isDefault", a.isDefault());
        m.put("enabled", a.enabled());
        m.put("remark", a.remark());
        return m;
    }

    private static Map<String, Object> toJournalMap(CashJournalEntry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.id());
        m.put("accountId", e.accountId());
        m.put("bizDate", e.bizDate() == null ? null : e.bizDate().toString());
        m.put("direction", e.direction().name());
        m.put("directionLabel", e.direction().label());
        m.put("amount", e.amount());
        m.put("balance", e.balance());
        m.put("sourceType", e.sourceType());
        m.put("sourceId", e.sourceId());
        m.put("summary", e.summary());
        return m;
    }
}
