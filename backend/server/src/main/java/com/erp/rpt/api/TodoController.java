package com.erp.rpt.api;

import com.erp.iam.security.RequirePermission;
import com.erp.rpt.application.TodoService;
import com.erp.shared.web.ApiResponses;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 待办中心接口（契约：/todos，FR-V3-03）。 */
@RestController
@RequestMapping("/api/v1/todos")
public class TodoController {

    private final TodoService service;

    public TodoController(TodoService service) {
        this.service = service;
    }

    @GetMapping
    @RequirePermission("dashboard:read")
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(defaultValue = "approval") String type,
            @RequestParam(defaultValue = "50") int limit) {
        List<Map<String, Object>> items = service.list(type, limit);
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("items", items);
        return ResponseEntity.ok(ApiResponses.ok(data));
    }
}
