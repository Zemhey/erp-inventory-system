package com.erp.rpt.api;

import com.erp.iam.security.RequirePermission;
import com.erp.rpt.application.CsvDocument;
import com.erp.rpt.application.ReportCsvService;
import com.erp.rpt.domain.ReportGroupBy;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 报表 CSV 导出（FR-RPT-09）。
 *
 * <p>每个端点与对应页面查询<b>接收同一组参数、调用同一个 ApplicationService 方法</b>，
 * 只把结果按页面同样的格式落成 CSV。因此“导出 == 页面”是可断言的同一批数据，
 * 而不是两套查询碰巧相等。
 *
 * <p>响应是 `text/csv;charset=UTF-8` + UTF-8 BOM，Excel 双击直接打开。
 * 报表全部只读，导出同样不写任何表。
 */
@RestController
@RequestMapping("/api/v1/reports")
public class ReportExportController {

    private final ReportCsvService exports;

    public ReportExportController(ReportCsvService exports) {
        this.exports = exports;
    }

    @GetMapping(value = "/purchase/export", produces = "text/csv;charset=UTF-8")
    @RequirePermission("rpt:read")
    public ResponseEntity<byte[]> purchaseExport(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String groupBy,
            @RequestParam(required = false) Long warehouseId) {
        ReportGroupBy dim = ReportRequestParams.parseGroupBy(groupBy, ReportGroupBy.SUPPLIER,
                ReportRequestParams.PURCHASE_DIMS);
        return csv(exports.purchaseCsv(from, to, dim, warehouseId));
    }

    @GetMapping(value = "/sales/export", produces = "text/csv;charset=UTF-8")
    @RequirePermission("rpt:read")
    public ResponseEntity<byte[]> salesExport(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String groupBy,
            @RequestParam(required = false) Long warehouseId) {
        ReportGroupBy dim = ReportRequestParams.parseGroupBy(groupBy, ReportGroupBy.CUSTOMER,
                ReportRequestParams.SALES_DIMS);
        return csv(exports.salesCsv(from, to, dim, warehouseId));
    }

    @GetMapping(value = "/inventory-summary/export", produces = "text/csv;charset=UTF-8")
    @RequirePermission("rpt:read")
    public ResponseEntity<byte[]> inventorySummaryExport(
            @RequestParam String period,
            @RequestParam(required = false) Long warehouseId) {
        return csv(exports.inventorySummaryCsv(
                ReportRequestParams.parsePeriod(period), warehouseId));
    }

    @GetMapping(value = "/operating/export", produces = "text/csv;charset=UTF-8")
    @RequirePermission("rpt:read")
    public ResponseEntity<byte[]> operatingExport(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String dimension,
            @RequestParam(required = false) Long warehouseId) {
        return csv(exports.operatingCsv(from, to,
                ReportRequestParams.parseDimension(dimension), warehouseId));
    }

    @GetMapping(value = "/inventory-turnover/export", produces = "text/csv;charset=UTF-8")
    @RequirePermission("rpt:read")
    public ResponseEntity<byte[]> inventoryTurnoverExport(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "30") int deadDays,
            @RequestParam(required = false) Long warehouseId) {
        return csv(exports.inventoryTurnoverCsv(from, to, deadDays, warehouseId));
    }

    @GetMapping(value = "/customer-contribution/export", produces = "text/csv;charset=UTF-8")
    @RequirePermission("rpt:read")
    public ResponseEntity<byte[]> customerContributionExport(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) Long warehouseId) {
        return csv(exports.customerContributionCsv(from, to, warehouseId, limit));
    }

    @GetMapping(value = "/aging/export", produces = "text/csv;charset=UTF-8")
    @RequirePermission("rpt:read")
    public ResponseEntity<byte[]> agingExport(
            @RequestParam String type,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return csv(exports.agingCsv(ReportRequestParams.parseAgingType(type), asOf));
    }

    private static ResponseEntity<byte[]> csv(CsvDocument doc) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8")
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + doc.filename() + "\"")
                .body(doc.content().getBytes(StandardCharsets.UTF_8));
    }
}
