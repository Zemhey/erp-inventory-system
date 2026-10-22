package com.erp.rma;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.inv.application.InventoryApplicationService;
import com.erp.mdm.application.CustomerApplicationService;
import com.erp.mdm.application.ProductApplicationService;
import com.erp.mdm.application.SkuApplicationService;
import com.erp.mdm.application.SupplierApplicationService;
import com.erp.mdm.application.WarehouseApplicationService;
import com.erp.pur.application.PurchaseOrderApplicationService;
import com.erp.rma.application.ReturnOrderApplicationService;
import com.erp.rma.application.ReturnOrderApplicationService.CreateCommand;
import com.erp.rma.application.ReturnOrderApplicationService.CreateLineCommand;
import com.erp.rma.domain.ReturnOrder;
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
 * 退货闭环集成测试（FR-RMA-01，计划书 §4.2）。
 *
 * <p>本类盯的是两件事：
 * <ul>
 *   <li><b>数量上限</b>：累计退货不得超过已出货 —— 建单时只是预检，
 *       真正的守卫在入库时的条件 UPDATE（TC-RMA-004 专门构造"两张各自合规、
 *       合计超量"的竞态形态）；</li>
 *   <li><b>失败必须无副作用</b>：每一次拒绝都要断言库存、流水、
 *       {@code returned_qty}、单据计数四者不变 —— 退货是逆向单据，
 *       脏一次就会连日清与报表一起对不上。</li>
 * </ul>
 *
 * <p>造数据一律走真实的进货 → 出货链路（不手改库存），
 * 这样"退货回补了多少"才有可对照的流水基线（TC-RMA-012）。
 */
@SpringBootTest
@DisplayName("退货 · 建单 / 状态机 / 入库回补 / 作废（FR-RMA-01）")
class ReturnOrderIT extends PostgresTestBase {

    private static final LocalDate PURCHASE_DATE = LocalDate.of(2026, 10, 1);
    private static final LocalDate SALES_DATE = LocalDate.of(2026, 10, 2);
    private static final LocalDate RETURN_DATE = LocalDate.of(2026, 10, 3);
    private static final long UNIT_PRICE = 12900L;
    private static final int DISCOUNT = 9000;

    @Autowired
    private ReturnOrderApplicationService returnOrders;
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

    @BeforeEach
    void setUp() {
        data = new TestDataFactory(jdbc);
        data.truncateAll();

        customerId = customerService.create(new CustomerApplicationService.CreateCommand(
                "CUS-RMA-01", "退货测试客户", null, DISCOUNT, "VIP", true)).id();
        supplierId = supplierService.create(new SupplierApplicationService.CreateCommand(
                "SUP-RMA-01", "退货测试供应商", null, null, null, true)).id();
        warehouseId = warehouseService.create(new WarehouseApplicationService.CreateCommand(
                "WH-RMA-01", "退货测试仓", null, true)).id();
        var product = productService.create(new ProductApplicationService.CreateCommand(
                "P-RMA-01", "退货测试商品", null, null, true));
        skuA = skuService.create(new SkuApplicationService.CreateCommand(
                product.id(), "白色", "L", null, UNIT_PRICE, true)).id();
        skuB = skuService.create(new SkuApplicationService.CreateCommand(
                product.id(), "黑色", "M", null, UNIT_PRICE, true)).id();
    }

    // ─────────────────────────── 建单 ───────────────────────────

