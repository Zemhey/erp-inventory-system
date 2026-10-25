package com.erp.sys.api;

import com.erp.iam.security.RequirePermission;
import com.erp.shared.web.ApiResponses;
import com.erp.sys.application.OrganizationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 组织架构接口（契约：/org，FR-V3-23）。 */
@RestController
@RequestMapping("/api/v1/org")
public class OrganizationController {

    private final OrganizationService service;

    public OrganizationController(OrganizationService service) {
        this.service = service;
    }

    /** 部门树（嵌套）。 */
    @GetMapping("/departments")
    @RequirePermission("sys:org")
    public ResponseEntity<Map<String, Object>> departmentTree(
            @RequestParam(defaultValue = "false") boolean includeDisabled) {
        List<Map<String, Object>> tree = service.departmentTree(includeDisabled);
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("items", tree);
        return ResponseEntity.ok(ApiResponses.ok(data));
    }

    public record CreateDepartment(@NotBlank String code, @NotBlank String name,
                                   Long parentId, Integer sortNo) {
    }

    @PostMapping("/departments")
    @RequirePermission("sys:org")
    public ResponseEntity<Map<String, Object>> createDepartment(
            @Valid @RequestBody CreateDepartment body) {
        var d = service.createDepartment(body.code(), body.name(),
                body.parentId(), body.sortNo() == null ? 0 : body.sortNo());
        return ResponseEntity.ok(ApiResponses.ok(toMap(d)));
    }

    public record UpdateDepartment(String name, Long parentId, Integer sortNo, Boolean enabled) {
    }

    @PutMapping("/departments/{id}")
    @RequirePermission("sys:org")
    public ResponseEntity<Map<String, Object>> updateDepartment(
            @PathVariable long id, @RequestBody UpdateDepartment body) {
        var d = service.updateDepartment(id, body.name(),
                body.parentId(), body.sortNo() == null ? 0 : body.sortNo(),
                body.enabled() != null && body.enabled());
        return ResponseEntity.ok(ApiResponses.ok(toMap(d)));
    }

    @DeleteMapping("/departments/{id}")
    @RequirePermission("sys:org")
    public ResponseEntity<Map<String, Object>> deleteDepartment(@PathVariable long id) {
        service.deleteDepartment(id);
        return ResponseEntity.ok(ApiResponses.ok(java.util.Map.of("deleted", id)));
    }

    @GetMapping("/positions")
    @RequirePermission("sys:org")
    public ResponseEntity<Map<String, Object>> positions(
            @RequestParam(required = false) Long departmentId) {
        List<Map<String, Object>> items = service.positions(departmentId).stream()
                .map(OrganizationController::toPositionMap).toList();
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("items", items);
        return ResponseEntity.ok(ApiResponses.ok(data));
    }

    public record CreatePosition(@NotNull Long departmentId, @NotBlank String code,
                                 @NotBlank String name, Integer level) {
    }

    @PostMapping("/positions")
    @RequirePermission("sys:org")
    public ResponseEntity<Map<String, Object>> createPosition(
            @Valid @RequestBody CreatePosition body) {
        var p = service.createPosition(body.departmentId(), body.code(), body.name(),
                body.level() == null ? 1 : body.level());
        return ResponseEntity.ok(ApiResponses.ok(toPositionMap(p)));
    }

    public record UpdatePosition(Long departmentId, String name, Integer level, Boolean enabled) {
    }

    @PutMapping("/positions/{id}")
    @RequirePermission("sys:org")
    public ResponseEntity<Map<String, Object>> updatePosition(
            @PathVariable long id, @RequestBody UpdatePosition body) {
        var p = service.updatePosition(id, body.departmentId(), body.name(),
                body.level() == null ? 1 : body.level(),
                body.enabled() != null && body.enabled());
        return ResponseEntity.ok(ApiResponses.ok(toPositionMap(p)));
    }

    @DeleteMapping("/positions/{id}")
    @RequirePermission("sys:org")
    public ResponseEntity<Map<String, Object>> deletePosition(@PathVariable long id) {
        service.deletePosition(id);
        return ResponseEntity.ok(ApiResponses.ok(java.util.Map.of("deleted", id)));
    }

    private static Map<String, Object> toMap(com.erp.sys.domain.Department d) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", d.id());
        m.put("parentId", d.parentId());
        m.put("code", d.code());
        m.put("name", d.name());
        m.put("sortNo", d.sortNo());
        m.put("leaderUserId", d.leaderUserId());
        m.put("enabled", d.enabled());
        m.put("createdAt", d.createdAt());
        m.put("updatedAt", d.updatedAt());
        return m;
    }

    private static Map<String, Object> toPositionMap(com.erp.sys.domain.Position p) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", p.id());
        m.put("departmentId", p.departmentId());
        m.put("code", p.code());
        m.put("name", p.name());
        m.put("level", p.level());
        m.put("enabled", p.enabled());
        m.put("createdAt", p.createdAt());
        m.put("updatedAt", p.updatedAt());
        return m;
    }
}
