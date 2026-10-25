package com.erp.sys.repository;

import com.erp.sys.domain.Department;
import com.erp.sys.domain.Position;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
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

    public long insertDepartment(String code, String name, Long parentId, int sortNo) {
        SimpleJdbcInsert insert = new SimpleJdbcInsert(jdbc)
                .withTableName("sys_department")
                .usingGeneratedKeyColumns("id");
        java.util.Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("parent_id", parentId);
        params.put("code", code);
        params.put("name", name);
        params.put("sort_no", sortNo);
        Number id = insert.executeAndReturnKey(params);
        return id.longValue();
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

    public long insertPosition(long departmentId, String code, String name, int level) {
        SimpleJdbcInsert insert = new SimpleJdbcInsert(jdbc)
                .withTableName("sys_position")
                .usingGeneratedKeyColumns("id");
        java.util.Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("department_id", departmentId);
        params.put("code", code);
        params.put("name", name);
        params.put("level", level);
        Number id = insert.executeAndReturnKey(params);
        return id.longValue();
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