    @Test
    @CaseId("TC-RMA-001")
    @DisplayName("★ 建单：金额由原出货行单价与折扣派生，客户/仓库继承原单，状态 DRAFT 且库存不变")
    void create_shouldDeriveAmountAndPartiesFromOriginOrder() {
        SalesOrder so = shippedOrder("001", skuA, 10, 6, "", null);

        ReturnOrder o = returnOrders.create(new CreateCommand(so.id(), RETURN_DATE, "客户尺码不合",
                List.of(new CreateLineCommand(so.lines().get(0).id(), 2, null, null, null)),
                "req-rma-001"));

        assertThat(o.status()).isEqualTo(DocumentStatus.DRAFT);
        assertThat(o.orderNo()).matches("RO\\d{8}-\\d{4,}");
        assertThat(o.originSalesOrderId()).isEqualTo(so.id());
        assertThat(o.customerId()).as("客户从原出货单派生").isEqualTo(so.customerId());
        assertThat(o.warehouseId()).as("仓库从原出货单派生").isEqualTo(so.warehouseId());
        assertThat(o.period()).isEqualTo("2026-10");

        var line = o.lines().get(0);
        assertThat(line.originLineId()).isEqualTo(so.lines().get(0).id());
        assertThat(line.skuId()).isEqualTo(skuA);
        assertThat(line.unitPriceAmount()).as("单价复制自原行").isEqualTo(UNIT_PRICE);
        assertThat(line.discountRate()).as("折扣复制自原行").isEqualTo(DISCOUNT);
        assertThat(line.amount()).as("2 × 12900 × 90% = 23220 分").isEqualTo(23220L);
        assertThat(o.totalAmount()).isEqualTo(23220L);
        assertThat(o.totalQty()).isEqualTo(2);

        assertThat(data.onHand(skuA, warehouseId, "")).as("建单不得改变库存").isEqualTo(4);
        assertThat(data.ledgerCount(o.orderNo())).as("建单不得产生流水").isZero();
    }

    @Test
    @CaseId("TC-RMA-002")
    @DisplayName("原出货单还没出货（DRAFT / SUBMITTED / APPROVED）→ RMA_ORIGIN_NOT_SHIPPED，且不建单")
    void create_whenOriginNotShipped_thenReject() {
        receivePurchase("002", skuA, 10, "", null);
        // 每个阶段各建一张出货单：出货是"建单即预占"，所以单次只订 3 件
        // （3 × 3 = 9 ≤ 10，第三张才不会因可用量不足而先失败 —— 那会测不到退货这层）
        for (String stage : new String[]{"draft", "submitted", "approved"}) {
            SalesOrder so = salesOrders.create(new SalesOrderApplicationService.CreateCommand(
                    customerId, warehouseId, SALES_DATE, "未出货",
                    List.of(new SalesOrderApplicationService.CreateLineCommand(
                            skuA, 3, UNIT_PRICE, DISCOUNT)), "req-so-002-" + stage));
            if (!"draft".equals(stage)) {
                salesOrders.submit(so.id());
            }
            if ("approved".equals(stage)) {
                salesOrders.approve(so.id());
            }

            long originLineId = so.lines().get(0).id();
            assertThatThrownBy(() -> returnOrders.create(new CreateCommand(so.id(), RETURN_DATE,
                    null, List.of(new CreateLineCommand(originLineId, 1, null, null, null)),
                    "req-rma-002-" + stage)))
                    .as("出货单处于 %s 阶段时不可退货", stage)
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ErrorCode.RMA_ORIGIN_NOT_SHIPPED);
        }

