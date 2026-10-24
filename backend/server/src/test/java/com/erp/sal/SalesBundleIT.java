package com.erp.sal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.inv.application.InventoryApplicationService;
import com.erp.sal.application.ProductBundleService;
import com.erp.sal.application.SalesOrderApplicationService;
import com.erp.sal.domain.ProductBundle;
import com.erp.sal.domain.SalesOrder;
import com.erp.sal.domain.SalesOrderLine;
import com.erp.shared.document.DocumentStatus;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
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
 * 套装拆解与可用量（FR-SAL-10 / INV-16）。
 *
 * <p>INV-16：套装出库数 × 配比 == 各组件扣减总量；套装本身不持有库存。
 */
@SpringBootTest
@DisplayName("销售 · 套装拆解（INV-16）")
class SalesBundleIT extends PostgresTestBase {

    @Autowired
    private ProductBundleService bundles;
    @Autowired
    private SalesOrderApplicationService salesOrders;
    @Autowired
    private InventoryApplicationService inventory;
    @Autowired
    private JdbcTemplate jdbc;

    private TestDataFactory data;
    private long customerId;
    private long warehouseId;
    private long componentA;
    private long componentB;
    private long bundleId;

    @BeforeEach
    void setUp() {
        data = new TestDataFactory(jdbc);
        data.truncateAll();
        customerId = data.customer("CUS-BUNDLE");
        warehouseId = data.warehouse("WH-BUNDLE");
        long productId = data.product("P-BUNDLE", "套装测试商品");
        componentA = data.sku(productId, "白色", "L");
        componentB = data.sku(productId, "黑色", "M");
        bundleId = bundles.create(new ProductBundleService.CreateCommand(
                "BUNDLE-01", "测试套装", 39900L, true,
                List.of(new ProductBundleService.ItemCommand(componentA, 2),
                        new ProductBundleService.ItemCommand(componentB, 3)))).id();
    }

    // ─────────────────────────── 可用量 ───────────────────────────

    @Test
    @CaseId("TC-SAL-037")
    @DisplayName("★ 套装可用量 = min(组件可用 ÷ 配比)：A 10/2=5，B 15/3=5 → 5")
    void availability_isMinOfComponentRatio() {
        stock(componentA, 10);
        stock(componentB, 15);

        var availability = bundles.availability(bundleId, warehouseId);

        assertThat(availability.availableQty()).isEqualTo(5);
        assertThat(availability.components()).hasSize(2);
        assertThat(availability.components().get(0).maxBundles()).isEqualTo(5);
        assertThat(availability.components().get(1).maxBundles()).isEqualTo(5);
    }

    @Test
    @CaseId("TC-SAL-038")
    @DisplayName("套装可用量取短板：A 7/2=3（向下取整），B 20/3=6 → 3")
    void availability_floorsAndTakesShortComponent() {
        stock(componentA, 3);
        stock(componentB, 20);

        assertThat(bundles.availability(bundleId, warehouseId).availableQty()).isEqualTo(1);

        // 补到 7 件：余额 +4，同时补一条流水，保持 INV-2
        data.stock(componentA, warehouseId, "", null, 7);
        data.ledger(componentA, warehouseId, "", 4, "PURCHASE_IN", "SEED-ADD-A");
        assertThat(bundles.availability(bundleId, warehouseId).availableQty()).isEqualTo(3);
    }

    @Test
    @CaseId("TC-SAL-046")
    @DisplayName("跨仓汇总口径 = min(Σ组件可用 ÷ 配比)，与单仓 min 不同")
    void availability_aggregateAcrossWarehouses() {
        long secondWarehouse = data.warehouse("WH-BUNDLE-2");
        stock(componentA, 3);
        stock(componentB, 10);
        data.stock(componentA, secondWarehouse, "", null, 4);
        data.ledger(componentA, secondWarehouse, "", 4, "PURCHASE_IN", "SEED-AGG-A");
        data.stock(componentB, secondWarehouse, "", null, 10);
        data.ledger(componentB, secondWarehouse, "", 10, "PURCHASE_IN", "SEED-AGG-B");

        assertThat(bundles.availability(bundleId, warehouseId).availableQty()).isEqualTo(1);
        assertThat(bundles.availability(bundleId, secondWarehouse).availableQty()).isEqualTo(2);
        // 汇总：A (3+4)/2=3，B (10+10)/3=6 → 3
        assertThat(bundles.availability(bundleId, null).availableQty()).isEqualTo(3);
    }

