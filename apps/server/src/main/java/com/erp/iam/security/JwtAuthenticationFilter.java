package com.erp.iam.security;

import com.erp.iam.domain.AppUser;
import com.erp.iam.application.AuthenticationService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * JWT 认证过滤器（FR-IAM-01）。
 *
 * <p>职责很窄：读 {@code Authorization: Bearer <token>} → 校验 → 把身份放进
 * {@link SecurityContextHolder}。**不做授权判断** —— 授权由
 * {@link SecurityConfig} 的规则与 {@link RequirePermission} 注解负责。
 *
 * <p><b>每次请求都回库取角色/权限</b>：token 里虽然带了角色，但那只是签发时的快照。
 * 如果管理员刚撤销了某人权限，却因为旧 token 仍有效而继续放行，那就是权限漏洞。
 * 代价是每请求一次查询（主键索引，微秒级），在 ERP 这个量级完全可接受。
 *
 * <p>无效 token 不会直接打断请求，而是**不设置身份**继续往下走：
 * 这样公开接口（登录）仍可访问，受保护接口则由后续的授权规则返回 401。
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String BEARER_PREFIX = "Bearer ";

    private final AuthenticationService authentication;

    public JwtAuthenticationFilter(AuthenticationService authentication) {
        this.authentication = authentication;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length()).trim();
            Optional<AppUser> user = authentication.resolve(token);
            user.filter(AppUser::enabled).ifPresent(u -> {
                List<SimpleGrantedAuthority> authorities = new java.util.ArrayList<>();
                u.roles().forEach(r -> authorities.add(new SimpleGrantedAuthority("ROLE_" + r)));
                u.permissions().forEach(p -> authorities.add(new SimpleGrantedAuthority("PERM_" + p)));
                var auth = new UsernamePasswordAuthenticationToken(u.username(), null, authorities);
                auth.setDetails(u.id());
                SecurityContextHolder.getContext().setAuthentication(auth);
            });
        }
        chain.doFilter(request, response);
    }
}
