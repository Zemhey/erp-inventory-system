package com.erp.rpt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.rpt.domain.CustomerContributionResult;
import com.erp.rpt.domain.CustomerContributionRow;
import com.erp.rpt.domain.CustomerContributionSummary;
import com.erp.rpt.domain.ReportGroupBy;
import com.erp.rpt.domain.ReportRow;
import com.erp.shared.error.BusinessException;
import com.erp.support.CaseId;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 客户贡献与复购（FR-RPT-07，TC-RPT-028~034）。
 *
 * <p>与出货统计共用同一金额公式和状态集合：本类把两张表按客户逐格对齐；
 * 复购、新客/回头客再从独立历史构造断言。
 */
@DisplayName("报表 · 客户贡献与复购（与出货一致、Top N、新客/回头客）")
class ReportContributionIT extends ReportV28TestSupport {

    @Test
    @CaseId("TC-RPT-028")
    @DisplayName("★ 与出货统计逐格一致：同区间同仓库的数量/金额/单据数完全相同")
    void contribution_matchesSalesReport_byCustomer() {
        seedContributionData();

        CustomerContributionResult result = reports.customerContribution(
                RANGE_FROM, RANGE_TO, null, 200);
        Map<String, ReportRow> sales = reports.salesReport(
                        RANGE_FROM, RANGE_TO, ReportGroupBy.CUSTOMER, null).stream()
                .collect(Collectors.toMap(ReportRow::key, Function.identity()));

        assertThat(result.items()).hasSize(3);
        for (CustomerContributionRow row : result.items()) {
            ReportRow s = sales.get(row.customerCode());
            assertThat(s).as("客户 %s 必须出现在出货统计里", row.customerCode()).isNotNull();
            assertThat(new long[]{row.orderCount(), row.qty(), row.amount()})
                    .as("客户 %s 的单据数/数量/金额", row.customerCode())
                    .containsExactly(s.orderCount(), s.qty(), s.amount());
        }
        assertThat(sales).hasSize(3);
        long salesQty = sales.values().stream().mapToLong(ReportRow::qty).sum();
        long salesAmount = sales.values().stream().mapToLong(ReportRow::amount).sum();
        assertThat(result.summary().totalQty()).isEqualTo(salesQty);
        assertThat(result.summary().totalAmount()).isEqualTo(salesAmount);
    }

    @Test
    @CaseId("TC-RPT-029")
    @DisplayName("复购 = 期间单据数 ≥ 2：两单为复购，一单不是")
    void repurchaseFlag_needsAtLeastTwoOrders() {
        seedContributionData();
        var items = reports.customerContribution(RANGE_FROM, RANGE_TO, null, 200).items();

        assertThat(byCode(items, "CUS-V28-1").orderCount()).isEqualTo(2);
        assertThat(byCode(items, "CUS-V28-1").repurchase()).isTrue();
        assertThat(byCode(items, "CUS-V28-2").orderCount()).isEqualTo(1);
        assertThat(byCode(items, "CUS-V28-2").repurchase()).isFalse();
        assertThat(byCode(items, "CUS-V28-3").repurchase()).isFalse();
    }

    @Test
    @CaseId("TC-RPT-030")
    @DisplayName("新客 = from 之前无已执行出货；历史客户为回头客；只有历史单的客户不出行")
    void newVsReturning_usesHistoryBeforeFrom() {
        seedContributionData();
        var items = reports.customerContribution(RANGE_FROM, RANGE_TO, null, 200).items();

        assertThat(byCode(items, "CUS-V28-2").newCustomer()).as("CUS-2 没有 3 月前历史").isTrue();
        assertThat(byCode(items, "CUS-V28-1").newCustomer()).as("CUS-1 有 1 月历史").isFalse();
        assertThat(byCode(items, "CUS-V28-3").newCustomer()).isFalse();
        assertThat(items).extracting(CustomerContributionRow::customerCode)
                .as("CUS-4 只有历史单，不进本期贡献").doesNotContain("CUS-V28-4");
    }

    @Test
    @CaseId("TC-RPT-031")
    @DisplayName("汇总覆盖全部客户：复购率万分比 3333、新客/回头客、总数守恒")
    void summary_isOverAllCustomers() {
        seedContributionData();
        CustomerContributionSummary s = reports.customerContribution(
                RANGE_FROM, RANGE_TO, null, 200).summary();

        assertThat(s.customerCount()).isEqualTo(3);
        assertThat(s.repurchaseCount()).isEqualTo(1);
        assertThat(s.repurchaseRateBp()).as("1/3 四舍五入 → 3333").isEqualTo(3333);
        assertThat(s.newCustomerCount()).isEqualTo(1);
        assertThat(s.returningCustomerCount()).isEqualTo(2);
        assertThat(s.orderCount()).isEqualTo(4);
        assertThat(s.totalQty()).isEqualTo(10);
        // 2000 + 2700 + 4000 + 1000 = 9700
        assertThat(s.totalAmount()).isEqualTo(9700);
    }

