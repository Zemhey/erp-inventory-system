package com.erp.sys.api;

import com.erp.iam.security.RequirePermission;
import com.erp.shared.web.ApiResponses;
import com.erp.sys.application.LoginLogQueryService;
import com.erp.sys.domain.LoginLog;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 登录日志接口（契约：/login-logs，FR-V3-29）。 */
@RestController
@RequestMapping("/api/v1/login-logs")
public class LoginLogController {

    private final LoginLogQueryService service;

    public LoginLogController(LoginLogQueryService service) {
        this.service = service;
    }

    @GetMapping
    @RequirePermission("sys:loginlog")
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(defaultValue = "100") int limit) {
        List<Map<String, Object>> items = service.recent(limit).stream()
                .map(LoginLogController::toMap).toList();
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("items", items);
        return ResponseEntity.ok(ApiResponses.ok(data));
    }

    @GetMapping("/online")
    @RequirePermission("sys:loginlog")
    public ResponseEntity<Map<String, Object>> online(
            @RequestParam(defaultValue = "30") int minutes) {
        List<Map<String, Object>> items = service.online(minutes).stream()
                .map(LoginLogController::toMap).toList();
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("items", items);
        return ResponseEntity.ok(ApiResponses.ok(data));
    }

    private static Map<String, Object> toMap(LoginLog l) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", l.id());
        m.put("userId", l.userId());
        m.put("username", l.username());
        m.put("ip", l.ip());
        m.put("userAgent", l.userAgent());
        m.put("loginAt", l.loginAt());
        m.put("success", l.success());
        m.put("failReason", l.failReason());
        return m;
    }
}
