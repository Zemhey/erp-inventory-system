package com.erp.aps.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.support.CaseId;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 审批状态机单元测试 —— L1 层，不依赖数据库，整体毫秒级（docs/06-测试策略 §2）。
 *
 * <p><b>照 {@code DocumentStatusMachineTest} 的写法</b>（任务书要求）：
 * 把"合法转移表"当成被测对象的<b>契约</b>，穷举 7 × 7 = 49 种组合，
 * 只有期望表列为合法的才允许通过，其余必须全部被拒绝。
 *
 * <p><b>★ 为什么两套状态机要各写一份穷举</b>（任务书坑 3）：
 * 审批状态机（{@link ApprovalStatus}）与单据状态机（{@code DocumentStatus}）
 * 是两个<b>独立</b>的七态机。它们看起来都叫"状态机"、都有 APPROVED/REJECTED，
 * 所以最容易被合并或复用 —— 那正是坑。各自穷举一遍的价值在于：
 * 这张期望表是<b>需求侧的第二表述</b>，与生产代码里的 {@code allowedNext()}
 * 独立书写。共用同一份数据的话，测试就只验证"代码等于代码"，永远绿。
 */
@DisplayName("审批 · 状态机（与单据状态机独立）")
class ApprovalStatusMachineTest {

    /**
     * 期望的合法转移集合 —— 与 {@link ApprovalStatus#allowedNext()} <b>独立书写</b>。
     *
     * <p>共 13 条：
     * <ul>
     *   <li>DRAFT → PENDING / WITHDRAWN / CANCELLED（3）</li>
     *   <li>PENDING → APPROVED / REJECTED / TRANSFERRED / WITHDRAWN / CANCELLED（5）</li>
     *   <li>TRANSFERRED → PENDING / APPROVED / REJECTED / WITHDRAWN / CANCELLED（5）</li>
     *   <li>四个终态无出路（0）</li>
     * </ul>
     * 合计 13 条合法、49 − 13 = 36 条非法。
     */
    private static final List<String> EXPECTED_LEGAL = List.of(
            "DRAFT->PENDING",
            "DRAFT->WITHDRAWN",
            "DRAFT->CANCELLED",
            "PENDING->APPROVED",
            "PENDING->REJECTED",
            "PENDING->TRANSFERRED",
            "PENDING->WITHDRAWN",
            "PENDING->CANCELLED",
            "TRANSFERRED->PENDING",
            "TRANSFERRED->APPROVED",
            "TRANSFERRED->REJECTED",
            "TRANSFERRED->WITHDRAWN",
            "TRANSFERRED->CANCELLED");

    private static boolean expectedLegal(ApprovalStatus from, ApprovalStatus to) {
        return EXPECTED_LEGAL.contains(from.name() + "->" + to.name());
    }

    static Stream<Arguments> allTransitions() {
        List<Arguments> args = new ArrayList<>();
        for (ApprovalStatus from : ApprovalStatus.values()) {
            for (ApprovalStatus to : ApprovalStatus.values()) {
                args.add(Arguments.of(from, to, expectedLegal(from, to)));
            }
        }
        return args.stream();
    }

    @Test
    @CaseId("TC-APS-001")
    @DisplayName("★ 穷举 7×7=49 种审批状态组合：合法转移必须通过，非法转移必须全部被拒绝")
    void everyTransition_mustMatchTheExpectedTable() {
        long legal = 0;
        long illegal = 0;
        for (ApprovalStatus from : ApprovalStatus.values()) {
            for (ApprovalStatus to : ApprovalStatus.values()) {
                boolean expect = expectedLegal(from, to);
                assertThat(from.canTransitionTo(to))
                        .as("审批转移 %s -> %s 的合法性判断与需求表不一致", from, to)
                        .isEqualTo(expect);
                if (expect) {
                    legal++;
                } else {
                    illegal++;
                }
            }
        }
        assertThat(legal)
                .as("合法转移共 %d 条（需求表 %d 条）", legal, EXPECTED_LEGAL.size())
                .isEqualTo(EXPECTED_LEGAL.size());
        assertThat(illegal).as("非法转移条数应为 49 − 13 = 36").isEqualTo(36);
        // ★ 硬验收第 2 条：legal + illegal 必须恰好等于 49
        assertThat(legal + illegal)
                .as("穷举必须覆盖全部 7×7 组合（硬验收要求 legal + illegal == 49）")
                .isEqualTo(49);
    }

