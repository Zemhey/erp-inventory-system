package com.erp.inv;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.inv.application.InventoryApplicationService;
import com.erp.inv.application.InventoryApplicationService.ReserveCommand;
import com.erp.inv.application.InventoryApplicationService.ReserveResult;
import com.erp.inv.application.ReservationExpiryScheduler;
import com.erp.support.CaseId;
import com.erp.support.PostgresTestBase;
import com.erp.support.TestClockConfig;
import com.erp.support.TestDataFactory;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 预占超时自动释放（BR-INV-05）—— M2-S4 的验收物。
 *
 * <p><b>时间全部由注入的时钟摆布，没有一秒是等来的</b>：
 * {@code @ActiveProfiles("clock")} 让 {@link TestClockConfig} 的 {@code @Primary}
 * 可控时钟接管 {@code InventoryApplicationService} 的 {@code ClockProvider}，
 * 于是"到期"这件事由 {@code clock.set / advance} 决定，
 * 断言只调 {@code expireOverdueReservations()}，不睡、不等真实调度（计划书 §6 坑 9b）。
 *
 * <p><b>"未到期不动"与"到期释放"是同一条边界的两侧</b>（TC-INV-012/013）：
 * TTL 从配置读进来（不在测试里写死 30），同一份数据在
 * {@code now + ttl - 1}、{@code now + ttl}、{@code now + ttl + 1} 三个点上各扫一次，
 * 结果依次是"不动 / 不动 / 释放"。边界两侧都扫过，才说明不是运气。
 *
 * <p><b>调度开关</b>：{@code erp.inventory.expire-scheduling-enabled=false}
 * 由 test classpath 的 {@code application-default.yml} / {@code application-clock.yml}
 * 提供。TC-INV-015 断言调度器 Bean 真的不在容器里 —— 否则这个开关被谁删掉都没人知道。
 */
@SpringBootTest
@ActiveProfiles("clock")
@DisplayName("库存 · 预占超时自动释放（BR-INV-05）")
class InventoryReservationExpiryIT extends PostgresTestBase {

    /** 可控时钟的起点；每条用例复位到这里，避免用例之间互相带着时间跑。 */
    private static final LocalDateTime BASE = LocalDateTime.of(2025, 6, 1, 9, 0, 0);

    @Autowired
    private InventoryApplicationService inventory;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private TestClockConfig.ControllableClock clock;

    /** ★ 边界由配置决定，不由测试写死：运维把 TTL 改成 10 分钟，这三条断言仍然成立。 */
    @Value("${erp.inventory.reservation-ttl-minutes}")
    private long ttlMinutes;

    private TestDataFactory data;
    private long warehouseId;
    private long skuId;

    @BeforeEach
    void setUp() {
        clock.set(BASE);
        data = new TestDataFactory(jdbc);
        data.truncateAll();
        warehouseId = data.warehouse("SH01");
        long productId = data.product("P-TS-001", "圆领T恤");
        skuId = data.sku(productId, "白色", "L");
    }

    // ─────────────────────────── 到期释放 ───────────────────────────

    @Test
    @CaseId("TC-INV-012")
    @DisplayName("到期 ACTIVE 预占被自动释放：可用量恢复、实存不动、状态置 EXPIRED")
    void expire_overdueReservation_shouldRestoreAvailableAndMarkExpired() {
        receiveIn("", 10);
        ReserveResult r = reserve("", 6, "req-exp-012");
        assertThat(data.available(skuId, warehouseId, "")).isEqualTo(4);

        clock.advance(Duration.ofMinutes(ttlMinutes + 1));
        int expired = inventory.expireOverdueReservations();

        assertThat(expired).as("只有 1 条到期").isEqualTo(1);
        assertThat(data.reserved(skuId, warehouseId, ""))
                .as("预占量必须回收")
                .isZero();
        assertThat(data.available(skuId, warehouseId, ""))
                .as("可用量恢复到实存（不变量 INV-3）")
                .isEqualTo(10);
        assertThat(data.onHand(skuId, warehouseId, ""))
                .as("超时释放不是出库，实存一点都不能动")
                .isEqualTo(10);
        assertThat(status(r.reservationId())).isEqualTo("EXPIRED");
    }

