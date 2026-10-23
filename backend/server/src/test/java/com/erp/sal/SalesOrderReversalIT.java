package com.erp.sal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.inv.application.InventoryApplicationService;
import com.erp.pur.application.PurchaseOrderApplicationService;
import com.erp.pur.application.PurchaseOrderApplicationService.CreateCommand;
import com.erp.pur.application.PurchaseOrderApplicationService.CreateLineCommand;
import com.erp.pur.application.PurchaseOrderApplicationService.ReceiveCommand;
import com.erp.pur.application.PurchaseOrderApplicationService.ReceiveLineCommand;
import com.erp.rma.application.ReturnOrderApplicationService;
import com.erp.rma.domain.ReturnOrder;
import com.erp.sal.application.SalesOrderApplicationService;
import com.erp.sal.domain.SalesOrder;
import com.erp.sal.domain.SalesOrderLine;
import com.erp.sal.repository.SalesOrderRepository;
import com.erp.shared.document.DocumentStatus;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.support.CaseId;
import com.erp.support.PostgresTestBase;
import com.erp.support.TestDataFactory;
import java.time.LocalDate;
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
 * 出货红冲集成测试（FR-SAL-04，M2.5）。
 *
 * <p>本类只测"已出货之后"的那条路 —— M1/M2 的
 * {@code SalesOrderIT} 已覆盖建单预占、FEFO 出库与未出货作废。
 *
 * <p>四条不能弯的线：
 * <ul>
 *   <li><b>库存事实</b>：红冲必须按原 {@code SALES_OUT} 流水逐批次回补，
 *       跑完后 INV-2（实存 == 流水净和）仍然为空；</li>
 *   <li><b>与退货互斥</b>：已有退货数量的单据拒绝红冲（退货已回补过库存），
 *       红冲后原单上的待入库退货也不能再入库（否则会二次回补）；</li>
 *   <li><b>幂等</b>：同 {@code X-Request-Id} 重放不产生第二套流水；</li>
 *   <li><b>并发</b>：两个异键请求同时红冲同一张单，只允许一个成功。</li>
 * </ul>
 *
 * <p>所有库存都用真实进货单造（而不是直插余额行），
 * 否则结尾的 {@code checkLedgerConsistency()} 会因为"库存没有对应流水"而误报。
 */
@SpringBootTest
@DisplayName("出货 · 红冲（FR-SAL-04）")
class SalesOrderReversalIT extends PostgresTestBase {

    private static final LocalDate BIZ_DATE = LocalDate.of(2025, 6, 1);

    @Autowired
    private SalesOrderApplicationService salesOrders;
    @Autowired
    private PurchaseOrderApplicationService purchaseOrders;
    @Autowired
    private ReturnOrderApplicationService returnOrders;
    @Autowired
    private InventoryApplicationService inventory;
    @Autowired
    private SalesOrderRepository salesOrderRepository;
    @Autowired
    private JdbcTemplate jdbc;

    private TestDataFactory data;
    private long warehouseId;
    private long customerId;
    private long supplierId;
    private long skuA;
    private long skuB;

    @BeforeEach
    void setUp() {
        data = new TestDataFactory(jdbc);
        data.truncateAll();

        warehouseId = data.warehouse("WH-REV-SAL");
        customerId = data.customer("CUS-REV-SAL");
        supplierId = data.supplier("SUP-REV-SAL");
        long productId = data.product("P-REV-SAL", "红冲测试商品");
        skuA = data.sku(productId, "白色", "L");
        skuB = data.sku(productId, "黑色", "M");
    }

