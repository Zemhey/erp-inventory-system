package com.erp.pur;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.inv.application.InventoryApplicationService;
import com.erp.mdm.application.ProductApplicationService;
import com.erp.mdm.application.SkuApplicationService;
import com.erp.mdm.application.SupplierApplicationService;
import com.erp.mdm.application.WarehouseApplicationService;
import com.erp.pur.application.PurchaseOrderApplicationService;
import com.erp.pur.application.PurchaseOrderApplicationService.CreateCommand;
import com.erp.pur.application.PurchaseOrderApplicationService.CreateLineCommand;
import com.erp.pur.application.PurchaseOrderApplicationService.ReceiveCommand;
import com.erp.pur.application.PurchaseOrderApplicationService.ReceiveLineCommand;
import com.erp.pur.domain.PurchaseOrder;
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
 * 进货闭环集成测试（FR-PUR-01 建单/提交/审核、FR-PUR-02 入库确认）。
 *
 * <p>这是 M1 的核心测试类：它第一次验证"单据 → 库存"的真实联动。
 *
 * <p>断言的重点不是"接口返回 200"，而是：
 * <ul>
 *   <li><b>状态机</b>：非法转移全部被拒（含"跳过审核直接入库"）；</li>
 *   <li><b>不变量 INV-2</b>：入库后实存 == 流水净和；</li>
 *   <li><b>BR-DOC-04</b>：累计入库不得超量；</li>
 *   <li><b>事务性</b>：一张单多行入库，任一行失败必须整批回滚（不留半成品）。</li>
 * </ul>
 */
@SpringBootTest
@DisplayName("进货 · 建单 / 审核 / 入库（→ 库存）")
class PurchaseOrderIT extends PostgresTestBase {

    @Autowired
    private PurchaseOrderApplicationService purchaseOrders;
    @Autowired
    private InventoryApplicationService inventory;
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
    private long supplierId;
    private long warehouseId;
    private long skuA;
    private long skuB;

    @BeforeEach
    void setUp() {
        data = new TestDataFactory(jdbc);
        data.truncateAll();

        supplierId = supplierService.create(new SupplierApplicationService.CreateCommand(
                "SUP-PO-01", "测试供应商", null, null, null, true)).id();
        warehouseId = warehouseService.create(new WarehouseApplicationService.CreateCommand(
                "WH-PO-01", "进货测试仓", null, true)).id();
        var product = productService.create(new ProductApplicationService.CreateCommand(
                "P-PO-01", "进货测试商品", null, null, true));
        skuA = skuService.create(new SkuApplicationService.CreateCommand(
                product.id(), "白色", "L", null, 12900L, true)).id();
        skuB = skuService.create(new SkuApplicationService.CreateCommand(
                product.id(), "黑色", "M", null, 12900L, true)).id();
    }

    // ─────────────────────────── 建单 ───────────────────────────

    @Test
    @CaseId("TC-PUR-001")
    @DisplayName("建单：单号为 PO+日期+流水，总金额由明细汇总（不反算），状态 DRAFT")
    void create_shouldComputeTotalsAndDraftStatus() {
        PurchaseOrder o = createDraft("req-po-001", 10, 1000L, 5, 2000L);

        assertThat(o.orderNo()).matches("PO\\d{8}-\\d{4,}");
        assertThat(o.status()).isEqualTo(DocumentStatus.DRAFT);
        assertThat(o.totalQty()).as("总数量 = 10 + 5").isEqualTo(15);
        assertThat(o.totalAmount()).as("总金额 = 10×1000 + 5×2000").isEqualTo(20000L);
        assertThat(o.lines()).hasSize(2);
        assertThat(o.lines().get(0).amount()).isEqualTo(10000L);
        assertThat(o.lines().get(1).amount()).isEqualTo(10000L);
        assertThat(o.period()).as("账期由业务日期推出").matches("\\d{4}-\\d{2}");
    }

