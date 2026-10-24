package com.erp.cst;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.cst.application.CostApplicationService;
import com.erp.cst.domain.CostDirection;
import com.erp.cst.domain.CostLayer;
import com.erp.cst.repository.CostLayerRepository;
import com.erp.pur.application.PurchaseOrderApplicationService;
import com.erp.pur.domain.PurchaseOrder;
import com.erp.rma.application.ReturnOrderApplicationService;
import com.erp.rma.domain.ReturnOrder;
import com.erp.sal.application.SalesOrderApplicationService;
import com.erp.sal.domain.SalesOrder;
import com.erp.support.CaseId;
import com.erp.support.PostgresTestBase;
import com.erp.support.TestDataFactory;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 成本引擎集成测试：真实 PostgreSQL + 真实进货/出货/退货链路。
 *
 * <p>纯算法由 {@code MovingAverageCostCalculatorTest} 覆盖；这里验证的是
 * 流水落库顺序、红冲反向流水、退货配对、并发重算与数据库约束。
 */
@SpringBootTest
@DisplayName("成本 · 引擎集成")
class CostEngineIT extends PostgresTestBase {

    private static final LocalDate BIZ_DATE = LocalDate.of(2026, 10, 8);
    private static final OffsetDateTime T0 =
            OffsetDateTime.of(2026, 10, 8, 9, 0, 0, 0, ZoneOffset.ofHours(8));

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private CostApplicationService costs;
    @Autowired
    private CostLayerRepository layers;
    @Autowired
    private PurchaseOrderApplicationService purchases;
    @Autowired
    private SalesOrderApplicationService sales;
    @Autowired
    private ReturnOrderApplicationService returns;
    @Autowired
    private PlatformTransactionManager txManager;

    private TestDataFactory data;
    private long supplierId;
    private long warehouseId;
    private long customerId;
    private long skuId;

    @BeforeEach
    void setUp() {
        data = new TestDataFactory(jdbc);
        data.truncateAll();
        supplierId = data.supplier("SUP-CST-ENGINE");
        warehouseId = data.warehouse("WH-CST-ENGINE");
        customerId = data.customer("CUS-CST-ENGINE");
        long productId = data.product("P-CST-ENGINE", "成本引擎商品");
        skuId = data.sku(productId, "黑色", "M");
    }

    @Test
    @CaseId("TC-CST-028")
    @DisplayName("从流水重算：成本层落表、汇总满足 INV-11")
    void recalculatePersistsLayers() {
        ledger(10, "PURCHASE_IN", "PO-1", 100L, T0.plusSeconds(1));
        ledger(10, "PURCHASE_IN", "PO-2", 200L, T0.plusSeconds(2));
        ledger(-5, "SALES_OUT", "SO-1", null, T0.plusSeconds(3));

        CostApplicationService.RecalculateResult result = costs.recalculate();

        assertThat(result.layerCount()).isEqualTo(3);
        assertThat(result.inboundCostAmount()).isEqualTo(3000);
        assertThat(result.outboundCostAmount()).isEqualTo(750);
        assertThat(result.endingCostAmount()).isEqualTo(2250);
        assertThat(result.balanced()).isTrue();
        assertThat(layers.findAll()).hasSize(3);
    }

    @Test
    @CaseId("TC-CST-029")
    @DisplayName("真实进货 + 出货链路：出库按移动均价结转，流水成本不被回写")
    void realPurchaseAndShipFlow() {
        receivePurchase(10, 1000, "req-029-po");
        shipSale(4, 2000, "req-029-so");

        CostApplicationService.RecalculateResult result = costs.recalculate();

        assertThat(result.inboundCostAmount()).isEqualTo(10_000);
        assertThat(result.outboundCostAmount()).isEqualTo(4_000);
        assertThat(result.endingCostAmount()).isEqualTo(6_000);
        assertThat(result.balanced()).isTrue();

        List<Long> outboundCosts = jdbc.queryForList("""
                SELECT unit_cost_amount FROM inventory_ledger
                 WHERE biz_type IN ('SALES_OUT','SALES_REVERSAL')
                """, Long.class);
        assertThat(outboundCosts).allMatch(Objects::isNull);
    }

    @Test
    @CaseId("TC-CST-030")
    @DisplayName("两笔进货后出货：单位成本 = 加权均价 150")
    void movingAverageOutbound() {
        ledger(10, "PURCHASE_IN", "PO-1", 100L, T0.plusSeconds(1));
        ledger(10, "PURCHASE_IN", "PO-2", 200L, T0.plusSeconds(2));
        ledger(-5, "SALES_OUT", "SO-1", null, T0.plusSeconds(3));

        costs.recalculate();

        CostLayer outbound = layers.findAll().stream()
                .filter(l -> l.bizType().equals("SALES_OUT"))
                .findFirst().orElseThrow();
        assertThat(outbound.unitCostAmount()).isEqualTo(150);
        assertThat(outbound.totalCostAmount()).isEqualTo(750);
        assertThat(outbound.poolQtyAfter()).isEqualTo(15);
        assertThat(outbound.poolCostAfter()).isEqualTo(2250);
    }