    @Test
    @CaseId("TC-SAL-018")
    @DisplayName("★ 红冲幂等：同 X-Request-Id 重放不产生第二套流水；已作废后换新键被拒")
    void reverse_isIdempotentPerRequestKey() {
        receiveStock(skuA, "", 10);
        SalesOrder o = shippedOrder(skuA, 5, "req-sal-rev-idem");
        assertThat(data.onHand(skuA, warehouseId, "")).isEqualTo(5);

        SalesOrder first = salesOrders.voidOrder(o.id(), "req-sal-rev-once");
        assertThat(first.status()).isEqualTo(DocumentStatus.VOIDED);
        assertThat(data.onHand(skuA, warehouseId, "")).isEqualTo(10);
        int ledgerAfterFirst = data.ledgerCount(o.orderNo());
        assertThat(ledgerAfterFirst).as("1 条 SALES_OUT + 1 条 SALES_REVERSAL").isEqualTo(2);

        SalesOrder replay = salesOrders.voidOrder(o.id(), "req-sal-rev-once");
        assertThat(replay.status()).isEqualTo(DocumentStatus.VOIDED);
        assertThat(data.ledgerCount(o.orderNo()))
                .as("★ 同键重放不得再写流水（INV-9）")
                .isEqualTo(ledgerAfterFirst);
        assertThat(data.onHand(skuA, warehouseId, "")).as("同键重放不得再回补库存").isEqualTo(10);

        assertThatThrownBy(() -> salesOrders.voidOrder(o.id(), "req-sal-rev-another"))
                .as("已作废 + 新键必须被拒，而不是让客户端以为又红冲了一次")
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SAL_STATUS_ILLEGAL)
                .hasMessageContaining("已作废");

