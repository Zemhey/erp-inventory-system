package com.erp.sys.application;

import com.erp.sys.repository.LoginLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 登录日志写入器（FR-V3-29）。
 *
 * <p>放在 sys 模块：sys_login_log 由 sys 拥有，写入口也归 sys。
 * 认证成功/失败时由 iam 的 {@code AuthenticationService} 调用本组件——
 * 这是允许的单向依赖（iam 依赖 sys，sys 不反向依赖 iam 的业务能力，
 * 只引用 iam.security 的权限注解）。
 *
 * <p>写入不依赖 Java 业务时间：login_at 用数据库默认值 now()，避免引入 ClockProvider。
 * IP / User-Agent 从请求上下文取（无 Web 上下文时记 null，不报错）。
 */
@Component
public class LoginLogRecorder {

    private final LoginLogRepository repository;

    public LoginLogRecorder(LoginLogRepository repository) {
        this.repository = repository;
    }

    public void record(long userId, String username, boolean success, String failReason) {
        String ip = null;
        String ua = null;
        var attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sa) {
            HttpServletRequest req = sa.getRequest();
            ip = clientIp(req);
            ua = req.getHeader("User-Agent");
        }
        repository.insert(userId, username, ip, ua, success, failReason);
    }

    private static String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return req.getRemoteAddr();
    }
}