    @Test
    @CaseId("TC-CST-031")
    @DisplayName("真实进货红冲：PURCHASE_REVERSAL 按原进价结转，期末归零")
    void realPurchaseReversal() {
        PurchaseOrder po = receivePurchase(10, 1000, "req-031-po");
        purchases.voidOrder(po.id(), "req-031-void");

        CostApplicationService.RecalculateResult result = costs.recalculate();

        CostLayer reversal = layers.findAll().stream()
                .filter(l -> l.bizType().equals("PURCHASE_REVERSAL"))
                .findFirst().orElseThrow();
        assertThat(reversal.direction()).isEqualTo(CostDirection.OUT);
        assertThat(reversal.totalCostAmount()).isEqualTo(10_000);
        assertThat(result.endingCostAmount()).isZero();
        assertThat(result.balanced()).isTrue();
    }

    @Test
    @CaseId("TC-CST-032")
    @DisplayName("真实出货红冲：SALES_REVERSAL 配对原出库成本，净额为 0")
    void realSalesReversal() {
        receivePurchase(10, 1000, "req-032-po");
        SalesOrder so = shipSale(4, 2000, "req-032-so");
        sales.voidOrder(so.id(), "req-032-void");

        costs.recalculate();

        CostLayer out = findLayer("SALES_OUT");
        CostLayer reversal = findLayer("SALES_REVERSAL");
        assertThat(out.totalCostAmount()).isEqualTo(4_000);
        assertThat(reversal.totalCostAmount()).isEqualTo(4_000);
        assertThat(reversal.unitCostAmount()).isEqualTo(1000);
        assertThat(layers.endingCost()).isEqualTo(10_000);
    }

    @Test
    @CaseId("TC-CST-033")
    @DisplayName("真实退货：RETURN_IN 按原出货成本回补")
    void realReturnInPairsOriginalCost() {
        receivePurchase(10, 1000, "req-033-po");
        SalesOrder so = shipSale(4, 2000, "req-033-so");
        returnGoods(so, 2, "req-033-ro");

        costs.recalculate();

        CostLayer returned = findLayer("RETURN_IN");
        assertThat(returned.unitCostAmount()).isEqualTo(1000);
        assertThat(returned.totalCostAmount()).isEqualTo(2_000);
        List<CostLayer> poolLayers = layers.findByPool(skuId, warehouseId);
        assertThat(poolLayers.get(poolLayers.size() - 1).poolQtyAfter()).isEqualTo(8);
        assertThat(layers.endingCost()).isEqualTo(8_000);
    }

    @Test
    @CaseId("TC-CST-034")
    @DisplayName("单池重算与全量重算结果完全一致")
    void poolRebuildMatchesFullRecalculate() {
        ledger(10, "PURCHASE_IN", "PO-1", 100L, T0.plusSeconds(1));
        ledger(10, "PURCHASE_IN", "PO-2", 200L, T0.plusSeconds(2));
        ledger(-5, "SALES_OUT", "SO-1", null, T0.plusSeconds(3));

        costs.rebuildPool(skuId, warehouseId);
        List<CostLayer> poolLayers = layers.findByPool(skuId, warehouseId);

        costs.recalculate();
        List<CostLayer> fullLayers = layers.findByPool(skuId, warehouseId);

        assertThat(poolLayers).isEqualTo(fullLayers);
    }

    @Test
    @CaseId("TC-CST-035")
    @DisplayName("★ 并发入库：advisory lock 串行化重算，均价不后写赢")
    void concurrentInboundKeepsBothCosts() throws Exception {
        CountDownLatch inserted = new CountDownLatch(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> insertAndRebuild(
                    1000L, T0.plusSeconds(1), "CONC-1", inserted));
            Future<?> second = pool.submit(() -> insertAndRebuild(
                    2000L, T0.plusSeconds(2), "CONC-2", inserted));
            first.get(60, TimeUnit.SECONDS);
            second.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        List<CostLayer> poolLayers = layers.findByPool(skuId, warehouseId);
        assertThat(poolLayers).hasSize(2);
        CostLayer latest = poolLayers.get(poolLayers.size() - 1);
        assertThat(latest.poolQtyAfter()).isEqualTo(20);
        assertThat(latest.poolCostAfter()).isEqualTo(30_000);

        ledger(-1, "SALES_OUT", "SO-CONC", null, T0.plusSeconds(3));
        costs.recalculate();
        CostLayer out = findLayer("SALES_OUT");
        assertThat(out.unitCostAmount()).isEqualTo(1500);
    }

    @Test
    @CaseId("TC-CST-036")
    @DisplayName("重算幂等：同一批流水连跑两次，成本层完全一致")
    void recalculateIsIdempotent() {
        ledger(10, "PURCHASE_IN", "PO-1", 100L, T0.plusSeconds(1));
        ledger(-4, "SALES_OUT", "SO-1", null, T0.plusSeconds(2));

        costs.recalculate();
        List<CostLayer> first = layers.findAll();
        costs.recalculate();
        List<CostLayer> second = layers.findAll();

        assertThat(second).isEqualTo(first);
    }

