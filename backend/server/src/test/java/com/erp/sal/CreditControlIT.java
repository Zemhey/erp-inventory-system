package com.erp.sal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.sal.application.CreditService;
import com.erp.sal.application.ProductBundleService;
import com.erp.sal.application.SalesOrderApplicationService;
import com.erp.sal.domain.SalesOrder;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.support.CaseId;
import com.erp.support.PostgresTestBase;
import com.erp.support.TestClockConfig;
import com.erp.support.TestDataFactory;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.test.context.ActiveProfiles;

/**
 * 客户信用与账期控制（FR-SAL-09）。
 *
 * <p>硬验收 6：信用超额被拒，且不产生任何预占。
 * 蓝图要求：边界值 + 并发。
 */
@SpringBootTest
@ActiveProfiles("clock")
@DisplayName("销售 · 信用与账期")
class CreditControlIT extends PostgresTestBase {

    @Autowired
    private SalesOrderApplicationService salesOrders;
    @Autowired
    private CreditService credit;
    @Autowired
    private ProductBundleService bundles;
    @Autowired
    private TestClockConfig.ControllableClock clock;
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
        clock.set(LocalDateTime.of(2026, 10, 20, 9, 0));
        customerId = data.customer("CUS-CREDIT");
        warehouseId = data.warehouse("WH-CREDIT");
        long productId = data.product("P-CREDIT", "信用测试商品");
        skuA = data.sku(productId, "白色", "L");
        skuB = data.sku(productId, "黑色", "M");
        stock(skuA, 100);
        stock(skuB, 100);
    }

    @Test
    @CaseId("TC-SAL-081")
    @DisplayName("额度 0 = 不设限：大额订单可建，且不写信用快照")
    void zeroLimit_meansUnlimited() {
        setCredit(0, 30);

        SalesOrder order = createOrder(100, "req-credit-unlimited");

        assertThat(order.totalAmount()).isEqualTo(100_000);
        assertThat(credit.view(customerId).creditLimit()).isZero();
        assertThat(credit.view(customerId).availableCredit()).isEqualTo(-1);
        assertThat(count("customer_credit_snapshots")).isZero();
    }

    @Test
    @CaseId("TC-SAL-082")
    @DisplayName("★★ 信用超额拒单：额度 5000，订单 6000 → 拒单且预占/订单/快照全部为 0")
    void overLimit_rejectsWithoutReservation() {
        setCredit(5000, 30);
        int reservedBefore = data.reserved(skuA, warehouseId, "");

        assertThatThrownBy(() -> createOrder(6, "req-credit-over", 1000L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SAL_CREDIT_LIMIT_EXCEEDED);

        assertThat(data.reserved(skuA, warehouseId, "")).as("硬验收 6：预占量不变")
                .isEqualTo(reservedBefore);
        assertThat(count("sales_orders")).isZero();
        assertThat(count("inventory_reservations")).isZero();
        assertThat(count("customer_credit_snapshots")).isZero();
    }

    @Test
    @CaseId("TC-SAL-083")
    @DisplayName("敞口包含未出货订单：额度 10000，先 6000 再过 5000 → 第二单被拒，第一单预占保留")
    void exposure_includesOpenOrders() {
        setCredit(10000, 30);
        SalesOrder first = createOrder(6, "req-credit-open-1");

        assertThatThrownBy(() -> createOrder(5, "req-credit-open-2"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SAL_CREDIT_LIMIT_EXCEEDED);

        assertThat(count("sales_orders")).isEqualTo(1);
        assertThat(data.reserved(skuA, warehouseId, "")).isEqualTo(6);
        assertThat(credit.view(customerId).exposureAmount()).isEqualTo(6000);
        assertThat(first.id()).isPositive();
    }

    @Test
    @CaseId("TC-SAL-084")
    @DisplayName("敞口包含应收余额：6000 出货后仍未回款，再下 5000 单被拒")
    void exposure_includesReceivableBalance() {
        setCredit(10000, 30);
        SalesOrder first = createOrder(6, "req-credit-ar-1");
        salesOrders.submit(first.id());
        salesOrders.approve(first.id());
        salesOrders.ship(first.id(), "req-credit-ar-1-ship");

        assertThatThrownBy(() -> createOrder(5, "req-credit-ar-2"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SAL_CREDIT_LIMIT_EXCEEDED);

        assertThat(credit.view(customerId).exposureAmount()).isEqualTo(6000);
        assertThat(count("sales_orders")).isEqualTo(1);
    }

    @Test
    @CaseId("TC-SAL-085")
    @DisplayName("边界值：敞口 == 额度允许；超出 1 分拒绝")
    void exactLimit_isAllowed() {
        setCredit(10000, 30);
        SalesOrder exact = createOrder(10, "req-credit-exact");
        assertThat(exact.totalAmount()).isEqualTo(10000);
        assertThat(credit.view(customerId).availableCredit()).isZero();

        assertThatThrownBy(() -> createOrder(10, "req-credit-exceed-1", 1001L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SAL_CREDIT_LIMIT_EXCEEDED);
    }

    @Test
    @CaseId("TC-SAL-086")
    @DisplayName("信用快照：日期来自 ClockProvider，记录额度/敞口/本单金额/订单号")
    void snapshot_usesClockProvider() {
        setCredit(10000, 45);

        SalesOrder order = createOrder(6, "req-credit-snapshot");

        var rows = jdbc.queryForList("""
                SELECT snapshot_date, credit_limit, credit_days, exposure_amount,
                       order_amount, sales_order_id
                  FROM customer_credit_snapshots WHERE customer_id = ?
                """, customerId);
        assertThat(rows).hasSize(1);
        var row = rows.get(0);
        assertThat(row.get("snapshot_date").toString()).isEqualTo("2026-10-20");
        assertThat(((Number) row.get("credit_limit")).longValue()).isEqualTo(10000);
        assertThat(((Number) row.get("credit_days")).intValue()).isEqualTo(45);
        assertThat(((Number) row.get("exposure_amount")).longValue()).isEqualTo(6000);
        assertThat(((Number) row.get("order_amount")).longValue()).isEqualTo(6000);
        assertThat(((Number) row.get("sales_order_id")).longValue()).isEqualTo(order.id());
        assertThat(clock.today()).isEqualTo(LocalDate.of(2026, 10, 20));
    }

    @Test
    @CaseId("TC-SAL-087")
    @DisplayName("调整额度/账期后立即生效：1000 拒 2000 单，提到 5000 后允许")
    void updateCredit_takesEffectImmediately() {
        setCredit(1000, 30);
        assertThatThrownBy(() -> createOrder(2, "req-credit-update-before"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SAL_CREDIT_LIMIT_EXCEEDED);

        credit.updateCredit(customerId, 5000, 60);

        SalesOrder order = createOrder(2, "req-credit-update-after");
        assertThat(order.totalAmount()).isEqualTo(2000);
        assertThat(credit.view(customerId).availableCredit()).isEqualTo(3000);
        assertThat(credit.view(customerId).creditDays()).isEqualTo(60);
    }

    @Test
    @CaseId("TC-SAL-088")
    @DisplayName("★ 并发授信：额度 10000，两笔 8000 并发 → 只成功一笔，另一笔超额")
    void concurrentOrders_onlyOneSucceeds() throws Exception {
        setCredit(10000, 30);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger overLimit = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                String requestId = "req-credit-race-" + i;
                futures.add(pool.submit(() -> {
                    try {
                        start.await(5, TimeUnit.SECONDS);
                        com.erp.shared.tenant.TenantContext.runIn(
                                com.erp.shared.tenant.TenantId.DEFAULT,
                                () -> createOrder(8, requestId));
                        success.incrementAndGet();
                    } catch (BusinessException ex) {
                        if (ex.getCode() == ErrorCode.SAL_CREDIT_LIMIT_EXCEEDED) {
                            overLimit.incrementAndGet();
                        } else {
                            throw ex;
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(success.get()).as("只能成功一笔").isEqualTo(1);
        assertThat(overLimit.get()).as("另一笔必须被信用拒绝").isEqualTo(1);
        assertThat(count("sales_orders")).isEqualTo(1);
        assertThat(data.reserved(skuA, warehouseId, "")).isEqualTo(8);
    }

    @Test
    @CaseId("TC-SAL-089")
    @DisplayName("★ 套装+自动选仓超额：拒单且两个仓的组件预占都为 0")
    void bundleAutoSplitOverLimit_leavesNoReservation() {
        setCredit(1000, 30);
        long secondWarehouse = data.warehouse("WH-CREDIT-2");
        data.stock(skuA, secondWarehouse, "", null, 10);
        data.ledger(skuA, secondWarehouse, "", 10, "PURCHASE_IN", "SEED-CREDIT-2A");
        data.stock(skuB, secondWarehouse, "", null, 10);
        data.ledger(skuB, secondWarehouse, "", 10, "PURCHASE_IN", "SEED-CREDIT-2B");
        long bundleId = bundles.create(new ProductBundleService.CreateCommand(
                "BUNDLE-CREDIT", "信用套装", 0L, true,
                List.of(new ProductBundleService.ItemCommand(skuA, 2),
                        new ProductBundleService.ItemCommand(skuB, 3)))).id();

        assertThatThrownBy(() -> salesOrders.createAutoSplit(
                new SalesOrderApplicationService.CreateCommand(
                        customerId, 0L, LocalDate.of(2025, 6, 1), "超额套装",
                        List.of(new SalesOrderApplicationService.CreateLineCommand(
                                0, 1, 0L, null, bundleId)), "req-credit-bundle")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SAL_CREDIT_LIMIT_EXCEEDED);

        assertThat(data.reserved(skuA, warehouseId, "")).isZero();
        assertThat(data.reserved(skuB, warehouseId, "")).isZero();
        assertThat(data.reserved(skuA, secondWarehouse, "")).isZero();
        assertThat(data.reserved(skuB, secondWarehouse, "")).isZero();
        assertThat(count("inventory_reservations")).isZero();
        assertThat(count("sales_orders")).isZero();
    }

    // ─────────────────────────── 辅助 ───────────────────────────

    private void setCredit(long limit, int days) {
        jdbc.update("UPDATE customers SET credit_limit = ?, credit_days = ? WHERE id = ?",
                limit, days, customerId);
    }

    private SalesOrder createOrder(int qty, String requestId) {
        return createOrder(qty, requestId, 1000L);
    }

    private SalesOrder createOrder(int qty, String requestId, long unitPrice) {
        return salesOrders.create(new SalesOrderApplicationService.CreateCommand(
                customerId, warehouseId, LocalDate.of(2025, 6, 1), "信用单",
                List.of(new SalesOrderApplicationService.CreateLineCommand(
                        skuA, qty, unitPrice, null)), requestId));
    }

    private void stock(long skuId, int qty) {
        data.stock(skuId, warehouseId, "", null, qty);
        data.ledger(skuId, warehouseId, "", qty, "PURCHASE_IN", "SEED-" + skuId);
    }

    private int count(String table) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return n == null ? 0 : n;
    }
}
