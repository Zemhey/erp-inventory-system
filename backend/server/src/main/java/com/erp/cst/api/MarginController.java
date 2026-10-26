package com.erp.cst.api;

import com.erp.cst.application.MarginApplicationService;
import com.erp.cst.application.MarginCsvService;
import com.erp.cst.domain.MarginSummaryRow;
import com.erp.iam.security.RequirePermission;
import com.erp.shared.web.ApiResponses;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
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
    private final MarginCsvService marginCsv;

    public MarginController(MarginApplicationService margins, MarginCsvService marginCsv) {
        this.margins = margins;
        this.marginCsv = marginCsv;
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

    /**
     * 毛利分析导出 CSV（V4-4 补齐：报表中心此前唯一没有服务端导出的就是这张表）。
     *
     * <p>参数与 {@code GET /reports/profit} 完全一致（同样是 {@code yyyy-MM} <b>账期</b>，
     * 不是 ISO 日期），内容来自同一次 {@code analyze} 调用，所以"导出 == 页面"。
     * 响应 {@code text/csv;charset=UTF-8} + UTF-8 BOM，Excel 双击即开。
     *
     * <p>权限仍是 {@code rpt:read}（与查询端点同一个），金额列按<b>分→元</b>落格。
     */
    @GetMapping(value = "/profit/export", produces = "text/csv;charset=UTF-8")
    @RequirePermission("rpt:read")
    public ResponseEntity<byte[]> profitExport(@RequestParam String from,
                                               @RequestParam String to,
                                               @RequestParam(required = false) String groupBy) {
        MarginCsvService.Result doc = marginCsv.csv(from, to, groupBy);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8")
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + doc.filename() + "\"")
                .body(doc.content().getBytes(StandardCharsets.UTF_8));
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
