package com.erp.shared.time;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 演示/回填用的业务时间注入过滤器（默认关闭）。
 *
 * <p>读请求头 {@code X-Demo-Biz-Time}（ISO-8601，例如 {@code 2026-03-18T14:20:00}），
 * 把当前请求线程的业务时间钉到那一刻，请求结束立即清除。
 *
 * <p><b>为什么排在 Spring Security 之后</b>：{@link Order} 用 {@link Ordered#LOWEST_PRECEDENCE}
 * ——安全过滤链本身以 {@code -100} 注册，因此本过滤器只会在<b>身份已认证</b>之后执行。
 * 匿名请求拿不到改时间的能力，这点由顺序而不是代码里的判断来保证。
 *
 * <p><b>开关</b>：{@code erp.demo.clock-override-enabled=false}（默认）时本过滤器不装，
 * 请求头被彻底忽略 —— 生产环境即便有人手动带上这个头也毫无作用。
 *
 * <p>本类只改"现在几点"，不改任何业务校验：库存不足、账期锁定、信用超额
 * 仍然会在对应服务里照常抛出。回填出来的账与真实操作产生的账完全同构。
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class DemoClockFilter extends OncePerRequestFilter {

    /** 请求头名：只在此处定义，避免 seeder 与后端各写一份拼错。 */
    public static final String HEADER = "X-Demo-Biz-Time";

    private final boolean enabled;

    public DemoClockFilter(@Value("${erp.demo.clock-override-enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !enabled;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String raw = request.getHeader(HEADER);
        LocalDateTime pinned = parse(raw);
        if (pinned != null) {
            DemoClockOverride.set(pinned);
        }
        try {
            chain.doFilter(request, response);
        } finally {
            // 同线程复用下必须清干净：留着会让下一个请求（可能是真实操作）用错时间
            DemoClockOverride.clear();
        }
    }

    /**
     * 容忍两种写法：完整时间戳，或只有日期（按当日 12:00 处理）。
     *
     * <p>只有日期时取正午是刻意的：跨时区部署时正午不会因时差滑到相邻的自然日，
     * 于是"这笔流水归哪一天"在演示数据里始终与单据 {@code biz_date} 一致。
     *
     * @return 解析成功返回时间，为空或格式不合法返回 {@code null}（不覆盖，用真实时间）
     */
    static LocalDateTime parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim();
        try {
            return LocalDateTime.parse(v);
        } catch (DateTimeParseException ignored) {
            // 落到下面的"只有日期"分支
        }
        try {
            return LocalDate.parse(v).atTime(12, 0);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
