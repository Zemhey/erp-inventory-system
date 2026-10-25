package com.erp.fin.api;

import com.erp.fin.application.FinInvoiceApplicationService;
import com.erp.fin.domain.Invoice;
import com.erp.fin.domain.InvoiceStatus;
import com.erp.fin.domain.InvoiceType;
import com.erp.iam.security.RequirePermission;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.web.ApiResponses;
import com.erp.shared.web.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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

/** 发票台账接口（FR-V3-11，契约 tag：fin）。type = OUTPUT 销项 / INPUT 进项（页面两个 Tab）。 */
@RestController
@RequestMapping("/api/v1/fin/invoices")
public class FinInvoiceController {

    public record InvoiceRequest(@NotBlank String invoiceNo, @NotBlank String type,
                                 Long partnerId, @NotBlank String partnerName,
                                 @NotNull BigDecimal amount, BigDecimal taxAmount,
                                 LocalDate invoiceDate, String sourceType, Long sourceId,
                                 String remark) {
    }

    private final FinInvoiceApplicationService invoices;

    public FinInvoiceController(FinInvoiceApplicationService invoices) {
        this.invoices = invoices;
    }

    @GetMapping
    @RequirePermission("fin:read")
    public ResponseEntity<Map<String, Object>> page(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageResult<Invoice> found = invoices.page(parseType(type), parseStatus(status), keyword,
                from, to, page, size);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Invoice inv : found.items()) {
            items.add(toMap(inv));
        }
        return ResponseEntity.ok(ApiResponses.page(
                new PageResult<>(items, found.total(), found.page(), found.size())));
    }

    @GetMapping("/{id}")
    @RequirePermission("fin:read")
    public ResponseEntity<Map<String, Object>> get(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponses.ok(toMap(invoices.require(id))));
    }

    @PostMapping
    @RequirePermission("fin:invoice:write")
    public ResponseEntity<Map<String, Object>> create(
            @RequestHeader("X-Request-Id") @NotBlank String requestId,
            @Valid @RequestBody InvoiceRequest req) {
        Invoice created = invoices.create(toCommand(req));
        return ResponseEntity.ok(ApiResponses.ok(toMap(created)));
    }

    @PutMapping("/{id}")
    @RequirePermission("fin:invoice:write")
    public ResponseEntity<Map<String, Object>> update(
            @RequestHeader("X-Request-Id") @NotBlank String requestId,
            @PathVariable long id,
            @Valid @RequestBody InvoiceRequest req) {
        return ResponseEntity.ok(ApiResponses.ok(toMap(invoices.update(id, toCommand(req)))));
    }

    /** 作废：释放该单据的已开额度（超额校验只统计 NORMAL）。 */
    @PostMapping("/{id}/void")
    @RequirePermission("fin:invoice:write")
    public ResponseEntity<Map<String, Object>> voidInvoice(
            @RequestHeader("X-Request-Id") @NotBlank String requestId,
            @PathVariable long id) {
        return ResponseEntity.ok(ApiResponses.ok(toMap(invoices.voidInvoice(id))));
    }

    @DeleteMapping("/{id}")
    @RequirePermission("fin:invoice:write")
    public ResponseEntity<Map<String, Object>> delete(
            @RequestHeader("X-Request-Id") @NotBlank String requestId,
            @PathVariable long id) {
        invoices.delete(id);
        return ResponseEntity.ok(ApiResponses.ok(Map.of("deleted", id)));
    }

    private static FinInvoiceApplicationService.InvoiceCommand toCommand(InvoiceRequest req) {
        return new FinInvoiceApplicationService.InvoiceCommand(req.invoiceNo(), req.type(),
                req.partnerId(), req.partnerName(), req.amount(), req.taxAmount(),
                req.invoiceDate(), req.sourceType(), req.sourceId(), req.remark());
    }

    private static Map<String, Object> toMap(Invoice inv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", inv.id());
        m.put("invoiceNo", inv.invoiceNo());
        m.put("type", inv.type().name());
        m.put("typeLabel", inv.type().label());
        m.put("partnerId", inv.partnerId());
        m.put("partnerName", inv.partnerName());
        m.put("amount", inv.amount());
        m.put("taxAmount", inv.taxAmount());
        m.put("totalAmount", inv.totalAmount());
        m.put("invoiceDate", inv.invoiceDate() == null ? null : inv.invoiceDate().toString());
        m.put("sourceType", inv.sourceType());
        m.put("sourceId", inv.sourceId());
        m.put("status", inv.status().name());
        m.put("statusLabel", inv.status().label());
        m.put("remark", inv.remark());
        return m;
    }

    private static InvoiceType parseType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return InvoiceType.parse(raw);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "type 取值非法：%s（允许 OUTPUT|INPUT，或省略）".formatted(raw));
        }
    }

    private static InvoiceStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return InvoiceStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "status 取值非法：%s（允许 NORMAL|VOID，或省略）".formatted(raw));
        }
    }
}
