package com.erp.pur;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.inv.application.InventoryApplicationService;
import com.erp.pur.application.PurchaseOrderApplicationService;
import com.erp.pur.application.PurchaseOrderApplicationService.CreateCommand;
import com.erp.pur.application.PurchaseOrderApplicationService.CreateLineCommand;
import com.erp.pur.application.PurchaseOrderApplicationService.ReceiveCommand;
import com.erp.pur.application.PurchaseOrderApplicationService.ReceiveLineCommand;
import com.erp.pur.domain.PurchaseOrder;
import com.erp.rma.application.ReturnOrderApplicationService;
import com.erp.sal.application.SalesOrderApplicationService;
import com.erp.sal.domain.SalesOrder;
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
 * 进货红冲集成测试（FR-PUR-03，M2.5）。
 *
 * <p>进货红冲比出货红冲多一条硬约束：<b>回退不能把实存减成负数，也不能减到低于已预占量</b>。
 * 因此本类的重点不是"红冲成功了"，而是：
 * <ul>
 *   <li>多批次/多次入库时，反向流水与原始流水一一对应（可追溯）；</li>
 *   <li>货已被下游消耗（出货 / 预占 / 退货回补到别的批次）时，红冲明确失败而不是部分成功；</li>
 *   <li>失败必须零副作用：状态、实存、流水都不动；</li>
 *   <li>幂等与并发和出货红冲同一套口径。</li>
 * </ul>
 */
@SpringBootTest
@DisplayName("进货 · 红冲（FR-PUR-03）")
class PurchaseOrderReversalIT extends PostgresTestBase {

    private static final LocalDate BIZ_DATE = LocalDate.of(2025, 6, 1);

    @Autowired
    private PurchaseOrderApplicationService purchaseOrders;
    @Autowired
    private SalesOrderApplicationService salesOrders;
    @Autowired
    private ReturnOrderApplicationService returnOrders;
    @Autowired
    private InventoryApplicationService inventory;
    @Autowired
    private JdbcTemplate jdbc;

    private TestDataFactory data;
    private long warehouseId;
    private long supplierId;
    private long customerId;
    private long skuA;

    @BeforeEach
    void setUp() {
        data = new TestDataFactory(jdbc);
        data.truncateAll();

        warehouseId = data.warehouse("WH-REV-PUR");
        supplierId = data.supplier("SUP-REV-PUR");
        customerId = data.customer("CUS-REV-PUR");
        long productId = data.product("P-REV-PUR", "进货红冲商品");
        skuA = data.sku(productId, "白色", "L");
    }

