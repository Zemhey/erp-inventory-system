package com.erp.pur.api;

import com.erp.pur.application.PurchaseOrderApplicationService;
import com.erp.pur.domain.PurchaseOrder;
import com.erp.pur.domain.PurchaseOrderLine;
import com.erp.pur.domain.PurchaseReceipt;
import com.erp.pur.domain.PurchaseReceiptLine;
import com.erp.shared.document.DocumentStatus;
import com.erp.iam.security.RequirePermission;
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
 * 进货单接口（契约：{@code /purchase-orders}）。
 *
 * <p>状态变更走子路径动作（{@code /submit}、{@code /approve}、{@code /receive}），
 * 与 docs/05 的契约约定一致 —— 而不是 {@code PUT /purchase-orders/{id} {status:...}}。
 * 这样每一条动作都能独立附带幂等键与权限。
 */
@RestController
@RequestMapping("/api/v1/purchase-orders")
public class PurchaseOrderController {

    private final PurchaseOrderApplicationService purchaseOrders;

    public PurchaseOrderController(PurchaseOrderApplicationService purchaseOrders) {
        this.purchaseOrders = purchaseOrders;
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

    public record ReceiveLineRequest(
            @NotNull Long lineId,
            @NotNull @Min(1) Integer qty,
            @Size(max = 64) String batchNo,
            LocalDate productionDate,
            LocalDate expiryDate,
            @Min(0) Integer qcQty,
            @Size(max = 500) String qcNote) {
    }

    public record ReceiveBody(@NotEmpty @Valid List<ReceiveLineRequest> lines) {
    }

    @GetMapping
    @RequirePermission("pur:read")
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false) DocumentStatus status,
            @RequestParam(required = false) Long supplierId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponses.page(purchaseOrders.page(status, supplierId, page, size)));
    }

    @GetMapping("/{id}")
    @RequirePermission("pur:read")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponses.ok(toMap(purchaseOrders.require(id))));
    }

    @PostMapping
    @RequirePermission("pur:create")
    public ResponseEntity<Map<String, Object>> create(
            @RequestHeader("X-Request-Id") @NotBlank String requestId,
            @Valid @RequestBody CreateRequest req) {
        List<PurchaseOrderApplicationService.CreateLineCommand> lines = req.lines().stream()
                .map(l -> new PurchaseOrderApplicationService.CreateLineCommand(
                        l.skuId(), l.qty(), l.unitPriceAmount()))
                .toList();
        PurchaseOrder o = purchaseOrders.create(new PurchaseOrderApplicationService.CreateCommand(
                req.supplierId(), req.warehouseId(), req.bizDate(), req.remark(), lines, requestId));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponses.ok(toMap(o)));
    }

    @PostMapping("/{id}/submit")
    @RequirePermission("pur:submit")
    public ResponseEntity<Map<String, Object>> submit(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponses.ok(toMap(purchaseOrders.submit(id))));
    }

    @PostMapping("/{id}/approve")
    @RequirePermission("pur:approve")
    public ResponseEntity<Map<String, Object>> approve(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponses.ok(toMap(purchaseOrders.approve(id))));
    }

    @PostMapping("/{id}/reject")
    @RequirePermission("pur:reject")
    public ResponseEntity<Map<String, Object>> reject(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponses.ok(toMap(purchaseOrders.reject(id))));
    }

    @PostMapping("/{id}/void")
    @RequirePermission("pur:void")
    public ResponseEntity<Map<String, Object>> voidOrder(
            @PathVariable long id,
            @RequestHeader("X-Request-Id") @NotBlank String requestId) {
        return ResponseEntity.ok(ApiResponses.ok(toMap(purchaseOrders.voidOrder(id, requestId))));
    }

    @PostMapping("/{id}/receive")
    @RequirePermission("pur:receive")
    public ResponseEntity<Map<String, Object>> receive(
            @PathVariable long id,
            @RequestHeader("X-Request-Id") @NotBlank String requestId,
            @Valid @RequestBody ReceiveBody body) {
        List<PurchaseOrderApplicationService.ReceiveLineCommand> lines = body.lines().stream()
                .map(l -> new PurchaseOrderApplicationService.ReceiveLineCommand(
                        l.lineId(), l.qty(), l.batchNo(), l.productionDate(), l.expiryDate(),
                        l.qcQty(), l.qcNote()))
                .toList();
        PurchaseOrder o = purchaseOrders.receive(id,
                new PurchaseOrderApplicationService.ReceiveCommand(lines, requestId));
        return ResponseEntity.ok(ApiResponses.ok(toMap(o)));
    }

    /** 到货/质检记录（FR-PUR-05）：前端"到货质检"页展开单据时使用。 */
    @GetMapping("/{id}/receipts")
    @RequirePermission("pur:read")
    public ResponseEntity<Map<String, Object>> receipts(@PathVariable long id) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (PurchaseReceipt r : purchaseOrders.receipts(id)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.id());
            m.put("receiptNo", r.receiptNo());
            m.put("purchaseOrderId", r.purchaseOrderId());
            m.put("supplierId", r.supplierId());
            m.put("warehouseId", r.warehouseId());
            m.put("bizDate", r.bizDate() == null ? null : r.bizDate().toString());
            m.put("totalQty", r.totalQty());
            m.put("acceptedQty", r.acceptedQty());
            m.put("rejectedQty", r.rejectedQty());
            m.put("acceptedAmount", r.acceptedAmount());
            List<Map<String, Object>> lines = new ArrayList<>();
            for (PurchaseReceiptLine l : r.lines()) {
                Map<String, Object> lm = new LinkedHashMap<>();
                lm.put("id", l.id());
                lm.put("lineNo", l.lineNo());
                lm.put("purchaseOrderLineId", l.purchaseOrderLineId());
                lm.put("skuId", l.skuId());
                lm.put("batchNo", l.batchNo());
                lm.put("receivedQty", l.receivedQty());
                lm.put("qcQty", l.qcQty());
                lm.put("rejectedQty", l.rejectedQty());
                lm.put("qcStatus", l.qcStatus().name());
                lm.put("qcNote", l.qcNote());
                lm.put("unitPriceAmount", l.unitPriceAmount());
                lm.put("acceptedAmount", l.acceptedAmount());
                lines.add(lm);
            }
            m.put("lines", lines);
            items.add(m);
        }
        return ResponseEntity.ok(ApiResponses.ok(items));
    }

    private static Map<String, Object> toMap(PurchaseOrder o) {
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