    @Test
    @CaseId("TC-INV-013")
    @DisplayName("未到期预占不动，且边界精确：ttl-1 与 ttl 都不放，ttl+1 才放")
    void expire_notYetDue_shouldLeaveReservationAlone() {
        receiveIn("", 10);
        ReserveResult r = reserve("", 6, "req-exp-013");

        clock.set(BASE.plusMinutes(ttlMinutes - 1));
        assertThat(inventory.expireOverdueReservations())
                .as("还差 1 分钟到期，一条都不该动")
                .isZero();

        clock.set(BASE.plusMinutes(ttlMinutes));
        assertThat(inventory.expireOverdueReservations())
                .as("expires_at == now 算未到期（边界为严格小于）")
                .isZero();
        assertThat(status(r.reservationId())).isEqualTo("ACTIVE");
        assertThat(data.reserved(skuId, warehouseId, "")).isEqualTo(6);
        assertThat(data.available(skuId, warehouseId, "")).isEqualTo(4);

        clock.advance(Duration.ofMinutes(1));
        assertThat(inventory.expireOverdueReservations())
                .as("过了边界 1 分钟就必须释放 —— 上面两次 isZero 与这一次 1 一起构成边界证据")
                .isEqualTo(1);
        assertThat(status(r.reservationId())).isEqualTo("EXPIRED");
        assertThat(data.available(skuId, warehouseId, "")).isEqualTo(10);
    }

    // ─────────────────────────── 不产生流水（INV-2 前提） ───────────────────────────

    @Test
    @CaseId("TC-INV-014")
    @DisplayName("超时释放只影响预占量，一条流水都不写（不变量 INV-2 的前提）")
    void expire_mustNotWriteAnyLedgerEntry() {
        // 用 receiveIn 建库存：这条入库本身必须留 1 条流水，作为"流水基线"
        receiveIn("", 10);
        long ledgerBefore = ledgerCount();
        assertThat(ledgerBefore).as("入库应有 1 条流水，否则下面的相等断言是空的").isEqualTo(1);

        reserve("", 6, "req-exp-014");
        assertThat(ledgerCount()).as("预占不写流水").isEqualTo(ledgerBefore);

        clock.advance(Duration.ofMinutes(ttlMinutes + 1));
        assertThat(inventory.expireOverdueReservations())
                .as("先确认释放真的发生了")
                .isEqualTo(1);

        assertThat(ledgerCount())
                .as("★ 释放只减 reserved_qty，不得凭空造出一条库存变动流水")
                .isEqualTo(ledgerBefore);
        assertThat(data.reserved(skuId, warehouseId, "")).isZero();
        assertThat(data.onHand(skuId, warehouseId, "")).isEqualTo(10);
        assertThat(inventory.checkLedgerConsistency())
                .as("不变量 INV-2：实存 == 流水净和，超时释放不得引入漂移")
                .isEmpty();
    }

    // ─────────────────────────── 幂等 + 调度开关 ───────────────────────────

    @Test
    @CaseId("TC-INV-015")
    @DisplayName("过期任务幂等且定时入口只是转发；测试上下文里调度器根本没装配（坑 9b 开关生效）")
    void expire_repeatedRuns_shouldBeIdempotent() {
        assertThat(context.getBeanNamesForType(ReservationExpiryScheduler.class))
                .as("erp.inventory.expire-scheduling-enabled=false 时不得有调度器："
                        + "@Scheduled 抢先释放会让本类的断言随机红")
                .isEmpty();

        receiveIn("", 10);
        ReserveResult byService = reserve("", 6, "req-exp-015");
        ReserveResult byScheduler = reserve("", 2, "req-exp-015-sched");
        clock.advance(Duration.ofMinutes(ttlMinutes + 1));

        // ① 先只走定时入口。测试容器里没有调度线程（上面已断言），所以手工实例化直接触发。
        //    若这个 @Scheduled 方法只是个空壳，两条预占会一起留在 ACTIVE，下面立刻红。
        new ReservationExpiryScheduler(inventory).expireOverdueReservations();
        assertThat(status(byScheduler.reservationId()))
                .as("定时入口必须真的调到 expireOverdueReservations()")
                .isEqualTo("EXPIRED");
        assertThat(status(byService.reservationId())).isEqualTo("EXPIRED");
        assertThat(data.reserved(skuId, warehouseId, "")).isZero();

        // ② 再手动跑两次：同一条预占不会被第二次释放
        assertThat(inventory.expireOverdueReservations())
                .as("第二次：已无 ACTIVE 可释放")
                .isZero();
        assertThat(inventory.expireOverdueReservations())
                .as("第三次同样空转")
                .isZero();

        assertThat(status(byService.reservationId()))
                .as("状态停在 EXPIRED，不被重复执行改写")
                .isEqualTo("EXPIRED");
        assertThat(countByStatus("EXPIRED")).isEqualTo(2);
        assertThat(data.reserved(skuId, warehouseId, ""))
                .as("预占量不得被重复扣减")
                .isZero();
        assertThat(data.onHand(skuId, warehouseId, "")).isEqualTo(10);
    }

