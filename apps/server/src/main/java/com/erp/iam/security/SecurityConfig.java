package com.erp.iam.security;

import com.erp.iam.application.AuthenticationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 安全配置（FR-IAM-01/02）。
 *
 * <p><b>默认拒绝</b>：{@code anyRequest().authenticated()}，只有显式列出的公开路径
 * 才匿名可访问。反过来写（默认放行、逐个加保护）几乎是所有"接口忘加鉴权"事故的成因。
 *
 * <p><b>无状态</b>：{@code STATELESS} + 关闭 CSRF。CSRF 攻击依赖浏览器自动携带 Cookie，
 * 而 JWT 放在 Authorization 头里不会自动发送，因此 CSRF 防护在此没有意义；
 * 留着它反而会逼前端在每次写请求里塞 token。
 *
 * <p><b>401 与 403 分开</b>：未登录 → 401（该去登录），已登录但无权限 → 403
 * （该找管理员要权限）。两者混在一起是前端体验与排障的常见痛点。
 */
@Configuration
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtFilter;
    private final AuthenticationService authentication;
    private final ObjectMapper objectMapper;

    public SecurityConfig(JwtAuthenticationFilter jwtFilter,
                          AuthenticationService authentication,
                          ObjectMapper objectMapper) {
        this.jwtFilter = jwtFilter;
        this.authentication = authentication;
        this.objectMapper = objectMapper;
    }

    /**
     * 注意：PasswordEncoder 声明在 {@link PasswordEncoderConfig} 里，<b>不在本类</b>。
     * 原因见那个类的说明 —— 放在这里会形成构造器循环依赖。
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // 登录本身必须匿名可访问
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                // 健康检查给探针用（不含业务数据）
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                // 结构化健康检查端点只查不变量，不允许匿名访问
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                .anyRequest().authenticated())
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((req, res, e) ->
                        writeError(res, HttpServletResponse.SC_UNAUTHORIZED, "IAM_UNAUTHENTICATED",
                                "未登录或登录已过期，请重新登录"))
                .accessDeniedHandler((req, res, e) ->
                        writeError(res, HttpServletResponse.SC_FORBIDDEN, "IAM_FORBIDDEN",
                                "当前账号无权执行该操作")))
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable);
        return http.build();
    }

    private void writeError(HttpServletResponse res, int status, String code, String message)
            throws java.io.IOException {
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        objectMapper.writeValue(res.getWriter(), body);
    }
}
