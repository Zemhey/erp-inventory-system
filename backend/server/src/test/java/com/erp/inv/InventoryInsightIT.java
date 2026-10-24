package com.erp.inv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.inv.application.InventoryApplicationService;
import com.erp.inv.application.InventoryApplicationService.ReserveCommand;
import com.erp.inv.application.InventoryInsightApplicationService;
import com.erp.inv.application.InventoryInsightApplicationService.BalanceDimensionRow;
import com.erp.inv.application.InventoryOperationsApplicationService;
import com.erp.inv.domain.AlertType;
import com.erp.inv.domain.InventoryAlert;
import com.erp.inv.domain.Ownership;
import com.erp.inv.domain.ReplenishmentSuggestion;
import com.erp.inv.domain.StockStatus;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.support.CaseId;
import com.erp.support.PostgresTestBase;
import com.erp.support.TestClockConfig;
import com.erp.support.TestDataFactory;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 五维结存 / 预警 / 补货建议（FR-INV-11 ~ 13）集成测试。
 *
 * <p>时间相关断言全部由注入时钟摆布：
 * 临期边界用 {@code today + N}、滞销边界用 {@code now - N}，
 * 不依赖真实日历，也不 sleep。
 *
 * <p>补货建议的核心是幂等：连续两次查询结果逐字段一致；
 * 已下单未到货的 SKU 必须从建议里消失。
 */
@SpringBootTest
@ActiveProfiles("clock")
@DisplayName("库存 · 五维结存 / 预警 / 补货建议")
class InventoryInsightIT extends PostgresTestBase {

    private static final LocalDateTime BASE = LocalDateTime.of(2025, 6, 1, 9, 0, 0);

    @Autowired
    private InventoryApplicationService inventory;

    @Autowired
    private InventoryOperationsApplicationService operations;

    @Autowired
    private InventoryInsightApplicationService insights;

    @Autowired
    private TestClockConfig.ControllableClock clock;

    @Autowired
    private JdbcTemplate jdbc;

    private TestDataFactory data;
    private long warehouseA;
    private long warehouseB;
    private long skuId;
    private long sku2Id;
    private final AtomicInteger seq = new AtomicInteger(1);

    @BeforeEach
    void setUp() {
        clock.set(BASE);
        data = new TestDataFactory(jdbc);
        data.truncateAll();
        warehouseA = data.warehouse("WH-IN-A");
        warehouseB = data.warehouse("WH-IN-B");
        long productId = data.product("P-INSIGHT", "洞察测试商品");
        skuId = data.sku(productId, "白色", "L");
        sku2Id = data.sku(productId, "黑色", "L");
    }

    private String req() {
        return "req-ins-" + seq.getAndIncrement();
    }

    private void stock(long warehouseId, long sku, String batch, LocalDate expiry, int qty) {
        inventory.receiveIn(sku, warehouseId, batch, null, expiry, qty,
                "PURCHASE_IN", "PO-INS-" + seq.getAndIncrement(), null, 1000L, req());
    }

    // ─────────────────────────── 五维结存（FR-INV-11） ───────────────────────────

    @Test
    @CaseId("TC-INV-070")
    @DisplayName("★ 五维结存：对每个 (仓, SKU, 批次, 权属)，Σ各状态 == 实存总量")
    void balanceDimensions_statusSumEqualsOnHand() {
        stock(warehouseA, skuId, "B1", null, 10);
        stock(warehouseA, skuId, "B2", null, 4);
        inventory.reserve(new ReserveCommand(skuId, warehouseA, "B1", 3, req()));
        operations.createFreeze(skuId, warehouseA, "B1", 2, "抽检", null, req());

        List<BalanceDimensionRow> rows = insights.balanceDimensions(
                skuId, warehouseA, null, null, null);

        Map<String, Integer> sumByBatch = rows.stream().collect(Collectors.groupingBy(
                BalanceDimensionRow::batchNo, Collectors.summingInt(BalanceDimensionRow::qty)));
        assertThat(sumByBatch)
                .as("INV-11：每个批次各状态之和 == on_hand_qty")
                .containsEntry("B1", data.onHand(skuId, warehouseA, "B1"))
                .containsEntry("B2", data.onHand(skuId, warehouseA, "B2"));
        assertThat(rows).allMatch(r -> r.ownership() == Ownership.SELF);

        assertThat(rows.stream().filter(r -> "B1".equals(r.batchNo())))
                .extracting(BalanceDimensionRow::stockStatus)
                .containsExactlyInAnyOrder(StockStatus.AVAILABLE, StockStatus.RESERVED,
                        StockStatus.FROZEN);
    }

