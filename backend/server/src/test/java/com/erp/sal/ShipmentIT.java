package com.erp.sal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.inv.application.InventoryApplicationService;
import com.erp.mdm.application.WarehouseApplicationService;
import com.erp.sal.application.SalesOrderApplicationService;
import com.erp.sal.application.ShipmentApplicationService;
import com.erp.sal.domain.CustomerReceivable;
import com.erp.sal.domain.SalesOrder;
import com.erp.sal.domain.Shipment;
import com.erp.sal.domain.ShipmentLine;
import com.erp.sal.domain.ShipmentPlan;
import com.erp.sal.domain.ShipmentStatus;
import com.erp.sal.repository.ReceivableRepository;
import com.erp.shared.document.DocumentStatus;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.time.ClockProvider;
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

/**
 * 拣货/发货作业（FR-SAL-06）。
 *
 * <p>覆盖：计划守恒、扫码累计与超扫、确认出库、幂等、跨仓拆单、部分发货、应收联动。
 */
@SpringBootTest
@DisplayName("销售 · 拣货发货（扫码确认）")
class ShipmentIT extends PostgresTestBase {

    @Autowired
    private SalesOrderApplicationService salesOrders;
    @Autowired
    private ShipmentApplicationService shipments;
    @Autowired
    private InventoryApplicationService inventory;
    @Autowired
    private WarehouseApplicationService warehouseService;
    @Autowired
    private ReceivableRepository receivables;
    @Autowired
    private ClockProvider clock;
    @Autowired
    private JdbcTemplate jdbc;

    private TestDataFactory data;
    private long customerId;
    private long warehouseId;
    private long skuA;
    private long skuB;

    @BeforeEach
    void setUp() {
        data = new TestDataFactory(jdbc);
        data.truncateAll();
        customerId = data.customer("CUS-SHIP");
        warehouseId = data.warehouse("WH-SHIP");
        long productId = data.product("P-SHIP", "发货测试商品");
        skuA = data.sku(productId, "白色", "L");
        skuB = data.sku(productId, "黑色", "M");
    }

    @Test
    @CaseId("TC-SAL-055")
    @DisplayName("★ 计划守恒：单仓订单生成一张发货单，Σ拣货行 == 未出量")
    void createPlan_conservesOpenQuantity() {
        SalesOrder order = approvedOrder(10, "req-ship-plan");
        ShipmentPlan plan = shipments.createPlan(order.id(), "req-ship-plan-1");

        assertThat(plan.shipments()).hasSize(1);
        Shipment shipment = plan.shipments().get(0);
        assertThat(shipment.status()).isEqualTo(ShipmentStatus.DRAFT);
        assertThat(shipment.warehouseId()).isEqualTo(warehouseId);
        assertThat(shipment.lines()).hasSize(1);
        assertThat(shipment.lines().get(0).plannedQty()).isEqualTo(10);
        assertThat(shipment.lines().get(0).reservationId()).isNotNull();
        assertThat(sumPlanned(shipment)).isEqualTo(10);
    }

