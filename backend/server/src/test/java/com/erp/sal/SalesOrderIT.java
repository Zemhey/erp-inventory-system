package com.erp.sal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.inv.application.InventoryApplicationService;
import com.erp.mdm.application.CustomerApplicationService;
import com.erp.mdm.application.ProductApplicationService;
import com.erp.mdm.application.SkuApplicationService;
import com.erp.mdm.application.SupplierApplicationService;
import com.erp.mdm.application.WarehouseApplicationService;
import com.erp.pur.application.PurchaseOrderApplicationService;
import com.erp.pur.application.PurchaseOrderApplicationService.CreateCommand;
import com.erp.pur.application.PurchaseOrderApplicationService.CreateLineCommand;
import com.erp.pur.application.PurchaseOrderApplicationService.ReceiveCommand;
import com.erp.pur.application.PurchaseOrderApplicationService.ReceiveLineCommand;
import com.erp.sal.application.SalesOrderApplicationService;
import com.erp.sal.domain.SalesOrder;
import com.erp.shared.document.DocumentStatus;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.support.CaseId;
import com.erp.support.PostgresTestBase;
import com.erp.support.TestDataFactory;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 出货闭环集成测试（FR-SAL-01 建单即预占、FR-SAL-02 出货确认）。
 *
 * <p>计划书 §4.2 点名的三个高价值用例里，有两个在本类：
 * <ul>
 *   <li><b>库存不足时整单回滚</b>（TC-SAL-003）—— 3 行明细第 3 行不足，
 *       前 2 行的预占必须一起回滚；</li>
 *   <li><b>FEFO 拆批次出货</b>（TC-SAL-009）—— 按最早效期扣减，单笔拆多批次。</li>
 * </ul>
 */
@SpringBootTest
@DisplayName("出货 · 建单预占 / 审核 / 出货（FEFO）")
class SalesOrderIT extends PostgresTestBase {

    @Autowired
    private SalesOrderApplicationService salesOrders;
    @Autowired
    private PurchaseOrderApplicationService purchaseOrders;
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
    private JdbcTemplate jdbc;

    private TestDataFactory data;
    private long customerId;
    private long warehouseId;
    private long supplierId;
    private long skuA;
    private long skuB;
    private long skuC;

    @BeforeEach
    void setUp() {
        data = new TestDataFactory(jdbc);
        data.truncateAll();

        customerId = customerService.create(new CustomerApplicationService.CreateCommand(
                "CUS-SAL-01", "出货测试客户", null, 10000, "NORMAL", true)).id();
        supplierId = supplierService.create(new SupplierApplicationService.CreateCommand(
                "SUP-SAL-01", "出货测试供应商", null, null, null, true)).id();
        warehouseId = warehouseService.create(new WarehouseApplicationService.CreateCommand(
                "WH-SAL-01", "出货测试仓", null, true)).id();
        var product = productService.create(new ProductApplicationService.CreateCommand(
                "P-SAL-01", "出货测试商品", null, null, true));
        skuA = skuService.create(new SkuApplicationService.CreateCommand(
                product.id(), "白色", "L", null, 12900L, true)).id();
        skuB = skuService.create(new SkuApplicationService.CreateCommand(
                product.id(), "黑色", "M", null, 12900L, true)).id();
        skuC = skuService.create(new SkuApplicationService.CreateCommand(
                product.id(), "蓝色", "S", null, 12900L, true)).id();
    }

    // ─────────────────────────── 建单即预占 ───────────────────────────

    @Test
    @CaseId("TC-SAL-001")
    @DisplayName("建单即预占：单据为 DRAFT，但库存已被锁住（实存不变、预占增加）")
    void create_shouldReserveImmediately() {
        stock(skuA, 10);

        SalesOrder o = createOrder("req-sal-001", List.of(new SalesOrderApplicationService.CreateLineCommand(skuA, 4, 12900L, null)));

        assertThat(o.status()).isEqualTo(DocumentStatus.DRAFT);
        assertThat(o.orderNo()).matches("SO\\d{8}-\\d{4,}");
        assertThat(o.lines().get(0).reservationIds().get(0)).as("明细必须回填预占 id").isNotNull();
        assertThat(data.onHand(skuA, warehouseId, "")).as("预占不改实存").isEqualTo(10);
        assertThat(data.reserved(skuA, warehouseId, "")).isEqualTo(4);
        assertThat(data.available(skuA, warehouseId, "")).isEqualTo(6);
    }

