package com.erp.pur.api;

import com.erp.iam.security.RequirePermission;
import com.erp.pur.application.PurchaseRequisitionApplicationService;
import com.erp.pur.domain.PurchaseOrder;
import com.erp.pur.domain.PurchaseOrderLine;
import com.erp.pur.domain.PurchaseRequisition;
import com.erp.shared.document.DocumentStatus;
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

/**
 * 请购单接口（FR-PUR-04）。
 *
 * <p>与进货单同一套子路径动作风格；转化动作单独要求 {@code pur:approve}
 * —— 它会把请购数量变成真实采购承诺，权限不应低于审核。
 */
@RestController
@RequestMapping("/api/v1/purchase-requisitions")
public class PurchaseRequisitionController {

    private final PurchaseRequisitionApplicationService requisitions;

    public PurchaseRequisitionController(PurchaseRequisitionApplicationService requisitions) {
        this.requisitions = requisitions;
    }

    public record LineRequest(
            @NotNull Long skuId,
            @NotNull @Min(1) Integer qty,
            @NotNull @Min(0) Long unitPriceAmount) {
    }

    public record CreateRequest(
            @NotNull Long supplierId,
            @NotNull Long warehouseId,
            LocalDate bizDate,
            @Size(max = 500) String remark,
            @NotEmpty @Valid List<LineRequest> lines) {
    }

    public record ConvertLineRequest(
            @NotNull Long lineId,
            @NotNull @Min(1) Integer qty) {
    }

    public record ConvertRequest(
            LocalDate bizDate,
            @Size(max = 500) String remark,
            @NotEmpty @Valid List<ConvertLineRequest> lines) {
    }

    @GetMapping
    @RequirePermission("pur:read")
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false) DocumentStatus status,
            @RequestParam(required = false) Long supplierId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponses.page(
                requisitions.page(status, supplierId, page, size)));
    }

    @GetMapping("/{id}")
    @RequirePermission("pur:read")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponses.ok(toMap(requisitions.require(id))));
    }

    @PostMapping
    @RequirePermission("pur:create")
    public ResponseEntity<Map<String, Object>> create(
            @RequestHeader("X-Request-Id") @NotBlank String requestId,
            @Valid @RequestBody CreateRequest req) {
        List<PurchaseRequisitionApplicationService.CreateLineCommand> lines = req.lines().stream()
                .map(l -> new PurchaseRequisitionApplicationService.CreateLineCommand(
                        l.skuId(), l.qty(), l.unitPriceAmount()))
                .toList();
        PurchaseRequisition r = requisitions.create(
                new PurchaseRequisitionApplicationService.CreateCommand(
                        req.supplierId(), req.warehouseId(), req.bizDate(), req.remark(),
                        lines, requestId));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponses.ok(toMap(r)));
    }

    @PostMapping("/{id}/submit")
    @RequirePermission("pur:submit")
    public ResponseEntity<Map<String, Object>> submit(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponses.ok(toMap(requisitions.submit(id))));
    }

    @PostMapping("/{id}/approve")
    @RequirePermission("pur:approve")
    public ResponseEntity<Map<String, Object>> approve(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponses.ok(toMap(requisitions.approve(id))));
    }

    @PostMapping("/{id}/convert")
    @RequirePermission("pur:approve")
    public ResponseEntity<Map<String, Object>> convert(
            @PathVariable long id,
            @RequestHeader("X-Request-Id") @NotBlank String requestId,
            @Valid @RequestBody ConvertRequest req) {
        List<PurchaseRequisitionApplicationService.ConvertLineCommand> lines = req.lines().stream()
                .map(l -> new PurchaseRequisitionApplicationService.ConvertLineCommand(
                        l.lineId(), l.qty()))
                .toList();
        PurchaseOrder order = requisitions.convert(id,
                new PurchaseRequisitionApplicationService.ConvertCommand(
                        req.bizDate(), req.remark(), lines, requestId));
        return ResponseEntity.ok(ApiResponses.ok(orderMap(order)));
    }

    private static Map<String, Object> toMap(PurchaseRequisition r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("orderNo", r.orderNo());
        m.put("status", r.status().name());
        m.put("supplierId", r.supplierId());
        m.put("warehouseId", r.warehouseId());
        m.put("bizDate", r.bizDate() == null ? null : r.bizDate().toString());
        m.put("totalAmount", r.totalAmount());
        m.put("totalQty", r.totalQty());
        m.put("period", r.period());
        m.put("remark", r.remark());
        m.put("convertedOrderId", r.convertedOrderId());
        List<Map<String, Object>> lines = new ArrayList<>();
        for (PurchaseOrderLine l : r.lines()) {
            Map<String, Object> lm = new LinkedHashMap<>();
            lm.put("id", l.id());
            lm.put("lineNo", l.lineNo());
            lm.put("skuId", l.skuId());
            lm.put("qty", l.qty());
            lm.put("unitPriceAmount", l.unitPriceAmount());
            lm.put("amount", l.amount());
            lines.add(lm);
        }
        m.put("lines", lines);
        return m;
    }

    /** 转单响应复用进货单结构（含明细），与 {@code GET /purchase-orders/{id}} 同形。 */
    private static Map<String, Object> orderMap(PurchaseOrder o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", o.id());
        m.put("orderNo", o.orderNo());
        m.put("status", o.status().name());
        m.put("supplierId", o.supplierId());
        m.put("warehouseId", o.warehouseId());
        m.put("bizDate", o.bizDate() == null ? null : o.bizDate().toString());
        m.put("totalAmount", o.totalAmount());
        m.put("totalQty", o.totalQty());
        m.put("period", o.period());
        m.put("remark", o.remark());
        List<Map<String, Object>> lines = new ArrayList<>();
        for (PurchaseOrderLine l : o.lines()) {
            Map<String, Object> lm = new LinkedHashMap<>();
            lm.put("id", l.id());
            lm.put("lineNo", l.lineNo());
            lm.put("skuId", l.skuId());
            lm.put("qty", l.qty());
            lm.put("receivedQty", l.receivedQty());
            lm.put("qcQty", l.qcQty());
            lm.put("qcStatus", l.qcStatus().name());
            lm.put("qcNote", l.qcNote());
            lm.put("rejectedQty", l.rejectedQty());
            lm.put("openQty", l.openQty());
            lm.put("unitPriceAmount", l.unitPriceAmount());
            lm.put("amount", l.amount());
            lines.add(lm);
        }
        m.put("lines", lines);
        return m;
    }
}