    @Test
    @CaseId("TC-PUR-016")
    @DisplayName("★ COMPLETED 红冲（多次入库、多批次）：逐条反向流水，两个批次各自回退")
    void reverse_completedMultiBatch_reversesEachReceiptLine() {
        PurchaseOrder o = draft(10, 1000L, "req-po-rev-multi");
        purchaseOrders.submit(o.id());
        purchaseOrders.approve(o.id());
        long lineId = o.lines().get(0).id();

        purchaseOrders.receive(o.id(), new ReceiveCommand(
                List.of(new ReceiveLineCommand(lineId, 4, "B1", null, null)), "req-po-rev-multi-r1"));
        PurchaseOrder completed = purchaseOrders.receive(o.id(), new ReceiveCommand(
                List.of(new ReceiveLineCommand(lineId, 6, "B2", null, null)), "req-po-rev-multi-r2"));
        assertThat(completed.status()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(data.onHand(skuA, warehouseId, "B1")).isEqualTo(4);
        assertThat(data.onHand(skuA, warehouseId, "B2")).isEqualTo(6);

        PurchaseOrder voided = purchaseOrders.voidOrder(o.id(), "req-po-rev-multi-void");

        assertThat(voided.status()).isEqualTo(DocumentStatus.VOIDED);
        assertThat(data.onHand(skuA, warehouseId, "B1")).as("B1 的 4 件必须回退").isZero();
        assertThat(data.onHand(skuA, warehouseId, "B2")).as("B2 的 6 件必须回退").isZero();
        assertThat(inventory.ledgerEntries(o.orderNo(), "PURCHASE_IN"))
                .as("原始入库流水两条").hasSize(2);
        assertThat(inventory.ledgerEntries(o.orderNo(), "PURCHASE_REVERSAL"))
                .as("★ 反向流水与原始流水一一对应（可追溯）")
                .hasSize(2);
        assertThat(inventory.ledgerEntries(o.orderNo(), "PURCHASE_REVERSAL").stream()
                .mapToInt(com.erp.inv.domain.InventoryLedgerEntry::changeQty).sum())
                .as("反向流水合计 = -10，与原始入库 +10 相抵（INV-8）")
                .isEqualTo(-10);
        assertThat(inventory.checkLedgerConsistency()).isEmpty();
    }

    @Test
    @CaseId("TC-PUR-017")
    @DisplayName("★ 红冲幂等：同 X-Request-Id 重放不产生第二套流水；已作废后换新键被拒")
    void reverse_isIdempotentPerRequestKey() {
        PurchaseOrder o = receivedOrder(5, "B-KEY", "req-po-rev-idem");
        int ledgerAfterReceive = data.ledgerCount(o.orderNo());
        assertThat(ledgerAfterReceive).isEqualTo(1);

        PurchaseOrder first = purchaseOrders.voidOrder(o.id(), "req-po-rev-once");
        assertThat(first.status()).isEqualTo(DocumentStatus.VOIDED);
        assertThat(data.onHand(skuA, warehouseId, "B-KEY")).isZero();

        PurchaseOrder replay = purchaseOrders.voidOrder(o.id(), "req-po-rev-once");
        assertThat(replay.status()).isEqualTo(DocumentStatus.VOIDED);
        assertThat(data.ledgerCount(o.orderNo()))
                .as("★ 同键重放不得再写流水（INV-9）")
                .isEqualTo(ledgerAfterReceive + 1);

        assertThatThrownBy(() -> purchaseOrders.voidOrder(o.id(), "req-po-rev-another"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.PUR_STATUS_ILLEGAL)
                .hasMessageContaining("已作废");

        assertThat(inventory.checkLedgerConsistency()).isEmpty();
    }

    @Test
    @CaseId("TC-PUR-018")
    @DisplayName("★ 锁账期拒绝红冲：SET_PERIOD_CLOSED，状态/库存/流水全部不动")
    void reverse_whenPeriodClosed_thenRejectedAndNoSideEffects() {
        PurchaseOrder o = receivedOrder(5, "B-LOCK", "req-po-rev-locked");

        jdbc.update("""
                INSERT INTO settlement_periods (period, status, closed_at)
                VALUES ('2025-06', 'CLOSED', now())
                """);

        assertThatThrownBy(() -> purchaseOrders.voidOrder(o.id(), "req-po-rev-locked-void"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SET_PERIOD_CLOSED)
                .hasMessageContaining("2025-06");

        assertThat(purchaseOrders.require(o.id()).status()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(data.onHand(skuA, warehouseId, "B-LOCK")).isEqualTo(5);
        assertThat(data.ledgerCount(o.orderNo())).as("被拒绝的红冲不得写流水").isEqualTo(1);
        assertThat(inventory.checkLedgerConsistency()).isEmpty();
    }

    @Test
    @CaseId("TC-PUR-019")
    @DisplayName("★ 货已被下游出货消耗：红冲被 INV_NEGATIVE_NOT_ALLOWED 拒绝，且零副作用")
    void reverse_whenStockAlreadyShippedOut_thenRejected() {
        PurchaseOrder o = receivedOrder(5, "B-SHIP", "req-po-rev-shipped");
        SalesOrder so = shippedSalesOrder(5, "req-po-rev-sales");

        assertThat(data.onHand(skuA, warehouseId, "B-SHIP")).isZero();

        assertThatThrownBy(() -> purchaseOrders.voidOrder(o.id(), "req-po-rev-shipped-void"))
                .as("原批次已无货可退，必须明确拒绝而不是把库存减成负数")
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.INV_NEGATIVE_NOT_ALLOWED)
                .hasMessageContaining("B-SHIP");

        assertThat(purchaseOrders.require(o.id()).status())
                .as("★ 失败不得改动单据状态")
                .isEqualTo(DocumentStatus.COMPLETED);
        assertThat(data.onHand(skuA, warehouseId, "B-SHIP")).isZero();
        assertThat(data.ledgerCount(o.orderNo())).as("失败不得留下任何反向流水").isEqualTo(1);
        assertThat(inventory.ledgerEntries(so.orderNo(), "SALES_REVERSAL"))
                .as("进货红冲失败不得顺带动到出货流水")
                .isEmpty();
        assertThat(inventory.checkLedgerConsistency()).isEmpty();
    }

    @Test
    @CaseId("TC-PUR-020")
    @DisplayName("★ 货已被出货单预占：红冲会跌破预占线，拒绝且不动库存")
    void reverse_whenStockReservedBySalesOrder_thenRejected() {
        PurchaseOrder o = receivedOrder(5, "B-RES", "req-po-rev-reserved");

        SalesOrder so = salesOrders.create(new SalesOrderApplicationService.CreateCommand(
                customerId, warehouseId, BIZ_DATE, "预占后红冲进货",
                List.of(new SalesOrderApplicationService.CreateLineCommand(skuA, 3, 1000L, null)),
                "req-po-rev-reserve"));
        assertThat(data.reserved(skuA, warehouseId, "B-RES")).isEqualTo(3);

        assertThatThrownBy(() -> purchaseOrders.voidOrder(o.id(), "req-po-rev-reserved-void"))
                .as("回退 5 件会让预占 3 件悬空（可用量为负），必须拒绝")
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.INV_NEGATIVE_NOT_ALLOWED)
                .hasMessageContaining("已预占 3");

        assertThat(data.onHand(skuA, warehouseId, "B-RES")).isEqualTo(5);
        assertThat(data.reserved(skuA, warehouseId, "B-RES")).isEqualTo(3);
        assertThat(purchaseOrders.require(o.id()).status()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(salesOrders.require(so.id()).status()).isEqualTo(DocumentStatus.DRAFT);
        assertThat(inventory.checkLedgerConsistency()).isEmpty();
    }

    @Test
    @CaseId("TC-PUR-021")
    @DisplayName("★ 退货回补到别的批次不算：红冲不跨批次挪用库存")
    void reverse_doesNotBorrowStockFromAnotherBatch() {
        PurchaseOrder o = receivedOrder(5, "B-ORIG", "req-po-rev-batch");
        SalesOrder so = shippedSalesOrder(5, "req-po-rev-batch-sale");

        // 退货入库时操作员把货放到另一个批次（M2 的批次口径：由操作员指定）
        long originLineId = so.lines().get(0).id();
        var ro = returnOrders.create(new ReturnOrderApplicationService.CreateCommand(
                so.id(), LocalDate.of(2025, 6, 2), "回补到别的批次",
                List.of(new ReturnOrderApplicationService.CreateLineCommand(
                        originLineId, 5, "B-OTHER", null, null)),
                "req-po-rev-batch-return"));
        returnOrders.submit(ro.id());
        returnOrders.approve(ro.id());
        returnOrders.receive(ro.id(), "req-po-rev-batch-return-recv");

        assertThat(data.onHand(skuA, warehouseId, "B-ORIG")).as("原批次仍是空的").isZero();
        assertThat(data.onHand(skuA, warehouseId, "B-OTHER")).isEqualTo(5);

        assertThatThrownBy(() -> purchaseOrders.voidOrder(o.id(), "req-po-rev-batch-void"))
                .as("总库存够不代表原批次够 —— 跨批次挪用会悄悄改写库存的批次结构")
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.INV_NEGATIVE_NOT_ALLOWED)
                .hasMessageContaining("B-ORIG");

        assertThat(purchaseOrders.require(o.id()).status()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(data.onHand(skuA, warehouseId, "B-ORIG")).isZero();
        assertThat(data.onHand(skuA, warehouseId, "B-OTHER")).isEqualTo(5);
        assertThat(inventory.checkLedgerConsistency()).isEmpty();
    }

    @Test
    @CaseId("TC-PUR-022")
    @DisplayName("★ 并发红冲（异键）：只有一个成功，库存只回退一次")
    void reverse_concurrentDifferentKeys_onlyOneWins() throws Exception {
        PurchaseOrder o = receivedOrder(5, "B-CONC", "req-po-rev-conc");

        final int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch readyGate = new CountDownLatch(threads);
        AtomicInteger succeeded = new AtomicInteger();
        List<ErrorCode> rejectedCodes = new ArrayList<>();

        List<Future<Void>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final String key = "req-po-conc-void-" + i;
            futures.add(pool.submit((Callable<Void>) () -> {
                readyGate.countDown();
                startGate.await(10, TimeUnit.SECONDS);
                try {
                    PurchaseOrder result = com.erp.shared.tenant.TenantContext.callIn(
                            com.erp.shared.tenant.TenantId.DEFAULT,
                            () -> purchaseOrders.voidOrder(o.id(), key));
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
                .allSatisfy(code -> assertThat(code).isEqualTo(ErrorCode.PUR_STATUS_ILLEGAL));

        assertThat(purchaseOrders.require(o.id()).status()).isEqualTo(DocumentStatus.VOIDED);
        assertThat(data.onHand(skuA, warehouseId, "B-CONC"))
                .as("只回退一次：-5 而不是 -10（DB CHECK 也不允许为负）")
                .isZero();
        assertThat(inventory.ledgerEntries(o.orderNo(), "PURCHASE_REVERSAL")).hasSize(1);
        assertThat(inventory.checkLedgerConsistency()).isEmpty();
    }

    // ─────────────────────────── 辅助 ───────────────────────────

    private PurchaseOrder draft(int qtyA, long unitPrice, String requestId) {
        return purchaseOrders.create(new CreateCommand(supplierId, warehouseId, BIZ_DATE,
                "进货红冲测试", List.of(new CreateLineCommand(skuA, qtyA, unitPrice)), requestId));
    }

    /** 建单 → 提交 → 审核 → 整单入库到指定批次，返回 COMPLETED 的进货单。 */
    private PurchaseOrder receivedOrder(int qty, String batchNo, String requestId) {
        PurchaseOrder o = draft(qty, 1000L, requestId);
        purchaseOrders.submit(o.id());
        purchaseOrders.approve(o.id());
        return purchaseOrders.receive(o.id(), new ReceiveCommand(
                List.of(new ReceiveLineCommand(o.lines().get(0).id(), qty, batchNo, null, null)),
                requestId + "-recv"));
    }

    /** 建出货单并出货确认（此时该批次是唯一可用批次，FEFO 必然从它扣减）。 */
    private SalesOrder shippedSalesOrder(int qty, String requestId) {
        SalesOrder so = salesOrders.create(new SalesOrderApplicationService.CreateCommand(
                customerId, warehouseId, BIZ_DATE, "进货红冲下游出货",
                List.of(new SalesOrderApplicationService.CreateLineCommand(skuA, qty, 1000L, null)),
                requestId));
        salesOrders.submit(so.id());
        salesOrders.approve(so.id());
        return salesOrders.ship(so.id(), requestId + "-ship");
    }
}
