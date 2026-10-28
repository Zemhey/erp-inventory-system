package com.erp.iam.repository;

import com.erp.iam.domain.AppUser;
import com.erp.shared.tenant.TenantContext;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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
            id, tenant_id, username, password_hash, display_name, enabled,
            failed_attempts, locked_until, password_expires_at, must_change_password
            """;

    private static final RowMapper<AppUser> USER_MAPPER = (rs, i) -> new AppUser(
            rs.getLong("id"),
            rs.getLong("tenant_id"),
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
        return com.erp.shared.tenant.TenantJdbc.insertReturningId(jdbc,
                """
                    INSERT INTO app_users (username, password_hash, display_name, enabled)
                    VALUES (?, ?, ?, ?)
                    RETURNING id
                    """,
                new Object[]{username, passwordHash, displayName, enabled});
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

    /**
     * 全局按用户名读账号行（<b>不装载角色</b>），只给"还不知道/不固定租户"的路径用：
     * 登录、token 解析、切换租户。调用方随后必须在<b>目标租户</b>下调用
     * {@link #withRolesAndPermissions(AppUser)} 装载该租户的角色。
     *
     * <p><b>★ 为什么不能继续用 {@code findByUsername} + 系统旁路</b>：
     * 后者会在旁路下顺手装载角色，而角色查询也被旁路放行 —— 于是一个多租户成员
     * 会拿到<b>所有租户的角色并集</b>，在租户 A 里拿到租户 B 的权限。
     * 这是 B1 引入成员关系后才会显形的漏洞，必须把"找账号"与"装角色"拆开。
     */
    public Optional<AppUser> findByUsernameGlobal(String username) {
        return TenantContext.callAsSystem(() -> jdbc.query(
                        "SELECT " + COLUMNS + " FROM app_users WHERE username = ?",
                        USER_MAPPER, username))
                .stream().findFirst();
    }

    /** 全局按 id 读账号行（不装载角色）；账号级属性（密码过期、启用状态）与租户无关。 */
    public Optional<AppUser> findByIdGlobal(long id) {
        return TenantContext.callAsSystem(() -> jdbc.query(
                        "SELECT " + COLUMNS + " FROM app_users WHERE id = ?", USER_MAPPER, id))
                .stream().findFirst();
    }

    /**
     * 在<b>当前租户上下文</b>下装载角色编码与权限并集。
     *
     * <p>调用方必须已经绑定明确的租户：这个方法是"某人在某租户里的权限"的
     * 唯一组装点，在系统旁路下调用会把所有租户的角色混在一起。
     */
    public AppUser withRolesAndPermissions(AppUser u) {
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
        return new AppUser(u.id(), u.tenantId(), u.username(), u.passwordHash(), u.displayName(),
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
     * 员工档案列表（账号 + 人事字段 + 部门/岗位名称）。
     *
     * <p><b>为什么不直接复用 {@link #page}</b>：那个方法返回的是 {@link AppUser}，
     * 只有登录相关字段，部门/岗位只有 id —— 页面要显示"销售部 / 销售代表"就得
     * 再拉两份字典自己翻译。员工列表是人事视角的页面，一次把名字拼好更省事，
     * 也避免前端为了 30 行数据去拉全量组织架构。
     *
     * <p>角色另用一条 {@code IN} 查询补齐，而不是在 SQL 里 {@code string_agg} 子查询：
     * 一页最多 200 行，多一条简单查询远比一条嵌套聚合好读；
     * 子查询在租户改写器下也更容易出意外。
     */
    public com.erp.shared.web.PageResult<EmployeeRow> pageEmployees(
            String keyword, Long departmentId, Boolean enabled, int page, int size) {
        int p = com.erp.shared.web.PageResult.normalizePage(page);
        int s = com.erp.shared.web.PageResult.normalizeSize(size);
        List<Object> args = new java.util.ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (u.username ILIKE ? OR u.display_name ILIKE ?"
                    + " OR u.employee_no ILIKE ? OR u.phone ILIKE ?)");
            String like = "%" + keyword.trim() + "%";
            for (int i = 0; i < 4; i++) {
                args.add(like);
            }
        }
        if (departmentId != null) {
            where.append(" AND u.department_id = ?");
            args.add(departmentId);
        }
        if (enabled != null) {
            where.append(" AND u.enabled = ?");
            args.add(enabled);
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM app_users u" + where,
                Long.class, args.toArray());
        List<Object> pageArgs = new java.util.ArrayList<>(args);
        pageArgs.add(s);
        pageArgs.add(com.erp.shared.web.PageResult.offset(p, s));
        List<EmployeeRow> rows = jdbc.query("""
                SELECT u.id, u.username, u.display_name, u.employee_no, u.phone, u.email,
                       u.hire_date, u.remark, u.department_id, d.name AS department_name,
                       u.position_id, ps.name AS position_name,
                       u.enabled, u.locked_until, u.must_change_password
                  FROM app_users u
                  LEFT JOIN sys_department d ON d.id = u.department_id
                  LEFT JOIN sys_position ps ON ps.id = u.position_id
                """ + where + " ORDER BY u.id LIMIT ? OFFSET ?",
                (rs, i) -> new EmployeeRow(
                        rs.getLong("id"),
                        rs.getString("username"),
                        rs.getString("display_name"),
                        rs.getString("employee_no"),
                        rs.getString("phone"),
                        rs.getString("email"),
                        rs.getObject("hire_date", java.time.LocalDate.class),
                        rs.getString("remark"),
                        rs.getObject("department_id", Long.class),
                        rs.getString("department_name"),
                        rs.getObject("position_id", Long.class),
                        rs.getString("position_name"),
                        rs.getBoolean("enabled"),
                        toInstant(rs.getObject("locked_until", java.time.OffsetDateTime.class)),
                        rs.getBoolean("must_change_password"),
                        List.of()),
                pageArgs.toArray());
        return new com.erp.shared.web.PageResult<>(withRoles(rows), total == null ? 0 : total, p, s);
    }

    /** 给一页员工补上角色编码（一条 IN 查询，避免 N+1）。 */
    private List<EmployeeRow> withRoles(List<EmployeeRow> rows) {
        if (rows.isEmpty()) {
            return rows;
        }
        String marks = String.join(",", java.util.Collections.nCopies(rows.size(), "?"));
        Map<Long, List<String>> byUser = new java.util.HashMap<>();
        jdbc.query("""
                SELECT ur.user_id, r.code
                  FROM app_user_roles ur
                  JOIN app_roles r ON r.id = ur.role_id
                 WHERE ur.user_id IN (%s)
                 ORDER BY r.code
                """.formatted(marks),
                rs -> {
                    byUser.computeIfAbsent(rs.getLong("user_id"), k -> new java.util.ArrayList<>())
                            .add(rs.getString("code"));
                },
                rows.stream().map(EmployeeRow::id).toArray());
        return rows.stream()
                .map(r -> new EmployeeRow(r.id(), r.username(), r.displayName(), r.employeeNo(),
                        r.phone(), r.email(), r.hireDate(), r.remark(), r.departmentId(),
                        r.departmentName(), r.positionId(), r.positionName(), r.enabled(),
                        r.lockedUntil(), r.mustChangePassword(),
                        byUser.getOrDefault(r.id(), List.of())))
                .toList();
    }

    /**
     * 写员工档案。全部字段覆盖式写入（null 表示清空），
     * 与页面"一个表单提交全部字段"的语义一致。
     */
    public boolean updateProfile(long userId, String employeeNo, Long departmentId, Long positionId,
                                 String phone, String email, java.time.LocalDate hireDate,
                                 String remark) {
        return jdbc.update("""
                UPDATE app_users
                   SET employee_no = ?, department_id = ?, position_id = ?,
                       phone = ?, email = ?, hire_date = ?, remark = ?, updated_at = now()
                 WHERE id = ?
                """, employeeNo, departmentId, positionId, phone, email, hireDate, remark,
                userId) == 1;
    }

    /** 员工档案行（列表用）：账号信息 + 人事字段 + 部门/岗位名称 + 角色。 */
    public record EmployeeRow(long id, String username, String displayName, String employeeNo,
                              String phone, String email, java.time.LocalDate hireDate,
                              String remark, Long departmentId, String departmentName,
                              Long positionId, String positionName, boolean enabled,
                              Instant lockedUntil, boolean mustChangePassword,
                              List<String> roles) {
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
