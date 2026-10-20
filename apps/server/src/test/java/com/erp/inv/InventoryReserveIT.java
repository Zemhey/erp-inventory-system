package com.erp.inv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.inv.application.InventoryApplicationService;
import com.erp.inv.application.InventoryApplicationService.ReserveCommand;
import com.erp.inv.application.InventoryApplicationService.ReserveResult;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.support.CaseId;
import com.erp.support.PostgresTestBase;
import com.erp.support.TestDataFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 库存预占集成测试 —— M0 的核心验收物。
 *
 * <p>测试的是<b>不变量</b>（docs/02-领域模型 §4），而不是硬编码的期望值：
 * <ul>
 *   <li>INV-1：可用量与实存永不为负 —— 即绝不超卖</li>
 *   <li>INV-2：实存 == 流水净和</li>
 *   <li>INV-3：可用量 == 实存 − 预占</li>
 *   <li>INV-9：相同 requestId 重复请求只生效一次</li>
 * </ul>
 *
 * <p>用真 PostgreSQL（Testcontainers），因为防超卖依赖真实的行锁与约束语义。
 */
@SpringBootTest
@DisplayName("库存 · 预占与并发防超卖")
class InventoryReserveIT extends PostgresTestBase {

    @Autowired
    private InventoryApplicationService inventory;

    @Autowired
    private JdbcTemplate jdbc;

    private TestDataFactory data;

    private long warehouseId;
    private long skuId;

    @BeforeEach
    void setUp() {
        data = new TestDataFactory(jdbc);
        data.truncateAll();
        warehouseId = data.warehouse("SH01");
        long productId = data.product("P-TS-001", "圆领T恤");
        skuId = data.sku(productId, "白色", "L");
    }

    // ─────────────────────────── 基础语义 ───────────────────────────

    @Test
    @CaseId("TC-INV-001")
    @DisplayName("预占成功后：实存不变、预占增加、可用量相应减少（不变量 INV-3）")
    void reserve_shouldKeepOnHandAndReduceAvailable() {
        data.stock(skuId, warehouseId, "", null, 10);

        ReserveResult r = inventory.reserve(
                new ReserveCommand(skuId, warehouseId, "", 4, "req-0001"));

        assertThat(r.duplicate()).isFalse();
        assertThat(r.reservedQty()).isEqualTo(4);
        assertThat(data.onHand(skuId, warehouseId, "")).isEqualTo(10);
        assertThat(data.reserved(skuId, warehouseId, "")).isEqualTo(4);
        assertThat(data.available(skuId, warehouseId, ""))
                .as("不变量 INV-3：可用量 = 实存 − 预占")
                .isEqualTo(6);
    }