    @Test
    @CaseId("TC-RPT-032")
    @DisplayName("Top N 只截断 items，summary 仍覆盖全量；limit 越界 400")
    void topN_truncatesItemsOnly() {
        seedContributionData();
        CustomerContributionResult top1 = reports.customerContribution(
                RANGE_FROM, RANGE_TO, null, 1);

        assertThat(top1.items()).extracting(CustomerContributionRow::customerCode)
                .as("金额最高的 CUS-1 单独入选").containsExactly("CUS-V28-1");
        assertThat(top1.summary().customerCount()).as("汇总不受 Top N 影响").isEqualTo(3);
        assertThat(top1.summary().totalAmount()).isEqualTo(9700);

        assertThatThrownBy(() -> reports.customerContribution(RANGE_FROM, RANGE_TO, null, 0))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("limit");
        assertThatThrownBy(() -> reports.customerContribution(RANGE_FROM, RANGE_TO, null, 201))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> reports.customerContribution(RANGE_TO, RANGE_FROM, null, 20))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("from");
    }

    @Test
    @CaseId("TC-RPT-033")
    @DisplayName("仓库过滤：分仓贡献之和 == 全部，且各自与同仓出货统计一致")
    void warehouseFilter_reconcilesWithSales() {
        seedContributionData();
        at(LocalDateTime.of(2026, 3, 4, 9, 0));
        purchase("po-con-w2", supplier2, wh2, LocalDate.of(2026, 3, 4), sku1, 50, 1000);
        at(LocalDateTime.of(2026, 3, 5, 9, 0));
        ship("so-con-w2", customer2, wh2, LocalDate.of(2026, 3, 5), sku1, 2, 1000, 10000);

        var all = reports.customerContribution(RANGE_FROM, RANGE_TO, null, 200);
        var wh1Result = reports.customerContribution(RANGE_FROM, RANGE_TO, wh1, 200);
        var wh2Result = reports.customerContribution(RANGE_FROM, RANGE_TO, wh2, 200);

        assertThat(wh1Result.items()).extracting(CustomerContributionRow::customerCode)
                .containsExactlyInAnyOrder("CUS-V28-1", "CUS-V28-2", "CUS-V28-3");
        assertThat(wh2Result.items()).extracting(CustomerContributionRow::customerCode)
                .containsExactly("CUS-V28-2");
        assertThat(wh2Result.items().get(0).amount()).isEqualTo(2000);

        assertThat(wh1Result.summary().totalAmount() + wh2Result.summary().totalAmount())
                .as("分仓金额之和必须等于全部")
                .isEqualTo(all.summary().totalAmount());
        assertThat(wh1Result.summary().totalQty() + wh2Result.summary().totalQty())
                .isEqualTo(all.summary().totalQty());

        Map<String, ReportRow> salesWh2 = reports.salesReport(
                        RANGE_FROM, RANGE_TO, ReportGroupBy.CUSTOMER, wh2).stream()
                .collect(Collectors.toMap(ReportRow::key, Function.identity()));
        assertThat(salesWh2.get("CUS-V28-2").amount()).isEqualTo(2000);
        assertThat(wh2Result.items().get(0).qty()).isEqualTo(salesWh2.get("CUS-V28-2").qty());
    }

    @Test
    @CaseId("TC-RPT-034")
    @DisplayName("空区间：items 为空、summary 全零、复购率 0，不除以零")
    void emptyRange_returnsZeroSummary() {
        var result = reports.customerContribution(
                LocalDate.of(2020, 1, 1), LocalDate.of(2020, 1, 31), null, 20);
        assertThat(result.items()).isEmpty();
        CustomerContributionSummary s = result.summary();
        assertThat(s.customerCount()).isZero();
        assertThat(s.repurchaseCount()).isZero();
        assertThat(s.repurchaseRateBp()).isZero();
        assertThat(s.newCustomerCount()).isZero();
        assertThat(s.returningCustomerCount()).isZero();
        assertThat(s.totalQty()).isZero();
        assertThat(s.totalAmount()).isZero();
        assertThat(s.orderCount()).isZero();
    }

    // ─────────────────────────── 造数与辅助 ───────────────────────────

    /**
     * CUS-1：1 月历史 + 3 月两单（4700 / 5 件 / 2 单）→ 回头客 + 复购；
     * CUS-2：3 月一单（4000 / 4 件）→ 新客；
     * CUS-3：1 月历史 + 3 月一单（1000 / 1 件）→ 回头客，不复购；
     * CUS-4：只有 1 月历史 → 不出行。
     */
    private void seedContributionData() {
        long customer4 = data.customer("CUS-V28-4", 10000);
        at(LocalDateTime.of(2026, 1, 5, 9, 0));
        purchase("po-con-stock", supplier1, wh1, LocalDate.of(2026, 1, 5), sku1, 200, 1000);
        at(LocalDateTime.of(2026, 1, 15, 9, 0));
        ship("so-con-hist1", customer1, wh1, LocalDate.of(2026, 1, 15), sku1, 1, 1000, 10000);
        ship("so-con-hist3", customer3, wh1, LocalDate.of(2026, 1, 15), sku1, 1, 1000, 10000);
        ship("so-con-hist4", customer4, wh1, LocalDate.of(2026, 1, 15), sku1, 1, 1000, 10000);
        at(LocalDateTime.of(2026, 3, 3, 9, 0));
        ship("so-con-1a", customer1, wh1, LocalDate.of(2026, 3, 3), sku1, 2, 1000, 10000);
        at(LocalDateTime.of(2026, 3, 10, 9, 0));
        ship("so-con-1b", customer1, wh1, LocalDate.of(2026, 3, 10), sku1, 3, 1000, 9000);
        at(LocalDateTime.of(2026, 3, 7, 9, 0));
        ship("so-con-2", customer2, wh1, LocalDate.of(2026, 3, 7), sku1, 4, 1000, 10000);
        at(LocalDateTime.of(2026, 3, 12, 9, 0));
        ship("so-con-3", customer3, wh1, LocalDate.of(2026, 3, 12), sku1, 1, 1000, 10000);
        at(DEFAULT_NOW);
    }

    private static CustomerContributionRow byCode(List<CustomerContributionRow> rows,
                                                  String code) {
        return rows.stream().filter(r -> r.customerCode().equals(code)).findFirst()
                .orElseThrow(() -> new AssertionError("缺少客户 " + code));
    }
}
