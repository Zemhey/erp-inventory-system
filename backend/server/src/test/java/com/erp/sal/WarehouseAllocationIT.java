package com.erp.sal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.mdm.application.WarehouseApplicationService;
import com.erp.sal.application.ProductBundleService;
import com.erp.sal.application.SalesOrderApplicationService;
import com.erp.sal.application.ShipmentApplicationService;
import com.erp.sal.domain.LineReservation;
import com.erp.sal.domain.SalesOrder;
import com.erp.sal.domain.SalesOrderLine;
import com.erp.sal.domain.Shipment;
import com.erp.sal.domain.ShipmentLine;
import com.erp.sal.domain.ShipmentPlan;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.support.CaseId;
import com.erp.support.PostgresTestBase;
import com.erp.support.TestDataFactory;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 发货选仓与拆单（FR-SAL-11）。
 *
 * <p>硬验收 5：Σ拆出行数量 == 原订单行数量；单仓不足才拆，够则不拆。
 */
@SpringBootTest
@DisplayName("销售 · 选仓优先级与拆单守恒")
class WarehouseAllocationIT extends PostgresTestBase {

    @Autowired
    private SalesOrderApplicationService salesOrders;
    @Autowired
    private ShipmentApplicationService shipments;
    @Autowired
    private ProductBundleService bundles;
    @Autowired
    private WarehouseApplicationService warehouseService;
    @Autowired
    private JdbcTemplate jdbc;

    private TestDataFactory data;
    private long customerId;
    private long whHigh;    // priority 10
    private long whNormal;  // priority 100
    private long whLow;     // priority 200
    private long skuA;
    private long skuC;

    @BeforeEach
    void setUp() {
        data = new TestDataFactory(jdbc);
        data.truncateAll();
        customerId = data.customer("CUS-ALLOC");
        whHigh = warehouseService.create(new WarehouseApplicationService.CreateCommand(
                "WH-HIGH", "高优先仓", null, true, 10)).id();
        whNormal = warehouseService.create(new WarehouseApplicationService.CreateCommand(
                "WH-NORMAL", "普通仓", null, true, 100)).id();
        whLow = warehouseService.create(new WarehouseApplicationService.CreateCommand(
                "WH-LOW", "低优先仓", null, true, 200)).id();
        long productId = data.product("P-ALLOC", "选仓测试商品");
        skuA = data.sku(productId, "白色", "L");
        skuC = data.sku(productId, "蓝色", "S");
    }

    @Test
    @CaseId("TC-SAL-047")
    @DisplayName("★ 单仓足够不拆：高优先仓有 20 件，买 15 → 只有一个仓的分配与一张发货单")
    void singleWarehouseEnough_doesNotSplit() {
        stock(skuA, whHigh, 20);
        stock(skuA, whNormal, 20);

        SalesOrder order = createAuto("req-alloc-single", line(skuA, 15));

        assertThat(order.warehouseId()).isEqualTo(whHigh);
        assertThat(order.lines().get(0).reservations())
                .allSatisfy(r -> assertThat(r.warehouseId()).isEqualTo(whHigh));
        assertThat(sumReserved(order.lines().get(0))).isEqualTo(15);
        assertThat(data.reserved(skuA, whNormal, "")).isZero();

        salesOrders.submit(order.id());
        salesOrders.approve(order.id());
        ShipmentPlan plan = shipments.createPlan(order.id(), "req-alloc-single-plan");
        assertThat(plan.shipments()).hasSize(1);
        assertThat(plan.shipments().get(0).warehouseId()).isEqualTo(whHigh);
        assertThat(plan.shipments().get(0).lines().stream()
                .mapToInt(ShipmentLine::plannedQty).sum()).isEqualTo(15);
    }