    // ─────────────────────────── 建单展开 ───────────────────────────

    @Test
    @CaseId("TC-SAL-039")
    @DisplayName("★ 卖 2 套 → 展开为组件行：A 4 件、B 6 件；组件被预占，套装不建库存行")
    void createOrder_expandsBundleIntoComponents() {
        stock(componentA, 10);
        stock(componentB, 15);

        SalesOrder order = createBundleOrder(2, "req-bundle-create");

        assertThat(order.lines()).hasSize(2);
        assertThat(order.totalQty()).isEqualTo(10);
        SalesOrderLine lineA = lineFor(order, componentA);
        SalesOrderLine lineB = lineFor(order, componentB);
        assertThat(lineA.qty()).isEqualTo(4);
        assertThat(lineB.qty()).isEqualTo(6);
        assertThat(lineA.bundleId()).isEqualTo(bundleId);
        assertThat(lineA.bundleRatio()).isEqualTo(2);
        assertThat(lineB.bundleRatio()).isEqualTo(3);
        assertThat(lineA.bundleQty()).isEqualTo(2);
        assertThat(data.reserved(componentA, warehouseId, "")).isEqualTo(4);
        assertThat(data.reserved(componentB, warehouseId, "")).isEqualTo(6);

        Integer componentBalanceRows = jdbc.queryForObject("""
                SELECT COUNT(*) FROM inventory_balances WHERE sku_id IN (?, ?)
                """, Integer.class, componentA, componentB);
        assertThat(componentBalanceRows).as("只有组件有余额行").isEqualTo(2);
        Integer otherRows = jdbc.queryForObject("""
                SELECT COUNT(*) FROM inventory_balances WHERE sku_id NOT IN (?, ?)
                """, Integer.class, componentA, componentB);
        assertThat(otherRows).as("套装不持有库存，不得多出余额行").isZero();
    }

