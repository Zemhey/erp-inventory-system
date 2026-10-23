package com.erp.set.api;

import com.erp.iam.security.RequirePermission;
import com.erp.set.application.SettlementApplicationService;
import com.erp.set.application.SettlementApplicationService.DailyItem;
import com.erp.set.application.SettlementApplicationService.DailyResult;
import com.erp.set.application.SettlementApplicationService.MonthlyResult;
import com.erp.set.application.SettlementApplicationService.ReconcileResult;
import com.erp.set.application.SettlementPeriodQueryService;
import com.erp.set.domain.SettlementPeriodRow;
import com.erp.set.domain.SettlementPeriodStatus;
import com.erp.set.domain.SettlementTotals;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.web.ApiResponses;
import com.erp.shared.web.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
 *
 * <p>{@code GET /periods} 是这四个动作之外唯一的 {@code set:read} 读出口：
 * 三个动作各要各的权限码，而"能进结算页"只等于有 {@code set:read}，
 * 没有它就只能看着空白页（计划书 §6 坑 15）。
 */
@RestController
@RequestMapping("/api/v1/settlements")
public class SettlementController {

    private final SettlementApplicationService settlements;
    private final SettlementPeriodQueryService periodQuery;

    public SettlementController(SettlementApplicationService settlements,
                                SettlementPeriodQueryService periodQuery) {
        this.settlements = settlements;
        this.periodQuery = periodQuery;
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

    /**
     * 账期列表（契约：{@code /settlements/periods}）。
     *
     * <p>结算页在 {@code set:read} 之下的唯一数据来源（计划书 §6 坑 15）：
     * 只读 {@code settlement_periods} 已落的行，不重算、不写表，
     * 所以它不需要幂等键，也不会因为"看一眼列表"就改动任何账目。
     *
     * <p>字面量路径 {@code /periods} 的匹配优先级高于 {@code /reconcile} 之类的
     * 同级路径，彼此不会误命中。
     */
    @GetMapping("/periods")
    @RequirePermission("set:read")
    public ResponseEntity<Map<String, Object>> periods(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageResult<SettlementPeriodRow> found = periodQuery.page(parseStatus(status), page, size);
        List<Map<String, Object>> items = new ArrayList<>(found.items().size());
        for (SettlementPeriodRow row : found.items()) {
            items.add(toPeriodRowMap(row));
        }
        return ResponseEntity.ok(ApiResponses.page(
                new PageResult<>(items, found.total(), found.page(), found.size())));
    }

    // ─────────────────────────── 参数解析 ───────────────────────────

    /**
     * 手工解析而不是绑成枚举：非法取值交给 Spring 会变成"参数类型错误"，
     * 而契约要求 400 + {@code SYS_VALIDATION_ERROR} 且消息里说清允许哪些值
     * （与 {@code ReportController.parseGroupBy} 同一套做法）。
     * 大小写不敏感，空串按"不筛选"处理。
     */
    private static SettlementPeriodStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return SettlementPeriodStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "status 取值非法：%s（允许 OPEN | CLOSED，或省略）".formatted(raw));
        }
    }

    // ─────────────────────────── 响应映射 ───────────────────────────

    /** 字段严格对齐契约 {@code SettlementPeriodRow}：七个汇总量逐字沿用 {@code SettlementTotals}。 */
    private static Map<String, Object> toPeriodRowMap(SettlementPeriodRow row) {
        Map<String, Object> m = new LinkedHashMap<>(toTotalsMap(row.totals()));
        m.put("period", row.period());
        m.put("status", row.status().name());
        m.put("closedAt", row.closedAt() == null ? null : row.closedAt().toString());
        m.put("closedBy", row.closedBy());
        return m;
    }

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
