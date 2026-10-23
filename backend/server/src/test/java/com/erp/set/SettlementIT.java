package com.erp.set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.erp.inv.application.InventoryApplicationService;
import com.erp.mdm.application.CustomerApplicationService;
import com.erp.mdm.application.ProductApplicationService;
import com.erp.mdm.application.SkuApplicationService;
import com.erp.mdm.application.SupplierApplicationService;
import com.erp.mdm.application.WarehouseApplicationService;
import com.erp.pur.application.PurchaseOrderApplicationService;
import com.erp.pur.domain.PurchaseOrder;
import com.erp.rma.application.ReturnOrderApplicationService;
import com.erp.rma.application.ReturnOrderApplicationService.CreateCommand;
import com.erp.rma.application.ReturnOrderApplicationService.CreateLineCommand;
import com.erp.rma.domain.ReturnOrder;
import com.erp.sal.application.SalesOrderApplicationService;
import com.erp.sal.domain.SalesOrder;
import com.erp.set.application.SettlementApplicationService;
import com.erp.set.application.SettlementApplicationService.DailyItem;
import com.erp.set.application.SettlementApplicationService.DailyResult;
import com.erp.set.application.SettlementApplicationService.MonthlyResult;
import com.erp.set.domain.SettlementPeriodStatus;
import com.erp.set.domain.SettlementTotals;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.support.CaseId;
import com.erp.support.PostgresTestBase;
import com.erp.support.TestClockConfig.ControllableClock;
import com.erp.support.TestDataFactory;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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
 * 结算集成测试（FR-SET-01/02/03，计划书 §4.3 TC-SET-001~014）。
 *
 * <p><b>本类真正在守的三件事</b>：
 * <ol>
 *   <li><b>口径唯一</b>：日清、月结、对账必须数出同一个数（TC-SET-010/011）。
 *       三个接口各写一份聚合 SQL 迟早分叉，而对账一旦不平就再也查不出是谁错了；</li>
 *   <li><b>锁账真的锁住</b>：月结之后 PUR/SAL/RMA 的建单与执行必须全部被拒且
 *       <b>不留任何副作用</b>（TC-SET-004/005/012），同时<b>未锁的账期不能误伤</b>
 *       （TC-SET-008 用 3 月证明锁是按月生效的）；</li>
 *   <li><b>幂等与并发</b>：重复日清/月结只覆盖不累加，并发日清只产生一行
 *       （TC-SET-003/007/013）。</li>
 * </ol>
 *
 * <p>造数据一律走真实的进货 → 出货 → 退货链路，不手改单据表 ——
 * 否则"日清汇总对不对"就退化成了"我写进去多少就读回多少"的同义反复。
 */
@SpringBootTest
@ActiveProfiles("clock")
@DisplayName("结算 · 日清 / 月结锁账 / 对账（FR-SET-01~03）")
class SettlementIT extends PostgresTestBase {

    private static final int SALE_DISCOUNT = 9000;
    private static final long SALE_PRICE = 12900L;

    @Autowired
    private SettlementApplicationService settlements;
    @Autowired
    private PurchaseOrderApplicationService purchaseOrders;
    @Autowired
    private SalesOrderApplicationService salesOrders;
    @Autowired
    private ReturnOrderApplicationService returnOrders;
    @Autowired
    private InventoryApplicationService inventory;
    @Autowired
    private CustomerApplicationService customerService;
    @Autowired
    private SupplierApplicationService supplierService;
    @Autowired
    private WarehouseApplicationService warehouseService;
    @Autowired
    private ProductApplicationService productService;
    @Autowired
    private SkuApplicationService skuService;
    @Autowired
    private ControllableClock clock;
    @Autowired
    private JdbcTemplate jdbc;

    private TestDataFactory data;
    private final AtomicInteger seq = new AtomicInteger();
    private long customerId;
    private long supplierId;
    private long wh1;
    private long wh2;
    private long skuA;
    private long skuB;
    private long skuC;

    @BeforeEach
    void setUp() {
        data = new TestDataFactory(jdbc);
        // ★ truncateAll 已包含 daily_settlements / settlement_periods：
        //   残留一行 CLOSED 会把后面所有进货/出货/退货用例拦成 SET_PERIOD_CLOSED（§6 坑 3）
        data.truncateAll();
        clock.set(LocalDateTime.of(2026, 10, 20, 9, 0, 0));

        customerId = customerService.create(new CustomerApplicationService.CreateCommand(
                "CUS-SET-01", "结算测试客户", null, SALE_DISCOUNT, "VIP", true)).id();
        supplierId = supplierService.create(new SupplierApplicationService.CreateCommand(
                "SUP-SET-01", "结算测试供应商", null, null, null, true)).id();
        wh1 = warehouseService.create(new WarehouseApplicationService.CreateCommand(
                "WH-SET-01", "结算一号仓", null, true)).id();
        wh2 = warehouseService.create(new WarehouseApplicationService.CreateCommand(
                "WH-SET-02", "结算二号仓", null, true)).id();
        long productId = productService.create(new ProductApplicationService.CreateCommand(
                "P-SET-01", "结算测试商品", null, null, true)).id();
        skuA = skuService.create(new SkuApplicationService.CreateCommand(
                productId, "白色", "L", null, SALE_PRICE, true)).id();
        skuB = skuService.create(new SkuApplicationService.CreateCommand(
                productId, "黑色", "M", null, SALE_PRICE, true)).id();
        skuC = skuService.create(new SkuApplicationService.CreateCommand(
                productId, "灰色", "S", null, SALE_PRICE, true)).id();
    }

