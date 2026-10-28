package com.erp.sys.repository;

import com.erp.shared.tenant.TenantJdbc;
import com.erp.shared.web.PageResult;
import com.erp.sys.domain.NlAuditFilter;
import com.erp.sys.domain.NlExecution;
import com.erp.sys.domain.NlExecutionStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 自然语言操作记录仓储（FR-V4-03 · S5/S6）。
 *
 * <p><b>为什么这个类名里没有 "Audit"</b>：{@code ArchitectureTest} 有一条规则
 * "审计仓储不得存在改删方法"，它按类名含 {@code Audit} 来圈定 {@code audit_logs} 的仓储——
 * 那张表是 append-only 的，谁给它加一个 {@code updateResult()} 就等于审计可篡改。
 * 本表不适用那条规则：它有状态机（PENDING → EXECUTED），**必须**能更新。
 * 名字如实写成 {@code NlExecution}，而不是靠"改个名绕过规则"——
 * 因为规则守的是 {@code audit_logs}，不是"所有看起来像审计的表"。
 *
 * <p><b>写入走 {@link TenantJdbc}</b>：列清单里不写 {@code tenant_id}，
 * 由租户改写器在 JDBC 层追加，避免"新增一张表忘了加租户谓词"。
 *
 * <p><b>JSON 一律原样存取</b>：这里不解析，也不知道 {@code NlPlan} 长什么样。
 * 好处是 sys 模块不必依赖 nl 模块（架构规则 11：sys 只依赖 shared 与 iam.security），
 * 解析放在应用层装配视图时做。
 */
@Repository
public class NlExecutionRepository {

    private static final String COLUMNS = """
            id, request_id, actor_id, actor_name, source, command_text, intent, write_op,
            status, verdict, review_source, summary, plan_json, review_json,
            doc_type, doc_id, doc_no, result_json, error_message,
            created_at, expires_at, decided_at, decided_by, decided_by_name""";

    private static final RowMapper<NlExecution> MAPPER = (rs, i) -> map(rs);

    private final JdbcTemplate jdbc;

    public NlExecutionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ─────────────────────────── 写 ───────────────────────────

    /** 新增一行计划记录。状态由调用方判定（BLOCKED / VETOED / PENDING）。 */
    public long insert(NewExecution row) {
        String sql = """
                INSERT INTO nl_execution (request_id, actor_id, actor_name, source, command_text,
                                          intent, write_op, status, verdict, review_source,
                                          summary, plan_json, review_json, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id""";
        return TenantJdbc.insertReturningId(jdbc, sql,
                row.requestId(), row.actorId(), row.actorName(), row.source(), row.commandText(),
                row.intent(), row.write(), row.status().name(), row.verdict(), row.reviewSource(),
                row.summary(), row.planJson(), row.reviewJson(), timestamp(row.expiresAt()));
    }

    /**
     * 执行成功：回填单号与结果。
     *
     * <p>允许从 {@code EXPIRED} 转成 {@code EXECUTED}：如果回执还在有效期内、
     * 但队列的惰性结转先跑过了，事实是"它确实执行了"，那就该如实记成执行过，
     * 而不是让档案与事实相反。
     */
    public int markExecuted(String requestId, String docType, Long docId, String docNo,
                            String resultJson, long actorId, String actorName) {
        return TenantJdbc.update(jdbc, """
                UPDATE nl_execution
                   SET status = 'EXECUTED', doc_type = ?, doc_id = ?, doc_no = ?, result_json = ?,
                       decided_at = now(), decided_by = ?, decided_by_name = ?
                 WHERE request_id = ? AND status IN ('PENDING', 'EXPIRED')""",
                docType, docId, docNo, resultJson, actorId, actorName, requestId);
    }

    /** 执行报错：留痕。业务失败也是一条"AI 想干但没干成"的事实。 */
    public int markFailed(String requestId, String errorMessage) {
        return TenantJdbc.update(jdbc, """
                UPDATE nl_execution
                   SET status = 'FAILED', error_message = ?, decided_at = now()
                 WHERE request_id = ? AND status IN ('PENDING', 'EXPIRED')""",
                truncate(errorMessage, 500), requestId);
    }

    /** 人主动驳回。只有 PENDING 能驳回（其余状态返回 0，由调用方给出明确错误）。 */
    public int dismiss(long id, long actorId, String actorName) {
        return TenantJdbc.update(jdbc, """
                UPDATE nl_execution
                   SET status = 'DISMISSED', decided_at = now(), decided_by = ?, decided_by_name = ?
                 WHERE id = ? AND status = 'PENDING'""",
                actorId, actorName, id);
    }

