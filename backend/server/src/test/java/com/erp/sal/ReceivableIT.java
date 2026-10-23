package com.erp.sal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.inv.application.InventoryApplicationService;
import com.erp.rma.application.ReturnOrderApplicationService;
import com.erp.rma.domain.ReturnOrder;
import com.erp.sal.application.ReceiptService;
import com.erp.sal.application.ReceivableService;
import com.erp.sal.application.SalesOrderApplicationService;
import com.erp.sal.domain.CustomerReceipt;
import com.erp.sal.domain.CustomerReceivable;
import com.erp.sal.domain.SalesOrder;
import com.erp.sal.repository.ReceivableRepository;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 应收与收款核销（FR-SAL-07/08）。
 *
 * <p>硬验收 1：应收余额 == 出货 − 退货 − 回款；
 * 硬验收 2：Σ核销明细 == 收款单金额；超额拒绝。
 */
@SpringBootTest
@ActiveProfiles("clock")
@DisplayName("销售 · 应收 / 收款核销")
class ReceivableIT extends PostgresTestBase {

    @Autowired
    private SalesOrderApplicationService salesOrders;
    @Autowired
    private ReceivableService receivableService;
    @Autowired
    private ReceiptService receipts;
    @Autowired
    private ReturnOrderApplicationService returns;
    @Autowired
    private ReceivableRepository receivables;
    @Autowired
    private InventoryApplicationService inventory;
    @Autowired
    private TestClockConfig.ControllableClock clock;
    @Autowired
    private JdbcTemplate jdbc;

    private TestDataFactory data;
    private long customerId;
    private long warehouseId;
    private long skuId;

    @BeforeEach
    void setUp() {
        data = new TestDataFactory(jdbc);
        data.truncateAll();
        clock.set(LocalDateTime.of(2026, 10, 20, 9, 0));
        customerId = data.customer("CUS-AR");
        jdbc.update("UPDATE customers SET credit_days = 30 WHERE id = ?", customerId);
        warehouseId = data.warehouse("WH-AR");
        long productId = data.product("P-AR", "应收测试商品");
        skuId = data.sku(productId, "白色", "L");
    }

    @Test
    @CaseId("TC-SAL-068")
    @DisplayName("★ 出货即生成应收：shipped=10000，balance=10000，到期日=时钟今天+30天")
    void shipmentCreatesReceivable() {
        SalesOrder order = shippedOrder(10, "req-ar-068");

        CustomerReceivable r = receivable(order.id());
        assertThat(r.shippedAmount()).isEqualTo(10000);
        assertThat(r.balance()).isEqualTo(10000);
        assertThat(r.receivedAmount()).isZero();
        assertThat(r.returnedAmount()).isZero();
        assertThat(r.dueDate()).isEqualTo(clock.today().plusDays(30));
        assertInvariant(r.id());
    }

    @Test
    @CaseId("TC-SAL-069")
    @DisplayName("★ 部分收款：收 3000 核销 3000 → balance 7000，核销明细合计 == 收款金额")
    void partialReceipt_allocatesExactly() {
        SalesOrder order = shippedOrder(10, "req-ar-069");
        CustomerReceivable r = receivable(order.id());

        CustomerReceipt receipt = receipts.create(receipt(3000,
                alloc(r.id(), 3000), "req-ar-069-receipt"));

        assertThat(receipt.amount()).isEqualTo(3000);
        assertThat(receipt.allocations()).singleElement()
                .satisfies(a -> assertThat(a.amount()).isEqualTo(3000));
        CustomerReceivable after = receivable(order.id());
        assertThat(after.receivedAmount()).isEqualTo(3000);
        assertThat(after.balance()).isEqualTo(7000);
        assertThat(after.status()).isEqualTo("OPEN");
        assertInvariant(after.id());
    }

    @Test
    @CaseId("TC-SAL-070")
    @DisplayName("一张收款单核销多张应收：10000 + 5000 → 第一张结清、第二张余 5000")
    void receipt_allocatesAcrossReceivables() {
        SalesOrder order1 = shippedOrder(10, "req-ar-070-a");
        SalesOrder order2 = shippedOrder(10, "req-ar-070-b");
        CustomerReceivable r1 = receivable(order1.id());
        CustomerReceivable r2 = receivable(order2.id());

        receipts.create(receipt(15000,
                List.of(alloc(r1.id(), 10000), alloc(r2.id(), 5000)),
                "req-ar-070-receipt"));

        assertThat(receivable(order1.id()).balance()).isZero();
        assertThat(receivable(order1.id()).status()).isEqualTo("SETTLED");
        assertThat(receivable(order2.id()).balance()).isEqualTo(5000);
        assertInvariant(r1.id());
        assertInvariant(r2.id());
    }

