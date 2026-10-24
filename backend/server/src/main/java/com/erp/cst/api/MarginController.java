package com.erp.cst.api;

import com.erp.cst.application.MarginApplicationService;
import com.erp.cst.domain.MarginSummaryRow;
import com.erp.iam.security.RequirePermission;
import com.erp.shared.web.ApiResponses;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 毛利分析接口（FR-CST-03）。
 *
 * <p>路径选择 {@code /reports/profit} 而不是 {@code /cost/margin}：
 * {@code docs/00} 早已把 {@code GET /reports/profit} 规划为 FR-RPT-04，
 * 统一在报表命名空间下更符合前端菜单与权限习惯（复用 {@code rpt:read}）。
 *
 * <p>只读：数据来自成本月结生成的 {@code margin_records}，本接口不写任何表。
 */
@RestController
@RequestMapping("/api/v1/reports")
public class MarginController {

    private final MarginApplicationService margins;

    public MarginController(MarginApplicationService margins) {
        this.margins = margins;
    }

    @GetMapping("/profit")
    @RequirePermission("rpt:read")
    public ResponseEntity<Map<String, Object>> profit(
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(required = false) String groupBy) {
        var items = margins.analyze(from, to, groupBy).stream()
                .map(MarginController::toMap).toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", items);
        return ResponseEntity.ok(ApiResponses.ok(data));
    }

    private static Map<String, Object> toMap(MarginSummaryRow row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", row.key());
        m.put("label", row.label());
        m.put("qty", row.qty());
        m.put("revenueAmount", row.revenueAmount());
        m.put("costAmount", row.costAmount());
        m.put("grossProfitAmount", row.grossProfitAmount());
        m.put("grossMarginRateBp", row.grossMarginRateBp());
        m.put("orderCount", row.orderCount());
        return m;
    }
}
