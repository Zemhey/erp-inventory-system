package com.erp.cst;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.cst.application.CostApplicationService;
import com.erp.cst.application.MarginApplicationService;
import com.erp.cst.domain.MarginRecord;
import com.erp.cst.domain.MarginSummaryRow;
import com.erp.cst.repository.CostLayerRepository;
import com.erp.cst.repository.MarginRecordRepository;
import com.erp.pur.application.PurchaseOrderApplicationService;
import com.erp.pur.domain.PurchaseOrder;
import com.erp.rma.application.ReturnOrderApplicationService;
import com.erp.rma.domain.ReturnOrder;
import com.erp.sal.application.SalesOrderApplicationService;
import com.erp.sal.domain.SalesOrder;
import com.erp.shared.error.BusinessException;
import com.erp.support.CaseId;
import com.erp.support.PostgresTestBase;
import com.erp.support.TestDataFactory;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** 毛利分析（FR-CST-03）：净额口径、商品/客户/期间维度、退货冲减。 */
@SpringBootTest
@DisplayName("成本 · 毛利分析")
class MarginAnalysisIT extends PostgresTestBase {

    private static final LocalDate OCT = LocalDate.of(2026, 10, 8);
    private static final LocalDate NOV = LocalDate.of(2026, 11, 8);

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private CostApplicationService costs;
    @Autowired
    private MarginApplicationService marginService;
    @Autowired
    private MarginRecordRepository margins;
    @Autowired
    private CostLayerRepository layers;
    @Autowired
    private PurchaseOrderApplicationService purchases;
    @Autowired
    private SalesOrderApplicationService sales;
    @Autowired
    private ReturnOrderApplicationService returns;

    private TestDataFactory data;
    private long supplierId;
    private long warehouseId;
    private long customerId;
    private long skuId;

    @BeforeEach
    void setUp() {
        data = new TestDataFactory(jdbc);
        data.truncateAll();
        supplierId = data.supplier("SUP-CST-MARGIN");
        warehouseId = data.warehouse("WH-CST-MARGIN");
        customerId = data.customer("CUS-CST-MARGIN");
        long productId = data.product("P-CST-MARGIN", "毛利商品");
        skuId = data.sku(productId, "红色", "S");
    }

    @Test
    @CaseId("TC-CST-047")
    @DisplayName("成本月结生成毛利记录：收入、成本、毛利与毛利率")
    void marginRecordGenerated() {
        receivePurchase(10, 1000, "req-047-po");
        shipSale(customerId, 4, 2000, OCT, "req-047-so");

        CostApplicationService.MonthlyResult result = costs.closeMonthly("2026-10", "req-047-m");

        assertThat(result.marginRecordCount()).isEqualTo(1);
        MarginRecord record = margins.findByPeriod("2026-10").get(0);
        assertThat(record.qty()).isEqualTo(4);
        assertThat(record.revenueAmount()).isEqualTo(8000);
        assertThat(record.costAmount()).isEqualTo(4000);
        assertThat(record.grossProfitAmount()).isEqualTo(4000);
        assertThat(record.grossMarginRateBp()).isEqualTo(5000);
    }

    @Test
    @CaseId("TC-CST-048")
    @DisplayName("毛利成本 = SALES_OUT 成本层合计")
    void marginCostEqualsOutboundCost() {
        receivePurchase(10, 1000, "req-048-po");
        shipSale(customerId, 4, 2000, OCT, "req-048-so");
        costs.closeMonthly("2026-10", "req-048-m");

        long outboundCost = layers.findAll().stream()
                .filter(l -> l.bizType().equals("SALES_OUT"))
                .mapToLong(l -> l.totalCostAmount())
                .sum();
        assertThat(margins.findByPeriod("2026-10").get(0).costAmount()).isEqualTo(outboundCost);
    }

