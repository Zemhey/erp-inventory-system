package com.erp.sal.api;

import com.erp.iam.security.RequirePermission;
import com.erp.sal.application.ShipmentApplicationService;
import com.erp.sal.domain.Shipment;
import com.erp.sal.domain.ShipmentLine;
import com.erp.sal.domain.ShipmentPlan;
import com.erp.sal.domain.ShipmentStatus;
import com.erp.shared.web.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 拣货/发货接口（FR-SAL-06）。 */
@RestController
@RequestMapping("/api/v1")
public class ShipmentController {

    private final ShipmentApplicationService shipments;

    public ShipmentController(ShipmentApplicationService shipments) {
        this.shipments = shipments;
    }

    public record ScanRequest(
            @NotNull Long shipmentLineId,
            @NotNull @Min(1) Integer qty,
            String barcode) {
    }

    /** 为订单生成发货计划（幂等）；单仓足够时一张发货单，跨仓时按仓拆单。 */
    @PostMapping("/sales-orders/{id}/shipments")
    @RequirePermission("sal:pick")
    public ResponseEntity<Map<String, Object>> createPlan(
            @PathVariable long id,
            @RequestHeader("X-Request-Id") @NotBlank String requestId) {
        return ResponseEntity.ok(ApiResponses.ok(toMap(shipments.createPlan(id, requestId))));
    }

    @GetMapping("/shipments")
    @RequirePermission("sal:read")
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false) ShipmentStatus status,
            @RequestParam(required = false) Long salesOrderId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponses.page(shipments.page(status, salesOrderId, page, size)));
    }

    @GetMapping("/shipments/{id}")
    @RequirePermission("sal:read")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponses.ok(toMap(shipments.require(id))));
    }

    @PostMapping("/shipments/{id}/scan")
    @RequirePermission("sal:pick")
    public ResponseEntity<Map<String, Object>> scan(@PathVariable long id,
                                                    @Valid @RequestBody ScanRequest req) {
        return ResponseEntity.ok(ApiResponses.ok(toMap(shipments.scan(
                id, req.shipmentLineId(), req.qty(), req.barcode()))));
    }

    @PostMapping("/shipments/{id}/confirm")
    @RequirePermission("sal:ship")
    public ResponseEntity<Map<String, Object>> confirm(
            @PathVariable long id,
            @RequestHeader("X-Request-Id") @NotBlank String requestId) {
        return ResponseEntity.ok(ApiResponses.ok(toMap(shipments.confirm(id, requestId))));
    }

    private static Map<String, Object> toMap(ShipmentPlan plan) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("planId", plan.id());
        m.put("planNo", plan.planNo());
        m.put("salesOrderId", plan.salesOrderId());
        List<Map<String, Object>> list = new ArrayList<>();
        for (Shipment shipment : plan.shipments()) {
            list.add(toMap(shipment));
        }
        m.put("shipments", list);
        return m;
    }

    static Map<String, Object> toMap(Shipment s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.id());
        m.put("shipmentNo", s.shipmentNo());
        m.put("planId", s.planId());
        m.put("salesOrderId", s.salesOrderId());
        m.put("warehouseId", s.warehouseId());
        m.put("status", s.status().name());
        List<Map<String, Object>> lines = new ArrayList<>();
        for (ShipmentLine l : s.lines()) {
            Map<String, Object> lm = new LinkedHashMap<>();
            lm.put("id", l.id());
            lm.put("shipmentLineId", l.id());
            lm.put("lineNo", l.lineNo());
            lm.put("salesOrderLineId", l.salesOrderLineId());
            lm.put("skuId", l.skuId());
            lm.put("reservationId", l.reservationId());
            lm.put("batchNo", l.batchNo());
            lm.put("plannedQty", l.plannedQty());
            lm.put("pickedQty", l.pickedQty());
            lines.add(lm);
        }
        m.put("lines", lines);
        return m;
    }
}
