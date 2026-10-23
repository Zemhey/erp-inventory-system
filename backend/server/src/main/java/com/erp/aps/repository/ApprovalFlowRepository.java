package com.erp.aps.repository;

import com.erp.aps.domain.ApprovalFlow;
import com.erp.shared.approval.ApprovalGate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 审批流定义仓储（{@code approval_flows}）。
 *
 * <p><b>只读为主的"配置表"仓储</b>：流程定义由种子/运维配置给出，
 * 接口只提供"列出 + 启停"。刻意不提供"任意改节点"的 CRUD ——
 * 见 {@link com.erp.aps.domain.ApprovalFlow} 类注释：不做通用 BPMN 设计器。
 *
 * <p>节点由 {@link ApprovalStepRepository} 单独装载（定义节点 {@code instance_id IS NULL}），
 * 不在本类里 join 回来 —— 一次查询解决 N+1 的写法在这里没有收益，
 * 因为一个流程最多两三个节点，不值得为它写一条带 {@code LEFT JOIN} 的复杂 SQL。
 */
@Repository
public class ApprovalFlowRepository {

    private static final RowMapper<ApprovalFlow> MAPPER = (rs, n) -> new ApprovalFlow(
            rs.getLong("id"),
            rs.getString("code"),
            rs.getString("name"),
            rs.getString("biz_type"),
            rs.getString("description"),
            rs.getBoolean("enabled"),
            List.of());   // 节点另行装载

    private static final String COLUMNS =
            "id, code, name, biz_type, description, enabled";

    private final JdbcTemplate jdbc;
    private final ApprovalStepRepository steps;

    public ApprovalFlowRepository(JdbcTemplate jdbc, ApprovalStepRepository steps) {
        this.jdbc = jdbc;
        this.steps = steps;
    }

    public Optional<ApprovalFlow> findByCode(String code) {
        return jdbc.query("SELECT " + COLUMNS + " FROM approval_flows WHERE code = ?",
                MAPPER, code).stream().findFirst().map(this::withSteps);
    }

    public Optional<ApprovalFlow> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM approval_flows WHERE id = ?",
                MAPPER, id).stream().findFirst().map(this::withSteps);
    }

    /**
     * 某单据类型当前启用中的流程。
     *
     * <p>数据库部分唯一索引 {@code uq_approval_flow_enabled_biz} 保证最多一条，
     * 因此这里用 {@code findFirst} 是安全的 —— 若哪天索引被删，
     * 那会是一个配置错误，而不是"随便挑一条"的合理行为。
     */
    public Optional<ApprovalFlow> findEnabledByBizType(ApprovalGate.BizType bizType) {
        return jdbc.query("SELECT " + COLUMNS + """
                  FROM approval_flows
                 WHERE biz_type = ? AND enabled
                 ORDER BY id
                """, MAPPER, bizType.name()).stream().findFirst().map(this::withSteps);
    }

    public List<ApprovalFlow> findAll() {
        return jdbc.query("SELECT " + COLUMNS + " FROM approval_flows ORDER BY biz_type, code",
                MAPPER).stream().map(this::withSteps).toList();
    }

    /**
     * 启停一条流程。
     *
     * <p>启用时可能撞上"同一单据类型已有启用流"的部分唯一索引 —— 由
     * {@code DbErrors} 翻译成 {@code APS_FLOW_INVALID}，而不是把约束违例原样抛成 500。
     *
     * @return 是否更新成功（false = 流程不存在）
     */
    public boolean setEnabled(long id, boolean enabled) {
        return jdbc.update("""
                UPDATE approval_flows
                   SET enabled = ?, updated_at = now()
                 WHERE id = ?
                """, enabled, id) == 1;
    }

    private ApprovalFlow withSteps(ApprovalFlow f) {
        return new ApprovalFlow(f.id(), f.code(), f.name(), f.bizType(), f.description(),
                f.enabled(), steps.findDefinitionSteps(f.id()));
    }
}