    @Test
    @CaseId("TC-SAL-040")
    @DisplayName("★★ INV-16：出货 2 套 → A 扣 4、B 扣 6；组件 shippedQty == 套数×配比")
    void shipBundle_componentsDeductByRatio() {
        stock(componentA, 10);
        stock(componentB, 15);
        SalesOrder order = createBundleOrder(2, "req-bundle-ship");
        salesOrders.submit(order.id());
        salesOrders.approve(order.id());

        SalesOrder shipped = salesOrders.ship(order.id(), "req-bundle-ship-out");

        assertThat(shipped.status()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(lineFor(shipped, componentA).shippedQty()).isEqualTo(4);
        assertThat(lineFor(shipped, componentB).shippedQty()).isEqualTo(6);
        assertThat(data.onHand(componentA, warehouseId, "")).isEqualTo(6);
        assertThat(data.onHand(componentB, warehouseId, "")).isEqualTo(9);
        assertThat(data.onHand(componentA, warehouseId, ""))
                .isEqualTo(10 - 2 * 2);
        assertThat(data.onHand(componentB, warehouseId, ""))
                .isEqualTo(15 - 2 * 3);
        assertThat(inventory.checkLedgerConsistency()).isEmpty();
        assertThat(inventory.ledgerEntries(shipped.orderNo(), "SALES_OUT"))
                .as("库存流水只落在组件 SKU 上")
                .allSatisfy(e -> assertThat(e.skuId()).isIn(componentA, componentB));
    }

    @Test
    @CaseId("TC-SAL-041")
    @DisplayName("INV-16 聚合：同一订单两组套装（1 套 + 2 套）→ 组件累计 3 套×配比")
    void multipleBundleGroups_keepConservation() {
        stock(componentA, 20);
        stock(componentB, 20);

        SalesOrder order = salesOrders.create(new SalesOrderApplicationService.CreateCommand(
                customerId, warehouseId, LocalDate.of(2025, 6, 1), "两组套装",
                List.of(new SalesOrderApplicationService.CreateLineCommand(0, 1, 0L, null, bundleId),
                        new SalesOrderApplicationService.CreateLineCommand(0, 2, 0L, null, bundleId)),
                "req-bundle-groups"));
        assertThat(order.lines()).hasSize(4);
        assertThat(order.lines().stream().map(SalesOrderLine::bundleGroupNo).distinct())
                .hasSize(2);

        salesOrders.submit(order.id());
        salesOrders.approve(order.id());
        SalesOrder shipped = salesOrders.ship(order.id(), "req-bundle-groups-ship");

        int shippedA = shipped.lines().stream().filter(l -> l.skuId() == componentA)
                .mapToInt(SalesOrderLine::shippedQty).sum();
        int shippedB = shipped.lines().stream().filter(l -> l.skuId() == componentB)
                .mapToInt(SalesOrderLine::shippedQty).sum();
        assertThat(shippedA).isEqualTo(3 * 2);
        assertThat(shippedB).isEqualTo(3 * 3);
        assertThat(data.onHand(componentA, warehouseId, "")).isEqualTo(20 - 6);
        assertThat(data.onHand(componentB, warehouseId, "")).isEqualTo(20 - 9);
    }

    @Test
    @CaseId("TC-SAL-042")
    @DisplayName("★ 套装库存不足：组件 A 只够 1 套，买 2 套 → 拒单且无任何预占")
    void createBundle_whenComponentInsufficient_thenRejectWithoutReservation() {
        stock(componentA, 3);     // 3/2 = 1 套
        stock(componentB, 30);

        assertThatThrownBy(() -> createBundleOrder(2, "req-bundle-short"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.INV_INSUFFICIENT_STOCK);

        assertThat(data.reserved(componentA, warehouseId, "")).isZero();
        assertThat(data.reserved(componentB, warehouseId, "")).isZero();
        assertThat(count("sales_orders")).isZero();
        assertThat(count("inventory_reservations")).isZero();
    }

    @Test
    @CaseId("TC-SAL-043")
    @DisplayName("停用套装不能建单：MDM_BUNDLE_DISABLED，且无预占")
    void createDisabledBundle_thenReject() {
        stock(componentA, 10);
        stock(componentB, 15);
        bundles.disable(bundleId);

        assertThatThrownBy(() -> createBundleOrder(1, "req-bundle-disabled"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.MDM_BUNDLE_DISABLED);
        assertThat(data.reserved(componentA, warehouseId, "")).isZero();
    }

    @Test
    @CaseId("TC-SAL-044")
    @DisplayName("套装定义校验：配比 <=0 / 组件重复 / 组件不存在 → 拒绝")
    void bundleValidation_rejectsInvalidDefinitions() {
        assertThatThrownBy(() -> bundles.create(new ProductBundleService.CreateCommand(
                "BUNDLE-BAD-1", "坏套装", 0L, true,
                List.of(new ProductBundleService.ItemCommand(componentA, 0)))))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SYS_VALIDATION_ERROR);

        assertThatThrownBy(() -> bundles.create(new ProductBundleService.CreateCommand(
                "BUNDLE-BAD-2", "坏套装", 0L, true,
                List.of(new ProductBundleService.ItemCommand(componentA, 1),
                        new ProductBundleService.ItemCommand(componentA, 2)))))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.MDM_BUNDLE_DUPLICATED);

        assertThatThrownBy(() -> bundles.create(new ProductBundleService.CreateCommand(
                "BUNDLE-BAD-3", "坏套装", 0L, true,
                List.of(new ProductBundleService.ItemCommand(999_999L, 1)))))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.MDM_SKU_NOT_FOUND);
    }

