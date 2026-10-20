package com.erp.sal.api;

import com.erp.sal.application.SalesOrderApplicationService;
import com.erp.sal.domain.SalesOrder;
import com.erp.sal.domain.SalesOrderLine;
import com.erp.shared.document.DocumentStatus;
import com.erp.iam.security.RequirePermission;
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

/**
 * 出货单接口（契约：{@code /sales-orders}）。
 */
@RestController
@RequestMapping("/api/v1/sales-orders")
public class SalesOrderController {

    private final SalesOrderApplicationService salesOrders;

    public SalesOrderController(SalesOrderApplicationService salesOrders) {
        this.salesOrders = salesOrders;
    }

    public record LineRequest(
            @NotNull Long skuId,
            @NotNull @Min(1) Integer qty,
            @NotNull @Min(0) Long unitPriceAmount,
            @Min(1) @Max(10000) Integer discountRate) {
    }

    public record CreateRequest(
            @NotNull Long customerId,
            @NotNull Long warehouseId,
            LocalDate bizDate,
            @Size(max = 500) String remark,
            @NotEmpty @Valid List<LineRequest> lines) {
    }

    @GetMapping
    @RequirePermission("sal:read")
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false) DocumentStatus status,
            @RequestParam(required = false) Long customerId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponses.page(salesOrders.page(status, customerId, page, size)));
    }

    @GetMapping("/{id}")
    @RequirePermission("sal:read")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponses.ok(toMap(salesOrders.require(id))));
    }

    @PostMapping
    @RequirePermission("sal:create")
    public ResponseEntity<Map<String, Object>> create(
            @RequestHeader("X-Request-Id") @NotBlank String requestId,
            @Valid @RequestBody CreateRequest req) {
        List<SalesOrderApplicationService.CreateLineCommand> lines = req.lines().stream()
                .map(l -> new SalesOrderApplicationService.CreateLineCommand(
                        l.skuId(), l.qty(), l.unitPriceAmount(), l.discountRate()))
                .toList();
        SalesOrder o = salesOrders.create(new SalesOrderApplicationService.CreateCommand(
                req.customerId(), req.warehouseId(), req.bizDate(), req.remark(), lines, requestId));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponses.ok(toMap(o)));
    }

    @PostMapping("/{id}/submit")
    @RequirePermission("sal:submit")
    public ResponseEntity<Map<String, Object>> submit(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponses.ok(toMap(salesOrders.submit(id))));
    }

    @PostMapping("/{id}/approve")
    @RequirePermission("sal:approve")
    public ResponseEntity<Map<String, Object>> approve(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponses.ok(toMap(salesOrders.approve(id))));
    }

    @PostMapping("/{id}/reject")
    @RequirePermission("sal:reject")
    public ResponseEntity<Map<String, Object>> reject(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponses.ok(toMap(salesOrders.reject(id))));
    }

    @PostMapping("/{id}/void")
    @RequirePermission("sal:void")
    public ResponseEntity<Map<String, Object>> voidOrder(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponses.ok(toMap(salesOrders.voidOrder(id))));
    }

    @PostMapping("/{id}/ship")
    @RequirePermission("sal:ship")
    public ResponseEntity<Map<String, Object>> ship(
            @PathVariable long id,
            @RequestHeader("X-Request-Id") @NotBlank String requestId) {
        return ResponseEntity.ok(ApiResponses.ok(toMap(salesOrders.ship(id, requestId))));
    }

    private static Map<String, Object> toMap(SalesOrder o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", o.id());
        m.put("orderNo", o.orderNo());
        m.put("status", o.status().name());
        m.put("customerId", o.customerId());
        m.put("warehouseId", o.warehouseId());
        m.put("bizDate", o.bizDate() == null ? null : o.bizDate().toString());
        m.put("totalAmount", o.totalAmount());
        m.put("totalQty", o.totalQty());
        m.put("period", o.period());
        m.put("remark", o.remark());
        List<Map<String, Object>> lines = new ArrayList<>();
        for (SalesOrderLine l : o.lines()) {
            Map<String, Object> lm = new LinkedHashMap<>();
            lm.put("id", l.id());
            lm.put("lineNo", l.lineNo());
            lm.put("skuId", l.skuId());
            lm.put("qty", l.qty());
            lm.put("shippedQty", l.shippedQty());
            lm.put("openQty", l.openQty());
            lm.put("unitPriceAmount", l.unitPriceAmount());
            lm.put("discountRate", l.discountRate());
            lm.put("amount", l.amount());
            lm.put("reservationIds", l.reservationIds());
            lines.add(lm);
        }
        m.put("lines", lines);
        return m;
    }
}
