package com.erp.aps.repository;

import com.erp.aps.domain.ApprovalStep;
import java.sql.Array;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 审批节点仓储（{@code approval_steps} 的定义态与实例态）。
 *
 * <p><b>本类不写 {@code aps} 之外的任何表</b>：审批只输出"可否"，
 * 不改单据状态、不碰库存、不动账目（任务书坑 2、坑 3）。
 * 这条边界由 {@code ArchitectureTest} 的「aps 不得依赖单据模块仓储」规则守着。
 *
 * <p><b>会签的原子性</b>：同意某个会签节点时，{@code approved_by} 用
 * {@code array_append} + {@code NOT (approved_by @> ARRAY[?])} 在一条 UPDATE 里完成
 * "同一人不重复计票"—— 应用层先 SELECT 判断再写，在并发双击下必然把一个人算成两票，
 * 于是"三会签"被两个人点过就通过了。这类缺陷只在真并发下出现，
 * 所以计数守卫必须落在 SQL 里（与库存防超卖同一个思路）。
 */
@Repository
public class ApprovalStepRepository {

    private static final RowMapper<ApprovalStep> MAPPER = (rs, n) -> new ApprovalStep(
            rs.getLong("id"),
            rs.getLong("flow_id"),
            rs.getObject("instance_id", Long.class),
            rs.getInt("step_no"),
            rs.getString("name"),
            rs.getString("approver_permission"),
            ApprovalStep.ApproveMode.valueOf(rs.getString("approve_mode")),
            rs.getInt("required_approvals"),
            rs.getLong("threshold_amount"),
            ApprovalStep.StepStatus.valueOf(rs.getString("status")),
            readLongArray(rs.getArray("approved_by")),
            rs.getObject("decided_at", OffsetDateTime.class),
            rs.getObject("decision_by", Long.class),
            rs.getString("remark"));

    private static final String COLUMNS = """
            id, flow_id, instance_id, step_no, name, approver_permission, approve_mode,
            required_approvals, threshold_amount, status, approved_by, decided_at,
            decision_by, remark
            """;

    private final com.erp.shared.tenant.TenantAwareJdbcTemplate jdbc;

