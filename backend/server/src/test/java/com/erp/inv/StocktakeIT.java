package com.erp.inv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.inv.application.InventoryApplicationService;
import com.erp.inv.application.InventoryApplicationService.ReserveCommand;
import com.erp.inv.application.InventoryOperationsApplicationService;
import com.erp.inv.application.InventoryOperationsApplicationService.StocktakeLineCommand;
import com.erp.inv.application.InventoryOperationsApplicationService.StocktakeResult;
import com.erp.inv.domain.Stocktake;
import com.erp.inv.domain.StocktakeLine;
import com.erp.inv.domain.StocktakeStatus;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.support.CaseId;
import com.erp.support.PostgresTestBase;
import com.erp.support.TestDataFactory;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 盘点与差异过账（FR-INV-08）集成测试。
 *
 * <p>守两条硬规则：
 * <ol>
 *   <li>差异必须落成 {@code ADJUST_IN/ADJUST_OUT} 流水（INV-2 不漂移）；</li>
 *   <li>差异为 0 的行<b>不得</b>产生过账流水；整单全 0 直接拒绝过账。</li>
 * </ol>
 */
@SpringBootTest
@DisplayName("库存 · 盘点与差异过账（FR-INV-08）")
class StocktakeIT extends PostgresTestBase {

    @Autowired
    private InventoryApplicationService inventory;

    @Autowired
    private InventoryOperationsApplicationService operations;

    @Autowired
    private JdbcTemplate jdbc;

    private TestDataFactory data;
    private long warehouseId;
    private long skuId;
    private long sku2Id;
    private final AtomicInteger seq = new AtomicInteger(1);

    @BeforeEach
    void setUp() {
        data = new TestDataFactory(jdbc);
        data.truncateAll();
        warehouseId = data.warehouse("WH-ST");
        long productId = data.product("P-STOCKTAKE", "盘点测试商品");
        skuId = data.sku(productId, "白色", "L");
        sku2Id = data.sku(productId, "黑色", "L");
    }

    private String req() {
        return "req-st-" + seq.getAndIncrement();
    }

    private void stock(long sku, String batch, int qty) {
        inventory.receiveIn(sku, warehouseId, batch, null, null, qty,
                "PURCHASE_IN", "PO-ST-" + seq.getAndIncrement(), null, 1000L, req());
    }

    private StocktakeLineCommand line(long sku, String batch, int countedQty) {
        return new StocktakeLineCommand(sku, batch, countedQty);
    }

    private StocktakeResult create(List<StocktakeLineCommand> lines) {
        return operations.createStocktake(warehouseId, null, null, lines, req());
    }

