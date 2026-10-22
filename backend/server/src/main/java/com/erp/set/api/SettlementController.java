package com.erp.set.api;

import com.erp.iam.security.RequirePermission;
import com.erp.set.application.SettlementApplicationService;
import com.erp.set.application.SettlementApplicationService.DailyItem;
import com.erp.set.application.SettlementApplicationService.DailyResult;
import com.erp.set.application.SettlementApplicationService.MonthlyResult;
import com.erp.set.application.SettlementApplicationService.ReconcileResult;
import com.erp.set.domain.SettlementTotals;
import com.erp.shared.web.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.LinkedHashMap;
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
 * 结算接口（契约：{@code /settlements}）。
 *
 * <p>三个动作各用各的权限码（{@code set:daily} / {@code set:monthly} /
 * {@code set:reconcile}），不在类上贴一个粗粒度 {@code set:*} ——
 * "财务能日清对账、但不能开单"这条职责分离（计划书 §3.6 / §7 决策 4）
 * 必须在方法签名上看得见，否则迟早被 someone 图省事合并掉。
 *
 * <p>账期格式不在这里用 {@code @Pattern} 拦：Bean Validation 的参数约束违例
 * 不会被 {@code GlobalExceptionHandler} 认成业务错误，会掉进兜底的 500。
 * 交给应用服务的 {@code parsePeriod} 统一变成 400 + {@code SYS_VALIDATION_ERROR}。
 */
@RestController
@RequestMapping("/api/v1/settlements")
public class SettlementController {

    private final SettlementApplicationService settlements;

    public SettlementController(SettlementApplicationService settlements) {
        this.settlements = settlements;
    }

    public record DailyRequest(@NotNull LocalDate bizDate,
                               Long warehouseId) {
    }

    public record MonthlyRequest(@NotBlank String period) {
    }

    @PostMapping("/daily")
    @RequirePermission("set:daily")
    public ResponseEntity<Map<String, Object>> daily(
            @RequestHeader("X-Request-Id") @NotBlank String requestId,
            @Valid @RequestBody DailyRequest req) {
        DailyResult result = settlements.settleDaily(req.bizDate(), req.warehouseId(), requestId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("bizDate", result.bizDate().toString());
        data.put("items", result.items().stream().map(SettlementController::toItemMap).toList());
        data.put("totals", toTotalsMap(result.totals()));
        return ResponseEntity.ok(ApiResponses.ok(data));
    }

    @PostMapping("/monthly")
    @RequirePermission("set:monthly")
    public ResponseEntity<Map<String, Object>> monthly(
            @RequestHeader("X-Request-Id") @NotBlank String requestId,
            @Valid @RequestBody MonthlyRequest req) {
        MonthlyResult result = settlements.settleMonthly(req.period(), requestId);
        Map<String, Object> data = new LinkedHashMap<>(toTotalsMap(result.totals()));
        data.put("period", result.period());
        data.put("status", result.status().name());
        data.put("closedAt", result.closedAt() == null ? null : result.closedAt().toString());
        data.put("duplicate", result.duplicate());
        return ResponseEntity.ok(ApiResponses.ok(data));
    }

    @GetMapping("/reconcile")
    @RequirePermission("set:reconcile")
    public ResponseEntity<Map<String, Object>> reconcile(@RequestParam String period) {
        ReconcileResult result = settlements.reconcile(period);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("period", result.period());
        data.put("generalTotal", result.generalTotal());
        data.put("detailTotal", result.detailTotal());
        data.put("diff", result.diff());
        data.put("balanced", result.balanced());
        return ResponseEntity.ok(ApiResponses.ok(data));
    }

    // ─────────────────────────── 响应映射 ───────────────────────────

    /** 字段严格对齐契约 {@code SettlementTotals}；netAmount 是派生值，放在最后。 */
    private static Map<String, Object> toTotalsMap(SettlementTotals t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("purchaseQty", t.purchaseQty());
        m.put("purchaseAmount", t.purchaseAmount());
        m.put("salesQty", t.salesQty());
        m.put("salesAmount", t.salesAmount());
        m.put("returnQty", t.returnQty());
        m.put("returnAmount", t.returnAmount());
        m.put("netAmount", t.netAmount());
        return m;
    }

    private static Map<String, Object> toItemMap(DailyItem item) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.putAll(toTotalsMap(item.totals()));
        m.put("warehouseId", item.warehouseId());
        return m;
    }
}