    @Test
    @CaseId("TC-SAL-002")
    @DisplayName("多行建单：每行独立预占（requestId 按行拆分，不会互相幂等吞掉）")
    void create_multiLine_shouldReserveEachLineIndependently() {
        stock(skuA, 10);
        stock(skuB, 10);

        SalesOrder o = createOrder("req-sal-002", List.of(
                new SalesOrderApplicationService.CreateLineCommand(skuA, 3, 1000L, null),
                new SalesOrderApplicationService.CreateLineCommand(skuB, 5, 2000L, null)));

        assertThat(o.lines()).hasSize(2);
        assertThat(data.reserved(skuA, warehouseId, "")).as("A 行必须真的预占 3").isEqualTo(3);
        assertThat(data.reserved(skuB, warehouseId, "")).as("B 行必须真的预占 5").isEqualTo(5);
        assertThat(o.lines().get(0).reservationIds().get(0))
                .as("两行必须是不同的预占记录")
                .isNotEqualTo(o.lines().get(1).reservationIds().get(0));
    }

    @Test
    @CaseId("TC-SAL-003")
    @DisplayName("★★ 库存不足时整单回滚：3 行明细第 3 行不足 → 前 2 行的预占必须一起回滚")
    void create_whenAnyLineInsufficient_mustRollbackWholeOrder() {
        stock(skuA, 10);
        stock(skuB, 10);
        stock(skuC, 1);          // 第 3 行只有 1 件，却要 5 件

        assertThatThrownBy(() -> createOrder("req-sal-003", List.of(
                new SalesOrderApplicationService.CreateLineCommand(skuA, 3, 1000L, null),
                new SalesOrderApplicationService.CreateLineCommand(skuB, 4, 1000L, null),
                new SalesOrderApplicationService.CreateLineCommand(skuC, 5, 1000L, null))))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.INV_INSUFFICIENT_STOCK);

        // ── 核心断言：前两行的预占必须归零，且余额行上的锁痕迹全部消失 ──
        assertThat(data.reserved(skuA, warehouseId, ""))
                .as("★ 第 1 行的预占必须随整单回滚")
                .isZero();
        assertThat(data.reserved(skuB, warehouseId, ""))
                .as("★ 第 2 行的预占必须随整单回滚")
                .isZero();
        assertThat(data.available(skuA, warehouseId, "")).isEqualTo(10);
        assertThat(data.available(skuB, warehouseId, "")).isEqualTo(10);
        assertThat(data.available(skuC, warehouseId, "")).as("失败行也不得留痕迹").isEqualTo(1);

