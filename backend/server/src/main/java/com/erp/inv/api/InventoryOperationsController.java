package com.erp.inv.api;

import com.erp.iam.security.RequirePermission;
import com.erp.inv.application.InventoryOperationsApplicationService;
import com.erp.inv.application.InventoryOperationsApplicationService.AdjustmentResult;
import com.erp.inv.application.InventoryOperationsApplicationService.FreezeResult;
import com.erp.inv.application.InventoryOperationsApplicationService.StocktakeLineCommand;
import com.erp.inv.application.InventoryOperationsApplicationService.StocktakeResult;
import com.erp.inv.application.InventoryOperationsApplicationService.TransferLineCommand;
import com.erp.inv.application.InventoryOperationsApplicationService.TransferResult;
import com.erp.inv.domain.FreezeStatus;
import com.erp.inv.domain.InventoryAdjustment;
import com.erp.inv.domain.InventoryFreeze;
import com.erp.inv.domain.InventoryTransfer;
import com.erp.inv.domain.Stocktake;
import com.erp.inv.domain.StocktakeLine;
import com.erp.inv.domain.StocktakeStatus;
import com.erp.inv.domain.TransferLine;
import com.erp.inv.domain.TransferStatus;
import com.erp.shared.web.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalDateTime;
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

/**
 * 库存作业接口（V2-1）：调拨 / 盘点 / 调整 / 冻结。
 *
 * <p>契约：{@code packages/api-contract/openapi.yaml} 的 {@code /inventory/transfers}、
 * {@code /inventory/stocktakes}、{@code /inventory/adjustments}、{@code /inventory/freezes}。
 * 所有 POST 都要求 {@code X-Request-Id}（不变量 INV-9）。
 */
@RestController
@RequestMapping("/api/v1/inventory")
public class InventoryOperationsController {

    private final InventoryOperationsApplicationService operations;

    public InventoryOperationsController(InventoryOperationsApplicationService operations) {
        this.operations = operations;
    }

    // ───────────────────────────── 调拨 ─────────────────────────────

    public record TransferLineRequest(
            @NotNull Long skuId,
            @Size(max = 64) String batchNo,
            @NotNull @Min(1) Integer qty,
            @Min(0) Long unitCostAmount) {
    }

    public record TransferCreateRequest(
            @NotNull Long fromWarehouseId,
            @NotNull Long toWarehouseId,
            LocalDate bizDate,
            @Size(max = 500) String remark,
            @NotEmpty @Valid List<TransferLineRequest> lines) {
    }

    @GetMapping("/transfers")
    @RequirePermission("inv:read")
    public ResponseEntity<Map<String, Object>> listTransfers(
            @RequestParam(required = false) TransferStatus status,
            @RequestParam(required = false) Long fromWarehouseId,
            @RequestParam(required = false) Long toWarehouseId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        var result = operations.pageTransfers(status, fromWarehouseId, toWarehouseId, page, size);
        List<Map<String, Object>> items = result.items().stream()
                .map(InventoryOperationsController::transferToMap).toList();
        return ResponseEntity.ok(ApiResponses.page(new com.erp.shared.web.PageResult<>(
                items, result.total(), result.page(), result.size())));
    }

    @PostMapping("/transfers")
    @RequirePermission("inv:write")
    public ResponseEntity<Map<String, Object>> createTransfer(
            @RequestHeader("X-Request-Id") @NotBlank String requestId,
            @Valid @RequestBody TransferCreateRequest req) {
        List<TransferLineCommand> lines = req.lines().stream()
                .map(l -> new TransferLineCommand(l.skuId(), l.batchNo(), l.qty(), l.unitCostAmount()))
                .toList();
        TransferResult r = operations.createTransfer(req.fromWarehouseId(), req.toWarehouseId(),
                req.bizDate(), req.remark(), lines, requestId);
        Map<String, Object> data = transferToMap(r.transfer());
        data.put("duplicate", r.duplicate());
        return ResponseEntity.ok(ApiResponses.ok(data));
    }

    @PostMapping("/transfers/{id}/receive")
    @RequirePermission("inv:write")
    public ResponseEntity<Map<String, Object>> receiveTransfer(
            @PathVariable long id,
            @RequestHeader("X-Request-Id") @NotBlank String requestId) {
        TransferResult r = operations.receiveTransfer(id, requestId);
        Map<String, Object> data = transferToMap(r.transfer());
        data.put("duplicate", r.duplicate());
        data.put("conservationDiff", operations.transferConservationDiff(id));
        return ResponseEntity.ok(ApiResponses.ok(data));
    }

    // ───────────────────────────── 盘点 ─────────────────────────────

    public record StocktakeLineRequest(
            @NotNull Long skuId,
            @Size(max = 64) String batchNo,
            @NotNull @Min(0) Integer countedQty) {
    }