    @Test
    @CaseId("TC-SAL-056")
    @DisplayName("计划幂等：同键重放返回首次计划，不重复拆行/不重复占用预占")
    void createPlan_isIdempotent() {
        SalesOrder order = approvedOrder(6, "req-ship-idem");
        ShipmentPlan first = shipments.createPlan(order.id(), "req-ship-idem-plan");
        ShipmentPlan second = shipments.createPlan(order.id(), "req-ship-idem-plan");

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.shipments()).hasSize(1);
        assertThat(count("so_shipments")).isEqualTo(1);
        assertThat(count("shipment_lines")).isEqualTo(first.shipments().get(0).lines().size());
    }

    @Test
    @CaseId("TC-SAL-057")
    @DisplayName("不同键重复计划同一批预占 → SAL_STATUS_ILLEGAL（防重复占用）")
    void createPlan_withDifferentKey_thenReject() {
        SalesOrder order = approvedOrder(3, "req-ship-double");
        shipments.createPlan(order.id(), "req-ship-double-1");

        assertThatThrownBy(() -> shipments.createPlan(order.id(), "req-ship-double-2"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SAL_STATUS_ILLEGAL);
        assertThat(count("so_shipments")).isEqualTo(1);
    }

    @Test
    @CaseId("TC-SAL-058")
    @DisplayName("★ 扫码累计：部分扫码 → PICKING；超过计划 → SAL_SHIPMENT_OVER_PICK 且数量不变")
    void scan_accumulatesAndRejectsOverPick() {
        Shipment shipment = planned(approvedOrder(5, "req-ship-scan"), "req-ship-scan-plan");
        ShipmentLine line = shipment.lines().get(0);

        Shipment afterPartial = shipments.scan(shipment.id(), line.id(), 2, null);
        assertThat(afterPartial.status()).isEqualTo(ShipmentStatus.PICKING);
        assertThat(afterPartial.lines().get(0).pickedQty()).isEqualTo(2);

        assertThatThrownBy(() -> shipments.scan(shipment.id(), line.id(), 4, null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SAL_SHIPMENT_OVER_PICK);

        assertThat(shipments.require(shipment.id()).lines().get(0).pickedQty()).isEqualTo(2);
    }

    @Test
    @CaseId("TC-SAL-059")
    @DisplayName("扫码条码校验：匹配则累计，不匹配必须拒绝且不累计")
    void scan_validatesBarcode() {
        jdbc.update("UPDATE skus SET barcode = ? WHERE id = ?", "6901000099", skuA);
        Shipment shipment = planned(approvedOrder(2, "req-ship-barcode"), "req-ship-barcode-plan");
        ShipmentLine line = shipment.lines().get(0);

        assertThatThrownBy(() -> shipments.scan(shipment.id(), line.id(), 1, "WRONG"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SYS_VALIDATION_ERROR);
        assertThat(shipments.require(shipment.id()).lines().get(0).pickedQty()).isZero();

        Shipment after = shipments.scan(shipment.id(), line.id(), 1, "6901000099");
        assertThat(after.lines().get(0).pickedQty()).isEqualTo(1);
    }

    @Test
    @CaseId("TC-SAL-060")
    @DisplayName("未全部扫码不能确认发货；扫满后状态 PICKED")
    void confirm_requiresFullyPicked() {
        Shipment shipment = planned(approvedOrder(4, "req-ship-picked"), "req-ship-picked-plan");
        ShipmentLine line = shipment.lines().get(0);
        shipments.scan(shipment.id(), line.id(), 3, null);

        assertThatThrownBy(() -> shipments.confirm(shipment.id(), "req-ship-confirm-early"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SAL_SHIPMENT_STATUS_ILLEGAL);
        assertThat(data.onHand(skuA, warehouseId, "")).isEqualTo(14);

        Shipment picked = shipments.scan(shipment.id(), line.id(), 1, null);
        assertThat(picked.status()).isEqualTo(ShipmentStatus.PICKED);
    }

    @Test
    @CaseId("TC-SAL-061")
    @DisplayName("★ 确认发货：实存扣减、预占转 CONSUMED、订单 COMPLETED、流水可追溯、INV-2 成立")
    void confirm_shipsAndConsumesReservations() {
        SalesOrder order = approvedOrder(6, "req-ship-confirm");
        Shipment shipment = planned(order, "req-ship-confirm-plan");
        pickAll(shipment);

        Shipment completed = shipments.confirm(shipment.id(), "req-ship-confirm-1");

        assertThat(completed.status()).isEqualTo(ShipmentStatus.COMPLETED);
        assertThat(salesOrders.require(order.id()).status()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(data.onHand(skuA, warehouseId, "")).isEqualTo(10);
        assertThat(data.reserved(skuA, warehouseId, "")).isZero();
        assertThat(reservationStatus(completed.lines().get(0).reservationId()))
                .isEqualTo("CONSUMED");
        assertThat(inventory.ledgerEntries(order.orderNo(), "SALES_OUT"))
                .singleElement()
                .satisfies(e -> assertThat(e.changeQty()).isEqualTo(-6));
        assertThat(inventory.checkLedgerConsistency()).isEmpty();
    }

    @Test
    @CaseId("TC-SAL-062")
    @DisplayName("确认幂等：同键重放不重复扣减；已完成换新键 → 409")
    void confirm_isIdempotent() {
        SalesOrder order = approvedOrder(3, "req-ship-confirm-idem");
        Shipment shipment = planned(order, "req-ship-confirm-idem-plan");
        pickAll(shipment);
        shipments.confirm(shipment.id(), "req-ship-confirm-idem-1");

        Shipment replay = shipments.confirm(shipment.id(), "req-ship-confirm-idem-1");
        assertThat(replay.status()).isEqualTo(ShipmentStatus.COMPLETED);
        assertThat(data.onHand(skuA, warehouseId, "")).as("重放不得二次扣减").isEqualTo(10);
        assertThat(count("inventory_ledger")).isEqualTo(2);   // 种子 + SALES_OUT

        assertThatThrownBy(() -> shipments.confirm(shipment.id(), "req-ship-confirm-idem-2"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SAL_SHIPMENT_STATUS_ILLEGAL);
    }

    @Test
    @CaseId("TC-SAL-063")
    @DisplayName("★ 跨仓拆单发货：两张发货单分别确认，两仓各自扣减且总量守恒")
    void crossWarehouseShipments_deductFromEachWarehouse() {
        long whHigh = warehouseService.create(new WarehouseApplicationService.CreateCommand(
                "WH-SHIP-H", "高优先仓", null, true, 10)).id();
        long whNormal = warehouseService.create(new WarehouseApplicationService.CreateCommand(
                "WH-SHIP-N", "普通仓", null, true, 100)).id();
        stock(skuA, whHigh, 10);
        stock(skuA, whNormal, 10);
        SalesOrder order = salesOrders.createAutoSplit(new SalesOrderApplicationService.CreateCommand(
                customerId, 0L, LocalDate.of(2025, 6, 1), "跨仓",
                List.of(new SalesOrderApplicationService.CreateLineCommand(skuA, 15, 1000L, null)),
                "req-ship-cross"));
        salesOrders.submit(order.id());
        salesOrders.approve(order.id());
        ShipmentPlan plan = shipments.createPlan(order.id(), "req-ship-cross-plan");
        assertThat(plan.shipments()).hasSize(2);

        for (Shipment shipment : plan.shipments()) {
            pickAll(shipment);
            shipments.confirm(shipment.id(), "req-ship-cross-" + shipment.warehouseId());
        }

        assertThat(salesOrders.require(order.id()).status()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(data.onHand(skuA, whHigh, "")).as("高优先仓应被扣 10").isZero();
        assertThat(data.onHand(skuA, whNormal, "")).as("普通仓应被扣 5").isEqualTo(5);
        assertThat(inventory.checkLedgerConsistency()).isEmpty();
    }

    @Test
    @CaseId("TC-SAL-064")
    @DisplayName("部分发货：先确认一张仓单 → 订单 PARTIAL；再确认另一张 → COMPLETED")
    void partialShipment_movesOrderToPartialThenCompleted() {
        long whHigh = warehouseService.create(new WarehouseApplicationService.CreateCommand(
                "WH-SHIP-P1", "仓一", null, true, 10)).id();
        long whNormal = warehouseService.create(new WarehouseApplicationService.CreateCommand(
                "WH-SHIP-P2", "仓二", null, true, 100)).id();
        stock(skuA, whHigh, 4);
        stock(skuA, whNormal, 4);
        SalesOrder order = salesOrders.createAutoSplit(new SalesOrderApplicationService.CreateCommand(
                customerId, 0L, LocalDate.of(2025, 6, 1), "部分发货",
                List.of(new SalesOrderApplicationService.CreateLineCommand(skuA, 8, 1000L, null)),
                "req-ship-partial"));
        salesOrders.submit(order.id());
        salesOrders.approve(order.id());
        ShipmentPlan plan = shipments.createPlan(order.id(), "req-ship-partial-plan");
        Shipment first = plan.shipments().get(0);
        Shipment second = plan.shipments().get(1);
        pickAll(first);
        shipments.confirm(first.id(), "req-ship-partial-1");

        SalesOrder partial = salesOrders.require(order.id());
        assertThat(partial.status()).isEqualTo(DocumentStatus.PARTIAL);
        assertThat(partial.lines().get(0).shippedQty()).isEqualTo(4);

        pickAll(second);
        shipments.confirm(second.id(), "req-ship-partial-2");
        assertThat(salesOrders.require(order.id()).status()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(data.onHand(skuA, whHigh, "")).isZero();
        assertThat(data.onHand(skuA, whNormal, "")).isZero();
    }

    @Test
    @CaseId("TC-SAL-065")
    @DisplayName("状态守卫：DRAFT 不能确认；COMPLETED 不能再扫码")
    void statusGuards_rejectInvalidActions() {
        SalesOrder order = approvedOrder(2, "req-ship-guard");
        Shipment shipment = planned(order, "req-ship-guard-plan");

        assertThatThrownBy(() -> shipments.confirm(shipment.id(), "req-ship-guard-confirm"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SAL_SHIPMENT_STATUS_ILLEGAL);

        pickAll(shipment);
        shipments.confirm(shipment.id(), "req-ship-guard-done");

        assertThatThrownBy(() -> shipments.scan(shipment.id(),
                shipment.lines().get(0).id(), 1, null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SAL_SHIPMENT_STATUS_ILLEGAL);
    }

    @Test
    @CaseId("TC-SAL-066")
    @DisplayName("订单已完成后再生成发货计划 → SAL_STATUS_ILLEGAL")
    void createPlan_afterOrderCompleted_thenReject() {
        SalesOrder order = approvedOrder(2, "req-ship-completed");
        Shipment shipment = planned(order, "req-ship-completed-plan");
        pickAll(shipment);
        shipments.confirm(shipment.id(), "req-ship-completed-confirm");

        assertThatThrownBy(() -> shipments.createPlan(order.id(), "req-ship-completed-new"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SAL_STATUS_ILLEGAL);
    }

    @Test
    @CaseId("TC-SAL-067")
    @DisplayName("★ 确认发货同步登记应收：金额 == 本次出货金额，余额 == 出货 − 回款，到期日走 ClockProvider")
    void confirm_recordsReceivable() {
        jdbc.update("UPDATE customers SET credit_days = 30 WHERE id = ?", customerId);
        SalesOrder order = approvedOrder(3, "req-ship-receivable");
        Shipment shipment = planned(order, "req-ship-receivable-plan");
        pickAll(shipment);
        shipments.confirm(shipment.id(), "req-ship-receivable-confirm");

        CustomerReceivable receivable = receivables.findByOrderId(order.id()).orElseThrow();
        long expectedAmount = SalesOrderApplicationService.lineAmount(3, 1000L, 10000);
        assertThat(receivable.shippedAmount()).isEqualTo(expectedAmount);
        assertThat(receivable.balance()).isEqualTo(expectedAmount);
        assertThat(receivable.receivedAmount()).isZero();
        assertThat(receivable.dueDate()).isEqualTo(clock.today().plusDays(30));
    }

    // ─────────────────────────── 辅助 ───────────────────────────

    private SalesOrder approvedOrder(int qty, String requestId) {
        stock(skuA, warehouseId, qty + 10);
        SalesOrder order = salesOrders.create(new SalesOrderApplicationService.CreateCommand(
                customerId, warehouseId, LocalDate.of(2025, 6, 1), "发货单",
                List.of(new SalesOrderApplicationService.CreateLineCommand(skuA, qty, 1000L, null)),
                requestId));
        salesOrders.submit(order.id());
        salesOrders.approve(order.id());
        return salesOrders.require(order.id());
    }

    private Shipment planned(SalesOrder order, String planRequestId) {
        return shipments.createPlan(order.id(), planRequestId).shipments().get(0);
    }

    private void pickAll(Shipment shipment) {
        for (ShipmentLine line : shipment.lines()) {
            shipments.scan(shipment.id(), line.id(), line.plannedQty(), null);
        }
    }

    private void stock(long skuId, long wh, int qty) {
        data.stock(skuId, wh, "", null, qty);
        data.ledger(skuId, wh, "", qty, "PURCHASE_IN", "SEED-" + skuId + "-" + wh);
    }

    private static int sumPlanned(Shipment shipment) {
        return shipment.lines().stream().mapToInt(ShipmentLine::plannedQty).sum();
    }

    private String reservationStatus(long reservationId) {
        return jdbc.queryForObject(
                "SELECT status FROM inventory_reservations WHERE id = ?", String.class, reservationId);
    }

    private int count(String table) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return n == null ? 0 : n;
    }
}
