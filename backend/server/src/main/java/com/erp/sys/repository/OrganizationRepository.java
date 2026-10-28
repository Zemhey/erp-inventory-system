package com.erp.sys.repository;

import com.erp.sys.domain.Department;
import com.erp.sys.domain.Position;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 组织架构仓储（FR-V3-23）：部门树 + 岗位。只读为主，附带基础写入口。
 *
 * <p>部门树的"树形"由应用层在 Java 里组装（一次取出整租户的部门再建树），
 * 不在 SQL 里做递归 CTE —— 部门量级很小，Java 组装更简单、也更好测。
 */
@Repository
public class OrganizationRepository {

    private static final RowMapper<Department> DEPT_MAPPER = (rs, i) -> new Department(
            rs.getLong("id"),
            rs.getObject("parent_id", Long.class),
            rs.getString("code"),
            rs.getString("name"),
            rs.getInt("sort_no"),
            rs.getObject("leader_user_id", Long.class),
            rs.getBoolean("enabled"),
            rs.getObject("created_at", java.time.OffsetDateTime.class),
            rs.getObject("updated_at", java.time.OffsetDateTime.class));

    private static final RowMapper<Position> POS_MAPPER = (rs, i) -> new Position(
            rs.getLong("id"),
            rs.getLong("department_id"),
            rs.getString("code"),
            rs.getString("name"),
            rs.getInt("level"),
            rs.getBoolean("enabled"),
            rs.getObject("created_at", java.time.OffsetDateTime.class),
            rs.getObject("updated_at", java.time.OffsetDateTime.class));

    private final JdbcTemplate jdbc;

    public OrganizationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ─────────────────────────── 部门 ───────────────────────────

    public List<Department> listDepartments(boolean onlyEnabled) {
        String sql = """
                SELECT id, parent_id, code, name, sort_no, leader_user_id, enabled,
                       created_at, updated_at
                  FROM sys_department
                """ + (onlyEnabled ? " WHERE enabled = TRUE" : "")
                + " ORDER BY sort_no, code";
        return jdbc.query(sql, DEPT_MAPPER);
    }

    public Optional<Department> findDepartment(long id) {
        return jdbc.query(
                        """
                                SELECT id, parent_id, code, name, sort_no, leader_user_id, enabled,
                                       created_at, updated_at
                                  FROM sys_department WHERE id = ?
                                """,
                        DEPT_MAPPER, id)
                .stream().findFirst();
    }

    public boolean departmentCodeExists(String code) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_department WHERE code = ?", Long.class, code);
        return n != null && n > 0;
    }

    public boolean hasChildDepartments(long id) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_department WHERE parent_id = ?", Long.class, id);
        return n != null && n > 0;
    }

    public boolean departmentInUse(long id) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM app_users WHERE department_id = ?", Long.class, id);
        return n != null && n > 0;
    }

    /**
     * 建部门。
     *
     * <p><b>为什么不用 {@code SimpleJdbcInsert}</b>：它内部走
     * {@code update(PreparedStatementCreator, KeyHolder)} 重载，拿不到 SQL 文本，
     * 而租户守卫（{@link com.erp.shared.tenant.TenantAwareJdbcTemplate}）只认得
     * "带文本"的调用 —— 于是任何一次建部门都直接 500
     * （{@code SYS_TENANT_CONTEXT_MISSING}），而且这个错误在菜单里点不出来：
     * 组织架构页在此之前只有"看"，没有"建"。
     * 统一走 {@code TenantJdbc.insertReturningId}：有 SQL 文本、能注入 tenant_id。
     */
    public long insertDepartment(String code, String name, Long parentId, int sortNo) {
        return com.erp.shared.tenant.TenantJdbc.insertReturningId(jdbc,
                """
                    INSERT INTO sys_department (parent_id, code, name, sort_no, enabled)
                    VALUES (?, ?, ?, ?, true)
                    RETURNING id
                    """,
                new Object[]{parentId, code, name, sortNo});
    }

    public void updateDepartment(long id, String name, Long parentId, int sortNo, boolean enabled) {
        jdbc.update(
                """
                        UPDATE sys_department
                           SET name = ?, parent_id = ?, sort_no = ?, enabled = ?,
                               updated_at = now()
                         WHERE id = ?
                        """,
                name, parentId, sortNo, enabled, id);
    }

    public void deleteDepartment(long id) {
        jdbc.update("DELETE FROM sys_department WHERE id = ?", id);
    }

    // ─────────────────────────── 岗位 ───────────────────────────

    public List<Position> listPositions(Long departmentId) {
        if (departmentId == null) {
            return jdbc.query(
                    """
                            SELECT id, department_id, code, name, level, enabled,
                                   created_at, updated_at
                              FROM sys_position ORDER BY level, code
                            """,
                    POS_MAPPER);
        }
        return jdbc.query(
                """
                        SELECT id, department_id, code, name, level, enabled,
                               created_at, updated_at
                          FROM sys_position WHERE department_id = ? ORDER BY level, code
                        """,
                POS_MAPPER, departmentId);
    }

    public boolean positionCodeExists(String code) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_position WHERE code = ?", Long.class, code);
        return n != null && n > 0;
    }

    public boolean positionInUse(long id) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM app_users WHERE position_id = ?", Long.class, id);
        return n != null && n > 0;
    }

    /** 建岗位：同上，必须走带 SQL 文本的插入，否则租户谓词注入不进去。 */
    public long insertPosition(long departmentId, String code, String name, int level) {
        return com.erp.shared.tenant.TenantJdbc.insertReturningId(jdbc,
                """
                    INSERT INTO sys_position (department_id, code, name, level, enabled)
                    VALUES (?, ?, ?, ?, true)
                    RETURNING id
                    """,
                new Object[]{departmentId, code, name, level});
    }

    public void updatePosition(long id, String name, int level, boolean enabled) {
        jdbc.update(
                """
                        UPDATE sys_position
                           SET name = ?, level = ?, enabled = ?, updated_at = now()
                         WHERE id = ?
                        """,
                name, level, enabled, id);
    }

    public void deletePosition(long id) {
        jdbc.update("DELETE FROM sys_position WHERE id = ?", id);
    }
}
