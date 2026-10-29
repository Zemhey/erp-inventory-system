package com.erp.sys.application;

import com.erp.sys.repository.LoginLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
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

    private static final Logger log = LoggerFactory.getLogger(LoginLogRecorder.class);

    /** 列宽见 V19 + V30。客户端可控字段一律先截断再写，别让长度约束把登录打成 500。 */
    private static final int USERNAME_MAX = 64;
    private static final int IP_MAX = 128;
    private static final int USER_AGENT_MAX = 512;
    private static final int FAIL_REASON_MAX = 128;

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
        try {
            repository.insert(
                    userId,
                    clip(username, USERNAME_MAX),
                    clip(ip, IP_MAX),
                    clip(ua, USER_AGENT_MAX),
                    success,
                    clip(failReason, FAIL_REASON_MAX));
        } catch (DataAccessException e) {
            // 登录日志是辅助记录：它写失败绝不能反过来让登录失败（V19.1 的同类事故）。
            // 只告警不上抛，认证结果由调用方决定。
            log.warn("登录日志写入失败，不影响本次登录：user={} error={}", username, e.getMessage());
        }
    }

    private static String clip(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }

    private static String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return req.getRemoteAddr();
    }
}
