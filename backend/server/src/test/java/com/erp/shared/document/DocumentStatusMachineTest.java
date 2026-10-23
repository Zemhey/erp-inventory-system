package com.erp.shared.document;

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
 * 单据状态机单元测试 —— L1 层，不依赖数据库，整体毫秒级（docs/06-测试策略 §2）。
 *
 * <p><b>本类的核心思想：把"合法转移表"当成被测对象的<b>契约</b>，
 * 然后穷举 7 × 7 = 49 种组合。</b> 只有被期望表列为合法的组合才允许通过，
 * 其余 29 种必须全部抛异常。
 *
 * <p>为什么值得这么写：状态机 bug 的典型形态是"某个非法转移被放过去了"
 * （比如 COMPLETED 又被改成 VOIDED，导致已出库的单据被作废但库存没回补）。
 * 这类 bug 不会在 happy path 上出现，只会出现在没人想到的组合里。
 * 穷举之后，这个命题就<b>不存在"没想到"</b>。
 */
@DisplayName("单据 · 统一状态机")
class DocumentStatusMachineTest {

    /**
     * 期望的合法转移集合 —— 与 {@link DocumentStatus} 里的表<b>独立书写</b>。
     *
     * <p>刻意不复用生产代码里的 {@code allowedNext()}：若两边共用同一份数据，
     * 测试就只能验证"代码等于代码"，永远绿。这里是需求侧的第二表述。
     */
    private static final List<String> EXPECTED_LEGAL = List.of(
            "DRAFT->SUBMITTED",
            "DRAFT->VOIDED",
            "SUBMITTED->APPROVED",
            "SUBMITTED->REJECTED",
            "SUBMITTED->VOIDED",
            "APPROVED->PARTIAL",
            "APPROVED->COMPLETED",
            "APPROVED->VOIDED",
            "PARTIAL->PARTIAL",
            "PARTIAL->COMPLETED",
            "PARTIAL->VOIDED",
            "REJECTED->DRAFT",
            "REJECTED->VOIDED");

    private static boolean expectedLegal(DocumentStatus from, DocumentStatus to) {
        return EXPECTED_LEGAL.contains(from.name() + "->" + to.name());
    }

    static Stream<Arguments> allTransitions() {
        List<Arguments> args = new ArrayList<>();
        for (DocumentStatus from : DocumentStatus.values()) {
            for (DocumentStatus to : DocumentStatus.values()) {
                args.add(Arguments.of(from, to, expectedLegal(from, to)));
            }
        }
        return args.stream();
    }

