package com.erp.pur.api;

import com.erp.iam.security.RequirePermission;
import com.erp.pur.application.PurchaseReturnService;
import com.erp.pur.domain.PurchaseReturn;
import com.erp.pur.domain.PurchaseReturnLine;
import com.erp.pur.domain.PurchaseReturnStatus;
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
 * 采购退货接口（FR-PUR-06）。
 *
 * <p>建单只收"原到货行 + 数量"：批次、单价、金额全部由后端从到货记录派生。
 * 执行（approve）同时扣库存与冲应付，是整个采购闭环里最危险的写动作，
 * 因此单独要求幂等键，并在服务层锁原进货行。
 */
@RestController
@RequestMapping("/api/v1/purchase-returns")
public class PurchaseReturnController {

    private final PurchaseReturnService returns;

    public PurchaseReturnController(PurchaseReturnService returns) {
        this.returns = returns;
    }

    public record LineRequest(
            @NotNull Long receiptLineId,
            @NotNull @Min(1) Integer qty) {
    }

    public record CreateRequest(
            @NotNull Long purchaseOrderId,
            LocalDate bizDate,
            @Size(max = 500) String remark,
            @NotEmpty @Valid List<LineRequest> lines) {
    }

    @GetMapping
    @RequirePermission("pur:read")
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false) PurchaseReturnStatus status,
            @RequestParam(required = false) Long supplierId,
            @RequestParam(required = false) Long purchaseOrderId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponses.page(
                returns.page(status, supplierId, purchaseOrderId, page, size)));
    }

    @GetMapping("/{id}")
    @RequirePermission("pur:read")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponses.ok(toMap(returns.require(id))));
    }

    @PostMapping
    @RequirePermission("pur:create")
    public ResponseEntity<Map<String, Object>> create(
            @RequestHeader("X-Request-Id") @NotBlank String requestId,
            @Valid @RequestBody CreateRequest req) {
        List<PurchaseReturnService.CreateLineCommand> lines = req.lines().stream()
                .map(l -> new PurchaseReturnService.CreateLineCommand(l.receiptLineId(), l.qty()))
                .toList();
        PurchaseReturn r = returns.create(new PurchaseReturnService.CreateCommand(
                req.purchaseOrderId(), req.bizDate(), req.remark(), lines, requestId));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponses.ok(toMap(r)));
    }

    @PostMapping("/{id}/approve")
    @RequirePermission("pur:approve")
    public ResponseEntity<Map<String, Object>> approve(
            @PathVariable long id,
            @RequestHeader("X-Request-Id") @NotBlank String requestId) {
        return ResponseEntity.ok(ApiResponses.ok(toMap(returns.approve(id, requestId))));
    }

    @PostMapping("/{id}/void")
    @RequirePermission("pur:void")
    public ResponseEntity<Map<String, Object>> voidReturn(
            @PathVariable long id,
            @RequestHeader("X-Request-Id") @NotBlank String requestId) {
        return ResponseEntity.ok(ApiResponses.ok(toMap(returns.voidReturn(id, requestId))));
    }

    private static Map<String, Object> toMap(PurchaseReturn r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("returnNo", r.returnNo());
        m.put("purchaseOrderId", r.purchaseOrderId());
        m.put("supplierId", r.supplierId());
        m.put("warehouseId", r.warehouseId());
        m.put("bizDate", r.bizDate() == null ? null : r.bizDate().toString());
        m.put("period", r.period());
        m.put("status", r.status().name());
        m.put("totalQty", r.totalQty());
        m.put("totalAmount", r.totalAmount());
        m.put("remark", r.remark());
        List<Map<String, Object>> lines = new ArrayList<>();
        for (PurchaseReturnLine l : r.lines()) {
            Map<String, Object> lm = new LinkedHashMap<>();
            lm.put("receiptLineId", l.receiptLineId());
            lm.put("purchaseOrderLineId", l.purchaseOrderLineId());
            lm.put("skuId", l.skuId());
            lm.put("qty", l.qty());
            lm.put("unitPriceAmount", l.unitPriceAmount());
            lm.put("amount", l.amount());
            lm.put("batchNo", l.batchNo());
            lines.add(lm);
        }
        m.put("lines", lines);
        return m;
    }
}
