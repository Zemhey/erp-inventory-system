package com.erp.sys.repository;

import com.erp.sys.domain.NumberRule;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** 单号规则仓储（FR-V3-25）。 */
@Repository
public class NumberRuleRepository {

    private static final RowMapper<NumberRule> MAPPER = (rs, i) -> new NumberRule(
            rs.getLong("id"),
            rs.getString("doc_type"),
            rs.getString("prefix"),
            rs.getString("date_pattern"),
            rs.getInt("seq_length"),
            rs.getString("reset_cycle"),
            rs.getBoolean("enabled"),
            rs.getObject("created_at", java.time.OffsetDateTime.class),
            rs.getObject("updated_at", java.time.OffsetDateTime.class));

    private final JdbcTemplate jdbc;

    public NumberRuleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<NumberRule> list() {
        return jdbc.query(
                """
                        SELECT id, doc_type, prefix, date_pattern, seq_length, reset_cycle,
                               enabled, created_at, updated_at
                          FROM sys_number_rule ORDER BY doc_type
                        """,
                MAPPER);
    }

    public Optional<NumberRule> findByDocType(String docType) {
        return jdbc.query(
                        """
                                SELECT id, doc_type, prefix, date_pattern, seq_length, reset_cycle,
                                       enabled, created_at, updated_at
                                  FROM sys_number_rule WHERE doc_type = ?
                                """,
                        MAPPER, docType)
                .stream().findFirst();
    }

    public void update(long id, String prefix, int seqLength, boolean enabled) {
        jdbc.update(
                """
                        UPDATE sys_number_rule
                           SET prefix = ?, seq_length = ?, enabled = ?, updated_at = now()
                         WHERE id = ?
                        """,
                prefix, seqLength, enabled, id);
    }

    /**
     * 预览用：读取某前缀某日期键的当前流水号（不消费）。
     * 与 {@code DocumentNumberGenerator} 共用 {@code document_sequences} 表，
     * 因此预览看到的"下一个号"就是真实取号会拿到的号（前提：当前日期键一致）。
     */
    public long currentSequence(String prefix, String dayKey) {
        Long v = jdbc.queryForObject(
                """
                        SELECT current_no FROM document_sequences
                         WHERE doc_prefix = ? AND day_key = ?
                        """,
                Long.class, prefix, dayKey);
        return v == null ? 0L : v;
    }
}