    @Test
    @CaseId("TC-DOC-001")
    @DisplayName("★ 穷举 7×7=49 种状态组合：合法转移必须通过，非法转移必须全部被拒绝")
    void everyTransition_mustMatchTheExpectedTable() {
        long legal = 0;
        long illegal = 0;
        for (DocumentStatus from : DocumentStatus.values()) {
            for (DocumentStatus to : DocumentStatus.values()) {
                boolean expect = expectedLegal(from, to);
                assertThat(from.canTransitionTo(to))
                        .as("转移 %s -> %s 的合法性判断与需求表不一致", from, to)
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
        assertThat(legal + illegal).as("穷举必须覆盖全部 7×7 组合").isEqualTo(49);
    }

    @ParameterizedTest(name = "[{index}] {0} -> {1}（期望{2}）")
    @MethodSource("allTransitions")
    @CaseId("TC-DOC-002")
    @DisplayName("★ 参数化穷举：非法转移抛 PUR_STATUS_ILLEGAL 且消息含当前态与目标态")
    void requireTransition_shouldThrowOnIllegalOnly(DocumentStatus from, DocumentStatus to,
                                                    boolean expectedLegal) {
        if (expectedLegal) {
            assertThat(DocumentStatusMachine.requireTransition(
                    from, to, ErrorCode.PUR_STATUS_ILLEGAL, "PO-X")).isEqualTo(to);
            return;
        }
        assertThatThrownBy(() -> DocumentStatusMachine.requireTransition(
                from, to, ErrorCode.PUR_STATUS_ILLEGAL, "PO-X"))
                .as("非法转移 %s -> %s 必须被拒绝", from, to)
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.PUR_STATUS_ILLEGAL)
                .hasMessageContaining(from.name())
                .hasMessageContaining(to.name())
                .hasMessageContaining("PO-X");
    }

    // ─────────────────────────── 状态语义 ───────────────────────────

    @Test
    @CaseId("TC-DOC-003")
    @DisplayName("终态不可再转移：COMPLETED / VOIDED 没有任何出路")
    void terminalStates_haveNoExit() {
        assertThat(DocumentStatus.COMPLETED.isTerminal()).isTrue();
        assertThat(DocumentStatus.VOIDED.isTerminal()).isTrue();
        assertThat(DocumentStatus.COMPLETED.allowedNext()).isEmpty();
        assertThat(DocumentStatus.VOIDED.allowedNext()).isEmpty();

        for (DocumentStatus any : DocumentStatus.values()) {
            assertThat(DocumentStatus.COMPLETED.canTransitionTo(any))
                    .as("已完成单据不允许再变更为 %s", any)
                    .isFalse();
            assertThat(DocumentStatus.VOIDED.canTransitionTo(any))
                    .as("已作废单据不允许再变更为 %s", any)
                    .isFalse();
        }
    }

    @Test
    @CaseId("TC-DOC-004")
    @DisplayName("可执行状态只有 APPROVED / PARTIAL：DRAFT 与 SUBMITTED 不得触碰库存")
    void onlyApprovedOrPartial_areExecutable() {
        assertThat(DocumentStatus.APPROVED.isExecutable()).isTrue();
        assertThat(DocumentStatus.PARTIAL.isExecutable()).isTrue();
        for (DocumentStatus s : List.of(DocumentStatus.DRAFT, DocumentStatus.SUBMITTED,
                DocumentStatus.COMPLETED, DocumentStatus.VOIDED, DocumentStatus.REJECTED)) {
            assertThat(s.isExecutable()).as("%s 不应可执行", s).isFalse();
        }
    }

    @Test
    @CaseId("TC-DOC-005")
    @DisplayName("未结状态判定：只有终态被视为已结")
    void isOpen_shouldExcludeTerminalStates() {
        assertThat(DocumentStatus.DRAFT.isOpen()).isTrue();
        assertThat(DocumentStatus.SUBMITTED.isOpen()).isTrue();
        assertThat(DocumentStatus.APPROVED.isOpen()).isTrue();
        assertThat(DocumentStatus.PARTIAL.isOpen()).isTrue();
        assertThat(DocumentStatus.COMPLETED.isOpen()).isFalse();
        assertThat(DocumentStatus.VOIDED.isOpen()).isFalse();
        // REJECTED 是可改动状态（可退回草稿），月结时算未结
        assertThat(DocumentStatus.REJECTED.isOpen()).isTrue();
    }

    @Test
    @CaseId("TC-DOC-006")
    @DisplayName("PARTIAL->PARTIAL 自环合法：分批入库/出货可以多次进入部分状态")
    void partialSelfLoop_isLegal() {
        assertThat(DocumentStatus.PARTIAL.canTransitionTo(DocumentStatus.PARTIAL))
                .as("分批执行的业务语义：可以反复'部分完成'")
                .isTrue();
    }

    // ─────────────────────────── 红冲（补偿性转移，M2.5） ───────────────────────────

    @Test
    @CaseId("TC-DOC-011")
    @DisplayName("★ 红冲白名单：仅 PARTIAL / COMPLETED 可补偿作废，目标恒为 VOIDED")
    void redReversal_isAllowedOnlyForExecutedStates() {
        assertThat(DocumentStatusMachine.requireRedReversal(
                DocumentStatus.PARTIAL, ErrorCode.PUR_STATUS_ILLEGAL, "PO-X"))
                .isEqualTo(DocumentStatus.VOIDED);
        assertThat(DocumentStatusMachine.requireRedReversal(
                DocumentStatus.COMPLETED, ErrorCode.SAL_STATUS_ILLEGAL, "SO-X"))
                .isEqualTo(DocumentStatus.VOIDED);

        for (DocumentStatus from : DocumentStatus.values()) {
            boolean allowed = from == DocumentStatus.PARTIAL || from == DocumentStatus.COMPLETED;
            if (allowed) {
                continue;
            }
            assertThatThrownBy(() -> DocumentStatusMachine.requireRedReversal(
                    from, ErrorCode.PUR_STATUS_ILLEGAL, "PO-X"))
                    .as("%s 不应允许红冲", from)
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ErrorCode.PUR_STATUS_ILLEGAL)
                    .hasMessageContaining("红冲")
                    .hasMessageContaining(from.name())
                    .hasMessageContaining("PO-X");
        }
    }

    @Test
    @CaseId("TC-DOC-012")
    @DisplayName("★ 红冲不改常规状态机：COMPLETED 在 7×7 表里仍是终态（月结 isOpen 口径不被污染）")
    void redReversal_doesNotMakeCompletedANonTerminalState() {
        assertThat(DocumentStatus.COMPLETED.canTransitionTo(DocumentStatus.VOIDED))
                .as("常规转移表必须继续拒绝 COMPLETED -> VOIDED")
                .isFalse();
        assertThat(DocumentStatus.COMPLETED.isTerminal()).isTrue();
        assertThat(DocumentStatus.COMPLETED.isOpen()).isFalse();

        assertThatThrownBy(() -> DocumentStatusMachine.requireTransition(
                DocumentStatus.COMPLETED, DocumentStatus.VOIDED,
                ErrorCode.SAL_STATUS_ILLEGAL, "SO-X"))
                .as("走错入口（requireTransition）时红冲依然被拦住")
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SAL_STATUS_ILLEGAL);
    }
}