    @Test
    @CaseId("TC-SAL-045")
    @DisplayName("套装明细不写金额：组件行按组件零售价与折扣计价，合计 == 行金额之和")
    void bundleOrder_pricesAreComponentBased() {
        stock(componentA, 10);
        stock(componentB, 15);

        SalesOrder order = createBundleOrder(1, "req-bundle-price", 9000);

        assertThat(order.lines()).allSatisfy(line ->
                assertThat(line.amount()).isEqualTo(SalesOrderApplicationService.lineAmount(
                        line.qty(), line.unitPriceAmount(), line.discountRate())));
        assertThat(order.totalAmount())
                .isEqualTo(order.lines().stream().mapToLong(SalesOrderLine::amount).sum());
        assertThat(order.lines()).allSatisfy(line -> assertThat(line.discountRate()).isEqualTo(9000));
    }

    @Test
    @CaseId("TC-SAL-090")
    @DisplayName("套装维护：更新配比/价格、按关键字查询、停用、编码重复拒绝")
    void bundleMaintenance() {
        ProductBundle updated = bundles.update(bundleId,
                new ProductBundleService.UpdateCommand("改名套装", 49900L, true,
                        List.of(new ProductBundleService.ItemCommand(componentA, 1),
                                new ProductBundleService.ItemCommand(componentB, 4))));
        assertThat(updated.name()).isEqualTo("改名套装");
        assertThat(updated.priceAmount()).isEqualTo(49900L);
        assertThat(updated.items()).hasSize(2);
        assertThat(updated.items().get(0).ratioQty()).isEqualTo(1);
        assertThat(updated.items().get(1).ratioQty()).isEqualTo(4);

        var page = bundles.page("改名", true, 1, 20);
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items().get(0).id()).isEqualTo(bundleId);

        assertThat(bundles.disable(bundleId).enabled()).isFalse();

        assertThatThrownBy(() -> bundles.create(new ProductBundleService.CreateCommand(
                "BUNDLE-01", "重复编码", 0L, true,
                List.of(new ProductBundleService.ItemCommand(componentA, 1)))))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.MDM_BUNDLE_DUPLICATED);
    }

    @Test
    @CaseId("TC-SAL-091")
    @DisplayName("建单行校验：SKU 与套装二选一；负单价拒绝且无预占")
    void createLineValidation() {
        stock(componentA, 10);
        stock(componentB, 15);

        assertThatThrownBy(() -> salesOrders.create(
                new SalesOrderApplicationService.CreateCommand(
                        customerId, warehouseId, LocalDate.of(2025, 6, 1), "非法行",
                        List.of(new SalesOrderApplicationService.CreateLineCommand(
                                componentA, 1, 1000L, null, bundleId)), "req-line-both")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SYS_VALIDATION_ERROR);

        assertThatThrownBy(() -> salesOrders.create(
                new SalesOrderApplicationService.CreateCommand(
                        customerId, warehouseId, LocalDate.of(2025, 6, 1), "负单价",
                        List.of(new SalesOrderApplicationService.CreateLineCommand(
                                componentA, 1, -1L, null)), "req-line-negative")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SYS_VALIDATION_ERROR);

        assertThat(data.reserved(componentA, warehouseId, "")).isZero();
        assertThat(count("sales_orders")).isZero();
    }

    // ─────────────────────────── 辅助 ───────────────────────────

    private SalesOrder createBundleOrder(int bundleQty, String requestId) {
        return createBundleOrder(bundleQty, requestId, null);
    }

    private SalesOrder createBundleOrder(int bundleQty, String requestId, Integer discountRate) {
        return salesOrders.create(new SalesOrderApplicationService.CreateCommand(
                customerId, warehouseId, LocalDate.of(2025, 6, 1), "套装单",
                List.of(new SalesOrderApplicationService.CreateLineCommand(
                        0, bundleQty, 0L, discountRate, bundleId)), requestId));
    }

    private SalesOrderLine lineFor(SalesOrder order, long skuId) {
        return order.lines().stream().filter(l -> l.skuId() == skuId).findFirst().orElseThrow();
    }

    private void stock(long skuId, int qty) {
        data.stock(skuId, warehouseId, "", null, qty);
        data.ledger(skuId, warehouseId, "", qty, "PURCHASE_IN", "SEED-" + skuId + "-" + qty);
    }

    private int count(String table) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return n == null ? 0 : n;
    }
}
