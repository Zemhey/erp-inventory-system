package com.erp.fin.api;

import com.erp.fin.application.FinExpenseApplicationService;
import com.erp.fin.domain.Expense;
import com.erp.fin.domain.ExpenseLine;
import com.erp.fin.domain.ExpenseStatus;
import com.erp.iam.security.RequirePermission;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.web.ApiResponses;
import com.erp.shared.web.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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

/** 费用报销接口（FR-V3-10，契约 tag：fin）。状态机：DRAFT → SUBMITTED → APPROVED → PAID。 */
@RestController
@RequestMapping("/api/v1/fin/expenses")
public class FinExpenseController {

    public record LineRequest(@NotBlank String expenseItem, @NotNull BigDecimal amount,
                              String invoiceNo, String remark) {
    }

    public record ExpenseRequest(@NotBlank String expenseType, Long departmentId,
                                 String departmentName, LocalDate applyDate, String remark,
                                 @NotEmpty @Valid List<LineRequest> lines) {
    }

    public record RejectRequest(String reason) {
    }

    private final FinExpenseApplicationService expenses;

    public FinExpenseController(FinExpenseApplicationService expenses) {
        this.expenses = expenses;
    }

    @GetMapping
    @RequirePermission("fin:read")
    public ResponseEntity<Map<String, Object>> page(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageResult<Expense> found = expenses.page(parseStatus(status), keyword, page, size);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Expense e : found.items()) {
            items.add(toListMap(e));
        }
        return ResponseEntity.ok(ApiResponses.page(
                new PageResult<>(items, found.total(), found.page(), found.size())));
    }

    @GetMapping("/{id}")
    @RequirePermission("fin:read")
    public ResponseEntity<Map<String, Object>> get(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponses.ok(toMap(expenses.require(id))));
    }

    @PostMapping
    @RequirePermission("fin:expense:write")
    public ResponseEntity<Map<String, Object>> create(
            @RequestHeader("X-Request-Id") @NotBlank String requestId,
            @Valid @RequestBody ExpenseRequest req) {
        return ResponseEntity.ok(ApiResponses.ok(toMap(expenses.create(toCommand(req)))));
    }

    @PutMapping("/{id}")
    @RequirePermission("fin:expense:write")
    public ResponseEntity<Map<String, Object>> update(
            @RequestHeader("X-Request-Id") @NotBlank String requestId,
            @PathVariable long id,
            @Valid @RequestBody ExpenseRequest req) {
        return ResponseEntity.ok(ApiResponses.ok(toMap(expenses.update(id, toCommand(req)))));
    }

    @DeleteMapping("/{id}")
    @RequirePermission("fin:expense:write")
    public ResponseEntity<Map<String, Object>> delete(
            @RequestHeader("X-Request-Id") @NotBlank String requestId,
            @PathVariable long id) {
        expenses.delete(id);
        return ResponseEntity.ok(ApiResponses.ok(Map.of("deleted", id)));
    }

    @PostMapping("/{id}/submit")
    @RequirePermission("fin:expense:write")
    public ResponseEntity<Map<String, Object>> submit(
            @RequestHeader("X-Request-Id") @NotBlank String requestId,
            @PathVariable long id) {
        return ResponseEntity.ok(ApiResponses.ok(toMap(expenses.submit(id))));
    }

    /** 审批要单独权限码：申请人（fin:expense:write）不该能自己批自己的单（职责分离）。 */
    @PostMapping("/{id}/approve")
    @RequirePermission("fin:expense:approve")
    public ResponseEntity<Map<String, Object>> approve(
            @RequestHeader("X-Request-Id") @NotBlank String requestId,
            @PathVariable long id) {
        return ResponseEntity.ok(ApiResponses.ok(toMap(expenses.approve(id))));
    }

    @PostMapping("/{id}/reject")
    @RequirePermission("fin:expense:approve")
    public ResponseEntity<Map<String, Object>> reject(
            @RequestHeader("X-Request-Id") @NotBlank String requestId,
            @PathVariable long id,
            @RequestBody(required = false) RejectRequest req) {
        return ResponseEntity.ok(ApiResponses.ok(toMap(expenses.reject(id,
                req == null ? null : req.reason()))));
    }

    /** 付款：置 PAID 并自动生成凭证（借 6602 / 贷 1002）+ 银行日记账。 */
    @PostMapping("/{id}/pay")
    @RequirePermission("fin:expense:approve")
    public ResponseEntity<Map<String, Object>> pay(
            @RequestHeader("X-Request-Id") @NotBlank String requestId,
            @PathVariable long id) {
        return ResponseEntity.ok(ApiResponses.ok(toMap(expenses.pay(id))));
    }

    private static FinExpenseApplicationService.ExpenseCommand toCommand(ExpenseRequest req) {
        List<FinExpenseApplicationService.LineInput> lines = new ArrayList<>();
        if (req.lines() != null) {
            for (LineRequest l : req.lines()) {
                lines.add(new FinExpenseApplicationService.LineInput(
                        l.expenseItem(), l.amount(), l.invoiceNo(), l.remark()));
            }
        }
        return new FinExpenseApplicationService.ExpenseCommand(req.expenseType(),
                req.departmentId(), req.departmentName(), req.applyDate(), req.remark(), lines);
    }

    private static Map<String, Object> toListMap(Expense e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.id());
        m.put("expenseNo", e.expenseNo());
        m.put("applicantId", e.applicantId());
        m.put("applicantName", e.applicantName());
        m.put("departmentId", e.departmentId());
        m.put("departmentName", e.departmentName());
        m.put("expenseType", e.expenseType());
        m.put("amount", e.amount());
        m.put("status", e.status().name());
        m.put("statusLabel", e.status().label());
        m.put("applyDate", e.applyDate() == null ? null : e.applyDate().toString());
        m.put("rejectReason", e.rejectReason());
        m.put("remark", e.remark());
        return m;
    }

    private static Map<String, Object> toMap(Expense e) {
        Map<String, Object> m = toListMap(e);
        m.put("paidAt", e.paidAt() == null ? null : e.paidAt().toString());
        List<Map<String, Object>> lines = new ArrayList<>();
        for (ExpenseLine l : e.lines()) {
            Map<String, Object> lm = new LinkedHashMap<>();
            lm.put("id", l.id());
            lm.put("lineNo", l.lineNo());
            lm.put("expenseItem", l.expenseItem());
            lm.put("amount", l.amount());
            lm.put("invoiceNo", l.invoiceNo());
            lm.put("remark", l.remark());
            lines.add(lm);
        }
        m.put("lines", lines);
        return m;
    }

    private static ExpenseStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return ExpenseStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "status 取值非法：%s（允许 DRAFT|SUBMITTED|APPROVED|PAID，或省略）"
                            .formatted(raw));
        }
    }
}