        assertThat(inventory.checkLedgerConsistency()).isEmpty();
    }

    @Test
    @CaseId("TC-SAL-019")
    @DisplayName("★ 锁账期拒绝红冲：SET_PERIOD_CLOSED，单据状态/库存/流水全部不动")
    void reverse_whenPeriodClosed_thenRejectedAndNoSideEffects() {
        receiveStock(skuA, "", 10);
        SalesOrder o = shippedOrder(skuA, 5, "req-sal-rev-locked");

        lockPeriod("2025-06");

        assertThatThrownBy(() -> salesOrders.voidOrder(o.id(), "req-sal-rev-locked-void"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SET_PERIOD_CLOSED)
                .hasMessageContaining("2025-06");

        assertThat(salesOrders.require(o.id()).status())
                .as("被拒绝的红冲不得改动状态")
                .isEqualTo(DocumentStatus.COMPLETED);
        assertThat(data.onHand(skuA, warehouseId, "")).as("更不得回补库存").isEqualTo(5);
        assertThat(data.ledgerCount(o.orderNo())).as("一条流水都不许多写").isEqualTo(1);
        assertThat(inventory.checkLedgerConsistency()).isEmpty();
    }

    @Test
    @CaseId("TC-SAL-020")
    @DisplayName("★ 有退货的出货单不能红冲（退货已回补过库存，再全额红冲会双补）")
    void reverse_whenAnyReturned_thenRejected() {
        receiveStock(skuA, "", 10);
        SalesOrder o = shippedOrder(skuA, 5, "req-sal-rev-with-return");
        long originLineId = o.lines().get(0).id();

        ReturnOrder ro = returnOrders.create(new ReturnOrderApplicationService.CreateCommand(
                o.id(), LocalDate.of(2025, 6, 2), "红冲场景退货",
                List.of(new ReturnOrderApplicationService.CreateLineCommand(originLineId, 2,
                        "BACK-01", null, null)),
                "req-sal-rev-return-create"));
        returnOrders.submit(ro.id());
        returnOrders.approve(ro.id());
        returnOrders.receive(ro.id(), "req-sal-rev-return-receive");

        // 退货由操作员指定批次（M2 口径），所以回补落在 BACK-01 上，而不是原出货批次 ''
        assertThat(data.onHand(skuA, warehouseId, "BACK-01")).as("退货回补到新批次").isEqualTo(2);
        assertThat(data.onHand(skuA, warehouseId, "")).as("原批次仍是出货后的 5 件").isEqualTo(5);

        assertThatThrownBy(() -> salesOrders.voidOrder(o.id(), "req-sal-rev-after-return"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SAL_STATUS_ILLEGAL)
                .hasMessageContaining("已退货");

        assertThat(salesOrders.require(o.id()).status()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(data.onHand(skuA, warehouseId, ""))
                .as("拒绝的红冲不得把原批次再回补成 7（那就和退货重复入库了）")
                .isEqualTo(5);
        assertThat(data.onHand(skuA, warehouseId, "BACK-01")).isEqualTo(2);
        assertThat(inventory.ledgerEntries(o.orderNo(), "SALES_REVERSAL")).isEmpty();
        assertThat(inventory.checkLedgerConsistency()).isEmpty();
    }

    @Test
    @CaseId("TC-SAL-021")
    @DisplayName("★ 红冲后原单上待入库的退货单不能再入库（否则会二次回补库存）")
    void returnReceive_afterOriginReversed_thenRejected() {
        receiveStock(skuA, "", 10);
        SalesOrder o = shippedOrder(skuA, 5, "req-sal-rev-pending-return");
        long originLineId = o.lines().get(0).id();

        // 退货单已建好并审核，但还没入库（returnedQty 仍为 0）
        ReturnOrder ro = returnOrders.create(new ReturnOrderApplicationService.CreateCommand(
                o.id(), LocalDate.of(2025, 6, 2), "红冲前待入库退货",
                List.of(new ReturnOrderApplicationService.CreateLineCommand(originLineId, 2,
                        "PEND-01", null, null)),
                "req-sal-rev-pending-create"));
        returnOrders.submit(ro.id());
        returnOrders.approve(ro.id());

        SalesOrder voided = salesOrders.voidOrder(o.id(), "req-sal-rev-pending-void");
        assertThat(voided.status()).isEqualTo(DocumentStatus.VOIDED);
        assertThat(data.onHand(skuA, warehouseId, "")).isEqualTo(10);

        assertThatThrownBy(() -> returnOrders.receive(ro.id(), "req-sal-rev-pending-receive"))
                .as("原单已红冲为 VOIDED，退货入库必须被拦住")
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.RMA_ORIGIN_NOT_SHIPPED)
                .hasMessageContaining("红冲");

        assertThat(data.onHand(skuA, warehouseId, ""))
                .as("★ 被拒绝的退货入库不得把库存再回补一次")
                .isEqualTo(10);
        assertThat(data.ledgerCount(ro.orderNo())).as("不得留下 RETURN_IN 流水").isZero();
        assertThat(salesOrders.require(o.id()).lines().get(0).returnedQty())
                .as("累计退货量不得被推进")
                .isZero();
        assertThat(returnOrders.require(ro.id()).status())
                .as("失败整体回滚，退货单仍在审核态")
                .isEqualTo(DocumentStatus.APPROVED);
        assertThat(inventory.checkLedgerConsistency()).isEmpty();
    }

    @Test
    @CaseId("TC-SAL-022")
    @DisplayName("★ PARTIAL 红冲：已出货行回补实存，未出货行的 ACTIVE 预占必须一起释放")
    void reverse_partialOrder_restoresShippedAndReleasesPendingReservations() {
        receiveStock(skuA, "", 10);
        receiveStock(skuB, "", 10);

        SalesOrder created = salesOrders.create(new SalesOrderApplicationService.CreateCommand(
                customerId, warehouseId, BIZ_DATE, "部分出货红冲",
                List.of(new SalesOrderApplicationService.CreateLineCommand(skuA, 2, 1000L, null),
                        new SalesOrderApplicationService.CreateLineCommand(skuB, 3, 1000L, null)),
                "req-sal-partial"));
        salesOrders.submit(created.id());
        SalesOrder approved = salesOrders.approve(created.id());

        // 当前 ship 接口是整单出货，PARTIAL 是"未来分批出货"的形态。
        // 这里按出货确认的同一套动作只出第一行，构造出真实的 PARTIAL 数据：
        // 累计出货量 + 库存扣减 + 预占消费，三者都做，缺一个 INV-2 就会漂移。
        SalesOrderLine lineA = approved.lines().get(0);
        assertThat(salesOrderRepository.applyShippedQty(lineA.id(), 2)).isTrue();
        inventory.shipOut(skuA, warehouseId, 2, "SALES_OUT",
                approved.orderNo(), approved.id(), "req-sal-partial-ship-line-a");
        for (long reservationId : lineA.reservationIds()) {
            inventory.consumeReservation(reservationId);
        }
        assertThat(salesOrderRepository.updateStatus(approved.id(), DocumentStatus.APPROVED,
                DocumentStatus.PARTIAL, approved.version())).isTrue();

        assertThat(data.onHand(skuA, warehouseId, "")).isEqualTo(8);
        assertThat(data.reserved(skuB, warehouseId, "")).as("第二行仍被预占锁住").isEqualTo(3);

        SalesOrder voided = salesOrders.voidOrder(created.id(), "req-sal-partial-reverse");

        assertThat(voided.status()).isEqualTo(DocumentStatus.VOIDED);
        assertThat(data.onHand(skuA, warehouseId, ""))
                .as("已出货的 2 件必须回补")
                .isEqualTo(10);
        assertThat(data.onHand(skuB, warehouseId, "")).isEqualTo(10);
        assertThat(data.reserved(skuB, warehouseId, ""))
                .as("★ 未出货行的预占必须释放，否则作废一单就永久锁死一批库存")
                .isZero();
        assertThat(inventory.sumActiveReservedQty(skuB, warehouseId)).isZero();
        assertThat(inventory.ledgerEntries(voided.orderNo(), "SALES_REVERSAL"))
                .as("只回补真实出货的那一批")
                .singleElement()
                .satisfies(e -> assertThat(e.changeQty()).isEqualTo(2));
        assertThat(inventory.checkLedgerConsistency()).isEmpty();
    }

    @Test
    @CaseId("TC-SAL-023")
    @DisplayName("★ 并发红冲（异键）：只有一个成功，库存只回补一次")
    void reverse_concurrentDifferentKeys_onlyOneWins() throws Exception {
        receiveStock(skuA, "", 10);
        SalesOrder o = shippedOrder(skuA, 5, "req-sal-rev-conc");

        final int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch readyGate = new CountDownLatch(threads);
        AtomicInteger succeeded = new AtomicInteger();
        List<ErrorCode> rejectedCodes = new ArrayList<>();

        List<Future<Void>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final String key = "req-sal-conc-void-" + i;
            futures.add(pool.submit((Callable<Void>) () -> {
                readyGate.countDown();
                startGate.await(10, TimeUnit.SECONDS);
                try {
                    SalesOrder result = salesOrders.voidOrder(o.id(), key);
                    assertThat(result.status()).isEqualTo(DocumentStatus.VOIDED);
                    succeeded.incrementAndGet();
                } catch (BusinessException e) {
                    synchronized (rejectedCodes) {
                        rejectedCodes.add(e.getCode());
                    }
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

        assertThat(succeeded.get()).as("★ 恰好一个红冲成功").isEqualTo(1);
        assertThat(rejectedCodes)
                .as("另一个必须被业务码拒绝（而不是 500 或静默成功）")
                .hasSize(threads - 1)
                .allSatisfy(code -> assertThat(code).isEqualTo(ErrorCode.SAL_STATUS_ILLEGAL));

        assertThat(salesOrders.require(o.id()).status()).isEqualTo(DocumentStatus.VOIDED);
        assertThat(data.onHand(skuA, warehouseId, ""))
                .as("库存只回补一次：10 而不是 15")
                .isEqualTo(10);
        assertThat(inventory.ledgerEntries(o.orderNo(), "SALES_REVERSAL")).hasSize(1);
        assertThat(inventory.checkLedgerConsistency()).isEmpty();
    }

    // ─────────────────────────── 辅助 ───────────────────────────

    /** 用真实进货单入库一个批次（保证 INV-2：每一件事实都有对应流水）。 */
    private void receiveStock(long skuId, String batchNo, int qty) {
        String key = "req-rev-stock-" + skuId + "-" + batchNo + "-" + qty;
        var po = purchaseOrders.create(new CreateCommand(
                supplierId, warehouseId, BIZ_DATE, "红冲备货",
                List.of(new CreateLineCommand(skuId, qty, 1000L)), key));
        purchaseOrders.submit(po.id());
        purchaseOrders.approve(po.id());
        purchaseOrders.receive(po.id(), new ReceiveCommand(
                List.of(new ReceiveLineCommand(po.lines().get(0).id(), qty,
                        batchNo, null, null)), key + "-recv"));
    }

    /** 建单 → 提交 → 审核 → 出货，返回 COMPLETED 的出货单。 */
    private SalesOrder shippedOrder(long skuId, int qty, String key) {
        SalesOrder o = salesOrders.create(new SalesOrderApplicationService.CreateCommand(
                customerId, warehouseId, BIZ_DATE, "红冲测试出货",
                List.of(new SalesOrderApplicationService.CreateLineCommand(skuId, qty, 1000L, null)),
                key));
        salesOrders.submit(o.id());
        salesOrders.approve(o.id());
        return salesOrders.ship(o.id(), key + "-ship");
    }

    /** 直接锁一个账期（等价于跑过一次月结，但不引入结算域的用例）。 */
    private void lockPeriod(String period) {
        jdbc.update("""
                INSERT INTO settlement_periods (period, status, closed_at)
                VALUES (?, 'CLOSED', now())
                """, period);
    }
}
