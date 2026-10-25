package com.erp.sys.repository;

import com.erp.sys.domain.SystemConfig;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Repository;

/** 系统参数仓储（FR-V3-24）。只读为主，附带按 id 更新值。 */
@Repository
public class SystemConfigRepository {

    private static final RowMapper<SystemConfig> MAPPER = (rs, i) -> new SystemConfig(
            rs.getLong("id"),
            rs.getString("config_group"),
            rs.getString("config_key"),
            rs.getString("config_value"),
            rs.getString("value_type"),
            rs.getString("remark"),
            rs.getObject("created_at", java.time.OffsetDateTime.class),
            rs.getObject("updated_at", java.time.OffsetDateTime.class));

    private final JdbcTemplate jdbc;

    public SystemConfigRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<SystemConfig> listByGroup(String group) {
        String sql = """
                SELECT id, config_group, config_key, config_value, value_type, remark,
                       created_at, updated_at
                  FROM sys_config
                """ + (group == null || group.isBlank() ? "" : " WHERE config_group = ? ")
                + " ORDER BY config_group, config_key";
        return group == null || group.isBlank()
                ? jdbc.query(sql, MAPPER)
                : jdbc.query(sql, MAPPER, group);
    }

    public boolean keyExistsInGroup(String group, String key) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_config WHERE config_group = ? AND config_key = ?",
                Long.class, group, key);
        return n != null && n > 0;
    }

    public long insert(String group, String key, String value, String type, String remark) {
        SimpleJdbcInsert insert = new SimpleJdbcInsert(jdbc)
                .withTableName("sys_config")
                .usingGeneratedKeyColumns("id");
        java.util.Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("config_group", group);
        params.put("config_key", key);
        params.put("config_value", value);
        params.put("value_type", type);
        params.put("remark", remark);
        Number id = insert.executeAndReturnKey(params);
        return id.longValue();
    }

    public void updateValue(long id, String value) {
        jdbc.update(
                "UPDATE sys_config SET config_value = ?, updated_at = now() WHERE id = ?",
                value, id);
    }
}