        Integer orderRows = jdbc.queryForObject("SELECT COUNT(*) FROM sales_orders", Integer.class);
        assertThat(orderRows).as("单据头必须一起回滚，不能留半个单子").isZero();
        Integer reservationRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_reservations", Integer.class);
        assertThat(reservationRows).as("不得残留任何预占记录").isZero();
    }

    @Test
    @CaseId("TC-SAL-004")
    @DisplayName("建单幂等：相同 requestId 重复提交返回同一张单，不重复预占")
    void create_withSameRequestId_isIdempotent() {
        stock(skuA, 10);

        SalesOrder first = createOrder("req-sal-idem", List.of(new SalesOrderApplicationService.CreateLineCommand(skuA, 4, 1000L, null)));
        SalesOrder second = createOrder("req-sal-idem", List.of(new SalesOrderApplicationService.CreateLineCommand(skuA, 4, 1000L, null)));

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(data.reserved(skuA, warehouseId, "")).as("只能预占一次").isEqualTo(4);
    }

    // ─────────────────────────── 金额 ───────────────────────────

    @Test
    @CaseId("TC-SAL-005")
    @DisplayName("★ 折扣按整数万分比计算：9 折 12900 分 × 2 = 23220 分（无浮点尾巴）")
    void create_withDiscount_shouldComputeExactly() {
        stock(skuA, 10);

        SalesOrder o = createOrder("req-sal-disc", List.of(
                new SalesOrderApplicationService.CreateLineCommand(skuA, 2, 12900L, 9000)));

        assertThat(o.lines().get(0).amount()).isEqualTo(23220L);
        assertThat(o.totalAmount()).isEqualTo(23220L);
        assertThat(o.lines().get(0).discountRate()).isEqualTo(9000);
    }

    @Test
    @CaseId("TC-SAL-006")
    @DisplayName("折扣金额四舍五入到分：3 × 333 × 9000/10000 = 899.1 → 899")
    void lineAmount_shouldRoundHalfUp() {
        assertThat(SalesOrderApplicationService.lineAmount(3, 333L, 9000)).isEqualTo(899L);
        assertThat(SalesOrderApplicationService.lineAmount(1, 1L, 5000)).isEqualTo(1L);   // 0.5 → 1
        assertThat(SalesOrderApplicationService.lineAmount(1, 1L, 4999)).isZero();        // 0.4999 → 0
        assertThat(SalesOrderApplicationService.lineAmount(2, 12900L, 10000)).isEqualTo(25800L);
    }

    @Test
    @CaseId("TC-SAL-007")
    @DisplayName("折扣率越界（0 / 10001）必须被拒绝")
    void create_withInvalidDiscount_thenReject() {
        stock(skuA, 10);

        for (int bad : new int[]{0, 10001, -5}) {
            assertThatThrownBy(() -> createOrder("req-sal-bad-" + Math.abs(bad), List.of(
                    new SalesOrderApplicationService.CreateLineCommand(skuA, 1, 1000L, bad))))
                    .as("折扣率 %d 必须被拒绝", bad)
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ErrorCode.SYS_VALIDATION_ERROR);
        }
    }

    // ─────────────────────────── 出货确认 ───────────────────────────

    @Test
    @CaseId("TC-SAL-008")
    @DisplayName("★ 出货确认：预占转出库，实存与预占同时减少，状态 COMPLETED，INV-2 成立")
    void ship_shouldConsumeReservationAndKeepInvariants() {
        stock(skuA, 10);
        SalesOrder o = createOrder("req-sal-ship", List.of(new SalesOrderApplicationService.CreateLineCommand(skuA, 6, 1000L, null)));
        salesOrders.submit(o.id());
        salesOrders.approve(o.id());

        SalesOrder after = salesOrders.ship(o.id(), "req-ship-1");

        assertThat(after.status()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(after.lines().get(0).shippedQty()).isEqualTo(6);
        assertThat(data.onHand(skuA, warehouseId, "")).as("实存 10 - 6").isEqualTo(4);
        assertThat(data.reserved(skuA, warehouseId, "")).as("预占已消费").isZero();
        assertThat(data.available(skuA, warehouseId, "")).isEqualTo(4);
        assertThat(inventory.checkLedgerConsistency())
                .as("★ 不变量 INV-2：出货后实存 == 流水净和")
                .isEmpty();

        // 流水可反查出货单号，且为负数
        Integer traced = jdbc.queryForObject("""
                SELECT COUNT(*) FROM inventory_ledger
                 WHERE biz_type = 'SALES_OUT' AND biz_ref_no = ? AND change_qty = -6
                """, Integer.class, o.orderNo());
        assertThat(traced).isEqualTo(1);
    }

    @Test
    @CaseId("TC-SAL-009")
    @DisplayName("★★ FEFO 拆批次端到端：库存全部带批次时建单自动分段预占，出货按效期扣减并拆多条流水")
    void ship_shouldFollowFefoAndSplitBatches() {
        // 库存全部是带批次的（真实进货场景）：B1 效期更早
        receiveStock(skuA, "B1", LocalDate.of(2025, 6, 1), 10);
        receiveStock(skuA, "B2", LocalDate.of(2025, 9, 1), 10);

        // 不存在 batch_no = '' 的余额行 —— 这正是 M0 的 reserve 会卡死的情形
        // （它要求指定批次的余额行存在）。建单应当自动按 FEFO 分段预占。
        SalesOrder o = createOrder("req-sal-fefo",
                List.of(new SalesOrderApplicationService.CreateLineCommand(skuA, 12, 1000L, null)));

        // ① 建单即完成两段预占：B1 预占 10，B2 预占 2
        assertThat(o.lines().get(0).reservations())
                .as("★ 12 件跨两个批次 → 必须是两段预占，不能只锁一段")
                .hasSize(2);
        assertThat(o.lines().get(0).totalReservedQty()).isEqualTo(12);
        assertThat(data.reserved(skuA, warehouseId, "B1")).isEqualTo(10);
        assertThat(data.reserved(skuA, warehouseId, "B2")).isEqualTo(2);
        assertThat(data.available(skuA, warehouseId, "B1")).isZero();

        salesOrders.submit(o.id());
        salesOrders.approve(o.id());
        salesOrders.ship(o.id(), "req-ship-fefo");

        // ② 按 FEFO 扣减：B1 被扣光，B2 扣 2（B2 总 10，其中 2 被本单预占）
        assertThat(data.onHand(skuA, warehouseId, "B1")).as("最早效期批次应被扣光").isZero();
        assertThat(data.onHand(skuA, warehouseId, "B2")).isEqualTo(8);
        assertThat(inventory.checkLedgerConsistency()).as("拆批次后 INV-2 仍成立").isEmpty();

        // ③ 单笔出货拆多批次 → 多条流水
        Integer ledgerRows = jdbc.queryForObject("""
                SELECT COUNT(*) FROM inventory_ledger
                 WHERE biz_type = 'SALES_OUT' AND biz_ref_no = ?
                """, Integer.class, o.orderNo());
        assertThat(ledgerRows).as("单笔出货拆多批次 → 两条流水").isEqualTo(2);

        // ④ 两段预占都必须被消费
        for (var seg : o.lines().get(0).reservations()) {
            assertThat(reservationStatus(seg.reservationId()))
                    .as("第 %d 段预占必须转为 CONSUMED", seg.seq())
                    .isEqualTo("CONSUMED");
        }
        assertThat(inventory.sumActiveReservedQty(skuA, warehouseId))
                .as("出货后不得残留 ACTIVE 预占")
                .isZero();
    }

    @Test
    @CaseId("TC-SAL-010")
    @DisplayName("★ 跳过审核直接出货（DRAFT）必须被拒绝，库存不动")
    void ship_whenDraft_thenRejected() {
        stock(skuA, 10);
        SalesOrder o = createOrder("req-sal-draft", List.of(new SalesOrderApplicationService.CreateLineCommand(skuA, 3, 1000L, null)));

        assertThatThrownBy(() -> salesOrders.ship(o.id(), "req-ship-draft"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SAL_STATUS_ILLEGAL);

        assertThat(data.onHand(skuA, warehouseId, "")).as("不得出库").isEqualTo(10);
        assertThat(data.reserved(skuA, warehouseId, "")).as("预占仍在").isEqualTo(3);
    }

    // ─────────────────────────── 作废释放预占 ───────────────────────────

    @Test
    @CaseId("TC-SAL-011")
    @DisplayName("★ 作废未出货单据必须释放预占（否则库存被永久锁死）")
    void void_beforeShip_shouldReleaseReservations() {
        stock(skuA, 10);
        SalesOrder o = createOrder("req-sal-void", List.of(new SalesOrderApplicationService.CreateLineCommand(skuA, 7, 1000L, null)));
        assertThat(data.reserved(skuA, warehouseId, "")).isEqualTo(7);

        SalesOrder voided = salesOrders.voidOrder(o.id(), "req-sal-void-1");

        assertThat(voided.status()).isEqualTo(DocumentStatus.VOIDED);
        assertThat(data.reserved(skuA, warehouseId, "")).as("★ 预占必须释放").isZero();
        assertThat(data.available(skuA, warehouseId, "")).as("可用量恢复").isEqualTo(10);
    }

    @Test
    @CaseId("TC-SAL-012")
    @DisplayName("★ 已出货单据红冲：按原批次回补实存、写 SALES_REVERSAL、状态 VOIDED")
    void void_afterShip_shouldReverseStockAndVoid() {
        stock(skuA, 10);
        SalesOrder o = createOrder("req-sal-void2", List.of(new SalesOrderApplicationService.CreateLineCommand(skuA, 5, 1000L, null)));
        salesOrders.submit(o.id());
        salesOrders.approve(o.id());
        salesOrders.ship(o.id(), "req-ship-v2");
        assertThat(salesOrders.require(o.id()).status()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(data.onHand(skuA, warehouseId, "")).isEqualTo(5);

        SalesOrder voided = salesOrders.voidOrder(o.id(), "req-sal-void-reverse");

        assertThat(voided.status()).isEqualTo(DocumentStatus.VOIDED);
        assertThat(data.onHand(skuA, warehouseId, "")).as("★ 红冲必须回补实存").isEqualTo(10);
        assertThat(data.reserved(skuA, warehouseId, "")).as("预占应已消费，不得复活").isZero();
        assertThat(inventory.ledgerEntries(o.orderNo(), "SALES_OUT")).hasSize(1);
        assertThat(inventory.ledgerEntries(o.orderNo(), "SALES_REVERSAL"))
                .as("红冲必须留下可追溯的反向流水")
                .singleElement()
                .satisfies(e -> assertThat(e.changeQty()).isEqualTo(5));
        assertThat(inventory.checkLedgerConsistency())
                .as("红冲后全库实存 == 流水净和（INV-2）")
                .isEmpty();
    }

    // ─────────────────────────── 校验 ───────────────────────────

    @Test
    @CaseId("TC-SAL-013")
    @DisplayName("客户或仓库停用后不能再建出货单")
    void create_whenCustomerOrWarehouseDisabled_thenReject() {
        stock(skuA, 10);
        customerService.disable(customerId);

        assertThatThrownBy(() -> createOrder("req-sal-dis", List.of(
                new SalesOrderApplicationService.CreateLineCommand(skuA, 1, 1000L, null))))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SYS_VALIDATION_ERROR)
                .hasMessageContaining("客户已停用");

        assertThat(data.reserved(skuA, warehouseId, "")).as("失败不得预占").isZero();
    }

    @Test
    @CaseId("TC-SAL-014")
    @DisplayName("出货单不存在：404 语义")
    void require_whenMissing_thenNotFound() {
        assertThatThrownBy(() -> salesOrders.require(777_777L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SAL_ORDER_NOT_FOUND);
    }

    @Test
    @CaseId("TC-SAL-015")
    @DisplayName("★ 端到端：进货入库 → 出货 → 两单流水可各自反查，且全库 INV-2 成立")
    void endToEnd_purchaseThenSales_shouldKeepGlobalConsistency() {
        // ① 进货 20 件入库
        var po = purchaseOrders.create(new CreateCommand(supplierId, warehouseId,
                LocalDate.of(2025, 6, 1), "进货",
                List.of(new CreateLineCommand(skuA, 20, 1000L)), "req-e2e-po"));
        purchaseOrders.submit(po.id());
        purchaseOrders.approve(po.id());
        purchaseOrders.receive(po.id(), new ReceiveCommand(
                List.of(new ReceiveLineCommand(po.lines().get(0).id(), 20, null, null, null)),
                "req-e2e-recv"));
        assertThat(data.onHand(skuA, warehouseId, "")).isEqualTo(20);

        // ② 出货 8 件
        SalesOrder so = createOrder("req-e2e-so", List.of(new SalesOrderApplicationService.CreateLineCommand(skuA, 8, 12900L, 9000)));
        salesOrders.submit(so.id());
        salesOrders.approve(so.id());
        salesOrders.ship(so.id(), "req-e2e-ship");

        assertThat(data.onHand(skuA, warehouseId, "")).isEqualTo(12);
        assertThat(so.totalAmount()).as("8 × 12900 × 0.9 = 92880").isEqualTo(92880L);

        // ③ 两条单据的流水都能按单号反查到
        assertThat(data.ledgerCount(po.orderNo())).isEqualTo(1);
        assertThat(data.ledgerCount(so.orderNo())).isEqualTo(1);

        // ④ 全库不变量
        assertThat(inventory.checkLedgerConsistency())
                .as("整条业务链跑完后，全库实存必须等于流水净和")
                .isEmpty();
    }

    // ─────────────────────────── 辅助 ───────────────────────────

    /** 直接造库存（绕过进货，用于聚焦出货语义）。 */
    private void stock(long skuId, int qty) {
        data.stock(skuId, warehouseId, "", null, qty);
        // ★ 余额与流水必须一致（不变量 INV-2），否则本类结尾的 checkLedgerConsistency 会误报。
        //   这不是"为了让测试变绿而造假数据" —— 而是"造数据也要造出满足不变量的数据"。
        //   造一条不对应流水的库存，本身就是在模拟"有人绕过流水改了实存"这种缺陷。
        data.ledger(skuId, warehouseId, "", qty, "PURCHASE_IN", "SEED-" + skuId + "-" + qty);
    }

    /** 通过进货单入库一个批次（用于 FEFO 场景，保证流水完整）。 */
    private void receiveStock(long skuId, String batchNo, LocalDate expiry, int qty) {
        var po = purchaseOrders.create(new CreateCommand(supplierId, warehouseId,
                LocalDate.of(2025, 6, 1), "备货", List.of(new CreateLineCommand(skuId, qty, 1000L)),
                "req-batch-" + batchNo));
        purchaseOrders.submit(po.id());
        purchaseOrders.approve(po.id());
        purchaseOrders.receive(po.id(), new ReceiveCommand(
                List.of(new ReceiveLineCommand(po.lines().get(0).id(), qty, batchNo, null, expiry)),
                "req-batch-recv-" + batchNo));
    }

    private SalesOrder createOrder(String requestId, List<SalesOrderApplicationService.CreateLineCommand> lines) {
        return salesOrders.create(new SalesOrderApplicationService.CreateCommand(
                customerId, warehouseId, LocalDate.of(2025, 6, 1), "测试出货单", lines, requestId));
    }

    // ─────────────────────────── 预占生命周期（M1 新增回归） ───────────────────────────

    @Test
    @CaseId("TC-SAL-016")
    @DisplayName("★★ 出货后预占记录必须转为 CONSUMED：不允许永远停在 ACTIVE（M1 真实捕获的缺陷）")
    void ship_mustMarkReservationConsumed() {
        stock(skuA, 10);
        SalesOrder o = createOrder("req-sal-consume",
                List.of(new SalesOrderApplicationService.CreateLineCommand(skuA, 6, 1000L, null)));
        long reservationId = o.lines().get(0).reservationIds().get(0);

        assertThat(reservationStatus(reservationId)).as("建单后应为 ACTIVE").isEqualTo("ACTIVE");

        salesOrders.submit(o.id());
        salesOrders.approve(o.id());
        salesOrders.ship(o.id(), "req-consume-ship");

        // ── 缺陷本体：shipOut 只改了余额行的数量，预占记录却仍是 ACTIVE ──
        assertThat(reservationStatus(reservationId))
                .as("★ 出货成功后预占记录必须变为 CONSUMED") 
                .isEqualTo("CONSUMED");
        // 余额行上的预占量也已扣光，两者必须一致（否则在途量统计会虚高）
        assertThat(inventory.sumActiveReservedQty(skuA, warehouseId))
                .as("仍为 ACTIVE 的预占合计必须归零")
                .isZero();
        assertThat(data.reserved(skuA, warehouseId, "")).isZero();
    }

    @Test
    @CaseId("TC-SAL-017")
    @DisplayName("作废后预占记录转为 RELEASED，且 ACTIVE 合计归零（与余额行一致）")
    void void_mustMarkReservationReleased() {
        stock(skuB, 10);
        SalesOrder o = createOrder("req-sal-release",
                List.of(new SalesOrderApplicationService.CreateLineCommand(skuB, 4, 1000L, null)));
        long reservationId = o.lines().get(0).reservationIds().get(0);

        salesOrders.voidOrder(o.id(), "req-sal-release");

        assertThat(reservationStatus(reservationId)).isEqualTo("RELEASED");
        assertThat(inventory.sumActiveReservedQty(skuB, warehouseId)).isZero();
        assertThat(data.reserved(skuB, warehouseId, "")).isZero();
    }

    private String reservationStatus(long reservationId) {
        return jdbc.queryForObject(
                "SELECT status FROM inventory_reservations WHERE id = ?", String.class, reservationId);
    }
}