    @Test
    @CaseId("TC-CST-037")
    @DisplayName("成本层独立持久化：不回写 inventory_ledger.unit_cost_amount")
    void ledgerRemainsImmutable() {
        receivePurchase(10, 1000, "req-037-po");
        shipSale(4, 2000, "req-037-so");
        costs.recalculate();

        List<Long> purchaseCosts = jdbc.queryForList("""
                SELECT unit_cost_amount FROM inventory_ledger WHERE biz_type = 'PURCHASE_IN'
                """, Long.class);
        List<Long> outboundCosts = jdbc.queryForList("""
                SELECT unit_cost_amount FROM inventory_ledger
                 WHERE biz_type IN ('SALES_OUT','SALES_REVERSAL')
                """, Long.class);
        assertThat(purchaseCosts).containsExactly(1000L);
        assertThat(outboundCosts).allMatch(Objects::isNull);
    }

    @Test
    @CaseId("TC-CST-038")
    @DisplayName("多仓成本池独立：互不污染均价与期末成本")
    void poolsAreIndependentAcrossWarehouses() {
        long secondWarehouse = data.warehouse("WH-CST-ENGINE-2");
        CostTestSupport.ledger(jdbc, skuId, warehouseId, "", 10, "PURCHASE_IN", "PO-A",
                100L, T0.plusSeconds(1));
        CostTestSupport.ledger(jdbc, skuId, secondWarehouse, "", 10, "PURCHASE_IN", "PO-B",
                200L, T0.plusSeconds(2));
        CostTestSupport.ledger(jdbc, skuId, warehouseId, "", -5, "SALES_OUT", "SO-A",
                null, T0.plusSeconds(3));

        costs.recalculate();

        assertThat(layers.findByPool(skuId, warehouseId)).last()
                .extracting(CostLayer::poolCostAfter).isEqualTo(500L);
        assertThat(layers.findByPool(skuId, secondWarehouse)).last()
                .extracting(CostLayer::poolCostAfter).isEqualTo(2000L);
        assertThat(layers.endingCost()).isEqualTo(2500);
    }

    // ─────────────────────────── 辅助 ───────────────────────────

    private void insertAndRebuild(long unitCost, OffsetDateTime at, String ref,
                                  CountDownLatch inserted) {
        new TransactionTemplate(txManager).execute(status -> {
            CostTestSupport.ledger(jdbc, skuId, warehouseId, "", 10, "PURCHASE_IN", ref,
                    unitCost, at);
            inserted.countDown();
            try {
                if (!inserted.await(20, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("并发测试等待另一事务插入超时");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("并发测试被中断", ex);
            }
            costs.rebuildPool(skuId, warehouseId);
            return null;
        });
    }

    private CostLayer findLayer(String bizType) {
        return layers.findAll().stream()
                .filter(l -> l.bizType().equals(bizType))
                .findFirst().orElseThrow();
    }

    private void ledger(int changeQty, String bizType, String ref, Long unitCost,
                        OffsetDateTime at) {
        CostTestSupport.ledger(jdbc, skuId, warehouseId, "", changeQty, bizType, ref,
                unitCost, at);
    }

    private PurchaseOrder receivePurchase(int qty, long unitPrice, String requestId) {
        PurchaseOrder po = purchases.create(new PurchaseOrderApplicationService.CreateCommand(
                supplierId, warehouseId, BIZ_DATE, "成本引擎集成",
                List.of(new PurchaseOrderApplicationService.CreateLineCommand(
                        skuId, qty, unitPrice)), requestId));
        purchases.submit(po.id());
        purchases.approve(po.id());
        return purchases.receive(po.id(),
                new PurchaseOrderApplicationService.ReceiveCommand(
                        List.of(new PurchaseOrderApplicationService.ReceiveLineCommand(
                                po.lines().get(0).id(), qty, null, null, null)),
                        requestId + "-recv"));
    }

    private SalesOrder shipSale(int qty, long unitPrice, String requestId) {
        SalesOrder so = sales.create(new SalesOrderApplicationService.CreateCommand(
                customerId, warehouseId, BIZ_DATE, "成本引擎集成",
                List.of(new SalesOrderApplicationService.CreateLineCommand(
                        skuId, qty, unitPrice, 10000)), requestId));
        sales.submit(so.id());
        sales.approve(so.id());
        return sales.ship(so.id(), requestId + "-ship");
    }

    private ReturnOrder returnGoods(SalesOrder so, int qty, String requestId) {
        ReturnOrder ro = returns.create(new ReturnOrderApplicationService.CreateCommand(
                so.id(), BIZ_DATE.plusDays(1), "成本引擎退货",
                List.of(new ReturnOrderApplicationService.CreateLineCommand(
                        so.lines().get(0).id(), qty, "", null, null)), requestId));
        returns.submit(ro.id());
        returns.approve(ro.id());
        return returns.receive(ro.id(), requestId + "-recv");
    }
}