    private int adjustLedgerCount(String stocktakeNo) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM inventory_ledger
                 WHERE biz_ref_no = ? AND biz_type IN ('ADJUST_IN','ADJUST_OUT')
                """, Integer.class, stocktakeNo);
        return n == null ? 0 : n;
    }

    // ─────────────────────────── 建单快照 ───────────────────────────

    @Test
    @CaseId("TC-INV-034")
    @DisplayName("建盘点单：账面量在此时快照，状态 DRAFT，差异由数据库生成列计算")
    void createStocktake_shouldSnapshotBookQty() {
        stock(skuId, "B1", 10);

        Stocktake stocktake = create(List.of(line(skuId, "B1", 7))).stocktake();

        assertThat(stocktake.status()).isEqualTo(StocktakeStatus.DRAFT);
        assertThat(stocktake.stocktakeNo()).startsWith("ST");
        assertThat(stocktake.postedAt()).isNull();
        assertThat(stocktake.lines()).singleElement().satisfies(l -> {
            assertThat(l.bookQty()).isEqualTo(10);
            assertThat(l.countedQty()).isEqualTo(7);
            assertThat(l.diffQty()).isEqualTo(-3);
        });

        // 建单只是快照，不改变库存
        assertThat(data.onHand(skuId, warehouseId, "B1")).isEqualTo(10);
        assertThat(adjustLedgerCount(stocktake.stocktakeNo())).isZero();
    }

    // ─────────────────────────── 差异过账 ───────────────────────────

    @Test
    @CaseId("TC-INV-035")
    @DisplayName("盘盈：过账生成 ADJUST_IN 正流水并更新实存")
    void postStocktake_withPositiveDiff_shouldWriteAdjustIn() {
        stock(skuId, "B1", 10);
        Stocktake stocktake = create(List.of(line(skuId, "B1", 13))).stocktake();

        Stocktake posted = operations.postStocktake(stocktake.id(), req()).stocktake();

        assertThat(posted.status()).isEqualTo(StocktakeStatus.POSTED);
        assertThat(posted.postedAt()).isNotNull();
        assertThat(data.onHand(skuId, warehouseId, "B1")).isEqualTo(13);
        assertThat(data.ledgerCountByType(posted.stocktakeNo(), "ADJUST_IN")).isEqualTo(1);
        assertThat(inventory.checkLedgerConsistency())
                .as("INV-2：盘盈必须同时有流水")
                .isEmpty();
    }

    @Test
    @CaseId("TC-INV-036")
    @DisplayName("盘亏：过账生成 ADJUST_OUT 负流水并更新实存")
    void postStocktake_withNegativeDiff_shouldWriteAdjustOut() {
        stock(skuId, "B1", 10);
        Stocktake stocktake = create(List.of(line(skuId, "B1", 6))).stocktake();

        operations.postStocktake(stocktake.id(), req());

        assertThat(data.onHand(skuId, warehouseId, "B1")).isEqualTo(6);
        assertThat(data.ledgerCountByType(stocktake.stocktakeNo(), "ADJUST_OUT")).isEqualTo(1);
        assertThat(inventory.checkLedgerConsistency()).isEmpty();
    }

    @Test
    @CaseId("TC-INV-037")
    @DisplayName("★ 差异为 0 的行不产生过账流水，有差异的行照常过账")
    void postStocktake_zeroDiffLine_shouldNotWriteLedger() {
        stock(skuId, "B1", 10);
        stock(sku2Id, "B1", 5);
        Stocktake stocktake = create(List.of(
                line(skuId, "B1", 12),   // +2
                line(sku2Id, "B1", 5)    // 0
        )).stocktake();

        operations.postStocktake(stocktake.id(), req());

        assertThat(data.onHand(skuId, warehouseId, "B1")).isEqualTo(12);
        assertThat(data.onHand(sku2Id, warehouseId, "B1")).isEqualTo(5);
        assertThat(data.ledgerCountByType(stocktake.stocktakeNo(), "ADJUST_IN"))
                .as("只有差异非 0 的行才产生流水")
                .isEqualTo(1);
        assertThat(data.ledgerCountByType(stocktake.stocktakeNo(), "ADJUST_OUT")).isZero();
        assertThat(inventory.checkLedgerConsistency()).isEmpty();
    }

    @Test
    @CaseId("TC-INV-038")
    @DisplayName("全部行差异为 0：拒绝过账，状态留在 DRAFT，不产生任何调整流水")
    void postStocktake_whenNoDifference_thenReject() {
        stock(skuId, "B1", 10);
        Stocktake stocktake = create(List.of(line(skuId, "B1", 10))).stocktake();

        assertThatThrownBy(() -> operations.postStocktake(stocktake.id(), req()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.INV_STOCKTAKE_NO_DIFFERENCE);

        assertThat(operations.requireStocktake(stocktake.id()).status())
                .isEqualTo(StocktakeStatus.DRAFT);
        assertThat(adjustLedgerCount(stocktake.stocktakeNo())).isZero();
        assertThat(data.onHand(skuId, warehouseId, "B1")).isEqualTo(10);
    }

    // ─────────────────────────── 幂等与状态 ───────────────────────────

    @Test
    @CaseId("TC-INV-039")
    @DisplayName("同键重复建盘点单：返回首次结果，不重复建单（INV-9）")
    void createStocktake_withSameRequestId_shouldBeIdempotent() {
        stock(skuId, "B1", 10);
        String requestId = req();

        Stocktake first = operations.createStocktake(warehouseId, null, null,
                List.of(line(skuId, "B1", 8)), requestId).stocktake();
        StocktakeResult replay = operations.createStocktake(warehouseId, null, null,
                List.of(line(skuId, "B1", 9)), requestId);

        assertThat(replay.duplicate()).isTrue();
        assertThat(replay.stocktake().id()).isEqualTo(first.id());
        assertThat(replay.stocktake().lines().get(0).countedQty()).isEqualTo(8);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM stocktakes", Integer.class)).isEqualTo(1);
    }

    @Test
    @CaseId("TC-INV-040")
    @DisplayName("同键重复过账：返回首次结果，库存只调整一次")
    void postStocktake_withSameRequestId_shouldBeIdempotent() {
        stock(skuId, "B1", 10);
        Stocktake stocktake = create(List.of(line(skuId, "B1", 13))).stocktake();
        String postRequest = req();

        operations.postStocktake(stocktake.id(), postRequest);
        StocktakeResult replay = operations.postStocktake(stocktake.id(), postRequest);

        assertThat(replay.duplicate()).isTrue();
        assertThat(data.onHand(skuId, warehouseId, "B1")).isEqualTo(13);
        assertThat(data.ledgerCountByType(stocktake.stocktakeNo(), "ADJUST_IN")).isEqualTo(1);
    }

    @Test
    @CaseId("TC-INV-041")
    @DisplayName("已过账的盘点单换键再过账：状态非法")
    void postStocktake_afterPostedWithDifferentKey_thenIllegal() {
        stock(skuId, "B1", 10);
        Stocktake stocktake = create(List.of(line(skuId, "B1", 12))).stocktake();
        operations.postStocktake(stocktake.id(), req());

        assertThatThrownBy(() -> operations.postStocktake(stocktake.id(), req()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.INV_STOCKTAKE_STATUS_ILLEGAL);
        assertThat(data.onHand(skuId, warehouseId, "B1")).isEqualTo(12);
    }

    @Test
    @CaseId("TC-INV-042")
    @DisplayName("盘亏会把实存压到预占以下：整单回滚，状态退回 DRAFT")
    void postStocktake_whenShrinkBelowReserved_thenRollback() {
        stock(skuId, "B1", 10);
        inventory.reserve(new ReserveCommand(skuId, warehouseId, "B1", 8, req()));
        Stocktake stocktake = create(List.of(line(skuId, "B1", 1))).stocktake();

        assertThatThrownBy(() -> operations.postStocktake(stocktake.id(), req()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.INV_NEGATIVE_NOT_ALLOWED);

        assertThat(operations.requireStocktake(stocktake.id()).status())
                .isEqualTo(StocktakeStatus.DRAFT);
        assertThat(data.onHand(skuId, warehouseId, "B1")).isEqualTo(10);
        assertThat(data.reserved(skuId, warehouseId, "B1")).isEqualTo(8);
        assertThat(adjustLedgerCount(stocktake.stocktakeNo())).isZero();
    }

    @Test
    @CaseId("TC-INV-043")
    @DisplayName("盘盈盘亏混合过账后，INV-2（实存 == 流水净和）仍成立")
    void postStocktake_shouldKeepLedgerConsistency() {
        stock(skuId, "B1", 10);
        stock(sku2Id, "B2", 7);
        Stocktake stocktake = create(List.of(
                line(skuId, "B1", 14),
                line(sku2Id, "B2", 3)
        )).stocktake();

        operations.postStocktake(stocktake.id(), req());

        assertThat(data.onHand(skuId, warehouseId, "B1")).isEqualTo(14);
        assertThat(data.onHand(sku2Id, warehouseId, "B2")).isEqualTo(3);
        assertThat(inventory.checkLedgerConsistency()).isEmpty();
    }

    @Test
    @CaseId("TC-INV-044")
    @DisplayName("盘点单不存在：404 语义")
    void requireStocktake_whenMissing_thenNotFound() {
        assertThatThrownBy(() -> operations.requireStocktake(999_999))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.INV_STOCKTAKE_NOT_FOUND);
    }

    @Test
    @CaseId("TC-INV-045")
    @DisplayName("实盘数量为负：参数校验拒绝")
    void createStocktake_withNegativeCountedQty_thenReject() {
        assertThatThrownBy(() -> create(List.of(line(skuId, "B1", -1))))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SYS_VALIDATION_ERROR)
                .hasMessageContaining("不能为负");
    }

    @Test
    @CaseId("TC-INV-046")
    @DisplayName("同一 SKU + 批次重复盘点：拒绝")
    void createStocktake_withDuplicateSkuBatch_thenReject() {
        assertThatThrownBy(() -> create(List.of(
                line(skuId, "B1", 1), line(skuId, "B1", 2))))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SYS_VALIDATION_ERROR)
                .hasMessageContaining("重复");
    }

    @Test
    @CaseId("TC-INV-047")
    @DisplayName("盘点列表：状态与仓库筛选生效")
    void pageStocktakes_shouldFilterByStatusAndWarehouse() {
        stock(skuId, "B1", 10);
        Stocktake draft = create(List.of(line(skuId, "B1", 9))).stocktake();
        Stocktake posted = create(List.of(line(skuId, "B1", 11))).stocktake();
        operations.postStocktake(posted.id(), req());

        assertThat(operations.pageStocktakes(StocktakeStatus.DRAFT, null, 1, 20).total())
                .isEqualTo(1);
        assertThat(operations.pageStocktakes(StocktakeStatus.POSTED, null, 1, 20).total())
                .isEqualTo(1);
        assertThat(operations.pageStocktakes(null, warehouseId, 1, 20).total()).isEqualTo(2);
        assertThat(((Stocktake) operations.pageStocktakes(null, warehouseId, 1, 1)
                .items().get(0)).lines()).isNotEmpty();
        assertThat(draft.id()).isNotEqualTo(posted.id());
    }
}
