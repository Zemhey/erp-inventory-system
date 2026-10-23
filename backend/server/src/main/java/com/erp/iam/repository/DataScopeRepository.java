package com.erp.iam.repository;

import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 数据权限仓储（{@code data_scopes}，FR-IAM-04）。
 *
 * <p><b>解析顺序（★ 写死在这里，越权矩阵对着它断言）</b>：
 * <ol>
 *   <li>取<b>用户直接配置</b>的行 + 该用户所属<b>全部角色</b>配置的行；</li>
 *   <li>两类里只要有一行 {@code ALL} → 全量（显式放开覆盖收窄）；</li>
 *   <li>否则若有 {@code WAREHOUSE} 行 → 取这些仓库的并集；</li>
 *   <li>否则若有 {@code SELF} 行 → 只看本人创建；</li>
 *   <li>一行都没有 → 全量（未配置 = 不收窄，见 V14 注释①）。</li>
 * </ol>
 *
 * <p><b>为什么 ALL 优先于 WAREHOUSE</b>：这是"角色 A 给我全量、角色 B 限定一仓"
 * 时唯一不让人意外的解释 —— 全量是更宽的授权，而收窄是另一条独立授权，
 * 不该被"更严的那条"顶掉。若反过来（交集），管理员给某人加一个只读角色
 * 反而会把他原本的全量权限收窄，那是个没人能预测的行为。
 *
 * <p>注意：这条规则<b>不</b>适用于 WAREHOUSE 与 SELF 同时存在的情形 ——
 * 那时两者取交集（既这几个仓、又是我建的），见 {@code DataScope} 类注释。
 */
@Repository
public class DataScopeRepository {

    /** 主体为用户的配置行，外加该用户全部角色上的配置行。 */
    private static final String SELECT_FOR_USER = """
            SELECT scope_type, scope_value
              FROM data_scopes
             WHERE user_id = ?
             UNION ALL
            SELECT ds.scope_type, ds.scope_value
              FROM data_scopes ds
              JOIN app_user_roles ur ON ur.role_id = ds.role_id
             WHERE ur.user_id = ?
            """;

    private final JdbcTemplate jdbc;

    public DataScopeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 某用户生效的全部范围行（用户直配 + 角色继承）。 */
    public List<ScopeRow> rowsForUser(long userId) {
        return jdbc.query(SELECT_FOR_USER,
                (rs, i) -> new ScopeRow(rs.getString("scope_type"),
                        rs.getObject("scope_value", Long.class)),
                userId, userId);
    }

    /** 某用户**直接配置**的行（不含角色继承）——覆盖式写入的对象。 */
    public List<ScopeRow> directRowsForUser(long userId) {
        return jdbc.query("""
                SELECT scope_type, scope_value FROM data_scopes WHERE user_id = ? ORDER BY id
                """, (rs, i) -> new ScopeRow(rs.getString("scope_type"),
                rs.getObject("scope_value", Long.class)), userId);
    }

    /** 某用户通过角色继承来的行（供界面解释"这个人要改哪个角色"）。 */
    public List<ScopeRow> inheritedRowsForUser(long userId) {
        return jdbc.query("""
                SELECT ds.scope_type, ds.scope_value
                  FROM data_scopes ds
                  JOIN app_user_roles ur ON ur.role_id = ds.role_id
                 WHERE ur.user_id = ?
                 ORDER BY ds.id
                """, (rs, i) -> new ScopeRow(rs.getString("scope_type"),
                rs.getObject("scope_value", Long.class)), userId);
    }

    /** 某角色配了哪些范围行（供角色管理界面展示）。 */
    public List<ScopeRow> rowsForRole(long roleId) {
        return jdbc.query("""
                SELECT scope_type, scope_value FROM data_scopes WHERE role_id = ?
                """, (rs, i) -> new ScopeRow(rs.getString("scope_type"),
                rs.getObject("scope_value", Long.class)), roleId);
    }

    /** 给用户加一条范围（幂等：同主体同类型同值不重复插）。 */
    public void addUserScope(long userId, String scopeType, Long scopeValue) {
        jdbc.update("""
                INSERT INTO data_scopes (subject_type, user_id, scope_type, scope_value)
                VALUES ('USER', ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, userId, scopeType, scopeValue);
    }

    /** 给角色加一条范围。 */
    public void addRoleScope(long roleId, String scopeType, Long scopeValue) {
        jdbc.update("""
                INSERT INTO data_scopes (subject_type, role_id, scope_type, scope_value)
                VALUES ('ROLE', ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, roleId, scopeType, scopeValue);
    }

    /** 清空某用户的全部范围配置（"改配置"= 先删后插，避免旧值残留）。 */
    public int clearUserScopes(long userId) {
        return jdbc.update("DELETE FROM data_scopes WHERE user_id = ?", userId);
    }

    /** 清空某角色的全部范围配置。 */
    public int clearRoleScopes(long roleId) {
        return jdbc.update("DELETE FROM data_scopes WHERE role_id = ?", roleId);
    }

    /**
     * 一条范围配置。
     *
     * @param scopeType  {@code ALL / WAREHOUSE / SELF}
     * @param scopeValue {@code WAREHOUSE} 时为仓库 id，其余为 null
     */
    public record ScopeRow(String scopeType, Long scopeValue) {
    }

    /** 便于测试断言：某用户最终的仓库集合。 */
    public List<Long> warehouseIdsForUser(long userId) {
        List<Long> out = new ArrayList<>();
        for (ScopeRow row : rowsForUser(userId)) {
            if ("WAREHOUSE".equals(row.scopeType()) && row.scopeValue() != null) {
                out.add(row.scopeValue());
            }
        }
        return out;
    }
}