    @Test
    @CaseId("TC-CST-049")
    @DisplayName("按商品聚合：同 SKU 多张出货单合并，orderCount 去重")
    void groupByProduct() {
        receivePurchase(10, 1000, "req-049-po");
        shipSale(customerId, 4, 2000, OCT, "req-049-so-1");
        shipSale(customerId, 2, 3000, OCT, "req-049-so-2");
        costs.closeMonthly("2026-10", "req-049-m");

        List<MarginSummaryRow> rows = marginService.analyze("2026-10", "2026-10", "product");

        assertThat(rows).hasSize(1);
        MarginSummaryRow row = rows.get(0);
        assertThat(row.key()).isEqualTo(String.valueOf(skuId));
        assertThat(row.label()).contains("毛利商品").contains("红色-S");
        assertThat(row.qty()).isEqualTo(6);
        assertThat(row.revenueAmount()).isEqualTo(14_000);
        assertThat(row.costAmount()).isEqualTo(6_000);
        assertThat(row.grossProfitAmount()).isEqualTo(8_000);
        assertThat(row.grossMarginRateBp()).isEqualTo(5714);
        assertThat(row.orderCount()).isEqualTo(2);
    }

    @Test
    @CaseId("TC-CST-050")
    @DisplayName("按客户聚合：不同客户各一行，金额按客户分组")
    void groupByCustomer() {
        long secondCustomer = data.customer("CUS-CST-MARGIN-2");
        receivePurchase(10, 1000, "req-050-po");
        shipSale(customerId, 2, 2000, OCT, "req-050-so-1");
        shipSale(secondCustomer, 3, 2000, OCT, "req-050-so-2");
        costs.closeMonthly("2026-10", "req-050-m");

        List<MarginSummaryRow> rows = marginService.analyze("2026-10", "2026-10", "customer");

        assertThat(rows).hasSize(2);
        MarginSummaryRow first = rows.stream()
                .filter(r -> r.key().equals(String.valueOf(customerId))).findFirst().orElseThrow();
        MarginSummaryRow second = rows.stream()
                .filter(r -> r.key().equals(String.valueOf(secondCustomer)))
                .findFirst().orElseThrow();
        assertThat(first.qty()).isEqualTo(2);
        assertThat(first.revenueAmount()).isEqualTo(4000);
        assertThat(second.qty()).isEqualTo(3);
        assertThat(second.revenueAmount()).isEqualTo(6000);
        assertThat(first.label()).contains("CUS-CST-MARGIN");
        assertThat(second.label()).contains("CUS-CST-MARGIN-2");
    }

