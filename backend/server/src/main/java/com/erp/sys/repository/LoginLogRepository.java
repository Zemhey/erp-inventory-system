package com.erp.sys.repository;

import com.erp.sys.domain.LoginLog;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Repository;

/** 登录日志仓储（FR-V3-29）。只追加（insert）与只读查询，无改删方法。 */
@Repository
public class LoginLogRepository {

    private static final RowMapper<LoginLog> MAPPER = (rs, i) -> new LoginLog(
            rs.getLong("id"),
            rs.getObject("user_id", Long.class),
            rs.getString("username"),
            rs.getString("ip"),
            rs.getString("user_agent"),
            rs.getObject("login_at", java.time.OffsetDateTime.class),
            rs.getBoolean("success"),
            rs.getString("fail_reason"));

    private final JdbcTemplate jdbc;

    public LoginLogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long userId, String username, String ip, String userAgent,
                       boolean success, String failReason) {
        SimpleJdbcInsert insert = new SimpleJdbcInsert(jdbc)
                .withTableName("sys_login_log")
                .usingGeneratedKeyColumns("id");
        java.util.Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("user_id", userId);
        params.put("username", username);
        params.put("ip", ip);
        params.put("user_agent", userAgent);
        params.put("success", success);
        params.put("fail_reason", failReason);
        insert.execute(params);
    }

    public List<LoginLog> list(int limit) {
        return jdbc.query(
                """
                        SELECT id, user_id, username, ip, user_agent, login_at, success, fail_reason
                          FROM sys_login_log
                         ORDER BY login_at DESC
                         LIMIT ?
                        """,
                MAPPER, limit);
    }

    /**
     * 在线用户（FR-V3-29）：近 30 分钟内成功登录的去重用户。
     * 用 SQL now() 计算时间窗，避免引入 Java 业务时间（读查询不依赖 ClockProvider）。
     */
    public List<LoginLog> recentOnline(int minutes) {
        return jdbc.query(
                """
                        SELECT DISTINCT ON (user_id) id, user_id, username, ip, user_agent,
                               login_at, success, fail_reason
                          FROM sys_login_log
                         WHERE success = TRUE
                           AND user_id IS NOT NULL
                           AND login_at >= now() - (?) * INTERVAL '1 minute'
                         ORDER BY user_id, login_at DESC
                        """,
                MAPPER, minutes);
    }
}