    @Test
    @CaseId("TC-INV-071")
    @DisplayName("五维结存：状态筛选只返回指定状态的数量")
    void balanceDimensions_shouldFilterByStatus() {
        stock(warehouseA, skuId, "B1", null, 10);
        inventory.reserve(new ReserveCommand(skuId, warehouseA, "B1", 3, req()));
        operations.createFreeze(skuId, warehouseA, "B1", 2, "抽检", null, req());

        List<BalanceDimensionRow> frozen = insights.balanceDimensions(
                null, null, null, StockStatus.FROZEN, null);
        assertThat(frozen).singleElement()
                .satisfies(r -> {
                    assertThat(r.qty()).isEqualTo(2);
                    assertThat(r.stockStatus()).isEqualTo(StockStatus.FROZEN);
                });

        assertThat(insights.balanceDimensions(null, null, null, StockStatus.RESERVED, null))
                .singleElement().extracting(BalanceDimensionRow::qty).isEqualTo(3);
    }

    @Test
    @CaseId("TC-INV-072")
    @DisplayName("五维结存：仓库 / SKU / 批次筛选生效")
    void balanceDimensions_shouldFilterByDimensions() {
        stock(warehouseA, skuId, "B1", null, 5);
        stock(warehouseA, skuId, "B2", null, 3);
        stock(warehouseB, skuId, "B1", null, 7);
        stock(warehouseA, sku2Id, "B1", null, 2);

        assertThat(insights.balanceDimensions(skuId, warehouseA, "B1", null, null))
                .allMatch(r -> r.warehouseId() == warehouseA
                        && r.skuId() == skuId && "B1".equals(r.batchNo()));
        assertThat(insights.balanceDimensions(sku2Id, null, null, null, null))
                .singleElement().extracting(BalanceDimensionRow::qty).isEqualTo(2);
        assertThat(insights.balanceDimensions(null, warehouseB, null, null, null))
                .singleElement().extracting(BalanceDimensionRow::qty).isEqualTo(7);
    }

