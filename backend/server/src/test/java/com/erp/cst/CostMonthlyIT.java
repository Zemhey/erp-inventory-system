package com.erp.cst;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.cst.application.CostApplicationService;
import com.erp.cst.domain.CostMonthlyRow;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.support.CaseId;
import com.erp.support.PostgresTestBase;
import com.erp.support.TestDataFactory;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** 成本月结转：期初/入/出/期末、幂等、跨期衔接、账期锁与重算。 */
@SpringBootTest
@DisplayName("成本 · 月结转")
class CostMonthlyIT extends PostgresTestBase {

    private static final OffsetDateTime SEP =
            OffsetDateTime.of(2026, 9, 15, 9, 0, 0, 0, ZoneOffset.ofHours(8));
    private static final OffsetDateTime OCT =
            OffsetDateTime.of(2026, 10, 6, 9, 0, 0, 0, ZoneOffset.ofHours(8));

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private CostApplicationService costs;

    private TestDataFactory data;
    private long warehouseId;
    private long skuId;

    @BeforeEach
    void setUp() {
        data = new TestDataFactory(jdbc);
        data.truncateAll();
        warehouseId = data.warehouse("WH-CST-MONTH");
        long productId = data.product("P-CST-MONTH", "成本月结商品");
        skuId = data.sku(productId, "白色", "L");
    }

    @Test
    @CaseId("TC-CST-039")
    @DisplayName("月结行 = 期初 + 入 − 出，期末金额与成本层一致")
    void closeMonthlyComputesBalance() {
        ledger(10, 100L, SEP);
        ledger(10, 200L, OCT);
        ledger(-5, null, OCT.plusSeconds(1));

        CostApplicationService.MonthlyResult result = costs.closeMonthly("2026-10", "req-039");

        assertThat(result.duplicate()).isFalse();
        assertThat(result.items()).hasSize(1);
        CostMonthlyRow row = result.items().get(0);
        assertThat(row.openingQty()).isEqualTo(10);
        assertThat(row.openingCostAmount()).isEqualTo(1000);
        assertThat(row.inboundQty()).isEqualTo(10);
        assertThat(row.inboundCostAmount()).isEqualTo(2000);
        assertThat(row.outboundQty()).isEqualTo(5);
        assertThat(row.outboundCostAmount()).isEqualTo(750);
        assertThat(row.closingQty()).isEqualTo(15);
        assertThat(row.closingCostAmount()).isEqualTo(2250);
        assertThat(row.closingQty()).isEqualTo(row.openingQty() + row.inboundQty()
                - row.outboundQty());
        assertThat(row.closingCostAmount()).isEqualTo(row.openingCostAmount()
                + row.inboundCostAmount() - row.outboundCostAmount());
    }

    @Test
    @CaseId("TC-CST-040")
    @DisplayName("★ 同账期跑两次：第二次 duplicate=true，业务字段与 computed_at 都不变")
    void closeMonthlyIsIdempotent() {
        ledger(10, 100L, SEP);
        ledger(10, 200L, OCT);
        ledger(-5, null, OCT.plusSeconds(1));

        CostApplicationService.MonthlyResult first = costs.closeMonthly("2026-10", "req-040-a");
        OffsetDateTime firstComputedAt = computedAt("2026-10");
        CostApplicationService.MonthlyResult second = costs.closeMonthly("2026-10", "req-040-b");

        assertThat(first.duplicate()).isFalse();
        assertThat(second.duplicate()).isTrue();
        assertThat(second.items()).isEqualTo(first.items());
        assertThat(computedAt("2026-10")).isEqualTo(firstComputedAt);
    }

    @Test
    @CaseId("TC-CST-041")
    @DisplayName("跨期衔接：下月期初 = 上月期末")
    void openingChainsAcrossPeriods() {
        ledger(10, 100L, SEP);
        CostApplicationService.MonthlyResult september = costs.closeMonthly("2026-09", "req-041-sep");

        ledger(10, 200L, OCT);
        ledger(-5, null, OCT.plusSeconds(1));
        CostApplicationService.MonthlyResult october = costs.closeMonthly("2026-10", "req-041-oct");

        assertThat(september.items()).hasSize(1);
        assertThat(october.items()).hasSize(1);
        assertThat(october.items().get(0).openingQty())
                .isEqualTo(september.items().get(0).closingQty());
        assertThat(october.items().get(0).openingCostAmount())
                .isEqualTo(september.items().get(0).closingCostAmount());
    }

    @Test
    @CaseId("TC-CST-042")
    @DisplayName("账期已锁：复用 PeriodGuard，成本月结被拒绝")
    void closedPeriodRejected() {
        jdbc.update("""
                INSERT INTO settlement_periods (period, status, net_amount, closed_at)
                VALUES ('2026-10', 'CLOSED', 0, now())
                """);

        assertThatThrownBy(() -> costs.closeMonthly("2026-10", "req-042"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ErrorCode.SET_PERIOD_CLOSED));
    }

