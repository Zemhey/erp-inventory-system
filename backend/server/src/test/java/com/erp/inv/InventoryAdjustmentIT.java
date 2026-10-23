package com.erp.inv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.inv.application.InventoryApplicationService;
import com.erp.inv.application.InventoryApplicationService.ReserveCommand;
import com.erp.inv.application.InventoryOperationsApplicationService;
import com.erp.inv.application.InventoryOperationsApplicationService.AdjustmentResult;
import com.erp.inv.domain.InventoryAdjustment;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.support.CaseId;
import com.erp.support.PostgresTestBase;
import com.erp.support.TestDataFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 库存调整（FR-INV-09）集成测试。
 *
 * <p>调整是"没有单据审批、但必须留原因"的库存变动：
 * 数量方向由正负号决定，流水类型固定 ADJUST_IN / ADJUST_OUT，
 * 原因写库作为审计依据。
 */
@SpringBootTest
@DisplayName("库存 · 调整（FR-INV-09）")
class InventoryAdjustmentIT extends PostgresTestBase {

    @Autowired
    private InventoryApplicationService inventory;

    @Autowired
    private InventoryOperationsApplicationService operations;

    @Autowired
    private JdbcTemplate jdbc;

    private TestDataFactory data;
    private long warehouseId;
    private long skuId;
    private final AtomicInteger seq = new AtomicInteger(1);

    @BeforeEach
    void setUp() {
        data = new TestDataFactory(jdbc);
        data.truncateAll();
        warehouseId = data.warehouse("WH-ADJ");
        long productId = data.product("P-ADJUST", "调整测试商品");
        skuId = data.sku(productId, "白色", "L");
    }

    private String req() {
        return "req-adj-" + seq.getAndIncrement();
    }

    private void stock(int qty) {
        inventory.receiveIn(skuId, warehouseId, "B1", null, null, qty,
                "PURCHASE_IN", "PO-ADJ-" + seq.getAndIncrement(), null, 1000L, req());
    }

    private AdjustmentResult adjust(int changeQty, String reason) {
        return operations.createAdjustment(skuId, warehouseId, "B1", changeQty, reason, 1200L, req());
    }

    @Test
    @CaseId("TC-INV-048")
    @DisplayName("调增：实存增加、写 ADJUST_IN 正流水、原因落库")
    void adjust_withPositiveQty_shouldIncreaseOnHand() {
        stock(10);

        InventoryAdjustment adjustment = adjust(5, "初始化建账").adjustment();

        assertThat(adjustment.changeQty()).isEqualTo(5);
        assertThat(adjustment.reason()).isEqualTo("初始化建账");
        assertThat(adjustment.adjustNo()).startsWith("AJ");
        assertThat(data.onHand(skuId, warehouseId, "B1")).isEqualTo(15);
        assertThat(data.ledgerCountByType(adjustment.adjustNo(), "ADJUST_IN")).isEqualTo(1);
        assertThat(inventory.checkLedgerConsistency()).isEmpty();
    }

    @Test
    @CaseId("TC-INV-049")
    @DisplayName("调减：实存减少、写 ADJUST_OUT 负流水")
    void adjust_withNegativeQty_shouldDecreaseOnHand() {
        stock(10);

        InventoryAdjustment adjustment = adjust(-3, "破损报损").adjustment();

        assertThat(adjustment.changeQty()).isEqualTo(-3);
        assertThat(data.onHand(skuId, warehouseId, "B1")).isEqualTo(7);
        assertThat(data.ledgerCountByType(adjustment.adjustNo(), "ADJUST_OUT")).isEqualTo(1);
        assertThat(inventory.checkLedgerConsistency()).isEmpty();
    }

    @Test
    @CaseId("TC-INV-050")
    @DisplayName("原因必填：空白原因直接拒绝，不产生任何库存变动")
    void adjust_withBlankReason_thenReject() {
        stock(10);

        assertThatThrownBy(() -> adjust(1, "   "))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.INV_ADJUST_REASON_REQUIRED);

        assertThatThrownBy(() -> adjust(1, null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.INV_ADJUST_REASON_REQUIRED);

        assertThat(data.onHand(skuId, warehouseId, "B1")).isEqualTo(10);
    }

    @Test
    @CaseId("TC-INV-051")
    @DisplayName("调整数量为 0：没有意义，拒绝")
    void adjust_withZeroQty_thenReject() {
        stock(10);

        assertThatThrownBy(() -> adjust(0, "误操作"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SYS_VALIDATION_ERROR)
                .hasMessageContaining("不能为 0");
    }

    @Test
    @CaseId("TC-INV-052")
    @DisplayName("同键重复调整：只生效一次，返回首次结果（INV-9）")
    void adjust_withSameRequestId_shouldBeIdempotent() {
        stock(10);
        String requestId = req();

        InventoryAdjustment first = operations.createAdjustment(skuId, warehouseId, "B1",
                5, "建账", 1200L, requestId).adjustment();
        AdjustmentResult replay = operations.createAdjustment(skuId, warehouseId, "B1",
                5, "建账", 1200L, requestId);

        assertThat(replay.duplicate()).isTrue();
        assertThat(replay.adjustment().id()).isEqualTo(first.id());
        assertThat(data.onHand(skuId, warehouseId, "B1")).isEqualTo(15);
        assertThat(data.ledgerCountByType(first.adjustNo(), "ADJUST_IN")).isEqualTo(1);
    }

    @Test
    @CaseId("TC-INV-053")
    @DisplayName("调减不得把实存压到预占以下：拒绝且不留流水")
    void adjust_belowReserved_thenReject() {
        stock(10);
        inventory.reserve(new ReserveCommand(skuId, warehouseId, "B1", 8, req()));

        assertThatThrownBy(() -> adjust(-3, "报损"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.INV_NEGATIVE_NOT_ALLOWED);

        assertThat(data.onHand(skuId, warehouseId, "B1")).isEqualTo(10);
        assertThat(data.reserved(skuId, warehouseId, "B1")).isEqualTo(8);
        Integer adjustLedger = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_ledger WHERE biz_type LIKE 'ADJUST%'",
                Integer.class);
        assertThat(adjustLedger).isZero();
    }

    @Test
    @CaseId("TC-INV-054")
    @DisplayName("调整列表：按 SKU / 仓库筛选，倒序返回")
    void pageAdjustments_shouldFilter() {
        stock(10);
        InventoryAdjustment first = adjust(2, "第一次").adjustment();
        InventoryAdjustment second = adjust(-1, "第二次").adjustment();

        var result = operations.pageAdjustments(skuId, warehouseId, 1, 20);
        assertThat(result.total()).isEqualTo(2);
        assertThat(((InventoryAdjustment) result.items().get(0)).id())
                .as("按 id 倒序")
                .isEqualTo(second.id());
        assertThat(((InventoryAdjustment) result.items().get(1)).id()).isEqualTo(first.id());

        assertThat(operations.pageAdjustments(999_999L, warehouseId, 1, 20).total()).isZero();
    }

    @Test
    @CaseId("TC-INV-055")
    @DisplayName("调增可以为尚不存在的批次建行；INV-2 仍然成立")
    void adjust_onMissingBatch_shouldCreateRowAndKeepInvariant() {
        InventoryAdjustment adjustment = adjust(6, "期初盘点建账").adjustment();

        assertThat(data.onHand(skuId, warehouseId, "B1")).isEqualTo(6);
        assertThat(data.ledgerCountByType(adjustment.adjustNo(), "ADJUST_IN")).isEqualTo(1);
        assertThat(inventory.checkLedgerConsistency()).isEmpty();
    }
}
