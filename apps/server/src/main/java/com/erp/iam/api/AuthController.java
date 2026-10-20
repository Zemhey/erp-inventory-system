package com.erp.iam.api;

import com.erp.iam.application.AuthenticationService;
import com.erp.iam.domain.AppUser;
import com.erp.iam.security.JwtAuthenticationFilter;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.web.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口（契约：{@code /auth/login}，FR-IAM-01）。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthenticationService authentication;

    public AuthController(AuthenticationService authentication) {
        this.authentication = authentication;
    }

    public record LoginRequest(
            @NotBlank @Size(max = 64) String username,
            @NotBlank @Size(max = 128) String password) {
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@Valid @RequestBody LoginRequest req) {
        var r = authentication.login(req.username(), req.password());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", r.token());
        data.put("tokenType", "Bearer");
        data.put("username", r.username());
        data.put("displayName", r.displayName());
        data.put("roles", r.roles());
        data.put("permissions", r.permissions());
        return ResponseEntity.ok(ApiResponses.ok(data));
    }

    /**
     * 当前登录用户信息（用于前端刷新页面后恢复权限菜单）。
     *
     * <p>无状态 JWT 的"登出"就是客户端丢弃 token，因此这里不提供 /logout：
     * 提供一个假的登出接口反而会让人误以为服务端吊销了 token。
     * 若将来需要真正的强制下线，应引入 token 黑名单或短 token + refresh token 机制。
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
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("username", user.username());
        data.put("displayName", user.displayName());
        data.put("roles", user.roles());
        data.put("permissions", user.permissions().stream().sorted().toList());
        return ResponseEntity.ok(ApiResponses.ok(data));
    }
}
