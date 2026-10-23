package com.erp.inv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.inv.application.InventoryApplicationService;
import com.erp.inv.application.InventoryApplicationService.ReserveCommand;
import com.erp.inv.application.InventoryOperationsApplicationService;
import com.erp.inv.application.InventoryOperationsApplicationService.FreezeResult;
import com.erp.inv.domain.FreezeStatus;
import com.erp.inv.domain.InventoryFreeze;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.support.CaseId;
import com.erp.support.PostgresTestBase;
import com.erp.support.TestClockConfig;
import com.erp.support.TestDataFactory;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 库存冻结 / 解冻（FR-INV-10）集成测试。
 *
 * <p><b>冻结不是库存变动</b>：实存、流水都不变，只把可用量"藏起来"。
 * 因此这里的断言分成两组：
 * <ul>
 *   <li>数量：available = onHand − reserved − frozen（INV-3 的扩展），且永不为负（INV-1）；</li>
 *   <li>流水：冻结/解冻前后流水条数不变（INV-2 不被污染）。</li>
 * </ul>
 *
 * <p>超时解冻用 {@code clock} profile 的可控时钟，不睡一秒。
 */
@SpringBootTest
@ActiveProfiles("clock")
@DisplayName("库存 · 冻结与解冻（FR-INV-10）")
class InventoryFreezeIT extends PostgresTestBase {

    private static final LocalDateTime BASE = LocalDateTime.of(2025, 6, 1, 9, 0, 0);

    @Autowired
    private InventoryApplicationService inventory;

    @Autowired
    private InventoryOperationsApplicationService operations;

    @Autowired
    private TestClockConfig.ControllableClock clock;

    @Autowired
    private JdbcTemplate jdbc;

    private TestDataFactory data;
    private long warehouseId;
    private long skuId;
    private final AtomicInteger seq = new AtomicInteger(1);

    @BeforeEach
    void setUp() {
        clock.set(BASE);
        data = new TestDataFactory(jdbc);
        data.truncateAll();
        warehouseId = data.warehouse("WH-FRZ");
        long productId = data.product("P-FREEZE", "冻结测试商品");
        skuId = data.sku(productId, "白色", "L");
    }

    private String req() {
        return "req-frz-" + seq.getAndIncrement();
    }

    private void stock(int qty) {
        inventory.receiveIn(skuId, warehouseId, "B1", null, null, qty,
                "PURCHASE_IN", "PO-FRZ-" + seq.getAndIncrement(), null, 1000L, req());
    }

    private FreezeResult freeze(int qty) {
        return operations.createFreeze(skuId, warehouseId, "B1", qty, "抽检冻结", null, req());
    }

    @Test
    @CaseId("TC-INV-056")
    @DisplayName("冻结只减少可用量：实存不变、frozen_qty 增加、可用量 = 实存 − 预占 − 冻结")
    void freeze_shouldReduceAvailableOnly() {
        stock(10);

        InventoryFreeze freeze = freeze(4).freeze();

        assertThat(freeze.status()).isEqualTo(FreezeStatus.ACTIVE);
        assertThat(data.onHand(skuId, warehouseId, "B1")).isEqualTo(10);
        assertThat(data.frozen(skuId, warehouseId, "B1")).isEqualTo(4);
        assertThat(data.reserved(skuId, warehouseId, "B1")).isZero();
        assertThat(data.available(skuId, warehouseId, "B1"))
                .as("INV-3：可用量 = 实存 − 预占 − 冻结")
                .isEqualTo(6);
    }

    @Test
    @CaseId("TC-INV-057")
    @DisplayName("冻结量超过可用量：拒绝（INV-1 可用量不得为负）")
    void freeze_whenExceedsAvailable_thenReject() {
        stock(10);
        inventory.reserve(new ReserveCommand(skuId, warehouseId, "B1", 6, req()));

        assertThatThrownBy(() -> freeze(5))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.INV_FREEZE_EXCEEDS_AVAILABLE)
                .hasMessageContaining("可用量 4");