    @Test
    @CaseId("TC-SAL-071")
    @DisplayName("★ 核销守恒：Σ分配 3000 ≠ 收款 5000 → SAL_RECEIPT_UNBALANCED，无收款单")
    void unbalancedReceipt_isRejected() {
        CustomerReceivable r = receivable(shippedOrder(10, "req-ar-071").id());

        assertThatThrownBy(() -> receipts.create(receipt(5000,
                alloc(r.id(), 3000), "req-ar-071-receipt")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SAL_RECEIPT_UNBALANCED);

        assertThat(count("customer_receipts")).isZero();
        assertThat(receivable(r.salesOrderId()).balance()).isEqualTo(10000);
    }

    @Test
    @CaseId("TC-SAL-072")
    @DisplayName("★ 超额核销：应收余额 10000，核销 10001 → SAL_RECEIPT_OVER_ALLOCATED，余额不变")
    void overAllocation_isRejected() {
        CustomerReceivable r = receivable(shippedOrder(10, "req-ar-072").id());

        assertThatThrownBy(() -> receipts.create(receipt(10001,
                alloc(r.id(), 10001), "req-ar-072-receipt")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SAL_RECEIPT_OVER_ALLOCATED);

        assertThat(count("customer_receipts")).isZero();
        assertThat(count("receivable_allocations")).isZero();
        assertThat(receivable(r.salesOrderId()).balance()).isEqualTo(10000);
    }

    @Test
    @CaseId("TC-SAL-073")
    @DisplayName("跨客户核销：应收不属于收款客户 → SAL_RECEIVABLE_NOT_FOUND，无收款单")
    void crossCustomerAllocation_isRejected() {
        CustomerReceivable r = receivable(shippedOrder(10, "req-ar-073").id());
        long otherCustomer = data.customer("CUS-AR-OTHER");

        assertThatThrownBy(() -> receipts.create(new ReceiptService.CreateCommand(
                otherCustomer, 1000, clock.today(), "跨客户",
                List.of(alloc(r.id(), 1000)), "req-ar-073-receipt")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SAL_RECEIVABLE_NOT_FOUND);
        assertThat(count("customer_receipts")).isZero();
        assertThat(receivable(r.salesOrderId()).balance()).isEqualTo(10000);
    }

    @Test
    @CaseId("TC-SAL-074")
    @DisplayName("同一应收在一张收款单里出现两次 → SYS_VALIDATION_ERROR")
    void duplicateReceivableInReceipt_isRejected() {
        CustomerReceivable r = receivable(shippedOrder(10, "req-ar-074").id());

        assertThatThrownBy(() -> receipts.create(receipt(2000,
                List.of(alloc(r.id(), 1000), alloc(r.id(), 1000)),
                "req-ar-074-receipt")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SYS_VALIDATION_ERROR);
        assertThat(count("customer_receipts")).isZero();
    }

    @Test
    @CaseId("TC-SAL-075")
    @DisplayName("收款幂等：同键重放返回首次收款单，不重复核销")
    void receipt_isIdempotent() {
        CustomerReceivable r = receivable(shippedOrder(10, "req-ar-075").id());
        CustomerReceipt first = receipts.create(receipt(3000,
                alloc(r.id(), 3000), "req-ar-075-receipt"));

        CustomerReceipt replay = receipts.create(receipt(3000,
                alloc(r.id(), 3000), "req-ar-075-receipt"));

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(count("customer_receipts")).isEqualTo(1);
        assertThat(count("receivable_allocations")).isEqualTo(1);
        assertThat(receivable(r.salesOrderId()).receivedAmount()).isEqualTo(3000);
        assertThat(receivable(r.salesOrderId()).balance()).isEqualTo(7000);
    }

    @Test
    @CaseId("TC-SAL-076")
    @DisplayName("★ 退货联动：退 2 件 → returned_amount 2000、balance 8000，恒等式仍成立")
    void returnReceive_reducesReceivable() {
        SalesOrder order = shippedOrder(10, "req-ar-076");
        CustomerReceivable before = receivable(order.id());
        ReturnOrder returnOrder = returns.create(new ReturnOrderApplicationService.CreateCommand(
                order.id(), clock.today(), "退货",
                List.of(new ReturnOrderApplicationService.CreateLineCommand(
                        order.lines().get(0).id(), 2, null, null, null)),
                "req-ar-076-return"));
        returns.submit(returnOrder.id());
        returns.approve(returnOrder.id());
        returns.receive(returnOrder.id(), "req-ar-076-return-receive");

        CustomerReceivable after = receivable(order.id());
        assertThat(after.returnedAmount()).isEqualTo(2000);
        assertThat(after.balance()).isEqualTo(8000);
        assertThat(after.shippedAmount()).isEqualTo(10000);
        assertThat(before.balance()).isEqualTo(10000);
        assertInvariant(after.id());
    }

    @Test
    @CaseId("TC-SAL-077")
    @DisplayName("全额收款 → SETTLED / balance 0；再核销任何金额 → OVER_ALLOCATED")
    void fullPayment_settlesAndBlocksFurtherAllocation() {
        SalesOrder order = shippedOrder(10, "req-ar-077");
        CustomerReceivable r = receivable(order.id());
        receipts.create(receipt(10000, alloc(r.id(), 10000), "req-ar-077-receipt"));

        CustomerReceivable settled = receivable(order.id());
        assertThat(settled.balance()).isZero();
        assertThat(settled.status()).isEqualTo("SETTLED");
        assertInvariant(settled.id());

        assertThatThrownBy(() -> receipts.create(receipt(1,
                alloc(settled.id(), 1), "req-ar-077-receipt-2")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SAL_RECEIPT_OVER_ALLOCATED);
    }

    @Test
    @CaseId("TC-SAL-078")
    @DisplayName("★ 已收款不可红冲：SAL_STATUS_ILLEGAL，订单/库存/应收均不变")
    void reversalWithReceipt_isRejected() {
        SalesOrder order = shippedOrder(10, "req-ar-078");
        CustomerReceivable r = receivable(order.id());
        receipts.create(receipt(3000, alloc(r.id(), 3000), "req-ar-078-receipt"));
        int onHandBefore = data.onHand(skuId, warehouseId, "");

        assertThatThrownBy(() -> salesOrders.voidOrder(order.id(), "req-ar-078-void"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SAL_STATUS_ILLEGAL);

        assertThat(salesOrders.require(order.id()).status())
                .isEqualTo(com.erp.shared.document.DocumentStatus.COMPLETED);
        assertThat(data.onHand(skuId, warehouseId, "")).isEqualTo(onHandBefore);
        assertThat(receivable(order.id()).receivedAmount()).isEqualTo(3000);
    }

    @Test
    @CaseId("TC-SAL-079")
    @DisplayName("未收款红冲：应收置 VOIDED、余额 0，库存回补，恒等式仍成立")
    void reversalWithoutReceipt_voidsReceivable() {
        SalesOrder order = shippedOrder(10, "req-ar-079");

        salesOrders.voidOrder(order.id(), "req-ar-079-void");

        CustomerReceivable r = receivable(order.id());
        assertThat(r.status()).isEqualTo("VOIDED");
        assertThat(r.balance()).isZero();
        assertThat(r.shippedAmount()).isZero();
        assertThat(data.onHand(skuId, warehouseId, "")).isEqualTo(20);
        assertInvariant(r.id());
    }

    @Test
    @CaseId("TC-SAL-080")
    @DisplayName("★ 账龄：时钟推进 31 天 → 汇总 overdueAmount == balance，逾期筛选命中")
    void overdueSummary_usesClock() {
        SalesOrder order = shippedOrder(10, "req-ar-080");
        CustomerReceivable r = receivable(order.id());
        receipts.create(receipt(3000, alloc(r.id(), 3000), "req-ar-080-receipt"));

        clock.advance(Duration.ofDays(31));
        var summary = receivableService.summary(customerId);
        assertThat(summary.shippedAmount()).isEqualTo(10000);
        assertThat(summary.receivedAmount()).isEqualTo(3000);
        assertThat(summary.balance()).isEqualTo(7000);
        assertThat(summary.overdueAmount()).as("到期日已过，全部余额逾期").isEqualTo(7000);

        var overduePage = receivableService.page(customerId, null, true, 1, 20);
        assertThat(overduePage.total()).isEqualTo(1);
        assertThat(inventory.checkLedgerConsistency()).isEmpty();
    }

    // ─────────────────────────── 辅助 ───────────────────────────

    private SalesOrder shippedOrder(int qty, String requestId) {
        data.stock(skuId, warehouseId, "", null, qty + 10);
        data.ledger(skuId, warehouseId, "", qty + 10, "PURCHASE_IN", "SEED-" + requestId);
        SalesOrder order = salesOrders.create(new SalesOrderApplicationService.CreateCommand(
                customerId, warehouseId, LocalDate.of(2025, 6, 1), "应收单",
                List.of(new SalesOrderApplicationService.CreateLineCommand(skuId, qty, 1000L, null)),
                requestId));
        salesOrders.submit(order.id());
        salesOrders.approve(order.id());
        return salesOrders.ship(order.id(), requestId + "-ship");
    }

    private CustomerReceivable receivable(long salesOrderId) {
        return receivables.findByOrderId(salesOrderId).orElseThrow();
    }

    private ReceiptService.AllocationCommand alloc(long receivableId, long amount) {
        return new ReceiptService.AllocationCommand(receivableId, amount);
    }

    private ReceiptService.CreateCommand receipt(long amount,
                                                 ReceiptService.AllocationCommand allocation,
                                                 String requestId) {
        return receipt(amount, List.of(allocation), requestId);
    }

    private ReceiptService.CreateCommand receipt(long amount,
                                                 List<ReceiptService.AllocationCommand> allocations,
                                                 String requestId) {
        return new ReceiptService.CreateCommand(customerId, amount, clock.today(),
                "收款", allocations, requestId);
    }

    private void assertInvariant(long receivableId) {
        CustomerReceivable r = receivables.findById(receivableId).orElseThrow();
        assertThat(r.balance())
                .as("INV：应收余额 == 出货 − 退货 − 回款")
                .isEqualTo(r.shippedAmount() - r.returnedAmount() - r.receivedAmount());
    }

    private int count(String table) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return n == null ? 0 : n;
    }
}
