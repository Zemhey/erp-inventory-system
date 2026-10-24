package com.erp.aps.domain;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 审批实例状态机（FR-APS-02，BR-APS-01）。
 *
 * <p><b>★ 这是与 {@code DocumentStatus} 完全独立的第二套状态机</b>（任务书坑 3）。
 * 两者回答的是不同的问题：
 * <ul>
 *   <li>{@code DocumentStatus} 回答"这张单子在业务上走到哪一步了"
 *       —— 含 V8 的红冲补偿转移、{@code isExecutable()} 决定能否动库存、
 *       {@code isOpen()} 决定月结算不算未结；</li>
 *   <li>{@code ApprovalStatus} 回答"这次审批走完了没有"
 *       —— 它不知道库存、不知道账期、也不该知道。</li>
 * </ul>
 * 合并成一套的诱惑很大（都叫"状态"、都有 APPROVED/REJECTED），但一旦合并，
 * 审批就会继承 {@code isExecutable()} 的语义，于是"审批通过"被误当成"可以出货"；
 * 反过来红冲也要在审批状态里长出一个补偿态。两条线各走各的，边界才守得住。
 *
 * <pre>
 * DRAFT       -> PENDING, WITHDRAWN, CANCELLED       （草稿：还没提交）
 * PENDING     -> APPROVED, REJECTED, TRANSFERRED, WITHDRAWN, CANCELLED
 * TRANSFERRED -> PENDING, APPROVED, REJECTED, WITHDRAWN, CANCELLED   （转办后仍在审）
 * APPROVED    -> （终态）
 * REJECTED    -> （终态；单据退回修改后重新提交会生成**新实例**，不倒回旧实例）
 * WITHDRAWN   -> （终态）
 * CANCELLED   -> （终态）
 * </pre>
 *
 * <p><b>为什么 REJECTED 是终态、不再给 DRAFT</b>：驳回后单据要改内容再走一遍，
 * 那是一张新审批（新的发起人、新的金额、新的节点阈值判定）。把旧实例倒回草稿会让
 * "这次驳回"的历史消失 —— 审批记录本身是可追溯的资产，不能覆盖。
 * 这一点与 {@code DocumentStatus.REJECTED -> DRAFT}（单据可退回草稿重提）不矛盾：
 * 单据重提会<b>新建</b>一个审批实例。
 *
 * <p><b>TRANSFERRED 为什么不是终态</b>：转办是"交给别人继续审"，
 * 接办人还要能同意/驳回，所以它必须能继续往下走。
 */
public enum ApprovalStatus {

    /** 草稿：实例已建但未提交，尚未进入任何人的待办。 */
    DRAFT,

    /** 待审批：停在某个节点，等待具备该节点权限的人处理。 */
    PENDING,

    /** 已转办：当前节点换了一个处理人，仍在审批中。 */
    TRANSFERRED,

    /** 已通过：终态。单据侧据此放行。 */
    APPROVED,

    /** 已驳回：终态。单据需退回修改后重新发起（新实例）。 */
    REJECTED,

    /** 已撤回：终态。发起人在通过之前收回。 */
    WITHDRAWN,

    /** 已取消：终态。单据被作废/红冲时连带取消，避免"死人待办"。 */
    CANCELLED;

    private static final Map<ApprovalStatus, Set<ApprovalStatus>> TRANSITIONS = buildTransitions();

    private static Map<ApprovalStatus, Set<ApprovalStatus>> buildTransitions() {
        Map<ApprovalStatus, Set<ApprovalStatus>> m = new EnumMap<>(ApprovalStatus.class);
        m.put(DRAFT, EnumSet.of(PENDING, WITHDRAWN, CANCELLED));
        m.put(PENDING, EnumSet.of(APPROVED, REJECTED, TRANSFERRED, WITHDRAWN, CANCELLED));
        m.put(TRANSFERRED, EnumSet.of(PENDING, APPROVED, REJECTED, WITHDRAWN, CANCELLED));
        m.put(APPROVED, EnumSet.noneOf(ApprovalStatus.class));
        m.put(REJECTED, EnumSet.noneOf(ApprovalStatus.class));
        m.put(WITHDRAWN, EnumSet.noneOf(ApprovalStatus.class));
        m.put(CANCELLED, EnumSet.noneOf(ApprovalStatus.class));
        return Collections.unmodifiableMap(m);
    }

    /** 从本状态可直接转移到的状态集合（不可变，按枚举声明顺序）。 */
    public Set<ApprovalStatus> allowedNext() {
        return TRANSITIONS.get(this);
    }

    /** 是否允许从当前状态转移到 {@code target}。 */
    public boolean canTransitionTo(ApprovalStatus target) {
        return target != null && allowedNext().contains(target);
    }

    /** 终态：不再有任何出路 —— 审批结论一旦落定就不可推翻。 */
    public boolean isTerminal() {
        return allowedNext().isEmpty();
    }

    /** 是否"在审批中"：待审或已转办。这两个状态占用"一张单据一个未结实例"的唯一槽位。 */
    public boolean isInFlight() {
        return this == PENDING || this == TRANSFERRED;
    }

    /**
     * 是否仍然"未结"（占用唯一槽位）。
     *
     * <p>刻意复用 {@link #isTerminal()} 的反面，而不是再罗列一遍枚举：
     * 罗列式定义每新增一个状态就会漏一个，且两套定义迟早互相矛盾
     * （这与 {@code DocumentStatus.isOpen()} 的注释是同一个教训）。
     */
    public boolean isOpen() {
        return !isTerminal();
    }

    /** 单据侧可据以放行的状态。 */
    public boolean allowsDocument() {
        return this == APPROVED;
    }
}
