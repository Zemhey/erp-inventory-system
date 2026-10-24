package com.erp.rpt.api;

import com.erp.iam.security.RequirePermission;
import com.erp.rpt.application.ReportApplicationService;
import com.erp.rpt.domain.AgingResult;
import com.erp.rpt.domain.AgingRow;
import com.erp.rpt.domain.AgingSummary;
import com.erp.rpt.domain.CustomerContributionResult;
import com.erp.rpt.domain.CustomerContributionRow;
import com.erp.rpt.domain.CustomerContributionSummary;
import com.erp.rpt.domain.InventoryTurnoverRow;
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

    @GetMapping("/aging")
    @RequirePermission("rpt:read")
    public ResponseEntity<Map<String, Object>> aging(
            @RequestParam String type,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        AgingResult result = reports.aging(ReportRequestParams.parseAgingType(type), asOf);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", result.items().stream()
                .map(ReportAdvancedController::toAgingMap).toList());
        data.put("summary", toAgingSummaryMap(result.summary()));
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
