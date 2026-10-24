package com.erp.iam.api;

import com.erp.iam.application.DataScopeAdminService;
import com.erp.iam.application.SecurityActorContext;
import com.erp.iam.application.UserAdminService;
import com.erp.iam.domain.AppUser;
import com.erp.iam.security.RequirePermission;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.web.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户与角色管理接口（FR-IAM-05）。
 *
 * <p><b>★ 每个方法单列权限码，不在类上贴一个 {@code iam:*}</b>：
 * "能看账号清单"（{@code iam:read}）与"能改别人的角色"
 * （{@code iam:user:write} / {@code iam:role:write}）是两件严重程度完全不同的事，
 * 合并成一个码就等于"任何能给新人看账号列表的人都自动能提权"。
 * 这与进货/出货把 submit/approve/ship 分开是同一个理由。
 *
 * <p>本层只做绑定与 DTO 转换；规则在 {@link UserAdminService} 与
 * {@link DataScopeAdminService}。密码<b>永不回显</b>：
 * 响应里没有 {@code passwordHash} 字段，也没有能反推它的东西。
 */
@RestController
@RequestMapping("/api/v1/iam")
public class IamAdminController {

    private final UserAdminService users;
    private final DataScopeAdminService dataScopes;
    private final SecurityActorContext actors;

    public IamAdminController(UserAdminService users, DataScopeAdminService dataScopes,
                              SecurityActorContext actors) {
        this.users = users;
        this.dataScopes = dataScopes;
        this.actors = actors;
    }

    // ─────────────────────────── 用户 ───────────────────────────

    public record CreateUserRequest(
            @NotBlank @Size(min = 3, max = 64) String username,
            @NotBlank @Size(min = 8, max = 128) String password,
            @Size(max = 64) String displayName,
            Boolean enabled,
            List<String> roles) {
    }

    public record UpdateRolesRequest(@NotNull List<String> roles) {
    }

    public record ResetPasswordRequest(@NotBlank @Size(min = 8, max = 128) String newPassword) {
    }

    public record SetEnabledRequest(@NotNull Boolean enabled) {
    }

    @GetMapping("/users")
    @RequirePermission("iam:read")
    public ResponseEntity<Map<String, Object>> listUsers(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        var found = users.pageUsers(keyword, page, size);
        List<Map<String, Object>> items = new ArrayList<>(found.items().size());
        for (AppUser u : found.items()) {
            items.add(toUserMap(u));
        }
        return ResponseEntity.ok(ApiResponses.page(new com.erp.shared.web.PageResult<>(
                items, found.total(), found.page(), found.size())));
    }