    @Test
    @CaseId("TC-INV-002")
    @DisplayName("可用量不足必须拒绝，且不产生任何副作用（错误码含定位信息）")
    void reserve_whenInsufficient_thenRejectWithNoSideEffect() {
        data.stock(skuId, warehouseId, "", null, 3);

        assertThatThrownBy(() -> inventory.reserve(
                new ReserveCommand(skuId, warehouseId, "", 5, "req-0002")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.INV_INSUFFICIENT_STOCK)
                .hasMessageContaining("可用量 3")
                .hasMessageContaining("需求 5");

        assertThat(data.reserved(skuId, warehouseId, ""))
                .as("失败必须不留副作用")
                .isZero();
        assertThat(data.available(skuId, warehouseId, "")).isEqualTo(3);
    }

    @Test
    @CaseId("TC-INV-003")
    @DisplayName("预占只受可用量约束：实存 10、已预占 8 时，预占 3 必须失败")
    void reserve_shouldCheckAvailableNotOnHand() {
        data.stock(skuId, warehouseId, "", null, 10);
        inventory.reserve(new ReserveCommand(skuId, warehouseId, "", 8, "req-0003"));

        assertThatThrownBy(() -> inventory.reserve(
                new ReserveCommand(skuId, warehouseId, "", 3, "req-0004")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.INV_INSUFFICIENT_STOCK);

        assertThat(data.onHand(skuId, warehouseId, "")).isEqualTo(10);
        assertThat(data.reserved(skuId, warehouseId, "")).isEqualTo(8);
    }

    @Test
    @CaseId("TC-INV-004")
    @DisplayName("释放预占后可用量恢复，实存不受影响")
    void release_shouldRestoreAvailable() {
        data.stock(skuId, warehouseId, "", null, 10);
        ReserveResult r = inventory.reserve(
                new ReserveCommand(skuId, warehouseId, "", 6, "req-0005"));
        assertThat(data.available(skuId, warehouseId, "")).isEqualTo(4);

        inventory.release(r.reservationId());

        assertThat(data.reserved(skuId, warehouseId, "")).isZero();
        assertThat(data.onHand(skuId, warehouseId, "")).isEqualTo(10);
        assertThat(data.available(skuId, warehouseId, "")).isEqualTo(10);
    }

    // ─────────────────────────── 幂等（不变量 INV-9） ───────────────────────────

    @Test
    @CaseId("TC-INV-005")
    @DisplayName("相同 requestId 重复预占只生效一次（幂等，不变量 INV-9）")
    void reserve_withSameRequestId_shouldBeIdempotent() {
        data.stock(skuId, warehouseId, "", null, 10);

        ReserveResult first = inventory.reserve(
                new ReserveCommand(skuId, warehouseId, "", 4, "req-idem-1"));
        ReserveResult second = inventory.reserve(
                new ReserveCommand(skuId, warehouseId, "", 4, "req-idem-1"));

        assertThat(first.duplicate()).isFalse();
        assertThat(second.duplicate()).as("第二次必须命中幂等").isTrue();
        assertThat(second.reservationId()).isEqualTo(first.reservationId());
        assertThat(data.reserved(skuId, warehouseId, ""))
                .as("只允许扣减一次，必须仍是 4")
                .isEqualTo(4);
    }

    // ─────────────────────────── ★ 并发防超卖 ───────────────────────────

    @Test
    @CaseId("TC-INV-006")
    @DisplayName("★ 并发预占：100 个请求抢 50 件库存，绝不超卖且无流水漂移")
    void concurrentReserve_mustNeverOversell() throws Exception {
        final int stockQty = 50;
        final int threads = 100;
        data.stock(skuId, warehouseId, "", null, stockQty);

        // 线程池必须 ≥ 线程数，否则排队中的任务永远到不了起跑线，
        // readyGate 会超时（这是测试自身的缺陷，与被测代码无关）。
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch readyGate = new CountDownLatch(threads);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        List<Future<Void>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(pool.submit((Callable<Void>) () -> {
                readyGate.countDown();
                startGate.await(10, TimeUnit.SECONDS);   // 所有线程同时起跑，制造真实竞争
                try {
                    inventory.reserve(new ReserveCommand(
                            skuId, warehouseId, "", 1, "req-conc-" + idx));
                    succeeded.incrementAndGet();
                } catch (BusinessException e) {
                    assertThat(e.getCode()).isEqualTo(ErrorCode.INV_INSUFFICIENT_STOCK);
                    rejected.incrementAndGet();
                }
                return null;
            }));
        }

        assertThat(readyGate.await(15, TimeUnit.SECONDS)).isTrue();
        startGate.countDown();
        for (Future<Void> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        // ── 核心断言：不变量 INV-1 / INV-3 ──
        assertThat(succeeded.get())
                .as("成功数必须恰好等于库存量（多一个就是超卖）")
                .isEqualTo(stockQty);
        assertThat(rejected.get()).isEqualTo(threads - stockQty);
        assertThat(data.onHand(skuId, warehouseId, "")).isEqualTo(stockQty);
        assertThat(data.reserved(skuId, warehouseId, "")).isEqualTo(stockQty);
        assertThat(data.available(skuId, warehouseId, ""))
                .as("不变量 INV-1：可用量绝不为负")
                .isZero();

        // ── 预占记录数必须与成功数一致 ──
        Integer reservationCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_reservations WHERE status = 'ACTIVE'",
                Integer.class);
        assertThat(reservationCount).isEqualTo(stockQty);
    }

    @Test
    @CaseId("TC-INV-007")
    @DisplayName("★ 并发预占同一 requestId：只允许一次生效（数据库唯一约束兜底）")
    void concurrentReserve_sameRequestId_onlyOnceEffective() throws Exception {
        data.stock(skuId, warehouseId, "", null, 10);
        final int threads = 20;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch readyGate = new CountDownLatch(threads);
        AtomicInteger effective = new AtomicInteger();
        AtomicInteger idempotentHit = new AtomicInteger();
        AtomicInteger concurrentConflict = new AtomicInteger();

        List<Future<Void>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit((Callable<Void>) () -> {
                readyGate.countDown();
                startGate.await(10, TimeUnit.SECONDS);
                try {
                    ReserveResult r = inventory.reserve(new ReserveCommand(
                            skuId, warehouseId, "", 5, "req-same-id"));
                    if (r.duplicate()) {
                        idempotentHit.incrementAndGet();
                    } else {
                        effective.incrementAndGet();
                    }
                } catch (BusinessException e) {
                    // 并发下可能因事务尚未提交而暂时找不到首次结果，或库存已被占用；
                    // 两者都属可接受结果 —— 关键是**不得产生额外扣减**。
                    assertThat(e.getCode()).isIn(
                            ErrorCode.SYS_DUPLICATE_REQUEST,
                            ErrorCode.INV_INSUFFICIENT_STOCK);
                    concurrentConflict.incrementAndGet();
                }
                return null;
            }));
        }

