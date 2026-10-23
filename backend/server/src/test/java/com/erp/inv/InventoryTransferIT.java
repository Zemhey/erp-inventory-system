package com.erp.inv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.inv.application.InventoryApplicationService;
import com.erp.inv.application.InventoryApplicationService.ReserveCommand;
import com.erp.inv.application.InventoryOperationsApplicationService;
import com.erp.inv.application.InventoryOperationsApplicationService.TransferLineCommand;
import com.erp.inv.application.InventoryOperationsApplicationService.TransferResult;
import com.erp.inv.domain.InventoryTransfer;
import com.erp.inv.domain.TransferLine;
import com.erp.inv.domain.TransferStatus;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.support.CaseId;
import com.erp.support.PostgresTestBase;
import com.erp.support.TestDataFactory;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 仓间调拨（FR-INV-07）集成测试。
 *
 * <p><b>核心断言是不变量 INV-7，而不是硬编码数量</b>：
 * 每张调拨单都校验 {@code 源仓流水 + 在途量 + 目的仓流水 == 0}。
 * 这个式子对"只发运"、"已到货"、"多批次"同样成立，
 * 所以它证明的是守恒本身，而不是某次实现的算术结果。
 */
@SpringBootTest
@DisplayName("库存 · 仓间调拨（FR-INV-07）")
class InventoryTransferIT extends PostgresTestBase {

    @Autowired
    private InventoryApplicationService inventory;

    @Autowired
    private InventoryOperationsApplicationService operations;

    @Autowired
    private JdbcTemplate jdbc;

    private TestDataFactory data;
    private long warehouseA;
    private long warehouseB;
    private long skuId;
    private long sku2Id;
    private final AtomicInteger seq = new AtomicInteger(1);

    @BeforeEach
    void setUp() {
        data = new TestDataFactory(jdbc);
        data.truncateAll();
        warehouseA = data.warehouse("WH-TR-A");
        warehouseB = data.warehouse("WH-TR-B");
        long productId = data.product("P-TRANSFER", "调拨测试商品");
        skuId = data.sku(productId, "白色", "L");
        sku2Id = data.sku(productId, "黑色", "L");
    }

    private String req() {
        return "req-tr-" + seq.getAndIncrement();
    }

    private void stock(long warehouseId, long sku, String batch, LocalDate expiry, int qty) {
        inventory.receiveIn(sku, warehouseId, batch, null, expiry, qty,
                "PURCHASE_IN", "PO-STOCK-" + seq.getAndIncrement(), null, 1000L, req());
    }

    private List<TransferLineCommand> lines(TransferLineCommand... commands) {
        return List.of(commands);
    }

    private TransferLineCommand line(long sku, String batch, int qty) {
        return new TransferLineCommand(sku, batch, qty, null);
    }

    private TransferResult create(long from, long to, List<TransferLineCommand> lines) {
        return operations.createTransfer(from, to, null, null, lines, req());
    }

