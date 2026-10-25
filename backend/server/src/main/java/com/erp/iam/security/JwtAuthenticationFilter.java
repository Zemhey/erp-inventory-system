package com.erp.iam.security;

import com.erp.iam.domain.AppUser;
import com.erp.iam.application.AuthenticationService;
import com.erp.shared.tenant.TenantContext;
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
        Optional<AppUser> authenticated = Optional.empty();
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length()).trim();
            authenticated = authentication.resolve(token).filter(AppUser::enabled);
            authenticated.ifPresent(u -> {
                List<SimpleGrantedAuthority> authorities = new java.util.ArrayList<>();
                u.roles().forEach(r -> authorities.add(new SimpleGrantedAuthority("ROLE_" + r)));
                u.permissions().forEach(p -> authorities.add(new SimpleGrantedAuthority("PERM_" + p)));
                var auth = new UsernamePasswordAuthenticationToken(u.username(), null, authorities);
                auth.setDetails(u.id());
                SecurityContextHolder.getContext().setAuthentication(auth);
            });
        }
        // ★ V2-7 FR-TNT-04：把租户绑定到本次请求的线程上，并保证**必定**解除。
        //
        //   为什么绑定在过滤器里而不是服务各自取：仓储只拿 JdbcTemplate、不接收请求
        //   对象，因此租户只能来自线程上下文；而线程上下文的生命周期必须与请求严格
        //   同寿命 —— 早一步解除会让控制器拿不到租户，漏解除会让线程池里的下一个
        //   请求继承上一个请求的租户（最难复现、后果最严重的一类缺陷）。
        //
        //   为什么"没有租户"时**不**绑定默认租户：无 token 的请求在 SecurityConfig 的
        //   anyRequest().authenticated() 处就被 401 挡掉，走不到业务代码；若将来有人
        //   加了 permitAll 的业务路径，"未绑定"会让它抛 SYS_TENANT_CONTEXT_MISSING
        //   （500），而不是静默读写默认租户。大声失败好过静默降级。
        if (authenticated.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        long tenantId = authenticated.get().tenantId();
        // 用数组持有"受检异常"，而不是把 chain.doFilter 的异常包成 RuntimeException：
        // 包了之后 GlobalExceptionHandler 看到的是包装层，错误码与报文都会变形，
        // 而且 ServletException 的语义（容器级错误）也会丢。
        Throwable[] failure = new Throwable[1];
        TenantContext.runIn(tenantId, () -> {
            try {
                chain.doFilter(request, response);
            } catch (IOException | ServletException | RuntimeException ex) {
                failure[0] = ex;
            }
        });
        if (failure[0] instanceof IOException io) {
            throw io;
        }
        if (failure[0] instanceof ServletException se) {
            throw se;
        }
        if (failure[0] instanceof RuntimeException re) {
            throw re;
        }
    }
}
