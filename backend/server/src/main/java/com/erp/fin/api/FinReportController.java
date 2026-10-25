package com.erp.fin.api;

import com.erp.fin.application.FinReportApplicationService;
import com.erp.iam.security.RequirePermission;
import com.erp.shared.web.ApiResponses;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 财务报表 + 财务中心总览接口（FR-V3-12/13，契约 tag：fin）。
 *
 * <p>报表权限拆两码：{@code fin:report} 管两张法定报表（能看经营数字），
 * {@code fin:read} 管总览卡片（页面入口级）。VIEWER 只有 fin:read ——
 * 能进财务中心首页，但看不到完整资产负债表。
 */
@RestController
@RequestMapping("/api/v1/fin")
public class FinReportController {

    private final FinReportApplicationService reports;

    public FinReportController(FinReportApplicationService reports) {
        this.reports = reports;
    }

    /** 资产负债表：响应里 diff/balanced 必须回给前端（简报："不平要报差异"）。 */
    @GetMapping("/reports/balance-sheet")
    @RequirePermission("fin:report")
    public ResponseEntity<Map<String, Object>> balanceSheet(@RequestParam String period) {
        return ResponseEntity.ok(ApiResponses.ok(reports.balanceSheet(period)));
    }

    @GetMapping("/reports/income-statement")
    @RequirePermission("fin:report")
    public ResponseEntity<Map<String, Object>> incomeStatement(@RequestParam String period) {
        return ResponseEntity.ok(ApiResponses.ok(reports.incomeStatement(period)));
    }

    @GetMapping("/overview")
    @RequirePermission("fin:read")
    public ResponseEntity<Map<String, Object>> overview() {
        return ResponseEntity.ok(ApiResponses.ok(reports.overview()));
    }
}
