package com.erp.rpt.api;

import com.erp.iam.security.RequirePermission;
import com.erp.rpt.application.ReportApplicationService;
import com.erp.rpt.domain.AgingGroupBy;
import com.erp.rpt.domain.AgingResult;
import com.erp.rpt.domain.AgingRow;
import com.erp.rpt.domain.AgingSummary;
import com.erp.rpt.domain.CustomerContributionResult;
import com.erp.rpt.domain.CustomerContributionRow;
import com.erp.rpt.domain.CustomerContributionSummary;
import com.erp.rpt.domain.InventoryTurnoverRow;
import com.erp.rpt.domain.InventoryValueResult;
import com.erp.rpt.domain.InventoryValueRow;
import com.erp.rpt.domain.OperatingReportRow;
import com.erp.shared.web.ApiResponses;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * V2-8 新增报表：经营日报/月报、库存周转与呆滞、客户贡献与复购、应收/应付账龄。
 *
 * <p>与 {@link ReportController} 一样是只读 GET。这里额外强调两条口径：
 * <ul>
 *   <li>经营报表的期初/入/出/期末来自流水，进货/销售来自单据，两组数分别列示；</li>
 *   <li>账龄 AR 以到期日、AP 以应付单业务日起算；四桶合计必须等于余额。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/reports")
public class ReportAdvancedController {

    private final ReportApplicationService reports;

    public ReportAdvancedController(ReportApplicationService reports) {
        this.reports = reports;
    }

    @GetMapping("/operating")
    @RequirePermission("rpt:read")
    public ResponseEntity<Map<String, Object>> operating(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String dimension,
            @RequestParam(required = false) Long warehouseId) {
        var rows = reports.operatingReport(from, to,
                ReportRequestParams.parseDimension(dimension), warehouseId);
        return items(rows.stream().map(ReportAdvancedController::toOperatingMap).toList());
    }

