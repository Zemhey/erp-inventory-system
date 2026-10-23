package com.erp.inv.api;

import com.erp.iam.security.RequirePermission;
import com.erp.inv.application.InventoryApplicationService;
import com.erp.inv.application.InventoryApplicationService.ReserveCommand;
import com.erp.inv.application.InventoryApplicationService.ReserveResult;
import com.erp.inv.domain.InventoryBalance;
import com.erp.inv.domain.InventoryLedgerEntry;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.web.ApiResponses;
import com.erp.shared.web.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
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
 * 库存接口（契约：packages/api-contract/openapi.yaml → /inventory*）。
 *
 * <p>本层只做参数绑定/校验与 DTO 转换，业务规则在 {@link InventoryApplicationService}。
 */
@RestController
@RequestMapping("/api/v1/inventory")
public class InventoryController {

    private final InventoryApplicationService inventory;

    public InventoryController(InventoryApplicationService inventory) {
        this.inventory = inventory;
    }

    // ─────────────────────────── 查询 ───────────────────────────

    @GetMapping
    @RequirePermission("inv:read")
    public ResponseEntity<Map<String, Object>> query(
            @RequestParam(required = false) Long skuId,
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) String batchNo,
            @RequestParam(defaultValue = "false") boolean onlyAvailable) {
        List<Map<String, Object>> items = inventory.query(skuId, warehouseId, batchNo, onlyAvailable)
                .stream().map(InventoryController::toMap).toList();
        return ResponseEntity.ok(ok(items));
    }

    @GetMapping("/ledger")
    @RequirePermission("inv:read")
    public ResponseEntity<Map<String, Object>> ledger(
            @RequestParam(required = false) Long skuId,
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) String bizRefNo,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        var result = inventory.queryLedger(skuId, warehouseId, bizRefNo, from, to, page, size);
        return ResponseEntity.ok(ApiResponses.page(new PageResult<>(
                result.items().stream().map(InventoryController::ledgerToMap).toList(),
                result.total(), result.page(), result.size())));
    }

    // ─────────────────────────── 预占 ───────────────────────────

    public record ReserveRequest(
            @NotNull Long skuId,
            @NotNull Long warehouseId,
            String batchNo,
            @NotNull @Min(1) Integer qty) {
    }

    @PostMapping("/reserve")
    @RequirePermission("inv:write")
    public ResponseEntity<Map<String, Object>> reserve(
            @RequestHeader("X-Request-Id") @NotBlank String requestId,
            @Valid @RequestBody ReserveRequest req) {
        ReserveResult r = inventory.reserve(new ReserveCommand(
                req.skuId(), req.warehouseId(), req.batchNo(), req.qty(), requestId));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reservationId", r.reservationId());
        data.put("skuId", r.skuId());
        data.put("warehouseId", r.warehouseId());
        data.put("batchNo", r.batchNo());
        data.put("reservedQty", r.reservedQty());
        data.put("onHandQty", r.onHandQty());
        data.put("availableQty", r.availableQty());
        data.put("duplicate", r.duplicate());
        return ResponseEntity.ok(ok(data));
    }

    @PostMapping("/reservations/{id}/release")
    @RequirePermission("inv:write")
    public ResponseEntity<Map<String, Object>> release(
            @PathVariable long id,
            @RequestHeader("X-Request-Id") @NotBlank String requestId) {
        ReserveResult r = inventory.release(id);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reservationId", r.reservationId());
        data.put("reservedQty", r.reservedQty());
        data.put("onHandQty", r.onHandQty());
        data.put("availableQty", r.availableQty());
        return ResponseEntity.ok(ok(data));
    }

    // ─────────────────────────── 内部辅助（供测试与 M1 使用） ───────────────────────────

    /** 入库：M0 阶段先作为内部能力暴露，M1 由进货单 /receive 调用。 */
    @PostMapping("/receipts")
    @RequirePermission("inv:write")
    public ResponseEntity<Map<String, Object>> receive(
            @RequestHeader("X-Request-Id") @NotBlank String requestId,
            @Valid @RequestBody ReceiveRequest req) {
        inventory.receiveIn(req.skuId(), req.warehouseId(),
                req.batchNo() == null ? "" : req.batchNo(),
                req.productionDate(), req.expiryDate(), req.qty(),
                "PURCHASE_IN", req.bizRefNo(), null, req.unitCostAmount(), requestId);
        InventoryBalance b = inventory.requireBalance(req.skuId(), req.warehouseId(),
                req.batchNo() == null ? "" : req.batchNo());
        return ResponseEntity.ok(ok(toMap(b)));
    }

    public record ReceiveRequest(
            @NotNull Long skuId,
            @NotNull Long warehouseId,
            String batchNo,
            java.time.LocalDate productionDate,
            java.time.LocalDate expiryDate,
            @NotNull @Min(1) Integer qty,
            @NotBlank String bizRefNo,
            Long unitCostAmount) {
    }

    /** 出库（FEFO）：M1 由出货单 /ship 调用。 */
    @PostMapping("/shipments")
    @RequirePermission("inv:write")
    public ResponseEntity<Map<String, Object>> ship(
            @RequestHeader("X-Request-Id") @NotBlank String requestId,
            @Valid @RequestBody ShipRequest req) {
        inventory.shipOut(req.skuId(), req.warehouseId(), req.qty(),
                "SALES_OUT", req.bizRefNo(), null, requestId);
        List<Map<String, Object>> items = inventory
                .query(req.skuId(), req.warehouseId(), null, false)
                .stream().map(InventoryController::toMap).toList();
        return ResponseEntity.ok(ok(items));
    }

    public record ShipRequest(
            @NotNull Long skuId,
            @NotNull Long warehouseId,
            @NotNull @Min(1) Integer qty,
            @NotBlank String bizRefNo) {
    }

    /** 不变量 INV-2 校验：实存必须等于流水净和。 */
    @GetMapping("/consistency-check")
    @RequirePermission("inv:read")
    public ResponseEntity<Map<String, Object>> consistencyCheck() {
        var violations = inventory.checkLedgerConsistency();
        if (!violations.isEmpty()) {
            throw new BusinessException(ErrorCode.INV_BALANCE_MISMATCH,
                    "库存与流水不一致，共 %d 条漂移".formatted(violations.size()),
                    violations);
        }
        return ResponseEntity.ok(ok(Map.of("consistent", true, "checked", true)));
    }

    // ─────────────────────────── 响应包装 ───────────────────────────

    private static Map<String, Object> ok(Object data) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", "OK");
        m.put("message", "success");
        m.put("data", data);
        return m;
    }

    private static Map<String, Object> ledgerToMap(InventoryLedgerEntry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.id());
        m.put("skuId", e.skuId());
        m.put("warehouseId", e.warehouseId());
        m.put("batchNo", e.batchNo());
        m.put("changeQty", e.changeQty());
        m.put("bizType", e.bizType());
        m.put("bizRefNo", e.bizRefNo());
        m.put("unitCostAmount", e.unitCostAmount());
        m.put("occurredAt", e.occurredAt() == null ? null : e.occurredAt().toString());
        return m;
    }

    private static Map<String, Object> toMap(InventoryBalance b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("skuId", b.skuId());
        m.put("warehouseId", b.warehouseId());
        m.put("batchNo", b.batchNo());
        m.put("expiryDate", b.expiryDate() == null ? null : b.expiryDate().toString());
        m.put("onHandQty", b.onHandQty());
        m.put("reservedQty", b.reservedQty());
        m.put("frozenQty", b.frozenQty());
        m.put("availableQty", b.availableQty());
        return m;
    }
}
