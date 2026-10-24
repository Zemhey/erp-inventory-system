package com.erp.aps.repository;

import com.erp.aps.domain.ApprovalInstance;
import com.erp.aps.domain.ApprovalStatus;
import com.erp.shared.approval.ApprovalGate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 审批实例仓储（{@code approval_instances}）。
 *
 * <p><b>所有状态推进都带 {@code version = ?} 条件</b>（与进货/出货仓储同构）：
 * 两个人同时点"同意"时，只有一个人能推进实例状态，另一个得到 0 行更新并被翻译成
 * {@code APS_STATUS_ILLEGAL}。没有这条乐观锁，会同时产生两条"审批通过"记录、
 * 库存被放行两次。
 *
 * <p><b>本类不 join 任何单据表</b>（任务书坑 3、ArchUnit 规则）：
 * 审批只知道 {@code biz_type + biz_id + biz_ref_no + amount}。
 * 单据的金额、单号、状态由调用方<b>推</b>进来，审批不去拉。
 */
@Repository
public class ApprovalInstanceRepository {

    private static final RowMapper<ApprovalInstance> MAPPER = (rs, n) -> new ApprovalInstance(
            rs.getLong("id"),
            rs.getLong("flow_id"),
            rs.getString("biz_type"),
            rs.getLong("biz_id"),
            rs.getString("biz_ref_no"),
            rs.getLong("amount"),
            rs.getInt("current_step"),
            ApprovalStatus.valueOf(rs.getString("status")),
            rs.getLong("initiator_id"),
            rs.getInt("version"),
            List.of());   // 节点另行装载

    private static final String COLUMNS = """
            id, flow_id, biz_type, biz_id, biz_ref_no, amount, current_step, status,
            initiator_id, version
            """;

    private final com.erp.shared.tenant.TenantAwareJdbcTemplate jdbc;

    public ApprovalInstanceRepository(com.erp.shared.tenant.TenantAwareJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 建实例（状态 DRAFT）。
     *
     * <p>幂等由 {@code uq_approval_instance_request} 兜底：同一 {@code requestId}
     * 重复提交会在这里抛约束违例，由应用层 {@code DbErrors} 翻译成
     * "返回首次结果"或 {@code SYS_DUPLICATE_REQUEST}。
     */
    public long insert(long flowId, ApprovalGate.BizType bizType, long bizId, String bizRefNo,
                       long amount, long initiatorId, String requestId) {
        return com.erp.shared.tenant.TenantJdbc.insertReturningId(jdbc,
                """
                    INSERT INTO approval_instances
                        (flow_id, biz_type, biz_id, biz_ref_no, amount, current_step,
                         status, initiator_id, request_id, version)
                    VALUES (?, ?, ?, ?, ?, 1, 'DRAFT', ?, ?, 0)
                    RETURNING id
                    """,
                new Object[]{flowId, bizType.name(), bizId, bizRefNo, amount, initiatorId, requestId});
    }

    public Optional<ApprovalInstance> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM approval_instances WHERE id = ?",
                MAPPER, id).stream().findFirst();
    }

    public Optional<ApprovalInstance> findByRequestId(String requestId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM approval_instances WHERE request_id = ?",
                MAPPER, requestId).stream().findFirst();
    }

    /**
     * 某张单据当前**未结**的审批实例。
     *
     * <p>"未结"= DRAFT/PENDING/TRANSFERRED，与部分唯一索引
     * {@code uq_approval_instance_open} 的谓词逐字一致 —— 两处必须同步，
     * 否则索引挡住的与代码查到的不是同一批行。
     */
    public Optional<ApprovalInstance> findOpen(ApprovalGate.BizType bizType, long bizId) {
        return jdbc.query("SELECT " + COLUMNS + """
                  FROM approval_instances
                 WHERE biz_type = ? AND biz_id = ?
                   AND status IN ('DRAFT', 'PENDING', 'TRANSFERRED')
                 ORDER BY id DESC
                """, MAPPER, bizType.name(), bizId).stream().findFirst();
    }

    /** 某张单据**最近一次**审批实例（不限状态）—— 用于"是否曾通过"的判定。 */
    public Optional<ApprovalInstance> findLatest(ApprovalGate.BizType bizType, long bizId) {
        return jdbc.query("SELECT " + COLUMNS + """
                  FROM approval_instances
                 WHERE biz_type = ? AND biz_id = ?
                 ORDER BY id DESC
                """, MAPPER, bizType.name(), bizId).stream().findFirst();
    }

    /**
     * 状态推进（乐观锁）。
     *
     * @param expectedVersion 读出来的版本；更新 0 行 = 并发冲突
     * @return 是否推进成功
     */
    public boolean updateStatus(long id, ApprovalStatus from, ApprovalStatus to,
                                int currentStep, int expectedVersion) {
        return jdbc.update("""
                UPDATE approval_instances
                   SET status = ?, current_step = ?, version = version + 1, updated_at = now()
                 WHERE id = ? AND status = ? AND version = ?
                """, to.name(), currentStep, id, from.name(), expectedVersion) == 1;
    }

    /** 分页列出（按状态/单据类型过滤），供审批工作台使用。 */
    public com.erp.shared.web.PageResult<ApprovalInstance> page(ApprovalStatus status,
                                                                ApprovalGate.BizType bizType,
                                                                int page, int size) {
        int p = com.erp.shared.web.PageResult.normalizePage(page);
        int s = com.erp.shared.web.PageResult.normalizeSize(size);
        List<Object> args = new java.util.ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        if (status != null) {
            where.append(" AND status = ?");
            args.add(status.name());
        }
        if (bizType != null) {
            where.append(" AND biz_type = ?");
            args.add(bizType.name());
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM approval_instances" + where,
                Long.class, args.toArray());
        List<Object> pageArgs = new java.util.ArrayList<>(args);
        pageArgs.add(s);
        pageArgs.add(com.erp.shared.web.PageResult.offset(p, s));
        List<ApprovalInstance> items = jdbc.query("SELECT " + COLUMNS
                + " FROM approval_instances" + where + " ORDER BY id DESC LIMIT ? OFFSET ?",
                MAPPER, pageArgs.toArray());
        return new com.erp.shared.web.PageResult<>(items, total == null ? 0 : total, p, s);
    }

    /** 供测试与审计读取 {@code created_at}（领域记录里不带它）。 */
    public Optional<OffsetDateTime> createdAt(long id) {
        return jdbc.queryForList("SELECT created_at FROM approval_instances WHERE id = ?",
                OffsetDateTime.class, id).stream().findFirst();
    }
}
