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
 * 账号仓储（FR-IAM-01 / FR-IAM-05 / FR-IAM-06）。
 *
 * <p>角色与权限在查用户时一并装载：一次查询解决，避免 N+1，
 * 也因为登录是高频路径且对延迟敏感。
 */
@Repository
public class AppUserRepository {

    /** 账号列表/详情共用的列清单。 */
    private static final String COLUMNS = """
            id, username, password_hash, display_name, enabled,
            failed_attempts, locked_until, password_expires_at, must_change_password
            """;

    private static final RowMapper<AppUser> USER_MAPPER = (rs, i) -> new AppUser(
            rs.getLong("id"),
            rs.getString("username"),
            rs.getString("password_hash"),
            rs.getString("display_name"),
            rs.getBoolean("enabled"),
            rs.getInt("failed_attempts"),
            toInstant(rs.getObject("locked_until", java.time.OffsetDateTime.class)),
            toInstant(rs.getObject("password_expires_at", java.time.OffsetDateTime.class)),
            rs.getBoolean("must_change_password"),
            List.of(),
            Set.of());

    /** TIMESTAMPTZ → Instant；null 保持 null。 */
    private static Instant toInstant(java.time.OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

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
        return jdbc.query("SELECT " + COLUMNS + " FROM app_users WHERE username = ?",
                USER_MAPPER, username).stream().findFirst()
                .map(this::withRolesAndPermissions);
    }

    public Optional<AppUser> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM app_users WHERE id = ?",
                USER_MAPPER, id).stream().findFirst()
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
                u.enabled(), u.failedAttempts(), u.lockedUntil(),
                u.passwordExpiresAt(), u.mustChangePassword(), roles, permissions);
    }

    public void assignRole(long userId, String roleCode) {
        jdbc.update("""
                INSERT INTO app_user_roles (user_id, role_id)
                SELECT ?, id FROM app_roles WHERE code = ?
                ON CONFLICT DO NOTHING
                """, userId, roleCode);
    }

    /** 直接按角色 id 关联（供角色管理接口用，角色必须已存在）。 */
    public void assignRoleById(long userId, long roleId) {
        jdbc.update("""
                INSERT INTO app_user_roles (user_id, role_id) VALUES (?, ?)
                ON CONFLICT DO NOTHING
                """, userId, roleId);
    }

    /** 清空某用户的角色（"改角色"= 先删后插，而不是增量加，免得旧角色残留）。 */
    public int clearRoles(long userId) {
        return jdbc.update("DELETE FROM app_user_roles WHERE user_id = ?", userId);
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

    // ─────────────────────────── FR-IAM-05 用户管理 ───────────────────────────

    /** 用户列表（可选关键字，匹配用户名/显示名）。 */
    public com.erp.shared.web.PageResult<AppUser> page(String keyword, int page, int size) {
        int p = com.erp.shared.web.PageResult.normalizePage(page);
        int s = com.erp.shared.web.PageResult.normalizeSize(size);
        List<Object> args = new java.util.ArrayList<>();
        String where = "";
        if (keyword != null && !keyword.isBlank()) {
            where = " WHERE username ILIKE ? OR display_name ILIKE ?";
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM app_users" + where,
                Long.class, args.toArray());
        List<Object> pageArgs = new java.util.ArrayList<>(args);
        pageArgs.add(s);
        pageArgs.add(com.erp.shared.web.PageResult.offset(p, s));
        List<AppUser> items = jdbc.query("SELECT " + COLUMNS + " FROM app_users" + where
                + " ORDER BY id LIMIT ? OFFSET ?", USER_MAPPER, pageArgs.toArray())
                .stream().map(this::withRolesAndPermissions).toList();
        return new com.erp.shared.web.PageResult<>(items, total == null ? 0 : total, p, s);
    }

    /**
     * 改密码（同时清零失败计数、解除锁定、更新过期时刻与强制改密标记）。
     *
     * <p>"改密成功即解锁"是有意的：用户被锁定的常见原因就是忘了密码，
     * 而他刚刚证明了自己能通过管理员重置拿到新密码。让锁继续挂着
     * 只会逼运维去手工 UPDATE。
     */
    public boolean updatePassword(long userId, String passwordHash, Instant expiresAt,
                                  boolean mustChange) {
        return jdbc.update("""
                UPDATE app_users
                   SET password_hash = ?, password_expires_at = ?, must_change_password = ?,
                       failed_attempts = 0, locked_until = NULL, updated_at = now()
                 WHERE id = ?
                """, passwordHash,
                expiresAt == null ? null : expiresAt.atZone(java.time.ZoneId.systemDefault())
                        .toOffsetDateTime(),
                mustChange, userId) == 1;
    }

    /** 启用/停用账号。 */
    public boolean setEnabled(long userId, boolean enabled) {
        return jdbc.update("""
                UPDATE app_users SET enabled = ?, updated_at = now() WHERE id = ?
                """, enabled, userId) == 1;
    }

    /** 管理员重置密码：置强制改密标记，让用户下次登录必须自己改。 */
    public boolean resetPassword(long userId, String passwordHash, Instant expiresAt) {
        return updatePassword(userId, passwordHash, expiresAt, true);
    }

    /** 累计失败次数（测试断言"失败计数真的落库了"用）。 */
    public int failedAttempts(long userId) {
        Integer v = jdbc.queryForObject(
                "SELECT failed_attempts FROM app_users WHERE id = ?", Integer.class, userId);
        return v == null ? 0 : v;
    }
}