    @Test
    @CaseId("TC-CST-051")
    @DisplayName("按期间聚合：两个账期各一行，收入按期间归属")
    void groupByPeriod() {
        receivePurchase(10, 1000, "req-051-po");
        shipSale(customerId, 2, 2000, OCT, "req-051-so-oct");
        shipSale(customerId, 3, 2000, NOV, "req-051-so-nov");
        costs.closeMonthly("2026-10", "req-051-m-oct");
        costs.closeMonthly("2026-11", "req-051-m-nov");

        List<MarginSummaryRow> rows = marginService.analyze("2026-10", "2026-11", "period");

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).key()).isEqualTo("2026-10");
        assertThat(rows.get(0).revenueAmount()).isEqualTo(4000);
        assertThat(rows.get(1).key()).isEqualTo("2026-11");
        assertThat(rows.get(1).revenueAmount()).isEqualTo(6000);
    }

    @Test
    @CaseId("TC-CST-052")
    @DisplayName("退货净额：数量、收入、成本同时冲减，毛利率按净额重算")
    void returnNetsMargin() {
        receivePurchase(10, 1000, "req-052-po");
        SalesOrder so = shipSale(customerId, 4, 2000, OCT, "req-052-so");
        costs.closeMonthly("2026-10", "req-052-m1");
        assertThat(margins.findByPeriod("2026-10").get(0).qty()).isEqualTo(4);

        returnGoods(so, 1, "req-052-ro");
        costs.closeMonthly("2026-10", "req-052-m2");

        MarginRecord record = margins.findByPeriod("2026-10").get(0);
        assertThat(record.qty()).isEqualTo(3);
        assertThat(record.revenueAmount()).isEqualTo(6000);
        assertThat(record.costAmount()).isEqualTo(3000);
        assertThat(record.grossProfitAmount()).isEqualTo(3000);
        assertThat(record.grossMarginRateBp()).isEqualTo(5000);
    }

    @Test
    @CaseId("TC-CST-053")
    @DisplayName("毛利查询：空账期返回空；区间/维度非法返回 400")
    void analyzeValidationAndEmpty() {
        assertThat(marginService.analyze("2026-10", "2026-10", "product")).isEmpty();

        assertThatThrownBy(() -> marginService.analyze("2026-12", "2026-10", "product"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode().name())
                        .isEqualTo("SYS_VALIDATION_ERROR"));
        assertThatThrownBy(() -> marginService.analyze("2026-10", "2026-10", "unknown"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode().name())
                        .isEqualTo("SYS_VALIDATION_ERROR"));
    }

    @Test
    @CaseId("TC-CST-054")
    @DisplayName("收入为 0：毛利率记 0，但成本与毛利按实际金额保留")
    void zeroRevenueKeepsZeroRate() {
        receivePurchase(1, 100, "req-054-po");
        shipSale(customerId, 1, 0, OCT, "req-054-so");
        costs.closeMonthly("2026-10", "req-054-m");

        MarginRecord record = margins.findByPeriod("2026-10").get(0);
        assertThat(record.revenueAmount()).isZero();
        assertThat(record.costAmount()).isEqualTo(100);
        assertThat(record.grossProfitAmount()).isEqualTo(-100);
        assertThat(record.grossMarginRateBp()).isZero();
    }

    @Test
    @CaseId("TC-CST-056")
    @DisplayName("完全退货：净数量为 0 的毛利记录被删除")
    void fullyReturnedRecordRemoved() {
        receivePurchase(2, 1000, "req-056-po");
        SalesOrder so = shipSale(customerId, 2, 2000, OCT, "req-056-so");
        costs.closeMonthly("2026-10", "req-056-m1");
        assertThat(margins.findByPeriod("2026-10")).hasSize(1);

        returnGoods(so, 2, "req-056-ro");
        CostApplicationService.MonthlyResult result =
                costs.closeMonthly("2026-10", "req-056-m2");

        assertThat(result.duplicate()).isFalse();
        assertThat(result.marginRecordCount()).isZero();
        assertThat(margins.findByPeriod("2026-10")).isEmpty();
    }

    // ─────────────────────────── 辅助 ───────────────────────────

    private void receivePurchase(int qty, long unitPrice, String requestId) {
        PurchaseOrder po = purchases.create(new PurchaseOrderApplicationService.CreateCommand(
                supplierId, warehouseId, OCT, "毛利分析",
                List.of(new PurchaseOrderApplicationService.CreateLineCommand(
                        skuId, qty, unitPrice)), requestId));
        purchases.submit(po.id());
        purchases.approve(po.id());
        purchases.receive(po.id(), new PurchaseOrderApplicationService.ReceiveCommand(
                List.of(new PurchaseOrderApplicationService.ReceiveLineCommand(
                        po.lines().get(0).id(), qty, null, null, null)), requestId + "-recv"));
    }

    private SalesOrder shipSale(long customer, int qty, long unitPrice, LocalDate bizDate,
                                String requestId) {
        SalesOrder so = sales.create(new SalesOrderApplicationService.CreateCommand(
                customer, warehouseId, bizDate, "毛利分析",
                List.of(new SalesOrderApplicationService.CreateLineCommand(
                        skuId, qty, unitPrice, 10000)), requestId));
        sales.submit(so.id());
        sales.approve(so.id());
        return sales.ship(so.id(), requestId + "-ship");
    }

    private ReturnOrder returnGoods(SalesOrder so, int qty, String requestId) {
        ReturnOrder ro = returns.create(new ReturnOrderApplicationService.CreateCommand(
                so.id(), OCT.plusDays(1), "毛利退货",
                List.of(new ReturnOrderApplicationService.CreateLineCommand(
                        so.lines().get(0).id(), qty, "", null, null)), requestId));
        returns.submit(ro.id());
        returns.approve(ro.id());
        return returns.receive(ro.id(), requestId + "-recv");
    }
}
