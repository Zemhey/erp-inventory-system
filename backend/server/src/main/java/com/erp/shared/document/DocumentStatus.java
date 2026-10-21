package com.erp.shared.document;

import java.util.Collections;
import java.util.EnumSet;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * 单据统一状态机（BR-DOC-01，docs/02-领域模型 §3）。
 *
 * <p><b>为什么所有单据共用一套状态</b>：进货单、出货单、退货单的业务差异在字段上，
 * 不在"一张单子怎么流动"上。若每种单据各写一套状态判断，最终会出现
 * "进货单允许 DRAFT→APPROVED，出货单不允许"这类不一致，
 * 而这种不一致只有靠人肉审查才能发现。
 *
 * <p><b>为什么合法转移写成枚举内的表，而不是散落在 Service 的 if 里</b>：
 * 转移规则是一张有限的表（7 × 7），把它显式写出来之后，
 * "非法转移必须全部被拒绝"就变成一个可以穷举的测试命题
 * （见 {@code DocumentStatusMachineTest}），而不是靠人想用例。
 *
 * <pre>
 * DRAFT      -> SUBMITTED, VOIDED
 * SUBMITTED  -> APPROVED, REJECTED, VOIDED
 * APPROVED   -> PARTIAL, COMPLETED, VOIDED
 * PARTIAL    -> PARTIAL, COMPLETED, VOIDED   （分批入库/出货：可多次部分执行）
 * COMPLETED  -> （终态）
 * VOIDED     -> （终态）
 * REJECTED   -> DRAFT, VOIDED
 * </pre>
 *
 * <p>注意 {@code PARTIAL -> PARTIAL} 是合法自环："部分入库"可以再来一次仍处于部分状态。
 * 自环在这个状态机里是<b>业务语义</b>，不是笔误。
 */
public enum DocumentStatus {

    /** 草稿：可改可删，不影响库存。 */
    DRAFT,

    /** 已提交：等待审核，不可改内容。 */
    SUBMITTED,

    /** 已审核：可以执行（入库/出库）。 */
    APPROVED,

    /** 部分执行：已执行一部分，仍可继续执行。 */
    PARTIAL,

    /** 已完成：终态。 */
    COMPLETED,

    /** 已作废：终态。 */
    VOIDED,

    /** 已驳回：可退回草稿修改后重新提交。 */
    REJECTED;

    private static final Map<DocumentStatus, Set<DocumentStatus>> TRANSITIONS = buildTransitions();

    private static Map<DocumentStatus, Set<DocumentStatus>> buildTransitions() {
        Map<DocumentStatus, Set<DocumentStatus>> m = new EnumMap<>(DocumentStatus.class);
        m.put(DRAFT, EnumSet.of(SUBMITTED, VOIDED));
        m.put(SUBMITTED, EnumSet.of(APPROVED, REJECTED, VOIDED));
        m.put(APPROVED, EnumSet.of(PARTIAL, COMPLETED, VOIDED));
        m.put(PARTIAL, EnumSet.of(PARTIAL, COMPLETED, VOIDED));
        m.put(COMPLETED, EnumSet.noneOf(DocumentStatus.class));
        m.put(VOIDED, EnumSet.noneOf(DocumentStatus.class));
        m.put(REJECTED, EnumSet.of(DRAFT, VOIDED));
        return Collections.unmodifiableMap(m);
    }

    /** 从本状态可直接转移到的状态集合（不可变，按枚举声明顺序）。 */
    public Set<DocumentStatus> allowedNext() {
        return TRANSITIONS.get(this);
    }

    /** 是否允许从当前状态转移到 {@code target}。 */
    public boolean canTransitionTo(DocumentStatus target) {
        return target != null && allowedNext().contains(target);
    }

    /** 终态：不再有任何出路，用于判断"这张单子已经死了"。 */
    public boolean isTerminal() {
        return allowedNext().isEmpty();
    }

    /** 是否处于"可执行"（能对库存产生变动）的状态。 */
    public boolean isExecutable() {
        return this == APPROVED || this == PARTIAL;
    }

    /**
     * 是否处于"未结"状态，即月结时算未完成单据。
     *
     * <p>未结 = <b>还有出路</b>，因此直接定义为"非终态"。
     * 刻意不写成 {@code this == DRAFT || ...} 的枚举罗列：那样每当新增状态都会漏一个，
     * 且与 {@link #isTerminal()} 出现两套并行定义、迟早互相矛盾。
     *
     * <p>特别注意 {@link #REJECTED}：它虽是"被驳回"，但可退回草稿重提（REJECTED -> DRAFT），
     * 因此<b>不是</b>终态，月结时不能当作已结单据放过。
     */
    public boolean isOpen() {
        return !isTerminal();
    }
}