    @ParameterizedTest(name = "[{index}] {0} -> {1}（期望{2}）")
    @MethodSource("allTransitions")
    @CaseId("TC-APS-002")
    @DisplayName("★ 参数化穷举 49 条：非法转移抛 APS_STATUS_ILLEGAL 且消息含当前态、目标态与实例标识")
    void requireTransition_shouldThrowOnIllegalOnly(ApprovalStatus from, ApprovalStatus to,
                                                    boolean expectedLegal) {
        if (expectedLegal) {
            assertThat(ApprovalStatusMachine.requireTransition(from, to, "APS-X")).isEqualTo(to);
            return;
        }
        assertThatThrownBy(() -> ApprovalStatusMachine.requireTransition(from, to, "APS-X"))
                .as("非法审批转移 %s -> %s 必须被拒绝", from, to)
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.APS_STATUS_ILLEGAL)
                .hasMessageContaining(from.name())
                .hasMessageContaining(to.name())
                .hasMessageContaining("APS-X");
    }

    // ─────────────────────────── 状态语义 ───────────────────────────

    @Test
    @CaseId("TC-APS-003")
    @DisplayName("终态不可再转移：APPROVED / REJECTED / WITHDRAWN / CANCELLED 都没有出路")
    void terminalStates_haveNoExit() {
        for (ApprovalStatus terminal : List.of(ApprovalStatus.APPROVED, ApprovalStatus.REJECTED,
                ApprovalStatus.WITHDRAWN, ApprovalStatus.CANCELLED)) {
            assertThat(terminal.isTerminal()).as("%s 应当是终态", terminal).isTrue();
            assertThat(terminal.allowedNext()).as("%s 不应有任何出路", terminal).isEmpty();
            for (ApprovalStatus any : ApprovalStatus.values()) {
                assertThat(terminal.canTransitionTo(any))
                        .as("%s 是终态，不允许再变更为 %s", terminal, any)
                        .isFalse();
            }
        }
    }

    @Test
    @CaseId("TC-APS-004")
    @DisplayName("非终态只有 DRAFT/PENDING/TRANSFERRED；isOpen 与 isTerminal 严格互补")
    void nonTerminalStates_areExactlyThree() {
        List<ApprovalStatus> open = new ArrayList<>();
        for (ApprovalStatus s : ApprovalStatus.values()) {
            assertThat(s.isOpen()).as("%s 的 isOpen 必须等于 !isTerminal", s)
                    .isEqualTo(!s.isTerminal());
            if (s.isOpen()) {
                open.add(s);
            }
        }
        assertThat(open).as("未结审批只可能是这三个").containsExactlyInAnyOrder(
                ApprovalStatus.DRAFT, ApprovalStatus.PENDING, ApprovalStatus.TRANSFERRED);
    }

    @Test
    @CaseId("TC-APS-005")
    @DisplayName("★ 在途状态只有 PENDING / TRANSFERRED：只有它们占用「一张单据一个未结实例」的槽位")
    void inFlightStates_arePendingAndTransferred() {
        assertThat(ApprovalStatus.PENDING.isInFlight()).isTrue();
        assertThat(ApprovalStatus.TRANSFERRED.isInFlight()).isTrue();
        for (ApprovalStatus s : List.of(ApprovalStatus.DRAFT, ApprovalStatus.APPROVED,
                ApprovalStatus.REJECTED, ApprovalStatus.WITHDRAWN, ApprovalStatus.CANCELLED)) {
            assertThat(s.isInFlight()).as("%s 不应算在途", s).isFalse();
        }
    }

    @Test
    @CaseId("TC-APS-006")
    @DisplayName("只有 APPROVED 让单据放行：REJECTED/WITHDRAWN/CANCELLED 都不放行")
    void onlyApprovedAllowsDocument() {
        assertThat(ApprovalStatus.APPROVED.allowsDocument()).isTrue();
        for (ApprovalStatus s : ApprovalStatus.values()) {
            if (s != ApprovalStatus.APPROVED) {
                assertThat(s.allowsDocument()).as("%s 不应放行单据", s).isFalse();
            }
        }
    }

    @Test
    @CaseId("TC-APS-007")
    @DisplayName("★ TRANSFERRED 不是终态：转办后接办人仍可同意/驳回（转办≠通过）")
    void transferred_isNotTerminal() {
        assertThat(ApprovalStatus.TRANSFERRED.isTerminal()).isFalse();
        assertThat(ApprovalStatus.TRANSFERRED.canTransitionTo(ApprovalStatus.PENDING)).isTrue();
        assertThat(ApprovalStatus.TRANSFERRED.canTransitionTo(ApprovalStatus.APPROVED)).isTrue();
        assertThat(ApprovalStatus.TRANSFERRED.canTransitionTo(ApprovalStatus.REJECTED)).isTrue();
    }

    @Test
    @CaseId("TC-APS-008")
    @DisplayName("★ REJECTED 是终态、不回 DRAFT：驳回后重提会生成新实例，旧结论不可推翻")
    void rejected_isTerminalAndDoesNotReturnToDraft() {
        assertThat(ApprovalStatus.REJECTED.isTerminal()).isTrue();
        assertThat(ApprovalStatus.REJECTED.canTransitionTo(ApprovalStatus.DRAFT)).isFalse();
        assertThat(ApprovalStatus.REJECTED.canTransitionTo(ApprovalStatus.PENDING)).isFalse();
    }

    @Test
    @CaseId("TC-APS-009")
    @DisplayName("自环一律非法：审批状态没有「原地不动」的业务语义（与单据 PARTIAL->PARTIAL 不同）")
    void selfLoops_areAllIllegal() {
        for (ApprovalStatus s : ApprovalStatus.values()) {
            assertThat(s.canTransitionTo(s))
                    .as("%s -> %s 的自环在审批里没有业务语义，必须非法", s, s)
                    .isFalse();
        }
    }

    @Test
    @CaseId("TC-APS-010")
    @DisplayName("目标状态为 null 时拒绝，且消息点明当前状态")
    void nullTarget_isRejected() {
        assertThatThrownBy(() -> ApprovalStatusMachine.requireTransition(
                ApprovalStatus.PENDING, null, "APS-1"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.APS_STATUS_ILLEGAL)
                .hasMessageContaining("PENDING");
    }

    @Test
    @CaseId("TC-APS-011")
    @DisplayName("★ 两套状态机互不干扰：审批的 7 个状态名与单据的 7 个状态名不是同一组")
    void approvalStatesAreDistinctFromDocumentStates() {
        // 单据七态：DRAFT SUBMITTED APPROVED PARTIAL COMPLETED VOIDED REJECTED
        // 审批七态：DRAFT PENDING TRANSFERRED APPROVED REJECTED WITHDRAWN CANCELLED
        // 交集（DRAFT/APPROVED/REJECTED）只是名字相同，语义不同 —— 这条用例把
        // "审批特有的四个状态"钉住，防止有人为了复用而把它们合并进 DocumentStatus。
        List<String> approvalOnly = List.of("PENDING", "TRANSFERRED", "WITHDRAWN", "CANCELLED");
        List<String> names = new ArrayList<>();
        for (ApprovalStatus s : ApprovalStatus.values()) {
            names.add(s.name());
        }
        assertThat(names).as("审批状态集合必须恰好是这七个").containsExactlyInAnyOrder(
                "DRAFT", "PENDING", "TRANSFERRED", "APPROVED", "REJECTED", "WITHDRAWN",
                "CANCELLED");
        assertThat(names).as("审批特有的状态不能被单据状态机吸收").containsAll(approvalOnly);
        assertThat(com.erp.shared.document.DocumentStatus.values()).hasSize(7);
        assertThat(ApprovalStatus.values()).as("两套都是七态，故穷举都是 49").hasSize(7);
    }
}
