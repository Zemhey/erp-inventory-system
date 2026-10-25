package com.erp.iam.api;

import com.erp.iam.application.TenantAdminService;
import com.erp.iam.security.RequirePermission;
import com.erp.shared.tenant.TenantRef;
import com.erp.shared.web.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台租户管理接口（B1 · FR-TNT-08）。
 *
 * <p><b>★ {@code adminBypass = false} 是本接口的关键</b>：
 * {@code PermissionAspect} 默认把 {@code ROLE_ADMIN} 当全权，而每个租户都有
 * 自己的 ADMIN。若不关掉短路，演示租户的管理员也能调用 /tenants ——
 * 那正是需求禁止的"普通租户管理员看得到、调得通"。
 * 关掉后，ADMIN 必须**真的持有** {@code tenant:manage}；V17 只把它种给
 * 平台租户（id=1）的 ADMIN。
 */
@RestController
@RequestMapping("/api/v1/tenants")
public class TenantAdminController {

    private final TenantAdminService tenants;

    public TenantAdminController(TenantAdminService tenants) {
        this.tenants = tenants;
    }

    public record SetEnabledRequest(@NotNull Boolean enabled) {
    }

    /** 租户清单（平台元数据，不分页）。 */
    @GetMapping
    @RequirePermission(value = "tenant:manage", adminBypass = false)
    public ResponseEntity<Map<String, Object>> list() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (TenantRef t : tenants.list()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", t.id());
            item.put("code", t.code());
            item.put("name", t.name());
            item.put("enabled", t.enabled());
            items.add(item);
        }
        return ResponseEntity.ok(ApiResponses.ok(Map.of("items", items)));
    }

    /**
     * 启用 / 停用租户（纯开关，不消费 {@code X-Request-Id}）。
     *
     * <p>停用后该租户的既有 token 在下一个请求就 401（{@code resolve} 校验
     * 租户启用），不需要等 token 过期 —— 这是"停用"应有的即时语义。
     */
    @PostMapping("/{id}/enabled")
    @RequirePermission(value = "tenant:manage", adminBypass = false)
    public ResponseEntity<Map<String, Object>> setEnabled(
            @PathVariable long id, @Valid @RequestBody SetEnabledRequest req) {
        TenantRef updated = tenants.setEnabled(id, req.enabled());
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", updated.id());
        item.put("code", updated.code());
        item.put("name", updated.name());
        item.put("enabled", updated.enabled());
        return ResponseEntity.ok(ApiResponses.ok(item));
    }
}
