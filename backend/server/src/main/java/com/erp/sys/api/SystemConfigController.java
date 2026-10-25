package com.erp.sys.api;

import com.erp.iam.security.RequirePermission;
import com.erp.shared.web.ApiResponses;
import com.erp.sys.application.SystemConfigService;
import com.erp.sys.domain.SystemConfig;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 系统参数接口（契约：/sys-config，FR-V3-24）。 */
@RestController
@RequestMapping("/api/v1/sys-config")
public class SystemConfigController {

    private final SystemConfigService service;

    public SystemConfigController(SystemConfigService service) {
        this.service = service;
    }

    @GetMapping
    @RequirePermission("sys:config")
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false) String group) {
        List<Map<String, Object>> items = service.list(group).stream()
                .map(SystemConfigController::toMap).toList();
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("items", items);
        return ResponseEntity.ok(ApiResponses.ok(data));
    }

    public record UpdateValue(String value) {
    }

    @PutMapping("/{id}")
    @RequirePermission("sys:config")
    public ResponseEntity<Map<String, Object>> updateValue(
            @PathVariable long id, @RequestBody UpdateValue body) {
        SystemConfig c = service.updateValue(id, body.value());
        return ResponseEntity.ok(ApiResponses.ok(toMap(c)));
    }

    private static Map<String, Object> toMap(SystemConfig c) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", c.id());
        m.put("configGroup", c.configGroup());
        m.put("configKey", c.configKey());
        m.put("configValue", c.configValue());
        m.put("valueType", c.valueType());
        m.put("remark", c.remark());
        m.put("createdAt", c.createdAt());
        m.put("updatedAt", c.updatedAt());
        return m;
    }
}