    @GetMapping("/users/{id}")
    @RequirePermission("iam:read")
    public ResponseEntity<Map<String, Object>> user(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponses.ok(toUserMap(users.requireUser(id))));
    }

    @PostMapping("/users")
    @RequirePermission("iam:user:write")
    public ResponseEntity<Map<String, Object>> createUser(
            @Valid @RequestBody CreateUserRequest req) {
        long id = users.createUser(req.username(), req.password(), req.displayName(),
                req.enabled() == null || req.enabled(), req.roles());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponses.ok(toUserMap(users.requireUser(id))));
    }

    /**
     * 改角色（覆盖式）。
     *
     * <p><b>授权即时生效</b>：改完的下一个请求就用新权限判定 —— 因为
     * {@code JwtAuthenticationFilter} 与 {@code PermissionAspect} 每次请求都回库。
     * 因此这个接口不需要"让对方重新登录"的提示，也不需要吊销 token。
     */
    @PutMapping("/users/{id}/roles")
    @RequirePermission("iam:user:write")
    public ResponseEntity<Map<String, Object>> updateRoles(
            @PathVariable long id, @Valid @RequestBody UpdateRolesRequest req) {
        users.updateRoles(id, req.roles(), currentActorId());
        return ResponseEntity.ok(ApiResponses.ok(toUserMap(users.requireUser(id))));
    }

    @PostMapping("/users/{id}/enabled")
    @RequirePermission("iam:user:write")
    public ResponseEntity<Map<String, Object>> setEnabled(
            @PathVariable long id, @Valid @RequestBody SetEnabledRequest req) {
        users.setEnabled(id, req.enabled(), currentActorId());
        return ResponseEntity.ok(ApiResponses.ok(toUserMap(users.requireUser(id))));
    }

    /**
     * 管理员重置密码。
     *
     * <p>只返回 {@code mustChangePassword=true}，<b>不回显新密码</b>：
     * 明文经过谁的手是可以被日志/浏览器历史记录的，而重置动作有留痕。
     * 正确做法是由管理员当面/另行告知用户。
     */
    @PostMapping("/users/{id}/reset-password")
    @RequirePermission("iam:user:write")
    public ResponseEntity<Map<String, Object>> resetPassword(
            @PathVariable long id, @Valid @RequestBody ResetPasswordRequest req) {
        users.resetPassword(id, req.newPassword());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", id);
        data.put("mustChangePassword", true);
        return ResponseEntity.ok(ApiResponses.ok(data));
    }

    // ─────────────────────────── 角色 ───────────────────────────

    public record CreateRoleRequest(
            @NotBlank @Size(max = 32) String code,
            @Size(max = 64) String name,
            @Size(max = 200) String description) {
    }

    public record ReplacePermissionsRequest(@NotNull List<String> permissions) {
    }

    @GetMapping("/roles")
    @RequirePermission("iam:read")
    public ResponseEntity<Map<String, Object>> listRoles() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (UserAdminService.RoleView r : users.listRoles()) {
            items.add(toRoleMap(r));
        }
        return ResponseEntity.ok(ApiResponses.ok(Map.of("items", items)));
    }

    @GetMapping("/roles/{id}/permissions")
    @RequirePermission("iam:read")
    public ResponseEntity<Map<String, Object>> rolePermissions(@PathVariable long id) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("role", toRoleMap(users.requireRole(id)));
        data.put("permissions", users.permissionsOfRole(id));
        return ResponseEntity.ok(ApiResponses.ok(data));
    }

    @PostMapping("/roles")
    @RequirePermission("iam:role:write")
    public ResponseEntity<Map<String, Object>> createRole(
            @Valid @RequestBody CreateRoleRequest req) {
        long id = users.createRole(req.code(), req.name(), req.description());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponses.ok(toRoleMap(users.requireRole(id))));
    }

    /** 覆盖式设置角色权限 —— 授权即时生效的触发点。 */
    @PutMapping("/roles/{id}/permissions")
    @RequirePermission("iam:role:write")
    public ResponseEntity<Map<String, Object>> replacePermissions(
            @PathVariable long id, @Valid @RequestBody ReplacePermissionsRequest req) {
        users.replacePermissions(id, req.permissions());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("role", toRoleMap(users.requireRole(id)));
        data.put("permissions", users.permissionsOfRole(id));
        return ResponseEntity.ok(ApiResponses.ok(data));
    }

    // ─────────────────────────── 数据权限 ───────────────────────────

    public record ReplaceScopesRequest(@NotEmpty List<String> scopes) {
    }

    @GetMapping("/users/{id}/data-scopes")
    @RequirePermission("iam:read")
    public ResponseEntity<Map<String, Object>> userScopes(@PathVariable long id) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("direct", scopesToMaps(dataScopes.directScopesOfUser(id)));
        data.put("inherited", scopesToMaps(dataScopes.inheritedScopesOfUser(id)));
        return ResponseEntity.ok(ApiResponses.ok(data));
    }

    @PutMapping("/users/{id}/data-scopes")
    @RequirePermission("iam:scope:write")
    public ResponseEntity<Map<String, Object>> replaceUserScopes(
            @PathVariable long id, @Valid @RequestBody ReplaceScopesRequest req) {
        dataScopes.replaceUserScopes(id, req.scopes());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("direct", scopesToMaps(dataScopes.directScopesOfUser(id)));
        data.put("inherited", scopesToMaps(dataScopes.inheritedScopesOfUser(id)));
        return ResponseEntity.ok(ApiResponses.ok(data));
    }

    @GetMapping("/roles/{id}/data-scopes")
    @RequirePermission("iam:read")
    public ResponseEntity<Map<String, Object>> roleScopes(@PathVariable long id) {
        return ResponseEntity.ok(ApiResponses.ok(
                Map.of("scopes", scopesToMaps(dataScopes.scopesOfRole(id)))));
    }

    @PutMapping("/roles/{id}/data-scopes")
    @RequirePermission("iam:scope:write")
    public ResponseEntity<Map<String, Object>> replaceRoleScopes(
            @PathVariable long id, @Valid @RequestBody ReplaceScopesRequest req) {
        dataScopes.replaceRoleScopes(id, req.scopes());
        return ResponseEntity.ok(ApiResponses.ok(
                Map.of("scopes", scopesToMaps(dataScopes.scopesOfRole(id)))));
    }

    // ─────────────────────────── 内部辅助 ───────────────────────────

    private long currentActorId() {
        return actors.currentUserId().orElseThrow(() -> new BusinessException(
                ErrorCode.IAM_UNAUTHENTICATED, "未登录或登录已过期，请重新登录"));
    }

    /** 账号 DTO —— <b>绝不含 passwordHash</b>（这是唯一不能犯的错）。 */
    private static Map<String, Object> toUserMap(AppUser u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.id());
        m.put("username", u.username());
        m.put("displayName", u.displayName());
        m.put("enabled", u.enabled());
        m.put("roles", u.roles());
        m.put("permissions", u.permissions().stream().sorted().toList());
        m.put("failedAttempts", u.failedAttempts());
        m.put("lockedUntil", u.lockedUntil() == null ? null : u.lockedUntil().toString());
        m.put("passwordExpiresAt",
                u.passwordExpiresAt() == null ? null : u.passwordExpiresAt().toString());
        m.put("mustChangePassword", u.mustChangePassword());
        return m;
    }

    private static Map<String, Object> toRoleMap(UserAdminService.RoleView r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("code", r.code());
        m.put("name", r.name());
        m.put("description", r.description());
        return m;
    }

    /**
     * 范围行 → DTO。
     *
     * <p>对外用 {@code "ALL"} / {@code "SELF"} / {@code "WAREHOUSE:<id>"} 这种
     * <b>与写入同形</b>的字符串（读写对称，前端不必写两套映射）。
     */
    private static List<String> scopesToMaps(List<DataScopeAdminService.ScopeView> rows) {
        List<String> out = new ArrayList<>(rows.size());
        for (DataScopeAdminService.ScopeView r : rows) {
            out.add(r.token());
        }
        return out;
    }
}