    /**
     * 惰性结转：把已经过了截止时间还挂着 PENDING 的记录标成 EXPIRED。
     *
     * <p>不用定时任务：定时任务会引入"任务没跑就永远挂着"的运维依赖，
     * 而队列的读次数本来就低，顺手结转的成本可以忽略。
     */
    public int expireOverdue() {
        return TenantJdbc.update(jdbc, """
                UPDATE nl_execution
                   SET status = 'EXPIRED'
                 WHERE status = 'PENDING' AND expires_at IS NOT NULL AND expires_at < now()""");
    }

    // ─────────────────────────── 读 ───────────────────────────

    public Optional<NlExecution> findById(long id) {
        List<NlExecution> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM nl_execution WHERE id = ?", MAPPER, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public PageResult<NlExecution> pagePending(int page, int size) {
        return page(new NlAuditFilter(NlExecutionStatus.PENDING.name(),
                null, null, null, null, null, null), page, size);
    }

    public long countPending() {
        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM nl_execution WHERE status = 'PENDING'", Long.class);
        return total == null ? 0L : total;
    }

    /**
     * 审计分页。
     *
     * <p>所有条件都走占位符绑定：这些值直接来自前端输入（intent / source / 时间），
     * 拼接就是 SQL 注入。
     */
    public PageResult<NlExecution> page(NlAuditFilter filter, int page, int size) {
        NlAuditFilter f = filter == null ? NlAuditFilter.none() : filter;
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();

        if (f.status() != null) {
            where.append(" AND status = ?");
            args.add(f.status().toUpperCase(java.util.Locale.ROOT));
        }
        if (f.intent() != null) {
            where.append(" AND intent = ?");
            args.add(f.intent());
        }
        if (f.actorId() != null) {
            where.append(" AND actor_id = ?");
            args.add(f.actorId());
        }
        if (f.source() != null) {
            where.append(" AND source = ?");
            args.add(f.source().toUpperCase(java.util.Locale.ROOT));
        }
        if (f.write() != null) {
            where.append(" AND write_op = ?");
            args.add(f.write());
        }
        if (f.from() != null) {
            where.append(" AND created_at >= ?");
            args.add(timestamp(f.from()));
        }
        if (f.to() != null) {
            where.append(" AND created_at < ?");
            args.add(timestamp(f.to()));
        }

        Long total = jdbc.queryForObject("SELECT count(*) FROM nl_execution" + where,
                Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(PageResult.normalizeSize(size));
        pageArgs.add((Math.max(1, page) - 1) * PageResult.normalizeSize(size));
        List<NlExecution> items = jdbc.query(
                "SELECT " + COLUMNS + " FROM nl_execution" + where
                        + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                MAPPER, pageArgs.toArray());

        return new PageResult<>(items, total == null ? 0L : total, Math.max(1, page),
                PageResult.normalizeSize(size));
    }

    // ─────────────────────────── 映射 ───────────────────────────

    private static NlExecution map(ResultSet rs) throws SQLException {
        return new NlExecution(
                rs.getLong("id"),
                rs.getString("request_id"),
                rs.getLong("actor_id"),
                rs.getString("actor_name"),
                rs.getString("source"),
                rs.getString("command_text"),
                rs.getString("intent"),
                rs.getBoolean("write_op"),
                NlExecutionStatus.valueOf(rs.getString("status")),
                rs.getString("verdict"),
                rs.getString("review_source"),
                rs.getString("summary"),
                rs.getString("plan_json"),
                rs.getString("review_json"),
                rs.getString("doc_type"),
                (Long) rs.getObject("doc_id"),
                rs.getString("doc_no"),
                rs.getString("result_json"),
                rs.getString("error_message"),
                instant(rs, "created_at"),
                instant(rs, "expires_at"),
                instant(rs, "decided_at"),
                (Long) rs.getObject("decided_by"),
                rs.getString("decided_by_name"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /**
     * 新增一行的入参。
     *
     * <p>用 record 而不是 14 个位置参数：调用点写错一个顺序，
     * 就会把"复核来源"塞进"摘要"里，而编译期毫无提示。
     */
    public record NewExecution(
            String requestId,
            long actorId,
            String actorName,
            String source,
            String commandText,
            String intent,
            boolean write,
            NlExecutionStatus status,
            String verdict,
            String reviewSource,
            String summary,
            String planJson,
            String reviewJson,
            Instant expiresAt) {
    }
}
