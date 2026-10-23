package com.erp.pur.api;

import com.erp.iam.security.RequirePermission;
import com.erp.pur.application.SupplierPayableService;
import com.erp.pur.domain.PayableAllocation;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 付款与核销接口（FR-PUR-08）。
 *
 * <p>一张付款单可包含多条核销明细（同一供应商），
 * 服务端强制 {@code Σ明细 == 付款金额}，并对每张应付做余额条件更新。
 */
@RestController
@RequestMapping("/api/v1/supplier-payments")
public class SupplierPaymentController {

    private final SupplierPayableService payables;

    public SupplierPaymentController(SupplierPayableService payables) {
        this.payables = payables;
    }

    public record AllocationRequest(
            @NotNull Long payableId,
            @NotNull @Min(1) Long amount) {
    }

    public record PaymentRequest(
            @NotNull Long supplierId,
            @NotNull @Min(1) Long amount,
            LocalDate bizDate,
            @Size(max = 500) String remark,
            @NotEmpty @Valid List<AllocationRequest> allocations) {
    }

    @PostMapping
    @RequirePermission("pur:pay")
    public ResponseEntity<Map<String, Object>> pay(
            @RequestHeader("X-Request-Id") @NotBlank String requestId,
            @Valid @RequestBody PaymentRequest req) {
        List<SupplierPayableService.AllocationCommand> allocations = req.allocations().stream()
                .map(a -> new SupplierPayableService.AllocationCommand(a.payableId(), a.amount()))
                .toList();
        SupplierPayableService.PaymentResult result = payables.pay(
                new SupplierPayableService.PaymentCommand(req.supplierId(), req.amount(),
                        req.bizDate(), req.remark(), allocations, requestId));

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("paymentNo", result.paymentNo());
        m.put("paymentAmount", result.paymentAmount());
        List<Map<String, Object>> details = new ArrayList<>();
        for (PayableAllocation a : result.allocations()) {
            Map<String, Object> am = new LinkedHashMap<>();
            am.put("payableId", a.payableId());
            am.put("amount", a.amount());
            details.add(am);
        }
        m.put("allocations", details);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponses.ok(m));
    }
}
