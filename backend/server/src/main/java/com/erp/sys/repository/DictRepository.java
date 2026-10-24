package com.erp.sys.repository;

import com.erp.sys.domain.DictItem;
import com.erp.sys.domain.DictType;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 字典仓储（FR-SYS-07 的配套读模型）。
 *
 * <p>本期字典<b>只读</b>：种子由 Flyway 迁移写入，界面维护留到 V2-7（参数/字典期）。
 * 理由与审计查询同源 —— 一次只做一件事，且"字典可写"必须同时交付
 * 权限码、审计覆盖与界面，否则就是个半成品写入口。
 */
@Repository
public class DictRepository {

    private static final RowMapper<DictType> TYPE_MAPPER = (rs, i) -> new DictType(
            rs.getLong("id"),
            rs.getString("code"),
            rs.getString("name"),
            rs.getString("description"),
            rs.getBoolean("enabled"));

    private static final RowMapper<DictItem> ITEM_MAPPER = (rs, i) -> new DictItem(
            rs.getLong("id"),
            rs.getString("type_code"),
            rs.getString("code"),
            rs.getString("label"),
            rs.getInt("sort_no"),
            rs.getBoolean("enabled"));

    private final JdbcTemplate jdbc;

    public DictRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 全部启用的字典类型（字典页的左栏）。 */
    public List<DictType> listTypes(boolean onlyEnabled) {
        String sql = """
                SELECT id, code, name, description, enabled
                  FROM dict_types
                """ + (onlyEnabled ? " WHERE enabled = TRUE" : "") + " ORDER BY code";
        return jdbc.query(sql, TYPE_MAPPER);
    }

    public Optional<DictType> findType(String code) {
        return jdbc.query("""
                SELECT id, code, name, description, enabled
                  FROM dict_types WHERE code = ?
                """, TYPE_MAPPER, code).stream().findFirst();
    }

    /** 某类型的字典项，按 sort_no 升序（同序号按 code 兜底，保证输出稳定）。 */
    public List<DictItem> listItems(String typeCode, boolean onlyEnabled) {
        String sql = """
                SELECT i.id, t.code AS type_code, i.code, i.label, i.sort_no, i.enabled
                  FROM dict_items i
                  JOIN dict_types t ON t.id = i.type_id
                 WHERE t.code = ?
                """ + (onlyEnabled ? " AND i.enabled = TRUE" : "")
                + " ORDER BY i.sort_no, i.code";
        return jdbc.query(sql, ITEM_MAPPER, typeCode);
    }
}
