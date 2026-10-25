package com.erp.rpt.api;

import com.erp.iam.security.RequirePermission;
import com.erp.rpt.application.DashboardService;
import com.erp.shared.web.ApiResponses;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 工作台聚合接口（契约：/dashboard/summary，FR-V3-02）。 */
@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

    private final DashboardService service;

    public DashboardController(DashboardService service) {
        this.service = service;
    }

    @GetMapping("/summary")
    @RequirePermission("dashboard:read")
    public ResponseEntity<Map<String, Object>> summary() {
        return ResponseEntity.ok(ApiResponses.ok(service.buildSummary()));
    }
}