    @GetMapping("/inventory-turnover")
    @RequirePermission("rpt:read")
    public ResponseEntity<Map<String, Object>> inventoryTurnover(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "30") int deadDays,
            @RequestParam(required = false) Long warehouseId) {
        var rows = reports.inventoryTurnover(from, to, deadDays, warehouseId);
        return items(rows.stream().map(ReportAdvancedController::toTurnoverMap).toList());
    }

    @GetMapping("/slow-moving")
    @RequirePermission("rpt:read")
    public ResponseEntity<Map<String, Object>> slowMoving(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(required = false) Long warehouseId) {
        var rows = reports.slowMoving(from, to, days, warehouseId);
        return items(rows.stream().map(ReportAdvancedController::toTurnoverMap).toList());
    }

    /**
     * 库存金额（库存总值）。
     *
     * <p><b>★ 单位分</b>（ADR-0005）：{@code unitCostAmount} / {@code costAmount} /
     * {@code summary.totalCostAmount} 全是分，全程成本口径，页面按分→元展示。
     *
     * <p>数据来自成本月结结果表 {@code cost_monthly}，权限仍是 {@code rpt:read}
     * —— 没有把这张报表挂到 {@code cst:*} 上，也没有让前端跨权限域取数。
     * 未做月结的维度金额列为 {@code null}，并在 {@code summary} 里如实报告覆盖率。
     */
    @GetMapping("/inventory-value")
    @RequirePermission("rpt:read")
    public ResponseEntity<Map<String, Object>> inventoryValue(
            @RequestParam String period,
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) String groupBy,
            @RequestParam(required = false) Integer limit) {
        var result = reports.inventoryValue(ReportRequestParams.parsePeriod(period), warehouseId,
                ReportRequestParams.parseValueDim(groupBy), limit);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", result.items().stream()
                .map(ReportAdvancedController::toInventoryValueMap).toList());
        data.put("summary", toInventoryValueSummaryMap(result.summary()));
        return ResponseEntity.ok(ApiResponses.ok(data));
    }

    @GetMapping("/customer-contribution")
    @RequirePermission("rpt:read")
    public ResponseEntity<Map<String, Object>> customerContribution(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) Long warehouseId) {
        CustomerContributionResult result = reports.customerContribution(from, to,
                warehouseId, limit);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", result.items().stream()
                .map(ReportAdvancedController::toContributionMap).toList());
        data.put("summary", toContributionSummaryMap(result.summary()));
        return ResponseEntity.ok(ApiResponses.ok(data));
    }

    /**
     * 应收 / 应付账龄。
     *
     * <p><b>★ 单位分</b>（ADR-0005）：余额与四桶都是分。
     *
     * <p>V4-4 追加三个可选参数，不传时与改动前逐字相同：
     * <ul>
     *   <li>{@code partnerId}：只看这一个客户/供应商（此前前端只能按名称包含匹配）；</li>
     *   <li>{@code groupBy}：{@code partner}（默认）| {@code document} 按单据展开，
     *       后者每行多回 {@code docNo}/{@code docDate}/{@code dueDate} 三列（AP 无到期日，恒 null）；</li>
     *   <li>{@code limit}：Top N 展示截断；{@code summary} 始终是全部行的合计，
     *       因此"截断后合计变小"这种假象不会出现，同时多回一列
     *       {@code data.returnedCount} 说明本次实际返回了几行（供前端显示"还有 N 行未列出"）。</li>
     * </ul>
     */
    @GetMapping("/aging")
    @RequirePermission("rpt:read")
    public ResponseEntity<Map<String, Object>> aging(
            @RequestParam String type,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestParam(required = false) Long partnerId,
            @RequestParam(required = false) String groupBy,
            @RequestParam(required = false) Integer limit) {
        AgingGroupBy dim = ReportRequestParams.parseAgingGroupBy(groupBy);
        AgingResult result = reports.aging(ReportRequestParams.parseAgingType(type), asOf,
                partnerId, dim, limit);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", result.items().stream()
                .map(ReportAdvancedController::toAgingMap).toList());
        data.put("summary", toAgingSummaryMap(result.summary()));
        data.put("returnedCount", result.items().size());
        return ResponseEntity.ok(ApiResponses.ok(data));
    }

    // ─────────────────────────── DTO 转换 ───────────────────────────

    private static ResponseEntity<Map<String, Object>> items(java.util.List<?> rows) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", rows);
        return ResponseEntity.ok(ApiResponses.ok(data));
    }

    private static Map<String, Object> toOperatingMap(OperatingReportRow r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("period", r.period());
        m.put("openingQty", r.openingQty());
        m.put("inboundQty", r.inboundQty());
        m.put("outboundQty", r.outboundQty());
        m.put("closingQty", r.closingQty());
        m.put("purchaseQty", r.purchaseQty());
        m.put("purchaseAmount", r.purchaseAmount());
        m.put("salesQty", r.salesQty());
        m.put("salesAmount", r.salesAmount());
        return m;
    }

    private static Map<String, Object> toTurnoverMap(InventoryTurnoverRow r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("skuId", r.skuId());
        m.put("skuCode", r.skuCode());
        m.put("skuName", r.skuName());
        m.put("warehouseId", r.warehouseId());
        m.put("warehouseName", r.warehouseName());
        m.put("openingQty", r.openingQty());
        m.put("closingQty", r.closingQty());
        m.put("outboundQty", r.outboundQty());
        m.put("turnoverDays", r.turnoverDays());
        m.put("idleDays", r.idleDays());
        m.put("stale", r.stale());
        return m;
    }

    private static Map<String, Object> toContributionMap(CustomerContributionRow r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("customerId", r.customerId());
        m.put("customerCode", r.customerCode());
        m.put("customerName", r.customerName());
        m.put("orderCount", r.orderCount());
        m.put("qty", r.qty());
        m.put("amount", r.amount());
        m.put("repurchase", r.repurchase());
        m.put("newCustomer", r.newCustomer());
        return m;
    }

    private static Map<String, Object> toContributionSummaryMap(CustomerContributionSummary s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("customerCount", s.customerCount());
        m.put("repurchaseCount", s.repurchaseCount());
        m.put("repurchaseRateBp", s.repurchaseRateBp());
        m.put("newCustomerCount", s.newCustomerCount());
        m.put("returningCustomerCount", s.returningCustomerCount());
        m.put("totalQty", s.totalQty());
        m.put("totalAmount", s.totalAmount());
        m.put("orderCount", s.orderCount());
        return m;
    }

    private static Map<String, Object> toAgingMap(AgingRow r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("partnerId", r.partnerId());
        m.put("partnerCode", r.partnerCode());
        m.put("partnerName", r.partnerName());
        m.put("balanceAmount", r.balanceAmount());
        m.put("bucket0", r.bucket0());
        m.put("bucket1", r.bucket1());
        m.put("bucket2", r.bucket2());
        m.put("bucket3", r.bucket3());
        // ★ V4-4 追加：只在 groupBy=document 时有值，默认粒度下恒 null（分）。
        m.put("docNo", r.docNo());
        m.put("docDate", r.docDate());
        m.put("dueDate", r.dueDate());
        return m;
    }

    private static Map<String, Object> toInventoryValueMap(InventoryValueRow r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", r.key());
        m.put("label", r.label());
        m.put("qty", r.qty());
        m.put("unitCostAmount", r.unitCostAmount());
        m.put("costAmount", r.costAmount());
        m.put("poolCount", r.poolCount());
        m.put("valuedPoolCount", r.valuedPoolCount());
        return m;
    }

    private static Map<String, Object> toInventoryValueSummaryMap(InventoryValueResult.Summary s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("poolCount", s.poolCount());
        m.put("valuedPoolCount", s.valuedPoolCount());
        m.put("totalQty", s.totalQty());
        m.put("valuedQty", s.valuedQty());
        m.put("totalCostAmount", s.totalCostAmount());
        m.put("coverageRateBp", s.coverageRateBp());
        return m;
    }

    private static Map<String, Object> toAgingSummaryMap(AgingSummary s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("partnerCount", s.partnerCount());
        m.put("totalBalance", s.totalBalance());
        m.put("bucket0", s.bucket0());
        m.put("bucket1", s.bucket1());
        m.put("bucket2", s.bucket2());
        m.put("bucket3", s.bucket3());
        return m;
    }
}
