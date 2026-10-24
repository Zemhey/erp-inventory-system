package com.erp.iam.security;

import com.erp.iam.application.SecurityActorContext;
import com.erp.iam.application.AuthenticationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.OptionalLong;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 密码过期 / 强制改密的拦截器（FR-IAM-06）。
 *
 * <p><b>★ 为什么必须"发 token 但拦业务接口"</b>：
 * 若登录时直接拒绝密码过期的用户，他就拿不到 token，也就无法调用改密接口
 * （改密接口需要登录）—— 死锁，只能找管理员重置，而重置又要求管理员操作。
 * 因此正确做法是：登录成功、签发 token、登录响应里明确
 * {@code mustChangePassword=true}，然后<b>在请求层</b>把所有业务接口拦成
 * {@code IAM_PASSWORD_EXPIRED}（403），只放行"改密"与"看自己"两个接口。
 *
 * <p><b>为什么用过滤器而不是在 {@code PermissionAspect} 里加判断</b>：
 * 切面只覆盖带 {@code @RequirePermission} 的方法，而"改密"这类接口
 * 不该要求业务权限码。漏掉任何一个无注解的接口就是一个绕过点，
 * 而过滤器按<b>路径</b>放行白名单，往返更短、更难漏。
 *
 * <p><b>白名单刻意只有三个</b>：
 * <ul>
 *   <li>{@code POST /api/v1/auth/change-password} —— 否则用户无法自救；</li>
 *   <li>{@code GET /api/v1/auth/me} —— 前端要知道"我现在是什么状态"；</li>
 *   <li>{@code POST /api/v1/auth/login} —— 登录本身（SecurityConfig 已 permitAll）。</li>
 * </ul>
 * 不把 {@code /actuator/health} 之类放进业务白名单，它们在 SecurityConfig 里就已是
 * permitAll，本过滤器只在有身份注入后才动作，因此不冲突。
 */
@Component
public class PasswordExpiryFilter extends OncePerRequestFilter {

    /** 允许在"必须改密"状态下访问的路径。 */
    private static final Set<String> ALLOWED_PATHS = Set.of(
            "/api/v1/auth/change-password",
            "/api/v1/auth/me",
            "/api/v1/auth/login");

    private final AuthenticationService authentication;
    private final SecurityActorContext actors;
    private final ObjectMapper objectMapper;

    public PasswordExpiryFilter(AuthenticationService authentication,
                                SecurityActorContext actors,
                                ObjectMapper objectMapper) {
        this.authentication = authentication;
        this.actors = actors;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (ALLOWED_PATHS.contains(path)) {
            chain.doFilter(request, response);
            return;
        }

        OptionalLong userId = actors.currentUserId();
        if (userId.isEmpty()) {
            // 没有身份：交给后面的授权规则返回 401，这里不越权代判
            chain.doFilter(request, response);
            return;
        }

        var reason = authentication.mustChangePasswordReason(userId.getAsLong());
        if (reason.isPresent()) {
            writeError(response, reason.get());
            return;
        }
        chain.doFilter(request, response);
    }

    private void writeError(HttpServletResponse res, String reason) throws IOException {
        res.setStatus(com.erp.shared.error.ErrorCode.IAM_PASSWORD_EXPIRED.httpStatus());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", com.erp.shared.error.ErrorCode.IAM_PASSWORD_EXPIRED.name());
        body.put("message", reason);
        objectMapper.writeValue(res.getWriter(), body);
    }
}