    @Test
    @CaseId("TC-SAL-048")
    @DisplayName("★ 单仓不足才拆：高优先仓 10，普通仓 10，买 15 → 10+5，低优先仓不动")
    void insufficientSingleWarehouse_splitsInPriorityOrder() {
        stock(skuA, whHigh, 10);
        stock(skuA, whNormal, 10);
        stock(skuA, whLow, 10);

        SalesOrder order = createAuto("req-alloc-split", line(skuA, 15));

        Map<Long, Integer> byWarehouse = allocationByWarehouse(order.lines().get(0));
        assertThat(byWarehouse).containsEntry(whHigh, 10).containsEntry(whNormal, 5);
        assertThat(byWarehouse).doesNotContainKey(whLow);
        assertThat(byWarehouse.values().stream().mapToInt(Integer::intValue).sum())
                .as("Σ拆出行数量 == 原订单行数量").isEqualTo(15);
    }

    @Test
    @CaseId("TC-SAL-049")
    @DisplayName("优先级顺序：高优先仓先吃满，再吃普通仓（两仓各 5 件买 10）")
    void allocation_followsPriority() {
        stock(skuA, whHigh, 5);
        stock(skuA, whNormal, 5);

        SalesOrder order = createAuto("req-alloc-priority", line(skuA, 10));

        assertThat(allocationByWarehouse(order.lines().get(0)))
                .containsEntry(whHigh, 5)
                .containsEntry(whNormal, 5);
    }

