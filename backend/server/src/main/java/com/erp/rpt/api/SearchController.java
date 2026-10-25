package com.erp.rpt.api;

import com.erp.iam.security.RequirePermission;
import com.erp.rpt.application.SearchService;
import com.erp.shared.web.ApiResponses;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 全局搜索接口（契约：/search，FR-V3-05）。 */
@RestController
@RequestMapping("/api/v1/search")
public class SearchController {

    private final SearchService service;

    public SearchController(SearchService service) {
        this.service = service;
    }

    @GetMapping
    @RequirePermission("dashboard:read")
    public ResponseEntity<Map<String, Object>> search(
            @RequestParam(required = false) String keyword) {
        return ResponseEntity.ok(ApiResponses.ok(service.search(keyword)));
    }
}