    public ApprovalStepRepository(com.erp.shared.tenant.TenantAwareJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 读 JDBC 的 {@code java.sql.Array} → {@code List<Long>}。
     *
     * <p>PG 的 {@code int8[]} 经由 JDBC 返回的是 {@code Long[]}；
     * 空数组在这里也要返回空 list 而不是 null（下游 {@code approvedBy} 的
     * 紧凑构造器虽然会兜底，但让"没有值"和"空数组"在类型上一致更省心）。
     */
    private static List<Long> readLongArray(Array array) {
        if (array == null) {
            return List.of();
        }
        try {
            Object raw = array.getArray();
            if (raw instanceof Long[] longs) {
                return List.of(longs);
            }
            if (raw instanceof Object[] objects) {
                List<Long> out = new ArrayList<>(objects.length);
                for (Object o : objects) {
                    out.add(((Number) o).longValue());
                }
                return out;
            }
            return List.of();
        } catch (SQLException ex) {
            throw new IllegalStateException("读取 approved_by 数组失败", ex);
        }
    }

    /** 流程定义节点（{@code instance_id IS NULL}），按序号升序。 */
    public List<ApprovalStep> findDefinitionSteps(long flowId) {
        return jdbc.query("SELECT " + COLUMNS + """
                  FROM approval_steps
                 WHERE flow_id = ? AND instance_id IS NULL
                 ORDER BY step_no
                """, MAPPER, flowId);
    }

    /** 实例快照节点，按序号升序。 */
    public List<ApprovalStep> findInstanceSteps(long instanceId) {
        return jdbc.query("SELECT " + COLUMNS + """
                  FROM approval_steps
                 WHERE instance_id = ?
                 ORDER BY step_no
                """, MAPPER, instanceId);
    }

    /** 取某个实例的当前节点。 */
    public Optional<ApprovalStep> findInstanceStep(long instanceId, int stepNo) {
        return jdbc.query("SELECT " + COLUMNS + """
                  FROM approval_steps
                 WHERE instance_id = ? AND step_no = ?
                """, MAPPER, instanceId, stepNo).stream().findFirst();
    }

    /** 实例化一个节点快照（复制定义的字段，状态置 PENDING 或 SKIPPED）。 */
    public long insertInstanceStep(long flowId, long instanceId, ApprovalStep def,
                                   ApprovalStep.StepStatus status) {
        var keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
        return com.erp.shared.tenant.TenantJdbc.insertReturningId(jdbc,
                """
                    INSERT INTO approval_steps
                        (flow_id, instance_id, step_no, name, approver_permission, approve_mode,
                         required_approvals, threshold_amount, status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    RETURNING id
                    """,
                new Object[]{flowId, instanceId, def.stepNo(), def.name(), def.approverPermission(), def.approveMode().name(), def.requiredApprovals(), def.thresholdAmount(), status.name()});
    }

    /**
     * 会签计票：把 {@code approverId} 追加进 {@code approved_by}，<b>同一人只计一次</b>。
     *
     * <p>返回更新行数：0 = 该人已经同意过（不重复计票，调用方据此保持幂等）；
     * 1 = 本次计入。条件 {@code NOT (approved_by @> ARRAY[?])} 由数据库在行锁下求值，
     * 并发双击不会算成两票。
     */
    public boolean addApproval(long stepId, long approverId) {
        return jdbc.update("""
                UPDATE approval_steps
                   SET approved_by = array_append(approved_by, ?),
                       updated_at  = now()
                 WHERE id = ? AND NOT (approved_by @> ARRAY[?]::BIGINT[])
                """, approverId, stepId, approverId) == 1;
    }

    /** 节点通过。 */
    public boolean markApproved(long stepId, long decisionBy, OffsetDateTime decidedAt,
                                String remark) {
        return jdbc.update("""
                UPDATE approval_steps
                   SET status = 'APPROVED', decision_by = ?, decided_at = ?,
                       remark = ?, updated_at = now()
                 WHERE id = ? AND status = 'PENDING'
                """, decisionBy, decidedAt, remark, stepId) == 1;
    }

    /** 节点驳回。 */
    public boolean markRejected(long stepId, long decisionBy, OffsetDateTime decidedAt,
                                String remark) {
        return jdbc.update("""
                UPDATE approval_steps
                   SET status = 'REJECTED', decision_by = ?, decided_at = ?,
                       remark = ?, updated_at = now()
                 WHERE id = ? AND status = 'PENDING'
                """, decisionBy, decidedAt, remark, stepId) == 1;
    }

    /**
     * 跳过某个节点<b>之后</b>的所有待处理节点（转办专用）。
     *
     * <p>与 {@link #skipPending} 的区别：那个用于"实例提前终止"，会把当前节点也标掉；
     * 转办时当前节点还要交给别人，必须留在 PENDING。
     */
    public int skipPendingAfter(long instanceId, int stepNo) {
        return jdbc.update("""
                UPDATE approval_steps
                   SET status = 'SKIPPED', updated_at = now()
                 WHERE instance_id = ? AND step_no > ? AND status = 'PENDING'
                """, instanceId, stepNo);
    }

    /** 剩余未处理节点在实例提前终止（驳回/撤回/取消）时统一标 SKIPPED。 */
    public int skipPending(long instanceId) {
        return jdbc.update("""
                UPDATE approval_steps
                   SET status = 'SKIPPED', updated_at = now()
                 WHERE instance_id = ? AND status = 'PENDING'
                """, instanceId);
    }

    /**
     * 转办时重开某一节点。
     *
     * <p>语义：把该节点的<b>票数清零</b>并回到 PENDING —— 因为接手人换了，
     * 前一位处理人的同意不能自动算给新处理人（那等于"转办即通过"，
     * 是最容易被利用的审批漏洞）。
     *
     * <p>只对 {@code PENDING}/{@code TRANSFERRED} 的节点有效；
     * 已 APPROVED/REJECTED 的节点不动，避免改写既成的结论。
     */
    public boolean reopen(long instanceId, int stepNo) {
        return jdbc.update("""
                UPDATE approval_steps
                   SET status = 'PENDING', approved_by = '{}'::BIGINT[],
                       decided_at = NULL, decision_by = NULL, updated_at = now()
                 WHERE instance_id = ? AND step_no = ? AND status IN ('PENDING', 'SKIPPED')
                """, instanceId, stepNo) == 1;
    }
}