        assertThat(data.frozen(skuId, warehouseId, "B1")).isZero();
        assertThat(data.available(skuId, warehouseId, "B1")).isEqualTo(4);
    }

    @Test
    @CaseId("TC-INV-058")
    @DisplayName("解冻：可用量恢复、状态 RELEASED、解冻时间来自注入时钟")
    void release_shouldRestoreAvailable() {
        stock(10);
        InventoryFreeze freeze = freeze(4).freeze();

        InventoryFreeze released = operations.releaseFreeze(freeze.id(), req()).freeze();

        assertThat(released.status()).isEqualTo(FreezeStatus.RELEASED);
        assertThat(released.releasedAt()).isNotNull();
        assertThat(data.frozen(skuId, warehouseId, "B1")).isZero();
        assertThat(data.available(skuId, warehouseId, "B1")).isEqualTo(10);
        assertThat(data.onHand(skuId, warehouseId, "B1")).isEqualTo(10);
    }

    @Test
    @CaseId("TC-INV-059")
    @DisplayName("同键重复冻结：只冻结一次（INV-9）")
    void freeze_withSameRequestId_shouldBeIdempotent() {
        stock(10);
        String requestId = req();

        InventoryFreeze first = operations.createFreeze(skuId, warehouseId, "B1", 4,
                "抽检", null, requestId).freeze();
        FreezeResult replay = operations.createFreeze(skuId, warehouseId, "B1", 4,
                "抽检", null, requestId);

        assertThat(replay.duplicate()).isTrue();
        assertThat(replay.freeze().id()).isEqualTo(first.id());
        assertThat(data.frozen(skuId, warehouseId, "B1")).isEqualTo(4);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_freezes", Integer.class)).isEqualTo(1);
    }

    @Test
    @CaseId("TC-INV-060")
    @DisplayName("同键重复解冻：返回首次结果，不重复释放冻结量")
    void release_withSameRequestId_shouldBeIdempotent() {
        stock(10);
        InventoryFreeze freeze = freeze(4).freeze();
        String releaseRequest = req();

        operations.releaseFreeze(freeze.id(), releaseRequest);
        FreezeResult replay = operations.releaseFreeze(freeze.id(), releaseRequest);

        assertThat(replay.duplicate()).isTrue();
        assertThat(data.frozen(skuId, warehouseId, "B1")).isZero();
        assertThat(data.available(skuId, warehouseId, "B1")).isEqualTo(10);
    }

    @Test
    @CaseId("TC-INV-061")
    @DisplayName("已解冻的记录换键再解冻：状态非法")
    void release_afterReleasedWithDifferentKey_thenIllegal() {
        stock(10);
        InventoryFreeze freeze = freeze(4).freeze();
        operations.releaseFreeze(freeze.id(), req());

        assertThatThrownBy(() -> operations.releaseFreeze(freeze.id(), req()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.INV_FREEZE_STATUS_ILLEGAL);
        assertThat(data.frozen(skuId, warehouseId, "B1")).isZero();
    }

    @Test
    @CaseId("TC-INV-062")
    @DisplayName("冻结量不可再被预占：预占校验的是可用量（INV-1 / INV-3）")
    void reserve_shouldNotConsumeFrozenQty() {
        stock(10);
        freeze(7);

        assertThatThrownBy(() -> inventory.reserve(
                new ReserveCommand(skuId, warehouseId, "B1", 4, req())))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.INV_INSUFFICIENT_STOCK);

        assertThat(data.onHand(skuId, warehouseId, "B1")).isEqualTo(10);
        assertThat(data.frozen(skuId, warehouseId, "B1")).isEqualTo(7);
        assertThat(data.available(skuId, warehouseId, "B1")).isEqualTo(3);

        // 可用量范围内仍可正常预占
        inventory.reserve(new ReserveCommand(skuId, warehouseId, "B1", 3, req()));
        assertThat(data.available(skuId, warehouseId, "B1")).isZero();
    }

    @Test
    @CaseId("TC-INV-063")
    @DisplayName("解冻量超过冻结量：余额不允许减成负数")
    void unfreeze_moreThanFrozen_thenReject() {
        stock(10);
        freeze(4);

        assertThatThrownBy(() -> inventory.unfreeze(skuId, warehouseId, "B1", 5))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.INV_FREEZE_NOT_FOUND);
        assertThat(data.frozen(skuId, warehouseId, "B1")).isEqualTo(4);
    }

    @Test
    @CaseId("TC-INV-064")
    @DisplayName("★ 冻结超时：到期后自动解冻并归还可用量（时钟快进 11 分钟）")
    void expireOverdueFreezes_shouldReleaseExpiredFreeze() {
        stock(10);
        InventoryFreeze freeze = operations.createFreeze(skuId, warehouseId, "B1", 4,
                "临时冻结", BASE.plusMinutes(10), req()).freeze();

        clock.advance(Duration.ofMinutes(10));
        assertThat(operations.expireOverdueFreezes())
                .as("恰好到期（expires_at == now）不算超时")
                .isZero();

        clock.advance(Duration.ofMinutes(1));
        assertThat(operations.expireOverdueFreezes()).isEqualTo(1);
        assertThat(operations.expireOverdueFreezes())
                .as("重复执行空转")
                .isZero();

        InventoryFreeze expired = operations.requireFreeze(freeze.id());
        assertThat(expired.status()).isEqualTo(FreezeStatus.EXPIRED);
        assertThat(expired.releasedAt()).isNotNull();
        assertThat(data.frozen(skuId, warehouseId, "B1")).isZero();
        assertThat(data.available(skuId, warehouseId, "B1")).isEqualTo(10);
    }

    @Test
    @CaseId("TC-INV-065")
    @DisplayName("未到期的冻结不得被超时任务释放")
    void expireOverdueFreezes_whenNotDue_shouldKeepActive() {
        stock(10);
        InventoryFreeze freeze = operations.createFreeze(skuId, warehouseId, "B1", 4,
                "临时冻结", BASE.plusMinutes(10), req()).freeze();

        clock.advance(Duration.ofMinutes(9));
        assertThat(operations.expireOverdueFreezes()).isZero();

        assertThat(operations.requireFreeze(freeze.id()).status()).isEqualTo(FreezeStatus.ACTIVE);
        assertThat(data.frozen(skuId, warehouseId, "B1")).isEqualTo(4);
    }

    @Test
    @CaseId("TC-INV-066")
    @DisplayName("冻结/解冻不写库存流水（INV-2 只约束实存变动）")
    void freezeAndRelease_shouldNotWriteLedger() {
        stock(10);
        Integer totalBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_ledger", Integer.class);

        InventoryFreeze freeze = freeze(4).freeze();
        operations.releaseFreeze(freeze.id(), req());

        Integer totalAfter = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_ledger", Integer.class);
        assertThat(totalAfter)
                .as("冻结与解冻都不改变实存，因此不得产生流水")
                .isEqualTo(totalBefore);
        assertThat(inventory.checkLedgerConsistency()).isEmpty();
    }

    @Test
    @CaseId("TC-INV-067")
    @DisplayName("冻结列表：状态 / SKU / 仓库筛选生效")
    void pageFreezes_shouldFilter() {
        stock(10);
        InventoryFreeze first = freeze(2).freeze();
        InventoryFreeze second = freeze(3).freeze();
        operations.releaseFreeze(second.id(), req());

        assertThat(operations.pageFreezes(FreezeStatus.ACTIVE, null, null, 1, 20).total())
                .isEqualTo(1);
        assertThat(operations.pageFreezes(FreezeStatus.RELEASED, null, null, 1, 20).total())
                .isEqualTo(1);
        assertThat(operations.pageFreezes(null, skuId, warehouseId, 1, 20).total()).isEqualTo(2);
        assertThat(operations.pageFreezes(FreezeStatus.ACTIVE, null, null, 1, 20)
                .items().get(0).id()).isEqualTo(first.id());
    }

    @Test
    @CaseId("TC-INV-068")
    @DisplayName("冻结记录不存在：404 语义")
    void requireFreeze_whenMissing_thenNotFound() {
        assertThatThrownBy(() -> operations.requireFreeze(999_999))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.INV_FREEZE_NOT_FOUND);
    }

    @Test
    @CaseId("TC-INV-069")
    @DisplayName("冻结到期时间不晚于当前时间：参数校验拒绝")
    void createFreeze_withPastExpiry_thenReject() {
        stock(10);

        assertThatThrownBy(() -> operations.createFreeze(skuId, warehouseId, "B1", 1,
                "过期时间", BASE.minusMinutes(1), req()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SYS_VALIDATION_ERROR)
                .hasMessageContaining("必须晚于当前时间");
    }
}