    @Test
    @CaseId("TC-SAL-050")
    @DisplayName("所有启用仓合计不足：整单回滚，无订单/预占/发货计划")
    void allWarehousesInsufficient_rollsBackEverything() {
        stock(skuA, whHigh, 3);
        stock(skuA, whNormal, 4);
        stock(skuA, whLow, 5);

        assertThatThrownBy(() -> createAuto("req-alloc-short", line(skuA, 20)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.INV_INSUFFICIENT_STOCK);

        assertThat(count("sales_orders")).isZero();
        assertThat(count("inventory_reservations")).isZero();
        assertThat(count("so_shipment_plans")).isZero();
        assertThat(data.reserved(skuA, whHigh, "")).isZero();
        assertThat(data.reserved(skuA, whNormal, "")).isZero();
        assertThat(data.reserved(skuA, whLow, "")).isZero();
    }

    @Test
    @CaseId("TC-SAL-051")
    @DisplayName("停用仓不参与自动选仓：高优先仓停用且有 100 件，仍从普通仓出")
    void disabledWarehouse_isExcluded() {
        warehouseService.disable(whHigh);
        stock(skuA, whHigh, 100);
        stock(skuA, whNormal, 10);

        SalesOrder order = createAuto("req-alloc-disabled", line(skuA, 5));

        assertThat(allocationByWarehouse(order.lines().get(0)))
                .containsExactly(Map.entry(whNormal, 5));
        assertThat(data.reserved(skuA, whHigh, "")).isZero();
    }

    @Test
    @CaseId("TC-SAL-052")
    @DisplayName("★ 套装自动选仓：按每仓成套量吃量，组件同仓成套（高优 2 套 + 普通 2 套）")
    void bundleAllocation_keepsComponentsInSameWarehouse() {
        long bundleId = bundles.create(new ProductBundleService.CreateCommand(
                "BUNDLE-ALLOC", "选仓套装", 0L, true,
                List.of(new ProductBundleService.ItemCommand(skuA, 2),
                        new ProductBundleService.ItemCommand(skuC, 3)))).id();
        // 高优先仓：A4/B6 → 2 套；普通仓：A10/B15 → 5 套
        stock(skuA, whHigh, 4);
        stock(skuC, whHigh, 6);
        stock(skuA, whNormal, 10);
        stock(skuC, whNormal, 15);

        SalesOrder order = salesOrders.createAutoSplit(new SalesOrderApplicationService.CreateCommand(
                customerId, 0L, LocalDate.of(2025, 6, 1), "套装选仓",
                List.of(new SalesOrderApplicationService.CreateLineCommand(0, 4, 0L, null, bundleId)),
                "req-alloc-bundle"));

        SalesOrderLine attrA = order.lines().stream()
                .filter(l -> l.skuId() == skuA).findFirst().orElseThrow();
        Map<Long, Integer> aByWarehouse = allocationByWarehouse(attrA);
        assertThat(aByWarehouse).containsEntry(whHigh, 4).containsEntry(whNormal, 4);
        assertThat(aByWarehouse.values().stream().mapToInt(Integer::intValue).sum())
                .as("组件 A 总量 == 4 套 × 配比 2").isEqualTo(8);
        SalesOrderLine attrC = order.lines().stream()
                .filter(l -> l.skuId() == skuC).findFirst().orElseThrow();
        assertThat(allocationByWarehouse(attrC))
                .containsEntry(whHigh, 6).containsEntry(whNormal, 6);
    }

    @Test
    @CaseId("TC-SAL-053")
    @DisplayName("多行自动选仓：每行各自守恒，互不吞量")
    void multiLineAllocation_eachLineConserved() {
        stock(skuA, whHigh, 10);
        stock(skuA, whNormal, 10);
        stock(skuC, whNormal, 5);

        SalesOrder order = createAuto("req-alloc-multi", line(skuA, 15), line(skuC, 5));

        SalesOrderLine lineA = order.lines().stream()
                .filter(l -> l.skuId() == skuA).findFirst().orElseThrow();
        SalesOrderLine lineC = order.lines().stream()
                .filter(l -> l.skuId() == skuC).findFirst().orElseThrow();
        assertThat(sumReserved(lineA)).isEqualTo(15);
        assertThat(allocationByWarehouse(lineA)).containsEntry(whHigh, 10).containsEntry(whNormal, 5);
        assertThat(sumReserved(lineC)).isEqualTo(5);
        assertThat(allocationByWarehouse(lineC)).containsExactly(Map.entry(whNormal, 5));
    }

    @Test
    @CaseId("TC-SAL-054")
    @DisplayName("★ 跨仓拆单：订单 15 拆成 10+5 → 两张发货单，Σ拣货行 == 原订单行数量")
    void splitOrder_createsSplitShipmentsConservingQuantity() {
        stock(skuA, whHigh, 10);
        stock(skuA, whNormal, 10);
        SalesOrder order = createAuto("req-alloc-ship-plan", line(skuA, 15));

        salesOrders.submit(order.id());
        salesOrders.approve(order.id());
        ShipmentPlan plan = shipments.createPlan(order.id(), "req-alloc-ship-plan");

        assertThat(plan.shipments()).hasSize(2);
        Map<Long, Integer> plannedByWarehouse = new LinkedHashMap<>();
        for (Shipment shipment : plan.shipments()) {
            plannedByWarehouse.put(shipment.warehouseId(),
                    shipment.lines().stream().mapToInt(ShipmentLine::plannedQty).sum());
        }
        assertThat(plannedByWarehouse).containsEntry(whHigh, 10).containsEntry(whNormal, 5);
        assertThat(plannedByWarehouse.values().stream().mapToInt(Integer::intValue).sum())
                .as("Σ拆单发货行数量 == 原订单行数量").isEqualTo(15);
    }

    // ─────────────────────────── 辅助 ───────────────────────────

    private SalesOrderApplicationService.CreateLineCommand line(long skuId, int qty) {
        return new SalesOrderApplicationService.CreateLineCommand(skuId, qty, 1000L, null);
    }

    private SalesOrder createAuto(String requestId,
                                  SalesOrderApplicationService.CreateLineCommand... lines) {
        return salesOrders.createAutoSplit(new SalesOrderApplicationService.CreateCommand(
                customerId, 0L, LocalDate.of(2025, 6, 1), "自动选仓",
                List.of(lines), requestId));
    }

    private void stock(long skuId, long warehouseId, int qty) {
        data.stock(skuId, warehouseId, "", null, qty);
        data.ledger(skuId, warehouseId, "", qty, "PURCHASE_IN",
                "SEED-" + skuId + "-" + warehouseId);
    }

    private static Map<Long, Integer> allocationByWarehouse(SalesOrderLine line) {
        Map<Long, Integer> byWarehouse = new LinkedHashMap<>();
        for (LineReservation r : line.reservations()) {
            byWarehouse.merge(r.warehouseId(), r.reservedQty(), Integer::sum);
        }
        return byWarehouse;
    }

    private static int sumReserved(SalesOrderLine line) {
        return line.reservations().stream().mapToInt(LineReservation::reservedQty).sum();
    }

    private int count(String table) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return n == null ? 0 : n;
    }
}