        assertThat(count("return_orders")).as("三次失败都不能留下退货单").isZero();
        assertThat(data.onHand(skuA, warehouseId, "")).isEqualTo(10);
    }

    @Test
    @CaseId("TC-RMA-003")
    @DisplayName("明细行不属于原出货单 → 404，整单不建、库存不动")
    void create_whenLineNotFromOriginOrder_thenReject() {
        SalesOrder orderA = shippedOrder("003a", skuA, 10, 6, "", null);
        SalesOrder orderB = shippedOrder("003b", skuB, 8, 4, "", null);
        long foreignLineId = orderB.lines().get(0).id();

        assertThatThrownBy(() -> returnOrders.create(new CreateCommand(orderA.id(), RETURN_DATE,
                null, List.of(new CreateLineCommand(foreignLineId, 1, null, null, null)),
                "req-rma-003")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SYS_NOT_FOUND)
                .hasMessageContaining("没有可退的明细行");

        assertThat(count("return_orders")).as("校验失败不得留下半个单子").isZero();
        assertThat(count("return_order_lines")).isZero();
        assertThat(returnedQty(foreignLineId)).as("另一张单的明细行不得被累加").isZero();
        assertThat(data.onHand(skuB, warehouseId, "")).isEqualTo(4);
    }

    @Test
    @CaseId("TC-RMA-004")
    @DisplayName("★★ 超退拦截：建单预检挡一次，两张各自合规的单在入库时被原子守卫再挡一次，全程无副作用")
    void createAndReceive_whenTotalReturnExceedsShipped_thenRejectWithNoSideEffects() {
        SalesOrder so = shippedOrder("004", skuA, 10, 5, "", null);
        long originLineId = so.lines().get(0).id();

        // ① 预检：一次就想退 6 件 > 已出货 5 件
        assertThatThrownBy(() -> returnOrders.create(new CreateCommand(so.id(), RETURN_DATE, null,
                List.of(new CreateLineCommand(originLineId, 6, null, null, null)), "req-rma-004-a")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.RMA_QTY_EXCEED_ORIGIN);

        // ② 两张各自 3 件（合计 6 > 5）：建单时都通过预检，第二张必须在入库时被数据库拦住
        ReturnOrder first = approvedReturn("004-b", so, originLineId, 3);
        ReturnOrder second = approvedReturn("004-c", so, originLineId, 3);
        int onHandBeforeFirst = data.onHand(skuA, warehouseId, "");
        returnOrders.receive(first.id(), "req-rma-004-recv-b");
        assertThat(returnedQty(originLineId)).as("第一张入库后累计退货 3").isEqualTo(3);

        assertThatThrownBy(() -> returnOrders.receive(second.id(), "req-rma-004-recv-c"))
                .as("★ 预检放行后，超退必须由条件 UPDATE 拦住")
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.RMA_QTY_EXCEED_ORIGIN);

        assertThat(returnedQty(originLineId)).as("失败的回退不得改累计退货量").isEqualTo(3);
        assertThat(data.onHand(skuA, warehouseId, ""))
                .as("失败的退货单不得回补库存（整个事务已回滚）")
                .isEqualTo(onHandBeforeFirst + 3);
        assertThat(data.ledgerCount(second.orderNo()))
                .as("失败的退货单不能留下流水").isZero();
        assertThat(returnOrders.require(second.id()).status())
                .as("失败的退货单仍是 APPROVED").isEqualTo(DocumentStatus.APPROVED);
    }

    @Test
    @CaseId("TC-RMA-005")
    @DisplayName("建单幂等：相同 requestId 重复提交返回同一张单，不新增记录")
    void create_withSameRequestId_isIdempotent() {
        SalesOrder so = shippedOrder("005", skuA, 10, 6, "", null);

        ReturnOrder first = returnOrders.create(new CreateCommand(so.id(), RETURN_DATE, "重复提交",
                List.of(new CreateLineCommand(so.lines().get(0).id(), 2, null, null, null)),
                "req-rma-005"));
        ReturnOrder second = returnOrders.create(new CreateCommand(so.id(), RETURN_DATE, "重复提交",
                List.of(new CreateLineCommand(so.lines().get(0).id(), 2, null, null, null)),
                "req-rma-005"));

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(count("return_orders")).as("只能建一张").isEqualTo(1);
        assertThat(count("return_order_lines")).isEqualTo(1);
        assertThat(second.lines().get(0).qty()).isEqualTo(2);
    }

    // ─────────────────────────── 状态机 ───────────────────────────

    @Test
    @CaseId("TC-RMA-006")
    @DisplayName("★ 状态机：DRAFT→SUBMITTED→APPROVED→COMPLETED 全通，跳步与终态后的动作全被拒")
    void statusMachine_shouldFollowDeclaredTransitionsOnly() {
        SalesOrder so = shippedOrder("006", skuA, 10, 6, "", null);
        ReturnOrder draft = returnOrders.create(new CreateCommand(so.id(), RETURN_DATE, null,
                List.of(new CreateLineCommand(so.lines().get(0).id(), 2, null, null, null)),
                "req-rma-006"));

        assertThatThrownBy(() -> returnOrders.approve(draft.id()))
                .as("DRAFT 不能直接审核")
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.RMA_STATUS_ILLEGAL);
        assertThatThrownBy(() -> returnOrders.receive(draft.id(), "req-rma-006-illegal-1"))
                .as("DRAFT 不能入库")
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.RMA_STATUS_ILLEGAL);

        assertThat(returnOrders.submit(draft.id()).status()).isEqualTo(DocumentStatus.SUBMITTED);
        assertThatThrownBy(() -> returnOrders.receive(draft.id(), "req-rma-006-illegal-2"))
                .as("SUBMITTED 仍不可入库")
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.RMA_STATUS_ILLEGAL);
        assertThat(returnOrders.approve(draft.id()).status()).isEqualTo(DocumentStatus.APPROVED);
        assertThat(returnOrders.receive(draft.id(), "req-rma-006-recv").status())
                .isEqualTo(DocumentStatus.COMPLETED);

        assertThatThrownBy(() -> returnOrders.submit(draft.id()))
                .as("COMPLETED 是终态")
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.RMA_STATUS_ILLEGAL);

        // 驳回支路：SUBMITTED → REJECTED（剩余可退 4，退 1 合规）
        ReturnOrder other = returnOrders.create(new CreateCommand(so.id(), RETURN_DATE, null,
                List.of(new CreateLineCommand(so.lines().get(0).id(), 1, null, null, null)),
                "req-rma-006-rej"));
        returnOrders.submit(other.id());
        assertThat(returnOrders.reject(other.id()).status()).isEqualTo(DocumentStatus.REJECTED);
    }

    // ─────────────────────────── 入库回补 ───────────────────────────

    @Test
    @CaseId("TC-RMA-007")
    @DisplayName("★ 入库：实存回补、产生 RETURN_IN 正向流水、原出货行 returned_qty 累加、状态 COMPLETED")
    void receive_shouldRestoreStockWriteLedgerAndAccumulateReturnedQty() {
        SalesOrder so = shippedOrder("007", skuA, 10, 6, "", null);
        int onHandBefore = data.onHand(skuA, warehouseId, "");
        ReturnOrder o = approvedReturn("007", so, so.lines().get(0).id(), 2);

        ReturnOrder done = returnOrders.receive(o.id(), "req-rma-007-recv");

        assertThat(done.status()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(data.onHand(skuA, warehouseId, "")).as("实存回补 2 件").isEqualTo(onHandBefore + 2);
        assertThat(returnedQty(so.lines().get(0).id()))
                .as("原出货行累计退货 +2").isEqualTo(2);

        Integer ledgerRows = jdbc.queryForObject("""
                SELECT COUNT(*) FROM inventory_ledger
                 WHERE biz_type = 'RETURN_IN' AND biz_ref_no = ? AND change_qty = 2
                """, Integer.class, o.orderNo());
        assertThat(ledgerRows).as("必须产生一条 RETURN_IN 正向流水，且能按退货单号反查").isEqualTo(1);
        assertThat(inventory.checkLedgerConsistency())
                .as("★ 不变量 INV-2：入库后全库实存仍等于流水净和").isEmpty();
    }

    @Test
    @CaseId("TC-RMA-008")
    @DisplayName("★★ 入库幂等：同 requestId 重复调用不重复回补；已完成后换新 requestId 再入库被拒")
    void receive_idempotencyAndDoubleReceiveGuards() {
        SalesOrder so = shippedOrder("008", skuA, 10, 6, "", null);
        ReturnOrder o = approvedReturn("008", so, so.lines().get(0).id(), 3);

        ReturnOrder first = returnOrders.receive(o.id(), "req-rma-008-recv");
        ReturnOrder again = returnOrders.receive(o.id(), "req-rma-008-recv");
        int onHandAfterFirst = data.onHand(skuA, warehouseId, "");

        assertThat(again.id()).isEqualTo(first.id());
        assertThat(again.status()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(onHandAfterFirst).as("回补只发生一次").isEqualTo(4 + 3);
        assertThat(data.onHand(skuA, warehouseId, ""))
                .as("★ 同幂等键重复调用不得再次回补").isEqualTo(onHandAfterFirst);
        assertThat(returnedQty(so.lines().get(0).id())).as("累计退货也只记一次").isEqualTo(3);
        assertThat(data.ledgerCount(o.orderNo())).as("流水不得重复").isEqualTo(1);

        assertThatThrownBy(() -> returnOrders.receive(o.id(), "req-rma-008-other"))
                .as("已完成后换幂等键再入库必须被拒")
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.RMA_STATUS_ILLEGAL);
        assertThat(data.onHand(skuA, warehouseId, "")).isEqualTo(onHandAfterFirst);
        assertThat(data.ledgerCount(o.orderNo())).isEqualTo(1);
    }

    @Test
    @CaseId("TC-RMA-009")
    @DisplayName("入库前作废：状态 VOIDED，库存 / 流水 / returned_qty 全部不变")
    void void_beforeReceive_shouldNotTouchStock() {
        SalesOrder so = shippedOrder("009", skuA, 10, 6, "", null);
        ReturnOrder o = approvedReturn("009", so, so.lines().get(0).id(), 2);
        int onHandBefore = data.onHand(skuA, warehouseId, "");

        ReturnOrder voided = returnOrders.voidOrder(o.id());

        assertThat(voided.status()).isEqualTo(DocumentStatus.VOIDED);
        assertThat(data.onHand(skuA, warehouseId, "")).as("作废不动库存").isEqualTo(onHandBefore);
        assertThat(data.ledgerCount(o.orderNo())).as("作废不产生流水").isZero();
        assertThat(returnedQty(so.lines().get(0).id()))
                .as("未入库的退货不得累加 returned_qty").isZero();

        assertThatThrownBy(() -> returnOrders.receive(o.id(), "req-rma-009-after-void"))
                .as("作废后不能再入库")
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.RMA_STATUS_ILLEGAL);
        assertThat(data.onHand(skuA, warehouseId, "")).isEqualTo(onHandBefore);
    }

    @Test
    @CaseId("TC-RMA-010")
    @DisplayName("入库后作废必须被拒绝（红冲属 M2.5），且已回补的库存不动")
    void void_afterReceive_thenReject() {
        SalesOrder so = shippedOrder("010", skuA, 10, 6, "", null);
        ReturnOrder o = approvedReturn("010", so, so.lines().get(0).id(), 2);
        returnOrders.receive(o.id(), "req-rma-010-recv");
        int onHandAfterReceive = data.onHand(skuA, warehouseId, "");

        assertThatThrownBy(() -> returnOrders.voidOrder(o.id()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.RMA_STATUS_ILLEGAL)
                .hasMessageContaining("红冲");

        assertThat(data.onHand(skuA, warehouseId, "")).isEqualTo(onHandAfterReceive);
        assertThat(returnOrders.require(o.id()).status()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(returnedQty(so.lines().get(0).id())).isEqualTo(2);
    }

    @Test
    @CaseId("TC-RMA-011")
    @DisplayName("★ 指定批次与效期回补：货进到新批次，且下一次出货的 FEFO 能看见它")
    void receive_intoSpecifiedBatch_shouldBeVisibleToFefo() {
        receivePurchase("011-stock", skuA, 10, "B1", LocalDate.of(2026, 6, 1));
        SalesOrder so = shipOnly("011", skuA, 6);
        assertThat(data.onHand(skuA, warehouseId, "B1")).as("出货按 FEFO 扣了 B1").isEqualTo(4);

        ReturnOrder o = approvedReturn("011", so, so.lines().get(0).id(), 2,
                "R1", null, LocalDate.of(2025, 12, 1));
        returnOrders.receive(o.id(), "req-rma-011-recv");

        assertThat(data.onHand(skuA, warehouseId, "R1")).as("回补进指定批次 R1").isEqualTo(2);
        assertThat(data.onHand(skuA, warehouseId, "B1")).as("B1 不受影响").isEqualTo(4);

        // 下一次出货 3 件：FEFO 必须先吃效期更早的 R1（2 件），再吃 B1（1 件）
        SalesOrder next = salesOrders.create(new SalesOrderApplicationService.CreateCommand(
                customerId, warehouseId, SALES_DATE, "再次出货",
                List.of(new SalesOrderApplicationService.CreateLineCommand(
                        skuA, 3, UNIT_PRICE, DISCOUNT)), "req-so-011-next"));
        assertThat(data.reserved(skuA, warehouseId, "R1"))
                .as("★ 退货批次已进入 FEFO 候选").isEqualTo(2);
        assertThat(data.reserved(skuA, warehouseId, "B1")).isEqualTo(1);
        assertThat(next.lines().get(0).totalReservedQty()).isEqualTo(3);
    }

    @Test
    @CaseId("TC-RMA-012")
    @DisplayName("★ 不变量：余额净和 == 流水净和；退货数量 == RETURN_IN 流水正变动合计")
    void receive_shouldKeepBalanceLedgerInvariant() {
        SalesOrder so = shippedOrder("012", skuA, 10, 6, "", null);
        ReturnOrder one = approvedReturn("012-a", so, so.lines().get(0).id(), 2);
        ReturnOrder two = approvedReturn("012-b", so, so.lines().get(0).id(), 1);
        returnOrders.receive(one.id(), "req-rma-012-recv-a");
        returnOrders.receive(two.id(), "req-rma-012-recv-b");

        assertThat(inventory.checkLedgerConsistency())
                .as("两次退货入库后，全库实存必须等于流水净和").isEmpty();

        Integer returnLedger = jdbc.queryForObject("""
                SELECT COALESCE(SUM(change_qty), 0) FROM inventory_ledger
                 WHERE biz_type = 'RETURN_IN'
                """, Integer.class);
        assertThat(returnLedger).as("流水里的退货正变动合计").isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "SELECT COALESCE(SUM(qty), 0) FROM return_order_lines", Integer.class))
                .as("必须与退货明细数量合计一致").isEqualTo(3);
        assertThat(returnedQty(so.lines().get(0).id()))
                .as("原出货行的累计退货量同样等于 3").isEqualTo(3);
        assertThat(data.onHand(skuA, warehouseId, "")).as("4 + 2 + 1 = 7").isEqualTo(7);
    }

    // ─────────────────────────── 辅助 ───────────────────────────

    /** 进货并整单入库到指定批次（保证库存有完整流水）。 */
    private void receivePurchase(String tag, long skuId, int qty, String batchNo, LocalDate expiry) {
        var po = purchaseOrders.create(new PurchaseOrderApplicationService.CreateCommand(
                supplierId, warehouseId, PURCHASE_DATE, "备货",
                List.of(new PurchaseOrderApplicationService.CreateLineCommand(skuId, qty, 1000L)),
                "req-po-" + tag));
        purchaseOrders.submit(po.id());
        purchaseOrders.approve(po.id());
        purchaseOrders.receive(po.id(), new PurchaseOrderApplicationService.ReceiveCommand(
                List.of(new PurchaseOrderApplicationService.ReceiveLineCommand(
                        po.lines().get(0).id(), qty, batchNo, null, expiry)),
                "req-po-recv-" + tag));
    }

    /** 先备货再出货，返回已 COMPLETED 的出货单。 */
    private SalesOrder shippedOrder(String tag, long skuId, int purchased, int shipped,
                                    String batchNo, LocalDate expiry) {
        receivePurchase(tag, skuId, purchased, batchNo, expiry);
        return shipOnly(tag, skuId, shipped);
    }

    /** 建出货单并出货（不重复备货，供已自行安排批次的用例使用）。 */
    private SalesOrder shipOnly(String tag, long skuId, int shipped) {
        var so = salesOrders.create(new SalesOrderApplicationService.CreateCommand(
                customerId, warehouseId, SALES_DATE, "出货",
                List.of(new SalesOrderApplicationService.CreateLineCommand(
                        skuId, shipped, UNIT_PRICE, DISCOUNT)), "req-so-" + tag));
        salesOrders.submit(so.id());
        salesOrders.approve(so.id());
        return salesOrders.ship(so.id(), "req-so-ship-" + tag);
    }

    /** 建一张退货单并推进到 APPROVED（此时仍不应影响库存）。 */
    private ReturnOrder approvedReturn(String tag, SalesOrder origin, long originLineId, int qty) {
        return approvedReturn(tag, origin, originLineId, qty, null, null, null);
    }

    private ReturnOrder approvedReturn(String tag, SalesOrder origin, long originLineId, int qty,
                                       String batchNo, LocalDate productionDate,
                                       LocalDate expiryDate) {
        ReturnOrder o = returnOrders.create(new CreateCommand(origin.id(), RETURN_DATE, "退货",
                List.of(new CreateLineCommand(originLineId, qty, batchNo, productionDate, expiryDate)),
                "req-rma-" + tag));
        returnOrders.submit(o.id());
        return returnOrders.approve(o.id());
    }

    private int returnedQty(long originLineId) {
        Integer v = jdbc.queryForObject(
                "SELECT returned_qty FROM sales_order_lines WHERE id = ?",
                Integer.class, originLineId);
        return v == null ? 0 : v;
    }

    private int count(String table) {
        Integer v = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return v == null ? 0 : v;
    }
}
