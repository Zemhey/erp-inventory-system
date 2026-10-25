package com.erp.iam.api;

import com.erp.iam.application.AuthenticationService;
import com.erp.iam.application.SecurityActorContext;
import com.erp.iam.domain.AppUser;
import com.erp.iam.security.JwtAuthenticationFilter;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.tenant.TenantRef;
import com.erp.shared.web.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口（契约：{@code /auth/login}、{@code /auth/me}、{@code /auth/change-password}、
 * {@code /auth/switch-tenant}）。
 *
 * <p>V2-6 追加 {@code /auth/change-password} 与登录响应里的
 * {@code mustChangePassword / passwordExpiresAt / passwordRules} 三字段（FR-IAM-06）。
 * 前者是"密码过期"状态下唯一可用的写接口 —— 没有它，过期用户就无法自救。
 *
 * <p><b>B1 追加</b>：登录可选 {@code tenantCode}；响应的 {@code data} 带
 * {@code tenantId / tenantCode / tenantName} 与 {@code tenants}（该账号的全部成员关系），
 * 并新增 {@code /auth/switch-tenant}。切换租户**重新签发 JWT**，不是前端改显示。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthenticationService authentication;
    private final SecurityActorContext actors;

    public AuthController(AuthenticationService authentication, SecurityActorContext actors) {
        this.authentication = authentication;
        this.actors = actors;
    }

    public record LoginRequest(
            @NotBlank @Size(max = 64) String username,
            @NotBlank @Size(max = 128) String password,
            /**
             * 可选租户编码（B1）。多租户成员用它选择本次登录的租户；
             * 缺省 = 账号的主租户。未知/非成员/已停用一律拒绝，不做"静默回落"。
             */
            @Size(max = 32) String tenantCode) {
    }

    public record SwitchTenantRequest(
            @NotBlank @Size(max = 32) String tenantCode) {
    }

    public record ChangePasswordRequest(
            @NotBlank @Size(max = 128) String oldPassword,
            @NotBlank @Size(max = 128) String newPassword) {
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@Valid @RequestBody LoginRequest req) {
        var r = authentication.login(req.username(), req.password(), req.tenantCode());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", r.token());
        data.put("tokenType", "Bearer");
        data.put("username", r.username());
        data.put("displayName", r.displayName());
        data.put("roles", r.roles());
        data.put("permissions", r.permissions());
        // FR-IAM-06：前端据此直接跳改密页，而不是让用户点开业务页面再吃一串 403
        data.put("mustChangePassword", r.mustChangePassword());
        data.put("passwordExpiresAt", r.passwordExpiresAt());
        data.put("passwordRules", r.passwordRules());
        // B1：当前租户 + 成员关系（切换器只在 memberships 数量 > 1 时出现）
        data.put("tenantId", r.tenantId());
        data.put("tenantCode", r.tenantCode());
        data.put("tenantName", r.tenantName());
        List<Map<String, Object>> tenantItems = new ArrayList<>();
        for (TenantRef t : r.tenants()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", t.id());
            item.put("code", t.code());
            item.put("name", t.name());
            item.put("enabled", t.enabled());
            tenantItems.add(item);
        }
        data.put("tenants", tenantItems);
        return ResponseEntity.ok(ApiResponses.ok(data));
    }

    /**
     * 切换本次会话的行动租户（B1 · FR-TNT-07）。
     *
     * <p><b>为什么必须 POST 且必须重新签发 token</b>：租户决定服务端每次请求的
     * 数据边界（JWT 的 {@code tid}）。前端只改显示会造成"界面是 B、读写是 A"。
     * 本接口校验成员关系与租户启用状态后返回一个**新 token**，前端必须整体覆盖会话。
     *
     * <p>幂等：状态迁移型（只换出一个新 token，无持久数量副作用），
     * **不消费 X-Request-Id**；重复切换只会产生多个彼此独立、tid 相同的 token。
     */
    @PostMapping("/switch-tenant")
    public ResponseEntity<Map<String, Object>> switchTenant(
            @Valid @RequestBody SwitchTenantRequest req) {
        long userId = actors.currentUserId().orElseThrow(() -> new BusinessException(
                ErrorCode.IAM_UNAUTHENTICATED, "未登录或登录已过期，请重新登录"));
        var r = authentication.switchTenant(userId, req.tenantCode());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", r.token());
        data.put("tokenType", "Bearer");
        data.put("username", r.username());
        data.put("displayName", r.displayName());
        data.put("roles", r.roles());
        data.put("permissions", r.permissions());
        data.put("mustChangePassword", r.mustChangePassword());
        data.put("passwordExpiresAt", r.passwordExpiresAt());
        data.put("passwordRules", r.passwordRules());
        data.put("tenantId", r.tenantId());
        data.put("tenantCode", r.tenantCode());
        data.put("tenantName", r.tenantName());
        List<Map<String, Object>> tenantItems = new ArrayList<>();
        for (TenantRef t : r.tenants()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", t.id());
            item.put("code", t.code());
            item.put("name", t.name());
            item.put("enabled", t.enabled());
            tenantItems.add(item);
        }
        data.put("tenants", tenantItems);
        return ResponseEntity.ok(ApiResponses.ok(data));
    }

    /**
     * 当前登录用户信息（用于前端刷新页面后恢复权限菜单与当前租户）。
     *
     * <p>无状态 JWT 的"登出"就是客户端丢弃 token，因此这里不提供 /logout：
     * 提供一个假的登出接口反而会让人误以为服务端吊销了 token。
     * 若将来需要真正的强制下线，应引入 token 黑名单或短 token + refresh token 机制。
     *
     * <p>V2-6 追加 {@code mustChangePassword / passwordExpiresAt / passwordRules}：
     * 让"过期状态"在刷新页面后仍然可见。
     * B1 追加 {@code tenantId / tenantCode / tenantName / tenants}：刷新页面后
     * 顶栏仍显示正确的租户，切换器仍只在多成员时出现。
     */
    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        String token = authorization != null && authorization.startsWith(JwtAuthenticationFilter.BEARER_PREFIX)
                ? authorization.substring(JwtAuthenticationFilter.BEARER_PREFIX.length()).trim()
                : null;
        AppUser user = authentication.resolve(token)
                .orElseThrow(() -> new BusinessException(ErrorCode.IAM_UNAUTHENTICATED,
                        "未登录或登录已过期，请重新登录"));
        AuthenticationService.SessionTenant tenancy = authentication.tenancyOf(user);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("username", user.username());
        data.put("displayName", user.displayName());
        data.put("roles", user.roles());
        data.put("permissions", user.permissions().stream().sorted().toList());
        data.put("mustChangePassword",
                authentication.mustChangePasswordReason(user.id()).isPresent());
        data.put("passwordExpiresAt",
                user.passwordExpiresAt() == null ? null : user.passwordExpiresAt().toString());
        data.put("passwordRules", authentication.passwordPolicy().describe());
        data.put("tenantId", tenancy.tenantId());
        data.put("tenantCode", tenancy.tenantCode());
        data.put("tenantName", tenancy.tenantName());
        List<Map<String, Object>> tenantItems = new ArrayList<>();
        for (TenantRef t : tenancy.tenants()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", t.id());
            item.put("code", t.code());
            item.put("name", t.name());
            item.put("enabled", t.enabled());
            tenantItems.add(item);
        }
        data.put("tenants", tenantItems);
        return ResponseEntity.ok(ApiResponses.ok(data));
    }

    /**
     * 修改自己的密码（FR-IAM-06）。
     *
     * <p><b>不加 {@code @RequirePermission}</b>：这是"任何人对自己账号"的操作，
     * 与业务权限码无关。要求一个业务权限码才能改密码，会让"刚被撤权的用户"
     * 无法更新自己的密码 —— 而他恰恰最需要改。
     *
     * <p>改密成功后旧 token 仍然有效（JWT 无状态），但除了
     * {@code mustChangePassword} 已清零之外没有别的状态变化，
     * 因此不强制重新登录。
     */
    @PostMapping("/change-password")
    public ResponseEntity<Map<String, Object>> changePassword(
            @Valid @RequestBody ChangePasswordRequest req) {
        long userId = actors.currentUserId().orElseThrow(() -> new BusinessException(
                ErrorCode.IAM_UNAUTHENTICATED, "未登录或登录已过期，请重新登录"));
        authentication.changePassword(userId, req.oldPassword(), req.newPassword());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("changed", true);
        data.put("mustChangePassword", false);
        return ResponseEntity.ok(ApiResponses.ok(data));
    }
}
