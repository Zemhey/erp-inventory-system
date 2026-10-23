package com.erp.rpt.api;

import com.erp.iam.security.RequirePermission;
import com.erp.rpt.application.ReportApplicationService;
import com.erp.rpt.domain.InventorySummaryRow;
import com.erp.rpt.domain.ReportGroupBy;
import com.erp.rpt.domain.ReportRow;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.web.ApiResponses;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 报表接口（契约：{@code /reports/purchase}、{@code /reports/sales}、
 * {@code /reports/inventory-summary}）。
 *
 * <p><b>整个模块只有 GET</b>：报表不产生单据、不写流水、不动结算行
 * （计划书 §3.1 "RPT 只读跨域查询，不写任何表"）。这条约束同时由
 * {@code ArchitectureTest} 拦住 —— 报表模块不许依赖其它模块的应用服务，
 * 想顺手改数据会在 CI 变红，而不是靠人评审时想起来。
 *
 * <p>本层只做参数绑定与 DTO 转换：{@code groupBy} 在这里落成枚举、
 * {@code period} 在这里解析成 {@link YearMonth}，非法取值直接 400
 * （契约的 enum 与 pattern 就是这个意思）。
 *
 * <p><b>★ 口径要在这里就写清楚，前端页面必须照抄展示</b>（§3.5 / §6 坑 4）：
 * 进货、出货两张表按<b>单据业务日期</b>；进销存汇总按<b>流水发生时间</b>。
 * 两者对同一天未必相等（跨天分批执行），这不是 bug，是口径。
 */
@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {

    /** 契约里进货统计的 groupBy 枚举（默认 supplier）。 */
    private static final EnumSet<ReportGroupBy> PURCHASE_DIMS =
            EnumSet.of(ReportGroupBy.SUPPLIER, ReportGroupBy.PRODUCT, ReportGroupBy.SKU,
                    ReportGroupBy.DAY, ReportGroupBy.MONTH);
    /** 契约里出货统计的 groupBy 枚举（默认 customer）。 */
    private static final EnumSet<ReportGroupBy> SALES_DIMS =
            EnumSet.of(ReportGroupBy.CUSTOMER, ReportGroupBy.PRODUCT, ReportGroupBy.SKU,
                    ReportGroupBy.DAY, ReportGroupBy.MONTH);

    private final ReportApplicationService reports;

    public ReportController(ReportApplicationService reports) {
        this.reports = reports;
    }

    @GetMapping("/purchase")
    @RequirePermission("rpt:read")
    public ResponseEntity<Map<String, Object>> purchase(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String groupBy,
            @RequestParam(required = false) Long warehouseId) {
        ReportGroupBy dim = parseGroupBy(groupBy, ReportGroupBy.SUPPLIER, PURCHASE_DIMS);
        return items(reports.purchaseReport(from, to, dim, warehouseId)
                .stream().map(ReportController::toMap).toList());
    }

    @GetMapping("/sales")
    @RequirePermission("rpt:read")
    public ResponseEntity<Map<String, Object>> sales(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String groupBy,
            @RequestParam(required = false) Long warehouseId) {
        ReportGroupBy dim = parseGroupBy(groupBy, ReportGroupBy.CUSTOMER, SALES_DIMS);
        return items(reports.salesReport(from, to, dim, warehouseId)
                .stream().map(ReportController::toMap).toList());
    }

    @GetMapping("/inventory-summary")
    @RequirePermission("rpt:read")
    public ResponseEntity<Map<String, Object>> inventorySummary(
            @RequestParam String period,
            @RequestParam(required = false) Long warehouseId) {
        return items(reports.inventorySummaryReport(parsePeriod(period), warehouseId)
                .stream().map(ReportController::toMap).toList());
    }

    // ─────────────────────────── 参数解析 ───────────────────────────

    /**
     * 手工解析而不是让 Spring 直接绑枚举：契约取值是小写
     * （{@code supplier / product / sku / day / month}），而 Java 常量是大写，
     * 交给 Spring 就要赌转换器的大小写策略。这里把规则写死，
     * 顺带给出一条比"参数类型错误"更能定位问题的消息
     * —— 前端最常见的错是把 {@code groupBy} 拼成报表名。
     */
    private static ReportGroupBy parseGroupBy(String raw, ReportGroupBy fallback,
                                              EnumSet<ReportGroupBy> allowed) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        ReportGroupBy parsed;
        try {
            parsed = ReportGroupBy.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "groupBy 取值非法：%s（本报表允许 %s）".formatted(raw, describe(allowed)));
        }
        if (!allowed.contains(parsed)) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "groupBy 取值非法：%s（本报表允许 %s）".formatted(raw, describe(allowed)));
        }
        return parsed;
    }

    /** 账期格式 {@code yyyy-MM}（契约 pattern），非法即 400。 */
    private static YearMonth parsePeriod(String raw) {
        try {
            return YearMonth.parse(raw.trim());
        } catch (DateTimeParseException | NullPointerException ex) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "period 格式非法：%s（要求 yyyy-MM，例如 2026-10）".formatted(raw));
        }
    }

    private static String describe(EnumSet<ReportGroupBy> allowed) {
        return allowed.stream()
                .map(d -> d.name().toLowerCase(Locale.ROOT))
                .collect(Collectors.joining(" | "));
    }

    // ─────────────────────────── 响应包装 ───────────────────────────

    /** 契约：报表响应是 {@code data.items} 数组。 */
    private static ResponseEntity<Map<String, Object>> items(List<?> rows) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", rows);
        return ResponseEntity.ok(ApiResponses.ok(data));
    }

    private static Map<String, Object> toMap(ReportRow r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", r.key());
        m.put("label", r.label());
        m.put("qty", r.qty());
        m.put("amount", r.amount());
        m.put("orderCount", r.orderCount());
        return m;
    }

    private static Map<String, Object> toMap(InventorySummaryRow r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("skuId", r.skuId());
        m.put("warehouseId", r.warehouseId());
        m.put("openingQty", r.openingQty());
        m.put("inboundQty", r.inboundQty());
        m.put("outboundQty", r.outboundQty());
        m.put("closingQty", r.closingQty());
        return m;
    }
}
