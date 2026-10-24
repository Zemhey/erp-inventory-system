package com.erp.sal.api;

import com.erp.iam.security.RequirePermission;
import com.erp.sal.application.SalesQuoteApplicationService;
import com.erp.sal.domain.SalesOrder;
import com.erp.sal.domain.SalesQuote;
import com.erp.sal.domain.SalesQuoteLine;
import com.erp.shared.document.DocumentStatus;
import com.erp.shared.web.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
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

/** 报价单接口（FR-SAL-05，契约申请见 .plan/V2-3-契约申请.md）。 */
@RestController
@RequestMapping("/api/v1/sales-quotes")
public class SalesQuoteController {

    private final SalesQuoteApplicationService quotes;

    public SalesQuoteController(SalesQuoteApplicationService quotes) {
        this.quotes = quotes;
    }

    public record LineRequest(
            @NotNull Long skuId,
            @NotNull @Min(1) Integer qty,
            @NotNull @Min(0) Long unitPriceAmount,
            @Min(1) @Max(10000) Integer discountRate) {
    }

    public record CreateRequest(
            @NotNull Long customerId,
            LocalDate bizDate,
            LocalDate validUntil,
            @Size(max = 500) String remark,
            @NotEmpty @Valid List<LineRequest> lines) {
    }

    public record ConvertRequest(Long warehouseId, @Size(max = 500) String remark) {
    }

    @GetMapping
    @RequirePermission("sal:read")
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false) DocumentStatus status,
            @RequestParam(required = false) Long customerId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponses.page(quotes.page(status, customerId, page, size)));
    }

    @GetMapping("/{id}")
    @RequirePermission("sal:read")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponses.ok(toMap(quotes.require(id))));
    }

    @PostMapping
    @RequirePermission("sal:quote")
    public ResponseEntity<Map<String, Object>> create(
            @RequestHeader("X-Request-Id") @NotBlank String requestId,
            @Valid @RequestBody CreateRequest req) {
        SalesQuote quote = quotes.create(new SalesQuoteApplicationService.CreateCommand(
                req.customerId(), req.bizDate(), req.validUntil(), req.remark(),
                req.lines().stream().map(l -> new SalesQuoteApplicationService.CreateLineCommand(
                        l.skuId(), l.qty(), l.unitPriceAmount(), l.discountRate())).toList(),
                requestId));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponses.ok(toMap(quote)));
    }

    @PostMapping("/{id}/submit")
    @RequirePermission("sal:quote")
    public ResponseEntity<Map<String, Object>> submit(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponses.ok(toMap(quotes.submit(id))));
    }

    @PostMapping("/{id}/approve")
    @RequirePermission("sal:quote")
    public ResponseEntity<Map<String, Object>> approve(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponses.ok(toMap(quotes.approve(id))));
    }

    @PostMapping("/{id}/reject")
    @RequirePermission("sal:quote")
    public ResponseEntity<Map<String, Object>> reject(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponses.ok(toMap(quotes.reject(id))));
    }

    @PostMapping("/{id}/void")
    @RequirePermission("sal:quote")
    public ResponseEntity<Map<String, Object>> voidQuote(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponses.ok(toMap(quotes.voidOrder(id))));
    }

    /** 转化为出货单；warehouseId 省略时按仓库 priority 自动选仓/拆单。 */
    @PostMapping("/{id}/convert")
    @RequirePermission("sal:quote")
    public ResponseEntity<Map<String, Object>> convert(
            @PathVariable long id,
            @RequestHeader("X-Request-Id") @NotBlank String requestId,
            @RequestBody(required = false) ConvertRequest req) {
        ConvertRequest body = req == null ? new ConvertRequest(null, null) : req;
        SalesOrder order = quotes.convert(id, new SalesQuoteApplicationService.ConvertCommand(
                body.warehouseId(), body.remark(), requestId));
        return ResponseEntity.ok(ApiResponses.ok(SalesOrderController.toMap(order)));
    }

    static Map<String, Object> toMap(SalesQuote q) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", q.id());
        m.put("quoteNo", q.quoteNo());
        m.put("status", q.status().name());
        m.put("customerId", q.customerId());
        m.put("bizDate", q.bizDate() == null ? null : q.bizDate().toString());
        m.put("validUntil", q.validUntil() == null ? null : q.validUntil().toString());
        m.put("totalAmount", q.totalAmount());
        m.put("totalQty", q.totalQty());
        m.put("remark", q.remark());
        m.put("convertedOrderId", q.convertedOrderId());
        List<Map<String, Object>> lines = new ArrayList<>();
        for (SalesQuoteLine l : q.lines()) {
            Map<String, Object> lm = new LinkedHashMap<>();
            lm.put("id", l.id());
            lm.put("lineNo", l.lineNo());
            lm.put("skuId", l.skuId());
            lm.put("qty", l.qty());
            lm.put("unitPriceAmount", l.unitPriceAmount());
            lm.put("discountRate", l.discountRate());
            lm.put("amount", l.amount());
            lines.add(lm);
        }
        m.put("lines", lines);
        return m;
    }
}