    public record StocktakeCreateRequest(
            @NotNull Long warehouseId,
            LocalDate bizDate,
            @Size(max = 500) String remark,
            @NotEmpty @Valid List<StocktakeLineRequest> lines) {
    }

    @GetMapping("/stocktakes")
    @RequirePermission("inv:read")
    public ResponseEntity<Map<String, Object>> listStocktakes(
            @RequestParam(required = false) StocktakeStatus status,
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        var result = operations.pageStocktakes(status, warehouseId, page, size);
        List<Map<String, Object>> items = result.items().stream()
                .map(InventoryOperationsController::stocktakeToMap).toList();
        return ResponseEntity.ok(ApiResponses.page(new com.erp.shared.web.PageResult<>(
                items, result.total(), result.page(), result.size())));
    }

    @PostMapping("/stocktakes")
    @RequirePermission("inv:write")
    public ResponseEntity<Map<String, Object>> createStocktake(
            @RequestHeader("X-Request-Id") @NotBlank String requestId,
            @Valid @RequestBody StocktakeCreateRequest req) {
        List<StocktakeLineCommand> lines = req.lines().stream()
                .map(l -> new StocktakeLineCommand(l.skuId(), l.batchNo(), l.countedQty()))
                .toList();
        StocktakeResult r = operations.createStocktake(req.warehouseId(), req.bizDate(),
                req.remark(), lines, requestId);
        Map<String, Object> data = stocktakeToMap(r.stocktake());
        data.put("duplicate", r.duplicate());
        return ResponseEntity.ok(ApiResponses.ok(data));
    }

    @PostMapping("/stocktakes/{id}/post")
    @RequirePermission("inv:write")
    public ResponseEntity<Map<String, Object>> postStocktake(
            @PathVariable long id,
            @RequestHeader("X-Request-Id") @NotBlank String requestId) {
        StocktakeResult r = operations.postStocktake(id, requestId);
        Map<String, Object> data = stocktakeToMap(r.stocktake());
        data.put("duplicate", r.duplicate());
        return ResponseEntity.ok(ApiResponses.ok(data));
    }

    // ───────────────────────────── 库存调整 ─────────────────────────────

    public record AdjustmentCreateRequest(
            @NotNull Long skuId,
            @NotNull Long warehouseId,
            @Size(max = 64) String batchNo,
            @NotNull Integer changeQty,
            @NotBlank @Size(max = 200) String reason,
            @Min(0) Long unitCostAmount) {
    }

    @GetMapping("/adjustments")
    @RequirePermission("inv:read")
    public ResponseEntity<Map<String, Object>> listAdjustments(
            @RequestParam(required = false) Long skuId,
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        var result = operations.pageAdjustments(skuId, warehouseId, page, size);
        List<Map<String, Object>> items = result.items().stream()
                .map(InventoryOperationsController::adjustmentToMap).toList();
        return ResponseEntity.ok(ApiResponses.page(new com.erp.shared.web.PageResult<>(
                items, result.total(), result.page(), result.size())));
    }

    @PostMapping("/adjustments")
    @RequirePermission("inv:write")
    public ResponseEntity<Map<String, Object>> createAdjustment(
            @RequestHeader("X-Request-Id") @NotBlank String requestId,
            @Valid @RequestBody AdjustmentCreateRequest req) {
        AdjustmentResult r = operations.createAdjustment(req.skuId(), req.warehouseId(),
                req.batchNo(), req.changeQty(), req.reason(), req.unitCostAmount(), requestId);
        Map<String, Object> data = adjustmentToMap(r.adjustment());
        data.put("duplicate", r.duplicate());
        return ResponseEntity.ok(ApiResponses.ok(data));
    }

    // ───────────────────────────── 冻结 / 解冻 ─────────────────────────────

    public record FreezeCreateRequest(
            @NotNull Long skuId,
            @NotNull Long warehouseId,
            @Size(max = 64) String batchNo,
            @NotNull @Min(1) Integer qty,
            @Size(max = 200) String reason,
            LocalDateTime expiresAt) {
    }

    @GetMapping("/freezes")
    @RequirePermission("inv:read")
    public ResponseEntity<Map<String, Object>> listFreezes(
            @RequestParam(required = false) FreezeStatus status,
            @RequestParam(required = false) Long skuId,
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        var result = operations.pageFreezes(status, skuId, warehouseId, page, size);
        List<Map<String, Object>> items = result.items().stream()
                .map(InventoryOperationsController::freezeToMap).toList();
        return ResponseEntity.ok(ApiResponses.page(new com.erp.shared.web.PageResult<>(
                items, result.total(), result.page(), result.size())));
    }