        assertThat(readyGate.await(15, TimeUnit.SECONDS)).isTrue();
        startGate.countDown();
        for (Future<Void> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        // ── 核心断言：真正生效的只有一次（不变量 INV-9） ──
        assertThat(effective.get())
                .as("同一个 requestId 只能有一次真正生效")
                .isEqualTo(1);
        assertThat(idempotentHit.get() + concurrentConflict.get())
                .as("其余线程要么幂等命中、要么可接受地冲突，不允许静默成功")
                .isEqualTo(threads - 1);
        assertThat(data.reserved(skuId, warehouseId, ""))
                .as("预占量必须是 5，而不是 5 的倍数")
                .isEqualTo(5);
        assertThat(data.onHand(skuId, warehouseId, ""))
                .as("不变量 INV-1：实存不得为负")
                .isEqualTo(10);
    }

    // ─────────────────────────── 流水一致性（不变量 INV-2） ───────────────────────────

    @Test
    @CaseId("TC-INV-008")
    @DisplayName("入库写正向流水后，实存与流水净和一致（不变量 INV-2）")
    void receiveIn_shouldKeepLedgerConsistent() {
        inventory.receiveIn(skuId, warehouseId, "B1", null, null,
                20, "PURCHASE_IN", "PO-TEST-001", null, 1000L, "req-in-1");

        assertThat(data.onHand(skuId, warehouseId, "B1")).isEqualTo(20);
        assertThat(inventory.checkLedgerConsistency())
                .as("不变量 INV-2：实存 == 流水净和，不得有漂移")
                .isEmpty();
    }

    @Test
    @CaseId("TC-INV-009")
    @DisplayName("按 FEFO 出库：优先扣减最早效期批次，单笔可拆多批次")
    void shipOut_shouldFollowFefoOrder() {
        // 批次 B1 效期更早，应先被扣减
        inventory.receiveIn(skuId, warehouseId, "B1", null,
                java.time.LocalDate.of(2025, 6, 1), 10, "PURCHASE_IN", "PO-1", null, 1000L, "req-in-b1");
        inventory.receiveIn(skuId, warehouseId, "B2", null,
                java.time.LocalDate.of(2025, 9, 1), 10, "PURCHASE_IN", "PO-2", null, 1000L, "req-in-b2");

        inventory.reserve(new ReserveCommand(skuId, warehouseId, "B1", 10, "req-r-b1"));
        inventory.reserve(new ReserveCommand(skuId, warehouseId, "B2", 2, "req-r-b2"));

        // 出库 12 件：应扣光 B1（10），再从 B2 扣 2
        inventory.shipOut(skuId, warehouseId, 12, "SALES_OUT", "SO-1", null, "req-out-1");

        assertThat(data.onHand(skuId, warehouseId, "B1"))
                .as("最早效期批次 B1 应被扣光")
                .isZero();
        assertThat(data.onHand(skuId, warehouseId, "B2")).isEqualTo(8);
        assertThat(inventory.checkLedgerConsistency())
                .as("不变量 INV-2 在拆批次后仍成立")
                .isEmpty();
    }

    // ─────────────────────────── 数据库约束兜底 ───────────────────────────

    @Test
    @CaseId("TC-INV-010")
    @DisplayName("数据库 CHECK 约束兜底：即使应用层有 bug，实存也不可能被写成负数")
    void databaseConstraint_mustPreventNegativeOnHand() {
        data.stock(skuId, warehouseId, "", null, 5);

        // 绕过应用层，直接执行违规 UPDATE —— 模拟"代码有 bug"的最坏情况
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE inventory_balances SET on_hand_qty = -1
                 WHERE sku_id = ? AND warehouse_id = ?
                """, skuId, warehouseId))
                .as("不变量 INV-1 必须由数据库兜底")
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        assertThat(data.onHand(skuId, warehouseId, "")).isEqualTo(5);
    }

    @Test
    @CaseId("TC-INV-011")
    @DisplayName("数据库 CHECK 约束兜底：预占不得超过实存")
    void databaseConstraint_mustPreventReservedExceedingOnHand() {
        data.stock(skuId, warehouseId, "", null, 5);

        assertThatThrownBy(() -> jdbc.update("""
                UPDATE inventory_balances SET reserved_qty = 6
                 WHERE sku_id = ? AND warehouse_id = ?
                """, skuId, warehouseId))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