    @Test
    @CaseId("TC-CST-043")
    @DisplayName("账期格式非法：月结与查询都返回参数错误")
    void invalidPeriodRejected() {
        assertThatThrownBy(() -> costs.closeMonthly("2026-13", "req-043"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ErrorCode.SYS_VALIDATION_ERROR));
        assertThatThrownBy(() -> costs.monthlyRows("2026-13"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ErrorCode.SYS_VALIDATION_ERROR));
    }

    @Test
    @CaseId("TC-CST-044")
    @DisplayName("期间数据变化后重跑：同键行更新且 version 递增")
    void changedDataUpdatesVersion() {
        ledger(10, 100L, SEP);
        ledger(10, 200L, OCT);
        costs.closeMonthly("2026-10", "req-044-a");
        CostMonthlyRow before = costs.monthlyRows("2026-10").get(0);
        assertThat(before.version()).isZero();
        assertThat(before.inboundCostAmount()).isEqualTo(2000);

        ledger(10, 300L, OCT.plusSeconds(2));
        CostApplicationService.MonthlyResult after =
                costs.closeMonthly("2026-10", "req-044-b");

        assertThat(after.duplicate()).isFalse();
        CostMonthlyRow updated = after.items().get(0);
        assertThat(updated.version()).isEqualTo(1);
        assertThat(updated.inboundQty()).isEqualTo(20);
        assertThat(updated.inboundCostAmount()).isEqualTo(5000);
        assertThat(updated.closingCostAmount()).isEqualTo(6000);
    }

    @Test
    @CaseId("TC-CST-045")
    @DisplayName("月结查询：无活动但有期初的账期仍落一行，未月结账期返回空")
    void monthlyQuery() {
        ledger(10, 100L, SEP);
        costs.closeMonthly("2026-09", "req-045-a");

        // 10 月没有任何流水，但 9 月期末形成了期初 → 10 月应有一行，入/出为 0
        CostApplicationService.MonthlyResult october =
                costs.closeMonthly("2026-10", "req-045-b");
        assertThat(october.items()).hasSize(1);
        assertThat(october.items().get(0).openingQty()).isEqualTo(10);
        assertThat(october.items().get(0).inboundQty()).isZero();
        assertThat(october.items().get(0).outboundQty()).isZero();
        assertThat(october.items().get(0).closingQty()).isEqualTo(10);
        assertThat(october.items().get(0).closingCostAmount()).isEqualTo(1000);

        assertThat(costs.monthlyRows("2026-09")).hasSize(1);
        assertThat(costs.monthlyRows("2026-10")).hasSize(1);
        assertThat(costs.monthlyRows("2026-11")).isEmpty();
    }

    @Test
    @CaseId("TC-CST-046")
    @DisplayName("无流水账期：月结成功但无行，duplicate=true")
    void emptyPeriodIsIdempotent() {
        CostApplicationService.MonthlyResult result = costs.closeMonthly("2026-11", "req-046");

        assertThat(result.items()).isEmpty();
        assertThat(result.marginRecordCount()).isZero();
        assertThat(result.duplicate()).isTrue();
    }

    @Test
    @CaseId("TC-CST-055")
    @DisplayName("月结重算后不再有流水的池：旧月结行被删除")
    void staleMonthlyRowDeleted() {
        long ledgerId = ledger(10, 100L, SEP);
        costs.closeMonthly("2026-09", "req-055-a");
        assertThat(costs.monthlyRows("2026-09")).hasSize(1);

        // 模拟数据修正：原流水被撤销后重算，该池在账期内不再有任何成本层
        jdbc.update("DELETE FROM inventory_ledger WHERE id = ?", ledgerId);
        CostApplicationService.MonthlyResult result =
                costs.closeMonthly("2026-09", "req-055-b");

        assertThat(result.duplicate()).isFalse();
        assertThat(result.items()).isEmpty();
        assertThat(costs.monthlyRows("2026-09")).isEmpty();
    }

    // ─────────────────────────── 辅助 ───────────────────────────

    private long ledger(int changeQty, Long unitCost, OffsetDateTime at) {
        return CostTestSupport.ledger(jdbc, skuId, warehouseId, "", changeQty,
                changeQty > 0 ? "PURCHASE_IN" : "SALES_OUT",
                changeQty > 0 ? "PO-" + at.toEpochSecond() : "SO-" + at.toEpochSecond(),
                unitCost, at);
    }

    private OffsetDateTime computedAt(String period) {
        return jdbc.queryForObject("""
                SELECT computed_at FROM cost_monthly
                 WHERE period = ? AND sku_id = ? AND warehouse_id = ?
                """, OffsetDateTime.class, period, skuId, warehouseId);
    }
}