    // ─────────────────────────── 日清 ───────────────────────────

    @Test
    @CaseId("TC-SET-001")
    @DisplayName("★ 日清（单仓）：采购/销售/退货的数量与金额按单据 biz_date 正确汇总")
    void daily_shouldAggregatePurchaseSalesReturnByBizDate() {
        LocalDate d = LocalDate.of(2026, 10, 5);
        purchaseOn(d, wh1, skuA, 10, 1000L);
        SalesOrder so = shipOn(d, wh1, List.of(saleLine(skuA, 4, SALE_PRICE, SALE_DISCOUNT)));
        receiveReturn(d, so, 2);

        DailyResult result = settlements.settleDaily(d, wh1, "req-set-001");

        assertThat(result.bizDate()).isEqualTo(d);
        assertThat(result.items()).hasSize(1);
        DailyItem item = result.items().get(0);
        assertThat(item.warehouseId()).isEqualTo(wh1);

        SettlementTotals t = item.totals();
        assertThat(t.purchaseQty()).isEqualTo(10);
        assertThat(t.purchaseAmount()).isEqualTo(10_000L);
        assertThat(t.salesQty()).isEqualTo(4);
        assertThat(t.salesAmount()).as("round(4 × 12900 × 9000 / 10000)").isEqualTo(46_440L);
        assertThat(t.returnQty()).isEqualTo(2);
        assertThat(t.returnAmount()).isEqualTo(23_220L);
        assertThat(t.netAmount()).as("净额只减退货，不减采购").isEqualTo(23_220L);
        assertThat(result.totals()).isEqualTo(t);

        assertThat(storedDaily(d, wh1)).as("响应必须等于库里真正落下的日清行").isEqualTo(t);
    }

    @Test
    @CaseId("TC-SET-002")
    @DisplayName("日清省略 warehouseId：按仓库返回 items，再给出跨仓 totals，跨仓不串数")
    void daily_withoutWarehouse_shouldReturnItemsPerWarehouseAndTotals() {
        LocalDate d = LocalDate.of(2026, 10, 6);
        purchaseOn(d, wh1, skuA, 10, 1000L);
        purchaseOn(d, wh2, skuB, 3, 2000L);
        shipOn(d, wh2, List.of(saleLine(skuB, 2, SALE_PRICE, 10000)));

        DailyResult result = settlements.settleDaily(d, null, "req-set-002");

        assertThat(result.items()).extracting(DailyItem::warehouseId)
                .as("两个仓都要出现，哪怕其中一个没有任何销售")
                .containsExactly(wh1, wh2);
        SettlementTotals one = totalsOf(result, wh1);
        SettlementTotals two = totalsOf(result, wh2);
        assertThat(one.purchaseAmount()).isEqualTo(10_000L);
        assertThat(one.salesAmount()).as("二号仓的出货不得串进一号仓").isZero();
        assertThat(two.purchaseAmount()).isEqualTo(6_000L);
        assertThat(two.salesAmount()).isEqualTo(25_800L);
        assertThat(two.salesQty()).isEqualTo(2);

        SettlementTotals totals = result.totals();
        assertThat(totals.purchaseQty()).isEqualTo(13);
        assertThat(totals.purchaseAmount()).isEqualTo(16_000L);
        assertThat(totals.salesQty()).isEqualTo(2);
        assertThat(totals.salesAmount()).isEqualTo(25_800L);
        assertThat(totals.netAmount()).isEqualTo(25_800L);
    }

    @Test
    @CaseId("TC-SET-003")
    @DisplayName("★ 日清幂等：同日重复执行结果一致且仍只有一行；重跑是覆盖而不是累加")
    void daily_repeatExecution_isIdempotentAndOverwrites() {
        LocalDate d = LocalDate.of(2026, 10, 7);
        purchaseOn(d, wh1, skuA, 10, 1000L);
        shipOn(d, wh1, List.of(saleLine(skuA, 4, SALE_PRICE, SALE_DISCOUNT)));

        DailyResult first = settlements.settleDaily(d, wh1, "req-set-003-a");
        DailyResult second = settlements.settleDaily(d, wh1, "req-set-003-b");

        assertThat(second.items()).isEqualTo(first.items());
        assertThat(second.totals()).isEqualTo(first.totals());
        assertThat(dailyRowCount(d)).as("同一天同一仓只能有一行").isEqualTo(1);

        // 再补一张当天到货的进货单：重跑必须把当天的账"改写成新的正确值"，
        // 而不是在旧值上再加一次（幂等键换了也一样）。
        purchaseOn(d, wh1, skuA, 5, 1000L);
        DailyResult third = settlements.settleDaily(d, wh1, "req-set-003-c");
        assertThat(third.totals().purchaseQty()).as("10 + 5，而不是 15 的两倍").isEqualTo(15);
        assertThat(third.totals().purchaseAmount()).as("10000 + 5000，不是 10000 的三倍")
                .isEqualTo(15_000L);
        assertThat(dailyRowCount(d)).isEqualTo(1);
    }

