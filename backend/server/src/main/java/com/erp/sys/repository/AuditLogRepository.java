package com.erp.sys.repository;

import com.erp.sys.domain.AuditAction;
import com.erp.sys.domain.AuditResult;
import com.erp.sys.domain.AuditLog;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 审计日志<b>只写</b>仓储（FR-SYS-06）。
 *
 * <p><b>★ 本类刻意只有 {@code insert} / {@code lastSnapshot} / {@code countBy} 三个方法</b>：
 * 审计是追加型数据，一旦提供 {@code update} 或 {@code delete}，
 * "谁改了什么"就可以被后改的人覆盖 —— 审计的价值归零。
 * 这条约束由两条门禁守着：
 * <ol>
 *   <li>{@code ArchitectureTest} 断言本类<b>不存在</b> update/delete/remove 方法（TC-ARCH-009）；</li>
 *   <li>数据库触发器 {@code trg_audit_logs_append_only} 让任何 UPDATE/DELETE 语句直接报错。</li>
 * </ol>
 * 两层缺一不可：代码层拦"自己人写错"，数据库层拦"绕过代码的手工 SQL"。
 *
 * <p>查询方法<b>不在本类</b>，见 {@link AuditLogQueryRepository} ——
 * 读写分离让"只写"这个语义在类名上就看得见。
 */
@Repository
public class AuditLogRepository {

    private static final RowMapper<AuditLog> MAPPER = (rs, i) -> new AuditLog(
            rs.getLong("id"),
            (Long) rs.getObject("actor_id"),
            rs.getString("actor_name"),
            AuditAction.valueOf(rs.getString("action")),
            rs.getString("resource_type"),
            rs.getString("resource_id"),
            rs.getString("biz_ref_no"),
            rs.getString("before_json"),
            rs.getString("after_json"),
            rs.getString("trace_id"),
            rs.getString("ip"),
            rs.getObject("occurred_at", OffsetDateTime.class),
            AuditResult.valueOf(rs.getString("result")),
            rs.getString("error_code"));

    private final JdbcTemplate jdbc;

    public AuditLogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条审计。返回自增主键，便于把"本次审计"与后续查询关联（测试也用得上）。
     *
     * <p>{@code tenant_id} 走数据库默认值 1，本期<b>不传</b> —— 多租户是 V2-7 的范围，
     * 这里提前写死租户号反而会在将来多租户上线时变成一处必须先清理的硬编码。
     */
    public long insert(Long actorId, String actorName, AuditAction action, String resourceType,
                       String resourceId, String bizRefNo, String beforeJson, String afterJson,
                       String traceId, String ip, OffsetDateTime occurredAt, AuditResult result,
                       String errorCode) {
        Long id = jdbc.queryForObject("""
                INSERT INTO audit_logs
                    (actor_id, actor_name, action, resource_type, resource_id, biz_ref_no,
                     before_json, after_json, trace_id, ip, occurred_at, result, error_code)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class,
                actorId, actorName, action.name(), resourceType, resourceId, bizRefNo,
                beforeJson, afterJson, traceId, ip,
                occurredAt == null ? OffsetDateTime.now() : occurredAt,
                result.name(), errorCode);
        return id == null ? -1 : id;
    }

    /**
     * 取某资源<b>上一次</b>成功审计的 after 快照，作为本次变更的 before。
     *
     * <p>为什么"上一次的 after"就是"这一次的 before"：审计是追加的，
     * 同一资源的历史快照片段天然首尾相接。这样既不需要在业务表上加影子列，
     * 也不需要每次变更前额外查一遍业务表（那会引入与业务事务的读竞争）。
     */
    public Optional<String> lastSnapshot(String resourceType, String resourceId) {
        if (resourceId == null || resourceId.isBlank()) {
            return Optional.empty();
        }
        List<String> found = jdbc.queryForList("""
                SELECT after_json::text FROM audit_logs
                 WHERE resource_type = ? AND resource_id = ? AND result = 'SUCCESS'
                   AND after_json IS NOT NULL
                 ORDER BY id DESC
                 LIMIT 1
                """, String.class, resourceType, resourceId);
        return found.stream().findFirst();
    }

    /** 审计行总数（指标端点用：审计链路本身是否还在正常工作）。 */
    public long countAll() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs", Long.class);
        return n == null ? 0 : n;
    }

    /** 某个时间段内的审计行数（自检/运维用）。 */
    public long countSince(OffsetDateTime since) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE occurred_at >= ?", Long.class,
                Timestamp.from(since.toInstant()));
        return n == null ? 0 : n;
    }
}