    @Test
    @CaseId("TC-PUR-002")
    @DisplayName("建单幂等：相同 requestId 重复提交返回同一张单，不新增记录")
    void create_withSameRequestId_isIdempotent() {
        PurchaseOrder first = createDraft("req-po-idem", 10, 1000L);
        PurchaseOrder second = createDraft("req-po-idem", 10, 1000L);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.orderNo()).isEqualTo(first.orderNo());
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM purchase_orders", Integer.class);
        assertThat(count).as("只能有一张单").isEqualTo(1);
    }

    @Test
    @CaseId("TC-PUR-003")
    @DisplayName("建单校验：空明细 / 数量为 0 / 单价为负 全部拒绝")
    void create_withInvalidLines_thenReject() {
        assertThatThrownBy(() -> purchaseOrders.create(new CreateCommand(
                supplierId, warehouseId, LocalDate.now(), null, List.of(), "req-bad-1")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SYS_VALIDATION_ERROR);

        assertThatThrownBy(() -> purchaseOrders.create(new CreateCommand(
                supplierId, warehouseId, LocalDate.now(), null,
                List.of(new CreateLineCommand(skuA, 0, 100L)), "req-bad-2")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SYS_VALIDATION_ERROR);

        assertThatThrownBy(() -> purchaseOrders.create(new CreateCommand(
                supplierId, warehouseId, LocalDate.now(), null,
                List.of(new CreateLineCommand(skuA, 1, -1L)), "req-bad-3")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SYS_VALIDATION_ERROR);
    }

    // ─────────────────────────── 状态机 ───────────────────────────

    @Test
    @CaseId("TC-PUR-004")
    @DisplayName("状态机正常路径：DRAFT → SUBMITTED → APPROVED")
    void statusFlow_happyPath() {
        PurchaseOrder o = createDraft("req-po-flow", 10, 1000L);

        assertThat(purchaseOrders.submit(o.id()).status()).isEqualTo(DocumentStatus.SUBMITTED);
        assertThat(purchaseOrders.approve(o.id()).status()).isEqualTo(DocumentStatus.APPROVED);
    }

    @Test
    @CaseId("TC-PUR-005")
    @DisplayName("★ 跳过审核直接入库（DRAFT 状态）必须被拒绝：状态机不许被绕过")
    void receive_whenDraft_thenRejected() {
        PurchaseOrder o = createDraft("req-po-draft", 10, 1000L);

        assertThatThrownBy(() -> purchaseOrders.receive(o.id(), new ReceiveCommand(
                List.of(new ReceiveLineCommand(o.lines().get(0).id(), 10, null, null, null)),
                "req-draft-recv")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.PUR_STATUS_ILLEGAL);

        assertThat(data.onHand(skuA, warehouseId, ""))
                .as("被拒绝的入库不得产生任何库存变动")
                .isZero();
    }

    @Test
    @CaseId("TC-PUR-006")
    @DisplayName("非法状态转移：DRAFT 不能直接 APPROVED，APPROVED 不能回 SUBMITTED")
    void illegalTransitions_areRejected() {
        PurchaseOrder o = createDraft("req-po-illegal", 3, 100L);

        assertThatThrownBy(() -> purchaseOrders.approve(o.id()))
                .as("DRAFT 不能直接审核")
                .hasFieldOrPropertyWithValue("code", ErrorCode.PUR_STATUS_ILLEGAL);

        purchaseOrders.submit(o.id());
        purchaseOrders.approve(o.id());

        assertThatThrownBy(() -> purchaseOrders.submit(o.id()))
                .as("已审核不能退回已提交")
                .hasFieldOrPropertyWithValue("code", ErrorCode.PUR_STATUS_ILLEGAL);
    }

    // ─────────────────────────── 入库（★ 核心） ───────────────────────────

    @Test
    @CaseId("TC-PUR-007")
    @DisplayName("★ 全额入库：状态变 COMPLETED，实存增加，且不变量 INV-2 成立")
    void receive_full_shouldCompleteAndKeepLedgerConsistent() {
        PurchaseOrder o = createDraft("req-po-full", 10, 1500L);
        purchaseOrders.submit(o.id());
        purchaseOrders.approve(o.id());

        PurchaseOrder after = purchaseOrders.receive(o.id(), new ReceiveCommand(
                List.of(new ReceiveLineCommand(o.lines().get(0).id(), 10, "BATCH-1",
                        LocalDate.of(2025, 1, 1), LocalDate.of(2027, 1, 1))),
                "req-recv-full"));

        assertThat(after.status()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(after.lines().get(0).receivedQty()).isEqualTo(10);
        assertThat(data.onHand(skuA, warehouseId, "BATCH-1")).isEqualTo(10);
        assertThat(inventory.checkLedgerConsistency())
                .as("★ 不变量 INV-2：入库后实存必须等于流水净和")
                .isEmpty();

        // 流水必须能反查到进货单号（FR-INV-02）
        Integer traced = jdbc.queryForObject("""
                SELECT COUNT(*) FROM inventory_ledger
                 WHERE biz_type = 'PURCHASE_IN' AND biz_ref_no = ? AND change_qty = 10
                """, Integer.class, o.orderNo());
        assertThat(traced).as("入库流水必须记录来源单号，可反查").isEqualTo(1);
    }

    @Test
    @CaseId("TC-PUR-008")
    @DisplayName("★ 分批入库：先入 4 件进 PARTIAL，再入 6 件进 COMPLETED，累计正确")
    void receive_partially_thenComplete() {
        PurchaseOrder o = createDraft("req-po-partial", 10, 1000L);
        purchaseOrders.submit(o.id());
        purchaseOrders.approve(o.id());
        long lineId = o.lines().get(0).id();

        PurchaseOrder first = purchaseOrders.receive(o.id(), new ReceiveCommand(
                List.of(new ReceiveLineCommand(lineId, 4, null, null, null)), "req-r-1"));
        assertThat(first.status()).as("部分入库 → PARTIAL").isEqualTo(DocumentStatus.PARTIAL);
        assertThat(first.lines().get(0).openQty()).isEqualTo(6);
        assertThat(data.onHand(skuA, warehouseId, "")).isEqualTo(4);

        PurchaseOrder second = purchaseOrders.receive(o.id(), new ReceiveCommand(
                List.of(new ReceiveLineCommand(lineId, 6, null, null, null)), "req-r-2"));
        assertThat(second.status()).as("全部入库 → COMPLETED").isEqualTo(DocumentStatus.COMPLETED);
        assertThat(data.onHand(skuA, warehouseId, "")).isEqualTo(10);
        assertThat(inventory.checkLedgerConsistency()).isEmpty();
    }

    @Test
    @CaseId("TC-PUR-009")
    @DisplayName("★ 超量入库必须被拒绝（BR-DOC-04 → PUR_OVER_RECEIVE），且库存不变")
    void receive_overQty_thenRejected() {
        PurchaseOrder o = createDraft("req-po-over", 10, 1000L);
        purchaseOrders.submit(o.id());
        purchaseOrders.approve(o.id());
        long lineId = o.lines().get(0).id();

        assertThatThrownBy(() -> purchaseOrders.receive(o.id(), new ReceiveCommand(
                List.of(new ReceiveLineCommand(lineId, 11, null, null, null)), "req-over-1")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.PUR_OVER_RECEIVE)
                .hasMessageContaining("超出 1");

        assertThat(data.onHand(skuA, warehouseId, "")).as("失败不得改变库存").isZero();
        assertThat(data.balanceRowExists(skuA, warehouseId, ""))
                .as("更严格：连余额行都不应被创建出来")
                .isFalse();
        assertThat(data.ledgerCount(o.orderNo())).as("失败不得产生任何流水").isZero();
        assertThat(purchaseOrders.require(o.id()).status()).isEqualTo(DocumentStatus.APPROVED);
    }

    @Test
    @CaseId("TC-PUR-010")
    @DisplayName("★ 多行入库其中一行超量：已处理行的库存增量必须随之回滚（整批事务）")
    void receive_whenOneLineFails_mustRollbackWholeBatch() {
        PurchaseOrder o = createDraft("req-po-rb", 10, 1000L, 5, 2000L);
        purchaseOrders.submit(o.id());
        purchaseOrders.approve(o.id());
        long line1 = o.lines().get(0).id();
        long line2 = o.lines().get(1).id();

        // 第 1 行合法（10/10），第 2 行超量（要求 9 > 单据 5）
        assertThatThrownBy(() -> purchaseOrders.receive(o.id(), new ReceiveCommand(
                List.of(new ReceiveLineCommand(line1, 10, null, null, null),
                        new ReceiveLineCommand(line2, 9, null, null, null)),
                "req-rb")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.PUR_OVER_RECEIVE);

        assertThat(data.onHand(skuA, warehouseId, ""))
                .as("第 1 行的入库必须随事务一起回滚 —— 不允许出现半成品")
                .isZero();
        assertThat(data.onHand(skuB, warehouseId, "")).isZero();
        assertThat(inventory.checkLedgerConsistency()).isEmpty();
        PurchaseOrder after = purchaseOrders.require(o.id());
        assertThat(after.lines().get(0).receivedQty()).as("累计入库量也必须回滚").isZero();
        assertThat(after.status()).isEqualTo(DocumentStatus.APPROVED);
    }

    @Test
    @CaseId("TC-PUR-011")
    @DisplayName("同一进货单多行入库不产生流水唯一键冲突（requestId 按行拆分）")
    void receive_multiLine_shouldNotHitLedgerUniqueConstraint() {
        PurchaseOrder o = createDraft("req-po-multi", 3, 1000L, 4, 1000L);
        purchaseOrders.submit(o.id());
        purchaseOrders.approve(o.id());

        PurchaseOrder after = purchaseOrders.receive(o.id(), new ReceiveCommand(
                List.of(new ReceiveLineCommand(o.lines().get(0).id(), 3, null, null, null),
                        new ReceiveLineCommand(o.lines().get(1).id(), 4, null, null, null)),
                "req-multi"));

        assertThat(after.status()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(data.onHand(skuA, warehouseId, "")).isEqualTo(3);
        assertThat(data.onHand(skuB, warehouseId, "")).isEqualTo(4);
        assertThat(inventory.checkLedgerConsistency()).isEmpty();
    }

    @Test
    @CaseId("TC-PUR-012")
    @DisplayName("已完成的进货单不允许再入库（终态保护）")
    void receive_afterCompleted_thenRejected() {
        PurchaseOrder o = createDraft("req-po-done", 5, 1000L);
        purchaseOrders.submit(o.id());
        purchaseOrders.approve(o.id());
        long lineId = o.lines().get(0).id();
        purchaseOrders.receive(o.id(), new ReceiveCommand(
                List.of(new ReceiveLineCommand(lineId, 5, null, null, null)), "req-done-1"));

        assertThatThrownBy(() -> purchaseOrders.receive(o.id(), new ReceiveCommand(
                List.of(new ReceiveLineCommand(lineId, 1, null, null, null)), "req-done-2")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.PUR_STATUS_ILLEGAL);
    }

    @Test
    @CaseId("TC-PUR-013")
    @DisplayName("入库时指定不存在的明细行：404 语义，不产生库存变动")
    void receive_withUnknownLine_thenNotFound() {
        PurchaseOrder o = createDraft("req-po-unknown", 5, 1000L);
        purchaseOrders.submit(o.id());
        purchaseOrders.approve(o.id());

        assertThatThrownBy(() -> purchaseOrders.receive(o.id(), new ReceiveCommand(
                List.of(new ReceiveLineCommand(999_999L, 1, null, null, null)), "req-unk")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SYS_NOT_FOUND);

        assertThat(data.onHand(skuA, warehouseId, "")).isZero();
    }

    @Test
    @CaseId("TC-PUR-014")
    @DisplayName("★ 已入库单据红冲：回退实存、写 PURCHASE_REVERSAL、状态 VOIDED")
    void void_afterReceived_shouldReverseStockAndVoid() {
        PurchaseOrder o = createDraft("req-po-void", 10, 1000L);
        purchaseOrders.submit(o.id());
        purchaseOrders.approve(o.id());
        long lineId = o.lines().get(0).id();

        // 只入了一部分（PARTIAL）：红冲应只回退实际入库量，而不是单据量
        PurchaseOrder partial = purchaseOrders.receive(o.id(), new ReceiveCommand(
                List.of(new ReceiveLineCommand(lineId, 4, null, null, null)), "req-void-1"));
        assertThat(partial.status()).isEqualTo(DocumentStatus.PARTIAL);
        assertThat(data.onHand(skuA, warehouseId, "")).isEqualTo(4);

        PurchaseOrder voided = purchaseOrders.voidOrder(o.id(), "req-po-reverse");

        assertThat(voided.status()).isEqualTo(DocumentStatus.VOIDED);
        assertThat(data.onHand(skuA, warehouseId, "")).as("★ 红冲必须回退实存").isZero();
        assertThat(inventory.ledgerEntries(o.orderNo(), "PURCHASE_IN")).hasSize(1);
        assertThat(inventory.ledgerEntries(o.orderNo(), "PURCHASE_REVERSAL"))
                .as("红冲必须留下可追溯的反向流水")
                .singleElement()
                .satisfies(e -> assertThat(e.changeQty()).isEqualTo(-4));
        assertThat(inventory.checkLedgerConsistency())
                .as("红冲后全库实存 == 流水净和（INV-2）")
                .isEmpty();
    }

    @Test
    @CaseId("TC-PUR-015")
    @DisplayName("草稿作废成功；作废后不能再提交")
    void void_draft_thenCannotSubmit() {
        PurchaseOrder o = createDraft("req-po-void2", 5, 1000L);

        PurchaseOrder voided = purchaseOrders.voidOrder(o.id(), "req-po-void2");
        assertThat(voided.status()).isEqualTo(DocumentStatus.VOIDED);

        assertThatThrownBy(() -> purchaseOrders.submit(o.id()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.PUR_STATUS_ILLEGAL);
    }

    // ─────────────────────────── 辅助 ───────────────────────────

    private PurchaseOrder createDraft(String requestId, int qtyA, long priceA) {
        return createDraft(requestId, qtyA, priceA, 0, 0L);
    }

    private PurchaseOrder createDraft(String requestId, int qtyA, long priceA,
                                      int qtyB, long priceB) {
        List<CreateLineCommand> lines = new java.util.ArrayList<>();
        lines.add(new CreateLineCommand(skuA, qtyA, priceA));
        if (qtyB > 0) {
            lines.add(new CreateLineCommand(skuB, qtyB, priceB));
        }
        return purchaseOrders.create(new CreateCommand(
                supplierId, warehouseId, LocalDate.of(2025, 6, 1), "测试进货单",
                lines, requestId));
    }
}