    private int ledgerCount(String bizType) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_ledger WHERE biz_type = ?", Integer.class, bizType);
        return n == null ? 0 : n;
    }

    // ─────────────────────────── 两段式与守恒 ───────────────────────────

    @Test
    @CaseId("TC-INV-017")
    @DisplayName("建单即调出：源仓实存减少、在途量增加，守恒式（INV-7）为 0")
    void createTransfer_shouldMoveStockToInTransitAndKeepConservation() {
        stock(warehouseA, skuId, "B1", LocalDate.of(2026, 1, 1), 10);

        TransferResult result = create(warehouseA, warehouseB, lines(line(skuId, "B1", 4)));

        InventoryTransfer transfer = result.transfer();
        assertThat(result.duplicate()).isFalse();
        assertThat(transfer.status()).isEqualTo(TransferStatus.IN_TRANSIT);
        assertThat(transfer.totalInTransitQty()).isEqualTo(4);
        assertThat(transfer.lines()).singleElement()
                .extracting(TransferLine::inTransitQty).isEqualTo(4);
        assertThat(transfer.transferNo()).startsWith("TO");

        assertThat(data.onHand(skuId, warehouseA, "B1")).isEqualTo(6);
        assertThat(data.onHand(skuId, warehouseB, "B1")).isZero();
        assertThat(operations.transferConservationDiff(transfer.id()))
                .as("不变量 INV-7：调出 + 在途 + 调入 == 0")
                .isZero();
        assertThat(data.ledgerCountByType(transfer.transferNo(), "TRANSFER_OUT")).isEqualTo(1);
        assertThat(data.ledgerCountByType(transfer.transferNo(), "TRANSFER_IN")).isZero();
    }

    @Test
    @CaseId("TC-INV-018")
    @DisplayName("到货：在途归零、目的仓实存增加，守恒式仍为 0")
    void receiveTransfer_shouldLandStockAndKeepConservation() {
        stock(warehouseA, skuId, "B1", LocalDate.of(2026, 1, 1), 10);
        InventoryTransfer created = create(warehouseA, warehouseB,
                lines(line(skuId, "B1", 4))).transfer();

        TransferResult received = operations.receiveTransfer(created.id(), req());

        InventoryTransfer transfer = received.transfer();
        assertThat(received.duplicate()).isFalse();
        assertThat(transfer.status()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(transfer.totalInTransitQty()).isZero();
        assertThat(transfer.receivedAt()).isNotNull();
        assertThat(data.onHand(skuId, warehouseA, "B1")).isEqualTo(6);
        assertThat(data.onHand(skuId, warehouseB, "B1")).isEqualTo(4);
        assertThat(operations.transferConservationDiff(transfer.id())).isZero();
        assertThat(data.ledgerCountByType(transfer.transferNo(), "TRANSFER_IN")).isEqualTo(1);
    }

    @Test
    @CaseId("TC-INV-019")
    @DisplayName("多张调拨单交错到货：每张单的守恒式各自为 0")
    void multipleTransfers_shouldEachKeepConservation() {
        stock(warehouseA, skuId, "B1", null, 20);
        InventoryTransfer first = create(warehouseA, warehouseB,
                lines(line(skuId, "B1", 3))).transfer();
        InventoryTransfer second = create(warehouseA, warehouseB,
                lines(line(skuId, "B1", 5))).transfer();

        operations.receiveTransfer(second.id(), req());

        assertThat(operations.transferConservationDiff(first.id())).isZero();
        assertThat(operations.transferConservationDiff(second.id())).isZero();
        assertThat(data.onHand(skuId, warehouseA, "B1")).isEqualTo(12);
        assertThat(data.onHand(skuId, warehouseB, "B1")).isEqualTo(5);
    }

    @Test
    @CaseId("TC-INV-020")
    @DisplayName("源仓可用量不足：整单拒绝，不建单、不产生调出流水")
    void createTransfer_whenInsufficient_thenRejectWithoutSideEffect() {
        stock(warehouseA, skuId, "B1", null, 3);

        assertThatThrownBy(() -> create(warehouseA, warehouseB, lines(line(skuId, "B1", 5))))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.INV_INSUFFICIENT_STOCK)
                .hasMessageContaining("需要调出 5");

        assertThat(data.onHand(skuId, warehouseA, "B1")).isEqualTo(3);
        assertThat(ledgerCount("TRANSFER_OUT")).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_transfers", Integer.class)).isZero();
    }

    @Test
    @CaseId("TC-INV-021")
    @DisplayName("调出仓与调入仓相同：参数校验拒绝")
    void createTransfer_withSameWarehouse_thenReject() {
        assertThatThrownBy(() -> create(warehouseA, warehouseA, lines(line(skuId, "", 1))))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SYS_VALIDATION_ERROR)
                .hasMessageContaining("不能相同");
    }

    @Test
    @CaseId("TC-INV-022")
    @DisplayName("同一 X-Request-Id 重复建调拨：只扣一次、返回首次结果（INV-9）")
    void createTransfer_withSameRequestId_shouldBeIdempotent() {
        stock(warehouseA, skuId, "B1", null, 10);
        String requestId = req();

        InventoryTransfer first = operations.createTransfer(warehouseA, warehouseB, null, null,
                lines(line(skuId, "B1", 4)), requestId).transfer();
        TransferResult replay = operations.createTransfer(warehouseA, warehouseB, null, null,
                lines(line(skuId, "B1", 3)), requestId);

        assertThat(replay.duplicate()).isTrue();
        assertThat(replay.transfer().id()).isEqualTo(first.id());
        assertThat(data.onHand(skuId, warehouseA, "B1"))
                .as("重放不得再次扣减")
                .isEqualTo(6);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_transfers", Integer.class)).isEqualTo(1);
        assertThat(ledgerCount("TRANSFER_OUT")).isEqualTo(1);
    }

    @Test
    @CaseId("TC-INV-023")
    @DisplayName("同键重复到货：返回首次结果，不重复增加目的仓库存")
    void receiveTransfer_withSameRequestId_shouldBeIdempotent() {
        stock(warehouseA, skuId, "B1", null, 10);
        InventoryTransfer transfer = create(warehouseA, warehouseB,
                lines(line(skuId, "B1", 4))).transfer();
        String receiveRequest = req();

        operations.receiveTransfer(transfer.id(), receiveRequest);
        TransferResult replay = operations.receiveTransfer(transfer.id(), receiveRequest);

        assertThat(replay.duplicate()).isTrue();
        assertThat(data.onHand(skuId, warehouseB, "B1")).isEqualTo(4);
        assertThat(data.ledgerCountByType(transfer.transferNo(), "TRANSFER_IN")).isEqualTo(1);
    }

    @Test
    @CaseId("TC-INV-024")
    @DisplayName("已到货的单换一个幂等键再次到货：状态非法，库存不变")
    void receiveTransfer_afterCompletedWithDifferentKey_thenIllegal() {
        stock(warehouseA, skuId, "B1", null, 10);
        InventoryTransfer transfer = create(warehouseA, warehouseB,
                lines(line(skuId, "B1", 4))).transfer();
        operations.receiveTransfer(transfer.id(), req());

        assertThatThrownBy(() -> operations.receiveTransfer(transfer.id(), req()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.INV_TRANSFER_STATUS_ILLEGAL);
        assertThat(data.onHand(skuId, warehouseB, "B1")).isEqualTo(4);
    }

    @Test
    @CaseId("TC-INV-025")
    @DisplayName("调拨单不存在：404 语义")
    void requireTransfer_whenMissing_thenNotFound() {
        assertThatThrownBy(() -> operations.requireTransfer(999_999))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.INV_TRANSFER_NOT_FOUND);
    }

    // ─────────────────────────── 多行 / 批次 / 占用 ───────────────────────────

    @Test
    @CaseId("TC-INV-026")
    @DisplayName("多 SKU 多批次调拨：逐行守恒，目的仓按原批次落账")
    void createTransfer_multiLineMultiBatch_shouldKeepConservation() {
        stock(warehouseA, skuId, "B1", null, 5);
        stock(warehouseA, skuId, "B2", null, 6);
        stock(warehouseA, sku2Id, "B1", null, 4);

        InventoryTransfer transfer = create(warehouseA, warehouseB, lines(
                line(skuId, "B1", 3), line(skuId, "B2", 2), line(sku2Id, "B1", 4))).transfer();
        assertThat(operations.transferConservationDiff(transfer.id())).isZero();

        operations.receiveTransfer(transfer.id(), req());

        assertThat(data.onHand(skuId, warehouseA, "B1")).isEqualTo(2);
        assertThat(data.onHand(skuId, warehouseA, "B2")).isEqualTo(4);
        assertThat(data.onHand(skuId, warehouseB, "B1")).isEqualTo(3);
        assertThat(data.onHand(skuId, warehouseB, "B2")).isEqualTo(2);
        assertThat(data.onHand(sku2Id, warehouseB, "B1")).isEqualTo(4);
        assertThat(operations.transferConservationDiff(transfer.id())).isZero();
    }

    @Test
    @CaseId("TC-INV-027")
    @DisplayName("冻结量不可调拨：可用量不足时拒绝")
    void createTransfer_shouldNotTouchFrozenStock() {
        stock(warehouseA, skuId, "B1", null, 10);
        operations.createFreeze(skuId, warehouseA, "B1", 5, "质检冻结", null, req());

        assertThatThrownBy(() -> create(warehouseA, warehouseB, lines(line(skuId, "B1", 6))))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.INV_INSUFFICIENT_STOCK);

        TransferResult ok = create(warehouseA, warehouseB, lines(line(skuId, "B1", 5)));
        assertThat(ok.transfer().status()).isEqualTo(TransferStatus.IN_TRANSIT);
        assertThat(data.frozen(skuId, warehouseA, "B1")).isEqualTo(5);
    }

    @Test
    @CaseId("TC-INV-028")
    @DisplayName("已预占的量不可调拨：扣减后不得低于预占量")
    void createTransfer_shouldNotBreakReservation() {
        stock(warehouseA, skuId, "B1", null, 10);
        inventory.reserve(new ReserveCommand(skuId, warehouseA, "B1", 4, req()));

        assertThatThrownBy(() -> create(warehouseA, warehouseB, lines(line(skuId, "B1", 7))))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.INV_INSUFFICIENT_STOCK);

        TransferResult ok = create(warehouseA, warehouseB, lines(line(skuId, "B1", 6)));
        assertThat(ok.transfer().status()).isEqualTo(TransferStatus.IN_TRANSIT);
        assertThat(data.onHand(skuId, warehouseA, "B1")).isEqualTo(4);
        assertThat(data.reserved(skuId, warehouseA, "B1")).isEqualTo(4);
    }

    @Test
    @CaseId("TC-INV-029")
    @DisplayName("批次生产日期/效期随调拨带到目的仓（FEFO 不丢效期）")
    void receiveTransfer_shouldCarryBatchDatesToDestination() {
        inventory.receiveIn(skuId, warehouseA, "B-EXP", LocalDate.of(2025, 1, 1),
                LocalDate.of(2026, 3, 1), 8, "PURCHASE_IN", "PO-EXP", null, 1000L, req());
        InventoryTransfer transfer = create(warehouseA, warehouseB,
                lines(line(skuId, "B-EXP", 3))).transfer();

        operations.receiveTransfer(transfer.id(), req());

        assertThat(jdbc.queryForObject("""
                SELECT expiry_date FROM inventory_balances
                 WHERE sku_id = ? AND warehouse_id = ? AND batch_no = 'B-EXP'
                """, LocalDate.class, skuId, warehouseB))
                .isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(jdbc.queryForObject("""
                SELECT production_date FROM inventory_balances
                 WHERE sku_id = ? AND warehouse_id = ? AND batch_no = 'B-EXP'
                """, LocalDate.class, skuId, warehouseB))
                .isEqualTo(LocalDate.of(2025, 1, 1));
    }

    @Test
    @CaseId("TC-INV-030")
    @DisplayName("调拨列表：状态与仓库筛选、分页字段正确")
    void pageTransfers_shouldFilterByStatusAndWarehouse() {
        stock(warehouseA, skuId, "B1", null, 20);
        InventoryTransfer inTransit = create(warehouseA, warehouseB,
                lines(line(skuId, "B1", 2))).transfer();
        InventoryTransfer completed = create(warehouseA, warehouseB,
                lines(line(skuId, "B1", 3))).transfer();
        operations.receiveTransfer(completed.id(), req());

        var byStatus = operations.pageTransfers(TransferStatus.IN_TRANSIT, null, null, 1, 20);
        assertThat(byStatus.total()).isEqualTo(1);
        assertThat(((InventoryTransfer) byStatus.items().get(0)).id()).isEqualTo(inTransit.id());

        var byFrom = operations.pageTransfers(null, warehouseA, null, 1, 20);
        assertThat(byFrom.total()).isEqualTo(2);

        var byTo = operations.pageTransfers(null, null, warehouseB, 1, 20);
        assertThat(byTo.total()).isEqualTo(2);

        var firstPage = operations.pageTransfers(null, null, null, 1, 1);
        assertThat(firstPage.items()).hasSize(1);
        assertThat(firstPage.page()).isEqualTo(1);
        assertThat(firstPage.size()).isEqualTo(1);
    }

    @Test
    @CaseId("TC-INV-031")
    @DisplayName("调拨数量必须为正、明细不得为空：参数校验拒绝")
    void createTransfer_withInvalidQty_thenReject() {
        stock(warehouseA, skuId, "B1", null, 10);

        assertThatThrownBy(() -> create(warehouseA, warehouseB, lines(line(skuId, "B1", 0))))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SYS_VALIDATION_ERROR)
                .hasMessageContaining("数量必须为正");

        assertThatThrownBy(() -> create(warehouseA, warehouseB, List.of()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SYS_VALIDATION_ERROR)
                .hasMessageContaining("至少需要一行");
    }

    @Test
    @CaseId("TC-INV-032")
    @DisplayName("同一 SKU + 批次重复出现：拒绝，避免流水唯一键冲突")
    void createTransfer_withDuplicateSkuBatch_thenReject() {
        assertThatThrownBy(() -> create(warehouseA, warehouseB, lines(
                line(skuId, "B1", 1), line(skuId, "B1", 2))))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SYS_VALIDATION_ERROR)
                .hasMessageContaining("重复");
    }

    @Test
    @CaseId("TC-INV-033")
    @DisplayName("源批次没有余额行：明确报库存不存在，而不是写成负库存")
    void createTransfer_whenSourceBatchMissing_thenNotFound() {
        assertThatThrownBy(() -> create(warehouseA, warehouseB, lines(line(skuId, "NO-SUCH", 1))))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SYS_NOT_FOUND)
                .hasMessageContaining("NO-SUCH");

        assertThat(ledgerCount("TRANSFER_OUT")).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_transfers", Integer.class)).isZero();
    }
}
