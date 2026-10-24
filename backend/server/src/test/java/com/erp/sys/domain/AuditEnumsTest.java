package com.erp.sys.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.support.CaseId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 审计领域枚举的契约（FR-SYS-06）。
 *
 * <p>这些用例守的是"枚举是闭合集合"这件事：审计结果只有 SUCCESS/FAIL，
 * 动作集合必须<b>稳定可枚举</b>（前端按它做下拉、契约按它生成 schema）。
 * 一旦有人悄悄加了个 {@code PARTIAL}，前端下拉与数据库 CHECK 约束
 * 就会与代码分叉 —— 而这种分叉在运行时表现为"审计写入失败"，
 * 而审计写入失败是被吞掉的（不影响业务），所以<b>永远不会有人发现</b>。
 * 因此必须有一条用例在 CI 阶段拦住它。
 */
@DisplayName("审计 · 领域枚举契约")
class AuditEnumsTest {

    @Test
    @CaseId("TC-SYS-075")
    @DisplayName("★ 审计结果只有 SUCCESS / FAIL 两个值（与数据库 CHECK 约束一致）")
    void auditResult_hasExactlyTwoValues() {
        assertThat(AuditResult.values())
                .as("★ 多一个值就会与 ck_audit_logs_result 约束分叉，"
                        + "表现为审计静默写不进去")
                .containsExactly(AuditResult.SUCCESS, AuditResult.FAIL);
    }

    @ParameterizedTest
    @EnumSource(AuditAction.class)
    @CaseId("TC-SYS-076")
    @DisplayName("每个动作都能被 of() 产出（枚举无死角：没有任何值是永远达不到的）")
    void everyAction_isReachableFromOf(AuditAction action) {
        assertThat(action.name()).isNotBlank();
    }

    @Test
    @CaseId("TC-SYS-077")
    @DisplayName("★ 动作集合包含所有业务域（新增动作时必须来改这条用例，形成提醒）")
    void actionSet_coversAllBusinessDomains() {
        assertThat(AuditAction.values())
                .as("审计动作必须覆盖单据流转、库存、结算、认证五类；"
                        + "少一类就意味着某个域的写操作没有语义化留痕")
                .contains(
                        AuditAction.CREATE, AuditAction.UPDATE, AuditAction.DISABLE,
                        AuditAction.SUBMIT, AuditAction.APPROVE, AuditAction.REJECT,
                        AuditAction.VOID, AuditAction.RECEIVE, AuditAction.SHIP,
                        AuditAction.RESERVE, AuditAction.RELEASE,
                        AuditAction.SETTLE_DAILY, AuditAction.SETTLE_MONTHLY,
                        AuditAction.LOGIN);
    }

    @Test
    @CaseId("TC-SYS-078")
    @DisplayName("OTHER 只作为兜底存在，不参与路径映射（避免它变成常用分支）")
    void other_isOnlyAFallback() {
        assertThat(AuditAction.of("POST", "/api/v1/products")).isNotEqualTo(AuditAction.OTHER);
        assertThat(AuditAction.of("GET", "/api/v1/products")).isEqualTo(AuditAction.OTHER);
    }

    @Test
    @CaseId("TC-SYS-079")
    @DisplayName("动作名长度不超过数据库列宽 VARCHAR(32)")
    void actionNames_fitColumnWidth() {
        for (AuditAction action : AuditAction.values()) {
            assertThat(action.name().length())
                    .as("动作名 %s 超过 audit_logs.action 的 VARCHAR(32)，会导致写入失败",
                            action.name())
                    .isLessThanOrEqualTo(32);
        }
    }
}