    // ─────────────────────────── 锁账 ───────────────────────────

    @Test
    @CaseId("TC-SET-004")
    @DisplayName("★★ 月结锁账后，PUR / SAL / RMA 建单全部返回 SET_PERIOD_CLOSED 且不落库")
    void monthlyLock_shouldRejectNewDocumentsInClosedPeriod() {
        LocalDate d = LocalDate.of(2026, 10, 15);
        purchaseOn(d, wh1, skuA, 10, 1000L);
        SalesOrder shipped = shipOn(d, wh1, List.of(saleLine(skuA, 4, SALE_PRICE, SALE_DISCOUNT)));
        settlements.settleMonthly("2026-10", "req-set-004-close");

        assertThatThrownBy(() -> purchaseOrders.create(
                new PurchaseOrderApplicationService.CreateCommand(supplierId, wh1,
                        LocalDate.of(2026, 10, 20), "锁账后建单",
                        List.of(new PurchaseOrderApplicationService.CreateLineCommand(skuA, 1, 1000L)),
                        "req-set-004-po")))
                .as("进货建单")
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SET_PERIOD_CLOSED);

        assertThatThrownBy(() -> salesOrders.create(
                new SalesOrderApplicationService.CreateCommand(customerId, wh1,
                        LocalDate.of(2026, 10, 20), "锁账后建单",
                        List.of(saleLine(skuA, 1, SALE_PRICE, SALE_DISCOUNT)),
                        "req-set-004-so")))
                .as("出货建单（会预占库存，所以更要拦）")
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SET_PERIOD_CLOSED);

        assertThatThrownBy(() -> returnOrders.create(new CreateCommand(shipped.id(),
                LocalDate.of(2026, 10, 21), "锁账后建单",
                List.of(new CreateLineCommand(shipped.lines().get(0).id(), 1, null, null, null)),
                "req-set-004-ro")))
                .as("退货建单")
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SET_PERIOD_CLOSED);

        assertThat(count("purchase_orders")).as("被拒的建单不能留下一张草稿").isEqualTo(1);
        assertThat(count("sales_orders")).isEqualTo(1);
        assertThat(count("return_orders")).isZero();

        // 锁账必须只锁住那一个账期：2026-11 没有期间行，照常可建单。
        var openPeriodOrder = purchaseOrders.create(
                new PurchaseOrderApplicationService.CreateCommand(supplierId, wh1,
                        LocalDate.of(2026, 11, 1), "未锁账期照常建单",
                        List.of(new PurchaseOrderApplicationService.CreateLineCommand(skuA, 1, 1000L)),
                        "req-set-004-open"));
        assertThat(openPeriodOrder.status().name()).isEqualTo("DRAFT");
        assertThat(count("purchase_orders")).isEqualTo(2);
    }

    @Test
    @CaseId("TC-SET-005")
    @DisplayName("★★ 锁账后入库 / 出货 / 退货入库全部被拒，库存、累计量与流水一条都不动")
    void monthlyLock_shouldRejectPendingDocumentExecutionWithoutSideEffects() {
        purchaseOn(LocalDate.of(2026, 10, 1), wh1, skuA, 10, 1000L);
        SalesOrder shipped = shipOn(LocalDate.of(2026, 10, 2), wh1,
                List.of(saleLine(skuA, 2, SALE_PRICE, SALE_DISCOUNT)));

        PurchaseOrder toReceive = approvedPurchase(LocalDate.of(2026, 10, 20), wh1, skuA, 3, 1000L);
        SalesOrder toShip = approvedSales(LocalDate.of(2026, 10, 21), wh1,
                List.of(saleLine(skuA, 2, SALE_PRICE, SALE_DISCOUNT)));
        ReturnOrder toReturn = approvedReturn(LocalDate.of(2026, 10, 22), shipped, 1);

        settlements.settleMonthly("2026-10", "req-set-005-close");

        int onHandBefore = data.onHand(skuA, wh1, "");

        assertThatThrownBy(() -> purchaseOrders.receive(toReceive.id(),
                new PurchaseOrderApplicationService.ReceiveCommand(
                        List.of(new PurchaseOrderApplicationService.ReceiveLineCommand(
                                toReceive.lines().get(0).id(), 3, null, null, null)),
                        "req-set-005-recv")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SET_PERIOD_CLOSED);

        assertThatThrownBy(() -> salesOrders.ship(toShip.id(), "req-set-005-ship"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SET_PERIOD_CLOSED);

        assertThatThrownBy(() -> returnOrders.receive(toReturn.id(), "req-set-005-return"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SET_PERIOD_CLOSED);

        assertThat(data.onHand(skuA, wh1, "")).as("三次被拒的执行都不得动库存").isEqualTo(onHandBefore);
        assertThat(int_("SELECT received_qty FROM purchase_order_lines WHERE id = ?",
                toReceive.lines().get(0).id())).as("累计入库量不得变化").isZero();
        assertThat(int_("SELECT shipped_qty FROM sales_order_lines WHERE id = ?",
                toShip.lines().get(0).id())).as("累计出货量不得变化").isZero();
        assertThat(int_("SELECT returned_qty FROM sales_order_lines WHERE id = ?",
                shipped.lines().get(0).id()))
                .as("未入库的退货不得累加原行累计退货量").isZero();
        assertThat(data.ledgerCount(toReceive.orderNo())).isZero();
        assertThat(data.ledgerCount(toShip.orderNo())).isZero();
        assertThat(data.ledgerCount(toReturn.orderNo())).as("被拒的退货入库不留流水").isZero();
        assertThat(inventory.checkLedgerConsistency())
                .as("不变量 INV-2：锁账期间的拒绝必须是干净的，余额仍等于流水净和").isEmpty();
    }

    @Test
    @CaseId("TC-SET-012")
    @DisplayName("已锁账期再执行日清 → SET_PERIOD_CLOSED，且已锁的日清行一个字节都不改")
    void daily_onClosedPeriod_thenRejectAndKeepStoredRow() {
        LocalDate d = LocalDate.of(2026, 10, 14);
        purchaseOn(d, wh1, skuA, 10, 1000L);
        settlements.settleMonthly("2026-10", "req-set-012-close");
        SettlementTotals locked = storedDaily(d, wh1);

        assertThatThrownBy(() -> settlements.settleDaily(d, wh1, "req-set-012-after"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SET_PERIOD_CLOSED);

        assertThat(storedDaily(d, wh1)).as("被拒的日清不得改写已锁账的账目").isEqualTo(locked);
        assertThat(dailyRowCount(d)).isEqualTo(1);
        // 没锁的账期照常能日清
        assertThat(settlements.settleDaily(LocalDate.of(2026, 11, 3), wh1, "req-set-012-open")
                .totals().purchaseAmount()).as("2026-11 未锁账，日清照做").isZero();
    }

    // ─────────────────────────── 月结 ───────────────────────────

    @Test
    @CaseId("TC-SET-006")
    @DisplayName("★ 月结：期间汇总正确、状态置 CLOSED、closedAt 来自注入时钟；OPEN 行走 version 乐观锁更新")
    void monthly_shouldClosePeriodWithInjectedClock() {
        clock.set(LocalDateTime.of(2026, 10, 31, 18, 30, 0));
        LocalDate d1 = LocalDate.of(2026, 10, 5);
        LocalDate d2 = LocalDate.of(2026, 10, 6);
        purchaseOn(d1, wh1, skuA, 10, 1000L);
        SalesOrder so = shipOn(d2, wh1, List.of(saleLine(skuA, 4, SALE_PRICE, SALE_DISCOUNT)));
        receiveReturn(d2, so, 1);

        MonthlyResult result = settlements.settleMonthly("2026-10", "req-set-006");

        assertThat(result.period()).isEqualTo("2026-10");
        assertThat(result.status()).isEqualTo(SettlementPeriodStatus.CLOSED);
        assertThat(result.duplicate()).isFalse();
        SettlementTotals t = result.totals();
        assertThat(t.purchaseQty()).isEqualTo(10);
        assertThat(t.purchaseAmount()).isEqualTo(10_000L);
        assertThat(t.salesQty()).isEqualTo(4);
        assertThat(t.salesAmount()).isEqualTo(46_440L);
        assertThat(t.returnQty()).isEqualTo(1);
        assertThat(t.returnAmount()).as("round(12900 × 90%)").isEqualTo(11_610L);
        assertThat(t.netAmount()).isEqualTo(34_830L);
        assertThat(result.closedAt()).isNotNull();
        assertThat(result.closedAt().toLocalDateTime())
                .as("★ closedAt 必须来自 ClockProvider，而不是系统时间").isEqualTo(clock.now());
        assertThat(storedPeriodStatus("2026-10")).isEqualTo("CLOSED");
        assertThat(storedPeriodTotals("2026-10")).isEqualTo(t);

        // 表默认 status='OPEN'，这种账期行走的是 version 乐观锁更新分支（不是 insert 分支）。
        LocalDate november = LocalDate.of(2026, 11, 3);
        jdbc.update("INSERT INTO settlement_periods (period, status) VALUES ('2026-11', 'OPEN')");
        purchaseOn(november, wh1, skuA, 6, 1000L);
        MonthlyResult fromOpen = settlements.settleMonthly("2026-11", "req-set-006-open");
        assertThat(fromOpen.duplicate()).as("OPEN 行是被更新成 CLOSED 的，不是幂等命中").isFalse();
        assertThat(fromOpen.status()).isEqualTo(SettlementPeriodStatus.CLOSED);
        assertThat(fromOpen.totals().purchaseAmount()).isEqualTo(6_000L);
        assertThat(int_("SELECT version FROM settlement_periods WHERE period = '2026-11'"))
                .as("乐观锁版本随月结推进").isEqualTo(1);
    }

    @Test
    @CaseId("TC-SET-007")
    @DisplayName("★★ 月结幂等与漂移：重复关闭返回同一汇总 duplicate=true；篡改过日清/汇总必须报 SET_ALREADY_SETTLED")
    void monthly_idempotencyAndDataDrift() {
        LocalDate d = LocalDate.of(2026, 10, 9);
        purchaseOn(d, wh1, skuA, 10, 1000L);
        shipOn(d, wh1, List.of(saleLine(skuA, 4, SALE_PRICE, SALE_DISCOUNT)));

        MonthlyResult first = settlements.settleMonthly("2026-10", "req-set-007-a");
        int dailyRowsAfterFirst = count("daily_settlements");
        MonthlyResult again = settlements.settleMonthly("2026-10", "req-set-007-b");

        assertThat(again.duplicate()).as("第二次必须命中幂等").isTrue();
        assertThat(again.totals()).isEqualTo(first.totals());
        assertThat(again.closedAt()).as("幂等命中时返回的是原锁账时刻，不是本次时刻")
                .isEqualTo(first.closedAt());
        assertThat(count("settlement_periods")).isEqualTo(1);
        assertThat(count("daily_settlements"))
                .as("幂等重跑不得追加日清行（有账务的 (日期, 仓库) 只有一行）")
                .isEqualTo(dailyRowsAfterFirst).isEqualTo(1);

        // ① 篡改日清行：已清账与重算不一致 —— 这是"锁账没锁住"的严重异常，必须拒绝而不是覆盖
        jdbc.update("""
                UPDATE daily_settlements SET sales_amount = sales_amount + 100,
                                              net_amount = net_amount + 100
                 WHERE biz_date = ? AND warehouse_id = ?
                """, d, wh1);
        BusinessException drift = catchThrowableOfType(
                () -> settlements.settleMonthly("2026-10", "req-set-007-c"), BusinessException.class);
        assertThat(drift.getCode()).isEqualTo(ErrorCode.SET_ALREADY_SETTLED);
        assertThat(detailsOf(drift))
                .as("details 必须指出是哪一天哪个仓漂移了")
                .containsEntry("bizDate", d.toString())
                .containsEntry("warehouseId", wh1);
        assertThat(storedDaily(d, wh1).salesAmount())
                .as("★ 报错后不得覆盖被篡改的行（覆盖就等于把数据问题埋掉）")
                .isEqualTo(46_540L);

        jdbc.update("""
                UPDATE daily_settlements SET sales_amount = sales_amount - 100,
                                              net_amount = net_amount - 100
                 WHERE biz_date = ? AND warehouse_id = ?
                """, d, wh1);
        assertThat(settlements.settleMonthly("2026-10", "req-set-007-d").duplicate())
                .as("复原之后幂等仍然成立").isTrue();

        // ② 篡改期间汇总：日清行没问题，但总账与单据对不上
        jdbc.update("""
                UPDATE settlement_periods SET sales_amount = sales_amount + 100,
                                              net_amount = net_amount + 100
                 WHERE period = '2026-10'
                """);
        assertThatThrownBy(() -> settlements.settleMonthly("2026-10", "req-set-007-e"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SET_ALREADY_SETTLED);
        assertThat(count("settlement_periods")).as("两次报错都不能新增账期行").isEqualTo(1);
    }

    @Test
    @CaseId("TC-SET-008")
    @DisplayName("★ 跨月边界：1/31 与 2/1 的单据归属不同账期，锁 1 月不影响 2 月")
    void monthly_shouldSplitAtMonthBoundary() {
        LocalDate jan31 = LocalDate.of(2026, 1, 31);
        LocalDate feb1 = LocalDate.of(2026, 2, 1);
        purchaseOn(jan31, wh1, skuA, 10, 1000L);
        purchaseOn(feb1, wh1, skuB, 3, 2000L);

        assertThat(settlements.settleDaily(jan31, wh1, "req-set-008-a").totals().purchaseAmount())
                .as("1/31 那天只有 1 月的单").isEqualTo(10_000L);
        assertThat(settlements.settleDaily(feb1, wh1, "req-set-008-b").totals().purchaseAmount())
                .as("2/1 那天只有 2 月的单").isEqualTo(6_000L);

        MonthlyResult jan = settlements.settleMonthly("2026-01", "req-set-008-c");
        MonthlyResult feb = settlements.settleMonthly("2026-02", "req-set-008-d");
        assertThat(jan.totals().purchaseAmount()).isEqualTo(10_000L);
        assertThat(jan.totals().purchaseQty()).isEqualTo(10);
        assertThat(feb.totals().purchaseAmount()).isEqualTo(6_000L);
        assertThat(feb.totals().purchaseQty()).isEqualTo(3);
        assertThat(jan.totals().salesQty() + feb.totals().salesQty()).isZero();
        assertThat(storedPeriodStatus("2026-01")).isEqualTo("CLOSED");
        assertThat(storedPeriodStatus("2026-02")).isEqualTo("CLOSED");
        assertThat(int_("SELECT COUNT(*) FROM settlement_periods")).isEqualTo(2);

        // 相邻账期不得被误伤：3 月没有期间行，建单照常
        var march = purchaseOrders.create(new PurchaseOrderApplicationService.CreateCommand(
                supplierId, wh1, LocalDate.of(2026, 3, 1), "3 月未锁账",
                List.of(new PurchaseOrderApplicationService.CreateLineCommand(skuA, 1, 1000L)),
                "req-set-008-march"));
        assertThat(march.bizDate()).isEqualTo(LocalDate.of(2026, 3, 1));
    }

    @Test
    @CaseId("TC-SET-009")
    @DisplayName("★ 跨年与闰月：2024-02-29 / 2024-12-31 / 2025-01-01 各自归入正确账期")
    void monthly_shouldHandleLeapDayAndYearBoundary() {
        LocalDate leapDay = LocalDate.of(2024, 2, 29);
        LocalDate yearEnd = LocalDate.of(2024, 12, 31);
        LocalDate newYearDay = LocalDate.of(2025, 1, 1);
        purchaseOn(leapDay, wh1, skuA, 5, 1000L);
        purchaseOn(yearEnd, wh1, skuA, 7, 1000L);
        purchaseOn(newYearDay, wh1, skuA, 11, 1000L);

        assertThat(settlements.settleDaily(leapDay, wh1, "req-set-009-a").totals().purchaseAmount())
                .isEqualTo(5_000L);
        assertThat(settlements.settleDaily(yearEnd, wh1, "req-set-009-b").totals().purchaseAmount())
                .isEqualTo(7_000L);
        assertThat(settlements.settleDaily(newYearDay, wh1, "req-set-009-c").totals().purchaseAmount())
                .isEqualTo(11_000L);

        MonthlyResult feb2024 = settlements.settleMonthly("2024-02", "req-set-009-d");
        MonthlyResult dec2024 = settlements.settleMonthly("2024-12", "req-set-009-e");
        MonthlyResult jan2025 = settlements.settleMonthly("2025-01", "req-set-009-f");

        assertThat(feb2024.totals().purchaseAmount())
                .as("闰日 2/29 必须落在 2024-02 这个账期里").isEqualTo(5_000L);
        assertThat(feb2024.totals().purchaseQty()).isEqualTo(5);
        assertThat(dec2024.totals().purchaseAmount()).isEqualTo(7_000L);
        assertThat(jan2025.totals().purchaseAmount())
                .as("2024-12-31 与 2025-01-01 必须分家，跨年不能串账期").isEqualTo(11_000L);
        assertThat(storedDaily(leapDay, wh1).purchaseAmount())
                .as("月结补齐的闰日行确实在 2 月内").isEqualTo(5_000L);
        assertThat(count("daily_settlements"))
                .as("闰月只有 29 天，加上 12/31 与 1/1 共 3 行；账期补齐不得凭空造出 2/30")
                .isEqualTo(3);
    }

    // ─────────────────────────── 对账 ───────────────────────────

    @Test
    @CaseId("TC-SET-010")
    @DisplayName("★ 对账平衡：差额 0、balanced=true；账期不存在或仍为 OPEN 时给 SET_PERIOD_NOT_CLOSED")
    void reconcile_shouldBalanceAfterMonthly() {
        assertThatThrownBy(() -> settlements.reconcile("2026-09"))
                .as("从未结过账的期间不能报成「账是平的」")
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SET_PERIOD_NOT_CLOSED);
        jdbc.update("INSERT INTO settlement_periods (period, status) VALUES ('2026-08', 'OPEN')");
        assertThatThrownBy(() -> settlements.reconcile("2026-08"))
                .as("期间行存在但还是 OPEN，同样不能对账")
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SET_PERIOD_NOT_CLOSED);

        LocalDate d = LocalDate.of(2026, 10, 12);
        purchaseOn(d, wh1, skuA, 10, 1000L);
        SalesOrder so = shipOn(d, wh1, List.of(saleLine(skuA, 4, SALE_PRICE, SALE_DISCOUNT)));
        receiveReturn(d, so, 1);
        MonthlyResult monthly = settlements.settleMonthly("2026-10", "req-set-010");

        var result = settlements.reconcile("2026-10");
        assertThat(result.period()).isEqualTo("2026-10");
        assertThat(result.generalTotal()).as("总账就是月结写下的净额")
                .isEqualTo(monthly.totals().netAmount()).isEqualTo(34_830L);
        assertThat(result.detailTotal()).as("明细按单据实时重算").isEqualTo(34_830L);
        assertThat(result.diff()).isZero();
        assertThat(result.balanced()).isTrue();
    }

    @Test
    @CaseId("TC-SET-011")
    @DisplayName("对账不平：篡改期间汇总 → SET_RECONCILE_MISMATCH，details 带 period/generalTotal/detailTotal/diff")
    void reconcile_mismatchCarriesDetails() {
        LocalDate d = LocalDate.of(2026, 10, 13);
        purchaseOn(d, wh1, skuA, 10, 1000L);
        shipOn(d, wh1, List.of(saleLine(skuA, 4, SALE_PRICE, SALE_DISCOUNT)));
        settlements.settleMonthly("2026-10", "req-set-011");
        assertThat(settlements.reconcile("2026-10").balanced()).isTrue();

        // 总账被人动过 100 分（CHECK 约束要求 net 与 sales/return 自洽，所以两列一起改）
        jdbc.update("""
                UPDATE settlement_periods SET sales_amount = sales_amount + 100,
                                              net_amount = net_amount + 100
                 WHERE period = '2026-10'
                """);

        BusinessException ex = catchThrowableOfType(
                () -> settlements.reconcile("2026-10"), BusinessException.class);
        assertThat(ex.getCode()).isEqualTo(ErrorCode.SET_RECONCILE_MISMATCH);
        assertThat(ex.getMessage()).contains("对账不平").contains("差额 100 分");
        assertThat(detailsOf(ex))
                .containsEntry("period", "2026-10")
                .containsEntry("generalTotal", 46_540L)
                .containsEntry("detailTotal", 46_440L)
                .containsEntry("diff", 100L);
    }

    // ─────────────────────────── 并发与舍入 ───────────────────────────

    @Test
    @CaseId("TC-SET-013")
    @DisplayName("★★ 并发日清：8 个线程同时清同一天，只产生一行/仓，金额一份不翻倍")
    void daily_concurrentExecution_producesSingleRowPerWarehouse() throws Exception {
        LocalDate d = LocalDate.of(2026, 10, 16);
        purchaseOn(d, wh1, skuA, 10, 1000L);
        shipOn(d, wh1, List.of(saleLine(skuA, 4, SALE_PRICE, SALE_DISCOUNT)));
        DailyResult baseline = settlements.settleDaily(d, null, "req-set-013-base");
        int threads = 8;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<DailyResult>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String requestId = "req-set-013-concurrent-" + i;
                futures.add(pool.submit(() -> {
                    start.await();
                    return settlements.settleDaily(d, null, requestId);
                }));
            }
            start.countDown();
            for (Future<DailyResult> future : futures) {
                assertThat(future.get(60, TimeUnit.SECONDS).totals())
                        .as("并发执行的结果必须完全一致").isEqualTo(baseline.totals());
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(dailyRowCount(d))
                .as("★ 靠唯一键 + upsert 收敛，8 个线程也只能留下每仓一行").isEqualTo(2);
        assertThat(storedDaily(d, wh1)).isEqualTo(totalsOf(baseline, wh1));
        assertThat(storedDaily(d, wh1).salesAmount())
                .as("并发把金额加成两倍是这类缺陷最贵的形态").isEqualTo(46_440L);
    }

    @Test
    @CaseId("TC-SET-014")
    @DisplayName("★ 金额舍入：奇数单价 × 折扣 9000 与 SAL 同一公式（HALF_UP），尾差不为负")
    void daily_shouldUseSameRoundingFormulaAsSales() {
        LocalDate d = LocalDate.of(2026, 10, 18);
        purchaseOn(d, wh1, skuA, 20, 1000L);
        purchaseOn(d, wh1, skuB, 20, 1000L);
        purchaseOn(d, wh1, skuC, 20, 1000L);
        SalesOrder so = shipOn(d, wh1, List.of(
                saleLine(skuA, 1, 15L, SALE_DISCOUNT),
                saleLine(skuB, 1, 25L, SALE_DISCOUNT),
                saleLine(skuC, 3, 333L, SALE_DISCOUNT)));

        assertThat(so.totalAmount())
                .as("SAL 侧：round(13.5)=14、round(22.5)=23、round(899.1)=899")
                .isEqualTo(936L);

        DailyResult result = settlements.settleDaily(d, wh1, "req-set-014");
        SettlementTotals before = result.totals();
        assertThat(before.salesAmount())
                .as("★ 日清必须复用 SAL 的 lineAmount，而不是自己再抄一套舍入").isEqualTo(936L);
        assertThat(before.salesAmount())
                .as("整数截断会得到 13 + 22 + 899 = 934；出现 934 说明有人把 HALF_UP 换成了整除")
                .isNotEqualTo(934L);
        assertThat(before.salesQty()).isEqualTo(5);
        assertThat(before.netAmount()).as("未退货时净额等于销售金额").isEqualTo(936L);

        ReturnOrder ro = receiveReturn(d, so, 1);
        assertThat(ro.lines().get(0).amount())
                .as("退货沿用原行折扣，同一公式").isEqualTo(14L);
        SettlementTotals after = settlements.settleDaily(d, wh1, "req-set-014-return").totals();
        assertThat(after.returnQty()).isEqualTo(1);
        assertThat(after.returnAmount()).isEqualTo(14L);
        assertThat(after.netAmount()).isEqualTo(922L);

        assertThat(after.purchaseAmount()).isNotNegative();
        assertThat(after.salesAmount()).isNotNegative();
        assertThat(after.returnAmount()).isNotNegative();
        assertThat(after.netAmount()).as("尾差不为负：单行舍入不会把净额压成负数").isNotNegative();
        assertThat(result.items()).hasSize(1);
    }

    // ─────────────────────────── 造数与断言辅助 ───────────────────────────

    private SalesOrderApplicationService.CreateLineCommand saleLine(long skuId, int qty,
                                                                   long unitPriceAmount,
                                                                   int discountRate) {
        return new SalesOrderApplicationService.CreateLineCommand(
                skuId, qty, unitPriceAmount, discountRate);
    }

    /** 进货并整单入库（走完建单→提交→审核→入库，保证有真实流水可对）。 */
    private void purchaseOn(LocalDate date, long warehouseId, long skuId, int qty, long unitPrice) {
        PurchaseOrder po = approvedPurchase(date, warehouseId, skuId, qty, unitPrice);
        purchaseOrders.receive(po.id(), new PurchaseOrderApplicationService.ReceiveCommand(
                List.of(new PurchaseOrderApplicationService.ReceiveLineCommand(
                        po.lines().get(0).id(), qty, null, null, null)),
                "req-set-recv-" + tag()));
    }

    /** 只推进到 APPROVED、<b>不入库</b>的进货单（给"锁账后执行被拒"用）。 */
    private PurchaseOrder approvedPurchase(LocalDate date, long warehouseId, long skuId, int qty,
                                          long unitPrice) {
        String t = tag();
        PurchaseOrder po = purchaseOrders.create(
                new PurchaseOrderApplicationService.CreateCommand(supplierId, warehouseId, date,
                        "结算备货-" + t,
                        List.of(new PurchaseOrderApplicationService.CreateLineCommand(
                                skuId, qty, unitPrice)),
                        "req-po-" + t));
        purchaseOrders.submit(po.id());
        return purchaseOrders.approve(po.id());
    }

    private SalesOrder shipOn(LocalDate date, long warehouseId,
                              List<SalesOrderApplicationService.CreateLineCommand> lines) {
        return ship(approvedSales(date, warehouseId, lines));
    }

    /** 只推进到 APPROVED、<b>不出货</b>的出货单（已预占，给"锁账后执行被拒"用）。 */
    private SalesOrder approvedSales(LocalDate date, long warehouseId,
                                    List<SalesOrderApplicationService.CreateLineCommand> lines) {
        SalesOrder so = salesOrders.create(new SalesOrderApplicationService.CreateCommand(
                customerId, warehouseId, date, "结算出货-" + tag(), lines, "req-so-" + tag()));
        salesOrders.submit(so.id());
        return salesOrders.approve(so.id());
    }

    private SalesOrder ship(SalesOrder so) {
        salesOrders.ship(so.id(), "req-ship-" + tag());
        return salesOrders.require(so.id());
    }

    /** 退货并整单入库完成（RETURN_IN 流水已落）。 */
    private ReturnOrder receiveReturn(LocalDate date, SalesOrder origin, int qty) {
        ReturnOrder ro = approvedReturn(date, origin, qty);
        return returnOrders.receive(ro.id(), "req-ro-recv-" + tag());
    }

    /** 只推进到 APPROVED、<b>不入库</b>的退货单。 */
    private ReturnOrder approvedReturn(LocalDate date, SalesOrder origin, int qty) {
        String t = tag();
        ReturnOrder ro = returnOrders.create(new CreateCommand(origin.id(), date, "结算退货-" + t,
                List.of(new CreateLineCommand(origin.lines().get(0).id(), qty, null, null, null)),
                "req-ro-" + t));
        returnOrders.submit(ro.id());
        return returnOrders.approve(ro.id());
    }

    private String tag() {
        return String.valueOf(seq.incrementAndGet());
    }

    /**
     * 读 {@code BusinessException.details}。
     *
     * <p>包一层是为了避开 {@code Map<?, ?>} 的通配捕获：直接断言会让
     * {@code containsEntry} 的键值类型推不出来，测试代码为了编译去做行内强转
     * 反而更难读。
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> detailsOf(BusinessException ex) {
        return (Map<String, Object>) ex.getDetails();
    }

    private static SettlementTotals totalsOf(DailyResult result, long warehouseId) {
        return result.items().stream()
                .filter(item -> item.warehouseId() == warehouseId)
                .map(DailyItem::totals)
                .findFirst()
                .orElseThrow(() -> new AssertionError("日清结果里没有仓库 " + warehouseId));
    }

    private SettlementTotals storedDaily(LocalDate date, long warehouseId) {
        return jdbc.queryForObject("""
                SELECT purchase_qty, purchase_amount, sales_qty, sales_amount,
                       return_qty, return_amount
                  FROM daily_settlements WHERE biz_date = ? AND warehouse_id = ?
                """, (rs, i) -> new SettlementTotals(rs.getLong(1), rs.getLong(2), rs.getLong(3),
                        rs.getLong(4), rs.getLong(5), rs.getLong(6)),
                date, warehouseId);
    }

    private SettlementTotals storedPeriodTotals(String period) {
        return jdbc.queryForObject("""
                SELECT purchase_qty, purchase_amount, sales_qty, sales_amount,
                       return_qty, return_amount
                  FROM settlement_periods WHERE period = ?
                """, (rs, i) -> new SettlementTotals(rs.getLong(1), rs.getLong(2), rs.getLong(3),
                        rs.getLong(4), rs.getLong(5), rs.getLong(6)),
                period);
    }

    private String storedPeriodStatus(String period) {
        return jdbc.queryForObject(
                "SELECT status FROM settlement_periods WHERE period = ?", String.class, period);
    }

    private int dailyRowCount(LocalDate date) {
        return int_("SELECT COUNT(*) FROM daily_settlements WHERE biz_date = ?", date);
    }

    private int count(String table) {
        return int_("SELECT COUNT(*) FROM " + table);
    }

    private int int_(String sql, Object... args) {
        Integer v = jdbc.queryForObject(sql, Integer.class, args);
        return v == null ? 0 : v;
    }
}
