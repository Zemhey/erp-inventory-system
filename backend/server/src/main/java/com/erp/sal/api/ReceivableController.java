package com.erp.sal.api;

import com.erp.iam.security.RequirePermission;
import com.erp.sal.application.ReceiptService;
import com.erp.sal.application.ReceivableService;
import com.erp.sal.domain.CustomerReceipt;
import com.erp.sal.domain.CustomerReceivable;
import com.erp.shared.time.ClockProvider;
import com.erp.shared.web.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 应收/收款接口（FR-SAL-07/08）。 */
@RestController
@RequestMapping("/api/v1")
public class ReceivableController {

    private final ReceivableService receivables;
    private final ReceiptService receipts;
    private final ClockProvider clock;

    public ReceivableController(ReceivableService receivables, ReceiptService receipts,
                                ClockProvider clock) {
        this.receivables = receivables;
        this.receipts = receipts;
        this.clock = clock;
    }

    public record AllocationRequest(@NotNull Long receivableId, @NotNull @Min(1) Long amount) {
    }

    public record ReceiptCreateRequest(
            @NotNull Long customerId,
            @NotNull @Min(1) Long amount,
            LocalDate bizDate,
            @Size(max = 500) String remark,
            @NotEmpty @Valid List<AllocationRequest> allocations) {
    }

    @GetMapping("/receivables")
    @RequirePermission("sal:read")
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Boolean overdue,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponses.page(receivables.page(
                customerId, status, overdue, page, size)));
    }

    @GetMapping("/receivables/summary")
    @RequirePermission("sal:read")
    public ResponseEntity<Map<String, Object>> summary(@RequestParam long customerId) {
        var s = receivables.summary(customerId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("customerId", customerId);
        m.put("shippedAmount", s.shippedAmount());
        m.put("returnedAmount", s.returnedAmount());
        m.put("receivedAmount", s.receivedAmount());
        m.put("balance", s.balance());
        m.put("overdueAmount", s.overdueAmount());
        return ResponseEntity.ok(ApiResponses.ok(m));
    }

    @GetMapping("/receivables/{id}")
    @RequirePermission("sal:read")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponses.ok(toMap(receivables.require(id), clock.today())));
    }

    /** 收款并核销：Σ allocations 必须精确等于 amount；超额核销拒绝。 */
    @PostMapping("/customer-receipts")
    @RequirePermission("sal:receipt")
    public ResponseEntity<Map<String, Object>> createReceipt(
            @RequestHeader("X-Request-Id") @NotBlank String requestId,
            @Valid @RequestBody ReceiptCreateRequest req) {
        CustomerReceipt receipt = receipts.create(new ReceiptService.CreateCommand(
                req.customerId(), req.amount(), req.bizDate(), req.remark(),
                req.allocations().stream().map(a -> new ReceiptService.AllocationCommand(
                        a.receivableId(), a.amount())).toList(),
                requestId));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponses.ok(toMap(receipt)));
    }

    @GetMapping("/customer-receipts")
    @RequirePermission("sal:read")
    public ResponseEntity<Map<String, Object>> listReceipts(
            @RequestParam(required = false) Long customerId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponses.page(receipts.page(customerId, page, size)));
    }

    @GetMapping("/customer-receipts/{id}")
    @RequirePermission("sal:read")
    public ResponseEntity<Map<String, Object>> receiptDetail(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponses.ok(toMap(receipts.require(id))));
    }

    static Map<String, Object> toMap(CustomerReceivable r, LocalDate today) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("salesOrderId", r.salesOrderId());
        m.put("customerId", r.customerId());
        m.put("bizDate", r.bizDate() == null ? null : r.bizDate().toString());
        m.put("dueDate", r.dueDate() == null ? null : r.dueDate().toString());
        m.put("shippedAmount", r.shippedAmount());
        m.put("returnedAmount", r.returnedAmount());
        m.put("receivedAmount", r.receivedAmount());
        m.put("balance", r.balance());
        m.put("status", r.status());
        m.put("overdue", r.overdue(today));
        return m;
    }

    static Map<String, Object> toMap(CustomerReceipt r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("receiptNo", r.receiptNo());
        m.put("customerId", r.customerId());
        m.put("amount", r.amount());
        m.put("bizDate", r.bizDate() == null ? null : r.bizDate().toString());
        m.put("remark", r.remark());
        List<Map<String, Object>> allocations = new ArrayList<>();
        for (CustomerReceipt.Allocation a : r.allocations()) {
            Map<String, Object> am = new LinkedHashMap<>();
            am.put("id", a.id());
            am.put("receivableId", a.receivableId());
            am.put("amount", a.amount());
            allocations.add(am);
        }
        m.put("allocations", allocations);
        return m;
    }
}
