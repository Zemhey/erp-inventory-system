package com.erp.sys.repository;

import com.erp.sys.domain.AuditAction;
import com.erp.sys.domain.AuditLog;
import com.erp.sys.domain.AuditQueryFilter;
import com.erp.sys.domain.AuditResult;
import com.erp.shared.web.PageResult;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 审计查询仓储（FR-SYS-07，<b>只读</b>）。
 *
 * <p>与 {@link AuditLogRepository} 分开的理由：审计查询是本系统里唯一
 * "可以随便给很多人开权限"的读接口，把读方法放进只写仓储里，
 * 会让"这个类能不能改审计"变成需要逐方法看的判断题。
 *
 * <p>SQL 全部走参数绑定，不做字符串拼接 —— 审计查询的过滤条件直接来自
 * 前端输入（actorId / bizRefNo / 时间范围），拼接就是 SQL 注入。
 */
@Repository
public class AuditLogQueryRepository {

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

    public AuditLogQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 查询条件（FR-SYS-07 要求"按人 / 单据 / 时间 / 操作类型"四个维度都能查）。
     *
     * <p>条件对象定义在 {@code com.erp.sys.domain.AuditQueryFilter} 而不是这里：
     * Controller 需要构造它，而分层规则禁止 api 依赖 repository。
     */

    public PageResult<AuditLog> page(AuditQueryFilter filter, int page, int size) {
        AuditQueryFilter f = filter == null ? AuditQueryFilter.none() : filter;
        int p = PageResult.normalizePage(page);
        int s = PageResult.normalizeSize(size);

        List<Object> args = new ArrayList<>();
        String where = where(f, args);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs" + where,
                Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(s);
        pageArgs.add(PageResult.offset(p, s));
        List<AuditLog> items = jdbc.query("""
                SELECT id, actor_id, actor_name, action, resource_type, resource_id, biz_ref_no,
                       before_json::text AS before_json, after_json::text AS after_json,
                       trace_id, ip, occurred_at, result, error_code
                  FROM audit_logs
                """ + where + " ORDER BY occurred_at DESC, id DESC LIMIT ? OFFSET ?",
                MAPPER, pageArgs.toArray());
        return new PageResult<>(items, total == null ? 0 : total, p, s);
    }

    public Optional<AuditLog> findById(long id) {
        return jdbc.query("""
                SELECT id, actor_id, actor_name, action, resource_type, resource_id, biz_ref_no,
                       before_json::text AS before_json, after_json::text AS after_json,
                       trace_id, ip, occurred_at, result, error_code
                  FROM audit_logs WHERE id = ?
                """, MAPPER, id).stream().findFirst();
    }

    /** 某条业务单据的全部审计轨迹（按时间正序：读起来像"这张单的经历"）。 */
    public List<AuditLog> findByBizRefNo(String bizRefNo) {
        return jdbc.query("""
                SELECT id, actor_id, actor_name, action, resource_type, resource_id, biz_ref_no,
                       before_json::text AS before_json, after_json::text AS after_json,
                       trace_id, ip, occurred_at, result, error_code
                  FROM audit_logs WHERE biz_ref_no = ? ORDER BY occurred_at ASC, id ASC
                """, MAPPER, bizRefNo);
    }

    /** 按 traceId 取本次请求产生的全部审计（traceId 贯穿的落库侧证据）。 */
    public List<AuditLog> findByTraceId(String traceId) {
        return jdbc.query("""
                SELECT id, actor_id, actor_name, action, resource_type, resource_id, biz_ref_no,
                       before_json::text AS before_json, after_json::text AS after_json,
                       trace_id, ip, occurred_at, result, error_code
                  FROM audit_logs WHERE trace_id = ? ORDER BY id ASC
                """, MAPPER, traceId);
    }

    private static String where(AuditQueryFilter f, List<Object> args) {
        StringBuilder sb = new StringBuilder(" WHERE 1 = 1");
        if (f.actorId() != null) {
            sb.append(" AND actor_id = ?");
            args.add(f.actorId());
        }
        if (f.actorName() != null && !f.actorName().isBlank()) {
            sb.append(" AND actor_name = ?");
            args.add(f.actorName().trim());
        }
        if (f.action() != null) {
            sb.append(" AND action = ?");
            args.add(f.action().name());
        }
        if (f.resourceType() != null && !f.resourceType().isBlank()) {
            sb.append(" AND resource_type = ?");
            args.add(f.resourceType().trim());
        }
        if (f.resourceId() != null && !f.resourceId().isBlank()) {
            sb.append(" AND resource_id = ?");
            args.add(f.resourceId().trim());
        }
        if (f.bizRefNo() != null && !f.bizRefNo().isBlank()) {
            sb.append(" AND biz_ref_no = ?");
            args.add(f.bizRefNo().trim());
        }
        if (f.result() != null) {
            sb.append(" AND result = ?");
            args.add(f.result().name());
        }
        if (f.from() != null) {
            sb.append(" AND occurred_at >= ?");
            args.add(Timestamp.from(f.from().toInstant()));
        }
        if (f.to() != null) {
            sb.append(" AND occurred_at < ?");
            args.add(Timestamp.from(f.to().toInstant()));
        }
        return sb.toString();
    }
}