    // ─────────────────────────── 与 ACTIVE 预占合计对账 ───────────────────────────

    @Test
    @CaseId("TC-INV-016")
    @DisplayName("过期释放后，余额行 reserved_qty 与 ACTIVE 预占合计逐行一致")
    void expire_shouldKeepBalanceConsistentWithActiveReservations() {
        receiveIn("B1", 20);
        receiveIn("B2", 20);

        // ① 已真实出货的预占：CONSUMED，不欠任何预留量
        ReserveResult shipped = reserve("B1", 4, "req-exp-016-ship");
        inventory.shipOut(skuId, warehouseId, 4, "SALES_OUT", "SO-EXP-016", null, "req-exp-016-out");
        assertThat(inventory.consumeReservation(shipped.reservationId())).isTrue();

        // ② 到期将被释放的两条
        reserve("B1", 5, "req-exp-016-b1");
        reserve("B2", 3, "req-exp-016-b2");

        // ③ 人为取消的一条：RELEASED，量已当场归还
        ReserveResult cancelled = reserve("B2", 2, "req-exp-016-cancel");
        inventory.release(cancelled.reservationId());

        // ④ 尚未到期的活动预占 —— 只有它该继续占着 B1 的量
        clock.set(BASE.plusMinutes(ttlMinutes));
        reserve("B1", 7, "req-exp-016-fresh");

        clock.advance(Duration.ofMinutes(1));
        assertThat(inventory.expireOverdueReservations())
                .as("到期的只有 ② 那两条")
                .isEqualTo(2);

        assertThat(data.reserved(skuId, warehouseId, "B1"))
                .as("B1：只剩 ④ 的 7")
                .isEqualTo(7);
        assertThat(data.reserved(skuId, warehouseId, "B2"))
                .as("B2：② 释放、③ 当场归还")
                .isZero();
        assertThat(status(cancelled.reservationId()))
                .as("取消的记录保持 RELEASED，过期任务不得把它改成 EXPIRED")
                .isEqualTo("RELEASED");

        // 逐行对账：每张余额行的 reserved_qty 必须等于该维度下 ACTIVE 预占之和
        List<Map<String, Object>> drift = jdbc.queryForList("""
                SELECT b.sku_id, b.warehouse_id, b.batch_no,
                       b.reserved_qty AS balance_reserved,
                       (SELECT COALESCE(SUM(r.reserved_qty), 0)
                          FROM inventory_reservations r
                         WHERE r.sku_id = b.sku_id AND r.warehouse_id = b.warehouse_id
                           AND r.batch_no = b.batch_no AND r.status = 'ACTIVE') AS active_sum
                  FROM inventory_balances b
                 WHERE b.reserved_qty <> (
                       SELECT COALESCE(SUM(r.reserved_qty), 0)
                         FROM inventory_reservations r
                        WHERE r.sku_id = b.sku_id AND r.warehouse_id = b.warehouse_id
                          AND r.batch_no = b.batch_no AND r.status = 'ACTIVE')
                """);
        assertThat(drift)
                .as("★ 余额行预占量与 ACTIVE 预占合计逐行一致（BR-INV-05 的对账口径）")
                .isEmpty();
        assertThat(inventory.sumActiveReservedQty(skuId, warehouseId))
                .as("SKU 维度合计 = 7 + 0")
                .isEqualTo(7);
    }

    // ─────────────────────────── 小工具 ───────────────────────────

    private void receiveIn(String batchNo, int qty) {
        inventory.receiveIn(skuId, warehouseId, batchNo, null, null, qty,
                "PURCHASE_IN", "PO-EXP-" + batchNo + "-" + qty, null, 1000L,
                "req-in-" + batchNo + "-" + qty);
    }

    private ReserveResult reserve(String batchNo, int qty, String requestId) {
        return inventory.reserve(new ReserveCommand(skuId, warehouseId, batchNo, qty, requestId));
    }

    private String status(long reservationId) {
        return jdbc.queryForObject(
                "SELECT status FROM inventory_reservations WHERE id = ?",
                String.class, reservationId);
    }

    private int countByStatus(String status) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_reservations WHERE status = ?",
                Integer.class, status);
        return n == null ? 0 : n;
    }

    private long ledgerCount() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM inventory_ledger", Long.class);
        return n == null ? 0 : n;
    }
}