    @PostMapping("/freezes")
    @RequirePermission("inv:write")
    public ResponseEntity<Map<String, Object>> createFreeze(
            @RequestHeader("X-Request-Id") @NotBlank String requestId,
            @Valid @RequestBody FreezeCreateRequest req) {
        FreezeResult r = operations.createFreeze(req.skuId(), req.warehouseId(), req.batchNo(),
                req.qty(), req.reason(), req.expiresAt(), requestId);
        Map<String, Object> data = freezeToMap(r.freeze());
        data.put("duplicate", r.duplicate());
        return ResponseEntity.ok(ApiResponses.ok(data));
    }

    @PostMapping("/freezes/{id}/release")
    @RequirePermission("inv:write")
    public ResponseEntity<Map<String, Object>> releaseFreeze(
            @PathVariable long id,
            @RequestHeader("X-Request-Id") @NotBlank String requestId) {
        FreezeResult r = operations.releaseFreeze(id, requestId);
        Map<String, Object> data = freezeToMap(r.freeze());
        data.put("duplicate", r.duplicate());
        return ResponseEntity.ok(ApiResponses.ok(data));
    }

    // ───────────────────────────── DTO 映射 ─────────────────────────────

    static Map<String, Object> transferToMap(InventoryTransfer t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.id());
        m.put("transferNo", t.transferNo());
        m.put("fromWarehouseId", t.fromWarehouseId());
        m.put("toWarehouseId", t.toWarehouseId());
        m.put("status", t.status().name());
        m.put("bizDate", t.bizDate() == null ? null : t.bizDate().toString());
        m.put("remark", t.remark());
        m.put("inTransitQty", t.totalInTransitQty());
        m.put("receivedAt", t.receivedAt() == null ? null : t.receivedAt().toString());
        m.put("createdAt", t.createdAt() == null ? null : t.createdAt().toString());
        List<Map<String, Object>> lines = new ArrayList<>();
        for (TransferLine line : t.lines()) {
            Map<String, Object> lm = new LinkedHashMap<>();
            lm.put("lineNo", line.lineNo());
            lm.put("skuId", line.skuId());
            lm.put("batchNo", line.batchNo());
            lm.put("qty", line.qty());
            lm.put("inTransitQty", line.inTransitQty());
            lm.put("productionDate", line.productionDate() == null ? null : line.productionDate().toString());
            lm.put("expiryDate", line.expiryDate() == null ? null : line.expiryDate().toString());
            lm.put("unitCostAmount", line.unitCostAmount());
            lines.add(lm);
        }
        m.put("lines", lines);
        return m;
    }

    static Map<String, Object> stocktakeToMap(Stocktake s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.id());
        m.put("stocktakeNo", s.stocktakeNo());
        m.put("warehouseId", s.warehouseId());
        m.put("status", s.status().name());
        m.put("bizDate", s.bizDate() == null ? null : s.bizDate().toString());
        m.put("remark", s.remark());
        m.put("totalDiffQty", s.totalDiffQty());
        m.put("postedAt", s.postedAt() == null ? null : s.postedAt().toString());
        m.put("createdAt", s.createdAt() == null ? null : s.createdAt().toString());
        List<Map<String, Object>> lines = new ArrayList<>();
        for (StocktakeLine line : s.lines()) {
            Map<String, Object> lm = new LinkedHashMap<>();
            lm.put("lineNo", line.lineNo());
            lm.put("skuId", line.skuId());
            lm.put("batchNo", line.batchNo());
            lm.put("bookQty", line.bookQty());
            lm.put("countedQty", line.countedQty());
            lm.put("diffQty", line.diffQty());
            lines.add(lm);
        }
        m.put("lines", lines);
        return m;
    }

    static Map<String, Object> adjustmentToMap(InventoryAdjustment a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.id());
        m.put("adjustNo", a.adjustNo());
        m.put("skuId", a.skuId());
        m.put("warehouseId", a.warehouseId());
        m.put("batchNo", a.batchNo());
        m.put("changeQty", a.changeQty());
        m.put("reason", a.reason());
        m.put("createdAt", a.createdAt() == null ? null : a.createdAt().toString());
        return m;
    }

    static Map<String, Object> freezeToMap(InventoryFreeze f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", f.id());
        m.put("skuId", f.skuId());
        m.put("warehouseId", f.warehouseId());
        m.put("batchNo", f.batchNo());
        m.put("qty", f.qty());
        m.put("reason", f.reason());
        m.put("status", f.status().name());
        m.put("expiresAt", f.expiresAt() == null ? null : f.expiresAt().toString());
        m.put("releasedAt", f.releasedAt() == null ? null : f.releasedAt().toString());
        m.put("createdAt", f.createdAt() == null ? null : f.createdAt().toString());
        return m;
    }
}
