package com.erp.cst.api;

import com.erp.cst.application.CostApplicationService;
import com.erp.cst.domain.CostMonthlyRow;
import com.erp.iam.security.RequirePermission;
import com.erp.shared.web.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 成本接口（契约申请见 .plan/V2-4-契约申请.md）。
 *
 * <p>写操作一律要求 {@code X-Request-Id}，与既有单据/结算接口保持一致；
 * 重算本身是幂等的，但幂等键仍用于排障追踪。
 */
@RestController
@RequestMapping("/api/v1/cost")
public class CostController {

    private final CostApplicationService costs;

    public CostController(CostApplicationService costs) {
        this.costs = costs;
    }

    public record MonthlyRequest(@NotBlank String period) {
    }

    @PostMapping("/recalculate")
    @RequirePermission("cst:recalc")
    public ResponseEntity<Map<String, Object>> recalculate(
            @RequestHeader("X-Request-Id") @NotBlank String requestId) {
        CostApplicationService.RecalculateResult result = costs.recalculate();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("layerCount", result.layerCount());
        data.put("inboundCostAmount", result.inboundCostAmount());
        data.put("outboundCostAmount", result.outboundCostAmount());
        data.put("endingCostAmount", result.endingCostAmount());
        data.put("balanced", result.balanced());
        return ResponseEntity.ok(ApiResponses.ok(data));
    }

    @PostMapping("/monthly")
    @RequirePermission("cst:monthly")
    public ResponseEntity<Map<String, Object>> closeMonthly(
            @RequestHeader("X-Request-Id") @NotBlank String requestId,
            @Valid @RequestBody MonthlyRequest request) {
        CostApplicationService.MonthlyResult result =
                costs.closeMonthly(request.period(), requestId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("period", result.period());
        data.put("duplicate", result.duplicate());
        data.put("items", result.items().stream().map(CostController::toMap).toList());
        data.put("marginRecordCount", result.marginRecordCount());
        return ResponseEntity.ok(ApiResponses.ok(data));
    }

    @GetMapping("/monthly")
    @RequirePermission("cst:read")
    public ResponseEntity<Map<String, Object>> monthly(@RequestParam String period) {
        List<CostMonthlyRow> rows = costs.monthlyRows(period);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", rows.stream().map(CostController::toMap).toList());
        return ResponseEntity.ok(ApiResponses.ok(data));
    }

    private static Map<String, Object> toMap(CostMonthlyRow row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("skuId", row.skuId());
        m.put("warehouseId", row.warehouseId());
        m.put("openingQty", row.openingQty());
        m.put("openingCostAmount", row.openingCostAmount());
        m.put("inboundQty", row.inboundQty());
        m.put("inboundCostAmount", row.inboundCostAmount());
        m.put("outboundQty", row.outboundQty());
        m.put("outboundCostAmount", row.outboundCostAmount());
        m.put("closingQty", row.closingQty());
        m.put("closingCostAmount", row.closingCostAmount());
        return m;
    }
}
