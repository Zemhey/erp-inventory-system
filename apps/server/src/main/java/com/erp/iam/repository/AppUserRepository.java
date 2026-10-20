package com.erp.iam.repository;

import com.erp.iam.domain.AppUser;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 账号仓储（FR-IAM-01）。
 *
 * <p>角色与权限在查用户时一并装载：一次查询解决，避免 N+1，
 * 也因为登录是高频路径且对延迟敏感。
 */
@Repository
public class AppUserRepository {

    private static final RowMapper<AppUser> USER_MAPPER = (rs, i) -> new AppUser(
            rs.getLong("id"),
            rs.getString("username"),
            rs.getString("password_hash"),
            rs.getString("display_name"),
            rs.getBoolean("enabled"),
            rs.getInt("failed_attempts"),
            rs.getObject("locked_until", java.time.OffsetDateTime.class) == null
                    ? null
                    : rs.getObject("locked_until", java.time.OffsetDateTime.class).toInstant(),
            List.of(),
            Set.of());

    private final JdbcTemplate jdbc;

    public AppUserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String username, String passwordHash, String displayName, boolean enabled) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO app_users (username, password_hash, display_name, enabled)
                    VALUES (?, ?, ?, ?)
                    """, new String[]{"id"});
            ps.setString(1, username);
            ps.setString(2, passwordHash);
            ps.setString(3, displayName);
            ps.setBoolean(4, enabled);
            return ps;
        }, keys);
        Number id = keys.getKey();
        if (id == null) {
            throw new IllegalStateException("插入账号后未取得主键：username=" + username);
        }
        return id.longValue();
    }

    public Optional<AppUser> findByUsername(String username) {
        return jdbc.query("""
                SELECT id, username, password_hash, display_name, enabled,
                       failed_attempts, locked_until
                  FROM app_users WHERE username = ?
                """, USER_MAPPER, username).stream().findFirst()
                .map(this::withRolesAndPermissions);
    }

    public Optional<AppUser> findById(long id) {
        return jdbc.query("""
                SELECT id, username, password_hash, display_name, enabled,
                       failed_attempts, locked_until
                  FROM app_users WHERE id = ?
                """, USER_MAPPER, id).stream().findFirst()
                .map(this::withRolesAndPermissions);
    }

    /** 装载角色编码与权限并集。 */
    private AppUser withRolesAndPermissions(AppUser u) {
        List<String> roles = jdbc.queryForList("""
                SELECT r.code FROM app_roles r
                  JOIN app_user_roles ur ON ur.role_id = r.id
                 WHERE ur.user_id = ? ORDER BY r.code
                """, String.class, u.id());
        Set<String> permissions = Set.copyOf(jdbc.queryForList("""
                SELECT DISTINCT rp.permission FROM app_role_permissions rp
                  JOIN app_user_roles ur ON ur.role_id = rp.role_id
                 WHERE ur.user_id = ?
                """, String.class, u.id()));
        return new AppUser(u.id(), u.username(), u.passwordHash(), u.displayName(),
                u.enabled(), u.failedAttempts(), u.lockedUntil(), roles, permissions);
    }

    public void assignRole(long userId, String roleCode) {
        jdbc.update("""
                INSERT INTO app_user_roles (user_id, role_id)
                SELECT ?, id FROM app_roles WHERE code = ?
                ON CONFLICT DO NOTHING
                """, userId, roleCode);
    }

    /**
     * 记录一次登录失败：错误计数 +1，达到阈值即写入锁定截止时间。
     *
     * <p>整个动作在一条 UPDATE 里完成，避免"读计数再写计数"的竞态 ——
     * 并发爆破时若用读-改-写，N 次失败可能只计到 1 次。
     *
     * <p><b>锁定截止时间由调用方传入，不用数据库的 {@code now()}</b>：
     * 业务时间必须来自可注入时钟（计划书 §3.5），否则测试里"快进时间"只移动了
     * 应用侧时钟，数据库写下的仍是真实时间 —— 两者相差几十年，
     * 锁立即过期或永不过期，测试与生产的语义就分家了。
     * （M1 开发中真实踩到：TC-IAM-002 用固定测试时钟时锁定从未生效。）
     *
     * @param threshold   失败多少次后锁定
     * @param lockUntil   锁定截止时间（调用方用 ClockProvider 计算）
     * @param nowDateTime 当前业务时间（用于 updated_at）
     * @return 更新后的账号（含最新计数与锁定时间）
     */
    public AppUser recordLoginFailure(long userId, int threshold,
                                      LocalDateTime lockUntil, LocalDateTime nowDateTime) {
        jdbc.update("""
                UPDATE app_users
                   SET failed_attempts = failed_attempts + 1,
                       locked_until = CASE
                           WHEN failed_attempts + 1 >= ? THEN ?
                           ELSE locked_until
                       END,
                       updated_at = ?
                 WHERE id = ?
                """, threshold, lockUntil, nowDateTime, userId);
        return findById(userId).orElseThrow(() -> new IllegalStateException(
                "记录登录失败后找不到账号：id=" + userId));
    }

    /** 登录成功：清零失败计数、解除锁定、记录最后登录时间（时间同样来自注入时钟）。 */
    public void recordLoginSuccess(long userId, LocalDateTime nowDateTime) {
        jdbc.update("""
                UPDATE app_users
                   SET failed_attempts = 0, locked_until = NULL,
                       last_login_at = ?, updated_at = ?
                 WHERE id = ?
                """, nowDateTime, nowDateTime, userId);
    }
}