    @Test
    @CaseId("TC-INV-073")
    @DisplayName("五维结存：未接入的权属类型显式拒绝，不静默当成自有")
    void balanceDimensions_withUnsupportedOwnership_thenReject() {
        assertThatThrownBy(() -> insights.balanceDimensions(
                null, null, null, null, Ownership.CONSIGNMENT))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SYS_VALIDATION_ERROR)
                .hasMessageContaining("CONSIGNMENT");
    }

    // ─────────────────────────── 预警（FR-INV-12） ───────────────────────────

    @Test
    @CaseId("TC-INV-074")
    @DisplayName("安全库存预警：可用量低于 min_level 时出现，阈值与数量正确")
    void alerts_whenBelowSafetyStock_shouldAlert() {
        stock(warehouseA, skuId, "B1", null, 3);
        data.skuLevels(skuId, 5, 20);

        List<InventoryAlert> alerts = insights.alerts(warehouseA, AlertType.SAFETY, null, null);

        assertThat(alerts).singleElement().satisfies(a -> {
            assertThat(a.type()).isEqualTo(AlertType.SAFETY);
            assertThat(a.skuId()).isEqualTo(skuId);
            assertThat(a.availableQty()).isEqualTo(3);
            assertThat(a.threshold()).isEqualTo(5);
        });
    }

    @Test
    @CaseId("TC-INV-075")
    @DisplayName("安全库存看的是可用量：实存够但被预占/冻结后仍需预警")
    void alerts_safetyUsesAvailableNotOnHand() {
        stock(warehouseA, skuId, "B1", null, 10);
        inventory.reserve(new ReserveCommand(skuId, warehouseA, "B1", 6, req()));
        operations.createFreeze(skuId, warehouseA, "B1", 2, "抽检", null, req());
        data.skuLevels(skuId, 5, 20);

        List<InventoryAlert> alerts = insights.alerts(warehouseA, AlertType.SAFETY, null, null);

        assertThat(alerts).singleElement().satisfies(a -> {
            assertThat(a.onHandQty()).isEqualTo(10);
            assertThat(a.availableQty()).isEqualTo(2);
        });
    }

    @Test
    @CaseId("TC-INV-076")
    @DisplayName("可用量不低于安全库存：不产生安全库存预警")
    void alerts_whenAboveSafetyStock_shouldNotAlert() {
        stock(warehouseA, skuId, "B1", null, 6);
        data.skuLevels(skuId, 5, 20);

        assertThat(insights.alerts(warehouseA, AlertType.SAFETY, null, null)).isEmpty();

        data.skuLevels(sku2Id, 0, 0);
        assertThat(insights.alerts(warehouseA, null, null, null))
                .as("min_level=0 表示不设阈值")
                .isEmpty();
    }

    @Test
    @CaseId("TC-INV-077")
    @DisplayName("临期预警：效期在 N 天内出现；远超阈值的不出现")
    void alerts_expiringWithinWindow_shouldAlert() {
        stock(warehouseA, skuId, "E-SOON", LocalDate.of(2025, 6, 20), 5);
        stock(warehouseA, skuId, "E-FAR", LocalDate.of(2025, 12, 31), 5);

        List<InventoryAlert> alerts = insights.alerts(warehouseA, AlertType.EXPIRING, 30, null);

        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).batchNo()).isEqualTo("E-SOON");
        assertThat(alerts.get(0).threshold()).isEqualTo(30);
        assertThat(alerts.get(0).expiryDate()).isEqualTo(LocalDate.of(2025, 6, 20));
    }

    @Test
    @CaseId("TC-INV-078")
    @DisplayName("临期边界：今天 + 30 天在内，今天 + 31 天在外（注入时钟）")
    void alerts_expiringBoundary_isExact() {
        stock(warehouseA, skuId, "E-30", BASE.toLocalDate().plusDays(30), 5);
        stock(warehouseA, skuId, "E-31", BASE.toLocalDate().plusDays(31), 5);

        List<InventoryAlert> alerts = insights.alerts(warehouseA, AlertType.EXPIRING, 30, null);

        assertThat(alerts).extracting(InventoryAlert::batchNo)
                .as("边界由 ClockProvider 的今天决定，与真实日历无关")
                .containsExactly("E-30");
    }

    @Test
    @CaseId("TC-INV-079")
    @DisplayName("滞销预警：超过 N 天没有出库时出现，最后出库时间来自流水")
    void alerts_slowMoving_shouldAlertAfterThreshold() {
        stock(warehouseA, skuId, "B1", null, 10);
        clock.advance(Duration.ofDays(100));

        List<InventoryAlert> alerts = insights.alerts(warehouseA, AlertType.SLOW_MOVING, null, 90);

        assertThat(alerts).singleElement().satisfies(a -> {
            assertThat(a.type()).isEqualTo(AlertType.SLOW_MOVING);
            assertThat(a.threshold()).isEqualTo(90);
            assertThat(a.lastMovementAt()).isNotNull();
            assertThat(a.onHandQty()).isEqualTo(10);
        });
    }

    @Test
    @CaseId("TC-INV-080")
    @DisplayName("滞销边界：未满 N 天不预警")
    void alerts_slowMoving_whenNotDue_shouldNotAlert() {
        stock(warehouseA, skuId, "B1", null, 10);
        clock.advance(Duration.ofDays(89));

        assertThat(insights.alerts(warehouseA, AlertType.SLOW_MOVING, null, 90)).isEmpty();

        clock.advance(Duration.ofDays(2));
        assertThat(insights.alerts(warehouseA, AlertType.SLOW_MOVING, null, 90)).hasSize(1);
    }

    @Test
    @CaseId("TC-INV-081")
    @DisplayName("预警类型筛选：只返回请求的类型")
    void alerts_shouldFilterByType() {
        stock(warehouseA, skuId, "B1", LocalDate.of(2025, 6, 10), 3);
        data.skuLevels(skuId, 5, 20);

        assertThat(insights.alerts(warehouseA, null, 30, 90))
                .extracting(InventoryAlert::type)
                .containsExactlyInAnyOrder(AlertType.SAFETY, AlertType.EXPIRING);

        assertThat(insights.alerts(warehouseA, AlertType.SAFETY, 30, 90))
                .allMatch(a -> a.type() == AlertType.SAFETY);
    }

    // ─────────────────────────── 补货建议（FR-INV-13） ───────────────────────────

    @Test
    @CaseId("TC-INV-082")
    @DisplayName("补货建议：可用量低于 min 时补到 max，建议量 = max − available")
    void replenishment_whenBelowMin_shouldSuggestToMax() {
        stock(warehouseA, skuId, "B1", null, 5);
        data.skuLevels(skuId, 10, 30);

        List<ReplenishmentSuggestion> suggestions = insights.replenishment(warehouseA);

        assertThat(suggestions).singleElement().satisfies(s -> {
            assertThat(s.skuId()).isEqualTo(skuId);
            assertThat(s.availableQty()).isEqualTo(5);
            assertThat(s.minLevel()).isEqualTo(10);
            assertThat(s.maxLevel()).isEqualTo(30);
            assertThat(s.suggestedQty()).isEqualTo(25);
        });
    }

    @Test
    @CaseId("TC-INV-083")
    @DisplayName("★ 补货建议幂等：连续两次调用结果逐字段一致，且不落任何表")
    void replenishment_shouldBeIdempotent() {
        stock(warehouseA, skuId, "B1", null, 5);
        data.skuLevels(skuId, 10, 30);
        long tablesBefore = jdbc.queryForObject("""
                SELECT (SELECT COUNT(*) FROM inventory_adjustments)
                     + (SELECT COUNT(*) FROM inventory_freezes)
                     + (SELECT COUNT(*) FROM inventory_transfers)
                     + (SELECT COUNT(*) FROM stocktakes)
                """, Long.class);

        List<ReplenishmentSuggestion> first = insights.replenishment(warehouseA);
        List<ReplenishmentSuggestion> second = insights.replenishment(warehouseA);

        assertThat(second).containsExactlyElementsOf(first);
        long tablesAfter = jdbc.queryForObject("""
                SELECT (SELECT COUNT(*) FROM inventory_adjustments)
                     + (SELECT COUNT(*) FROM inventory_freezes)
                     + (SELECT COUNT(*) FROM inventory_transfers)
                     + (SELECT COUNT(*) FROM stocktakes)
                """, Long.class);
        assertThat(tablesAfter)
                .as("补货建议是纯查询，不应该落表")
                .isEqualTo(tablesBefore);
    }

    @Test
    @CaseId("TC-INV-084")
    @DisplayName("★ 已下单未到货的 SKU 不重复建议（APPROVED 且有未收数量）")
    void replenishment_withOpenPurchaseOrder_shouldBeSkipped() {
        stock(warehouseA, skuId, "B1", null, 5);
        data.skuLevels(skuId, 10, 30);
        long supplierId = data.supplier("SUP-OPEN");
        long poId = jdbc.queryForObject("""
                INSERT INTO purchase_orders
                    (order_no, status, supplier_id, warehouse_id, biz_date, total_amount, total_qty)
                VALUES (?, 'APPROVED', ?, ?, ?, 0, 0)
                RETURNING id
                """, Long.class, "PO-OPEN-1", supplierId, warehouseA, BASE.toLocalDate());
        jdbc.update("""
                INSERT INTO purchase_order_lines
                    (order_id, line_no, sku_id, qty, received_qty, unit_price_amount, amount)
                VALUES (?, 1, ?, 20, 0, 100, 2000)
                """, poId, skuId);

        assertThat(insights.replenishment(warehouseA))
                .as("已有未到货的进货单，不得再建议补货")
                .isEmpty();
    }

    @Test
    @CaseId("TC-INV-085")
    @DisplayName("可用量已达标或 max_level=0：都不产生补货建议")
    void replenishment_whenAboveMinOrDisabled_shouldNotSuggest() {
        stock(warehouseA, skuId, "B1", null, 15);
        stock(warehouseA, sku2Id, "B1", null, 1);
        data.skuLevels(skuId, 10, 30);
        data.skuLevels(sku2Id, 10, 0);

        assertThat(insights.replenishment(warehouseA)).isEmpty();
    }
}
