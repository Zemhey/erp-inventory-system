package com.erp.iam.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 角色仓储（{@code app_roles} / {@code app_role_permissions}，FR-IAM-05）。
 *
 * <p><b>授权即时生效的实现点</b>：这个仓储改的是
 * {@code app_role_permissions}，而 {@code PermissionAspect} 与
 * {@code JwtAuthenticationFilter} 每次请求都会回库重读权限并集 ——
 * 因此改完角色权限后，<b>同一个会话的下一个请求</b>就用新权限判定，
 * 不需要重新登录（硬验收第 4 条）。
 *
 * <p>如果哪天有人为了"性能"把权限缓存进 token 或本地缓存，
 * 这条性质就丢了，而症状是"撤了权还能继续操作" —— 权限漏洞。
 * 所以这里刻意不做任何缓存，并在 TC-IAM-0xx 里钉死这个行为。
 */
@Repository
public class RoleRepository {

    private static final RowMapper<RoleRow> MAPPER = (rs, n) -> new RoleRow(
            rs.getLong("id"),
            rs.getString("code"),
            rs.getString("name"),
            rs.getString("description"));

    private final JdbcTemplate jdbc;

    public RoleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<RoleRow> findAll() {
        return jdbc.query("SELECT id, code, name, description FROM app_roles ORDER BY id", MAPPER);
    }

    public Optional<RoleRow> findById(long id) {
        return jdbc.query("SELECT id, code, name, description FROM app_roles WHERE id = ?",
                MAPPER, id).stream().findFirst();
    }

    public Optional<RoleRow> findByCode(String code) {
        return jdbc.query("SELECT id, code, name, description FROM app_roles WHERE code = ?",
                MAPPER, code).stream().findFirst();
    }

    /** 某角色的权限码清单（升序，便于界面稳定展示与测试断言）。 */
    public List<String> permissionsOf(long roleId) {
        return jdbc.queryForList("""
                SELECT permission FROM app_role_permissions WHERE role_id = ? ORDER BY permission
                """, String.class, roleId);
    }

    /** 某角色的用户数（删除/停用前的"是否还有人在用"提示）。 */
    public int userCount(long roleId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM app_user_roles WHERE role_id = ?", Integer.class, roleId);
        return n == null ? 0 : n;
    }

    /** 建角色。编码撞 {@code uq_app_role_code} 由调用方翻译成 409。 */
    public long insert(String code, String name, String description) {
        var keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO app_roles (code, name, description) VALUES (?, ?, ?)
                    """, new String[]{"id"});
            ps.setString(1, code);
            ps.setString(2, name);
            ps.setString(3, description);
            return ps;
        }, keys);
        Number id = keys.getKey();
        if (id == null) {
            throw new IllegalStateException("插入角色后未取得主键：code=" + code);
        }
        return id.longValue();
    }

    /** 改名/改说明（编码不可改：权限码与审计都引用它）。 */
    public boolean update(long id, String name, String description) {
        return jdbc.update("""
                UPDATE app_roles SET name = ?, description = ? WHERE id = ?
                """, name, description, id) == 1;
    }

    /**
     * 覆盖式设置角色的权限码集合。
     *
     * <p>"覆盖"而不是"增量追加"：增量会让界面上的"取消勾选"无法生效
     * （用户以为删掉了，实际只是没再加）。删后插在一个事务里完成，
     * 中间态不可见。
     */
    public void replacePermissions(long roleId, java.util.Collection<String> permissions) {
        jdbc.update("DELETE FROM app_role_permissions WHERE role_id = ?", roleId);
        if (permissions == null || permissions.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("""
                INSERT INTO app_role_permissions (role_id, permission) VALUES (?, ?)
                ON CONFLICT DO NOTHING
                """, permissions.stream()
                .map(p -> new Object[]{roleId, p})
                .toList());
    }

    /** 角色行（契约 {@code Role}）。 */
    public record RoleRow(long id, String code, String name, String description) {
    }
}
