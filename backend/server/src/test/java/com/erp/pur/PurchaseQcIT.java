package com.erp.pur;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.pur.application.PurchaseOrderApplicationService.ReceiveCommand;
import com.erp.pur.application.PurchaseOrderApplicationService.ReceiveLineCommand;
import com.erp.pur.domain.PurchaseOrder;
import com.erp.pur.domain.PurchaseReceipt;
import com.erp.pur.domain.QcStatus;
import com.erp.pur.domain.SupplierPayable;
import com.erp.shared.document.DocumentStatus;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.support.CaseId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 到货质检（FR-PUR-05）。
 *
 * <p>核心不变量：<b>不合格数量不产生可用库存</b> ——
 * 既没有余额行、也没有库存流水，更没有应付金额。
 */
@DisplayName("采购 · 到货质检（FR-PUR-05）")
class PurchaseQcIT extends PurchaseLoopTestSupport {

    @Test
    @CaseId("TC-PUR-036")
    @DisplayName("★ 全部合格：实存/可用量增加，行 qc_status=PASSED，应付按合格金额生成")
    void receive_allPassed_entersStockAndPayable() {
        PurchaseOrder o = createApprovedOrder("req-qc-036", 10, 1000L);

        PurchaseOrder after = receive(o.id(), o.lines().get(0).id(), 10, null, "B1", "req-rcv-036");

        assertThat(after.status()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(after.lines().get(0).qcStatus()).isEqualTo(QcStatus.PASSED);
        assertThat(after.lines().get(0).qcQty()).isEqualTo(10);
        assertThat(data.onHand(skuA, warehouseId, "B1")).isEqualTo(10);
        assertThat(data.available(skuA, warehouseId, "B1")).isEqualTo(10);
        assertThat(inventory.checkLedgerConsistency()).isEmpty();

        PurchaseReceipt receipt = purchaseOrders.receipts(o.id()).get(0);
        assertThat(receipt.acceptedAmount()).isEqualTo(10_000L);
        assertThat(receipt.acceptedQty()).isEqualTo(10);
        assertThat(receipt.rejectedQty()).isZero();
        SupplierPayable p = payables.requireByReceiptId(receipt.id());
        assertThat(p.originalAmount()).isEqualTo(10_000L);
        assertThat(p.balanceAmount()).isEqualTo(10_000L);
        assertThat(p.status()).isEqualTo("OPEN");
    }

    @Test
    @CaseId("TC-PUR-037")
    @DisplayName("★ 部分合格：只有合格量入库与生成应付，不合格量不产生可用库存")
    void receive_partiallyPassed_onlyQcQtyEntersStock() {
        PurchaseOrder o = createApprovedOrder("req-qc-037", 10, 1000L);

        PurchaseOrder after = receive(o.id(), o.lines().get(0).id(), 10, 4, "B1", "req-rcv-037");

        assertThat(after.lines().get(0).receivedQty()).as("到货量含不合格").isEqualTo(10);
        assertThat(after.lines().get(0).qcQty()).isEqualTo(4);
        assertThat(after.lines().get(0).rejectedQty()).isEqualTo(6);
        assertThat(after.lines().get(0).qcStatus()).isEqualTo(QcStatus.PARTIAL);
        assertThat(data.onHand(skuA, warehouseId, "B1")).as("只有 4 件合格品入库").isEqualTo(4);
        assertThat(data.available(skuA, warehouseId, "B1")).isEqualTo(4);
        assertThat(inventory.checkLedgerConsistency()).isEmpty();

        PurchaseReceipt receipt = purchaseOrders.receipts(o.id()).get(0);
        assertThat(receipt.acceptedQty()).isEqualTo(4);
        assertThat(receipt.rejectedQty()).isEqualTo(6);
        assertThat(receipt.acceptedAmount()).isEqualTo(4_000L);
        assertThat(payables.requireByReceiptId(receipt.id()).originalAmount()).isEqualTo(4_000L);
    }

    @Test
    @CaseId("TC-PUR-038")
    @DisplayName("★ 全部不合格：不入库、不生成应付，行 qc_status=FAILED，但到货量正常关闭")
    void receive_allRejected_noStockNoPayable() {
        PurchaseOrder o = createApprovedOrder("req-qc-038", 10, 1000L);

        PurchaseOrder after = receive(o.id(), o.lines().get(0).id(), 10, 0, "B1", "req-rcv-038");

        assertThat(after.status()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(after.lines().get(0).qcStatus()).isEqualTo(QcStatus.FAILED);
        assertThat(after.lines().get(0).qcQty()).isZero();
        assertThat(after.lines().get(0).receivedQty()).isEqualTo(10);
        assertThat(data.balanceRowExists(skuA, warehouseId, "B1"))
                .as("不合格品不得创建余额行").isFalse();
        assertThat(data.ledgerCount(o.orderNo())).as("不合格品不得产生库存流水").isZero();
        Integer payablesCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM supplier_payables", Integer.class);
        assertThat(payablesCount).isZero();
        assertThat(inventory.checkLedgerConsistency()).isEmpty();
    }

    @Test
    @CaseId("TC-PUR-039")
    @DisplayName("合格数量 > 到货量：400 PUR_QC_QTY_INVALID，无任何库存/到货副作用")
    void receive_qcQtyOverReceived_thenRejected() {
        PurchaseOrder o = createApprovedOrder("req-qc-039", 5, 1000L);

        assertThatThrownBy(() -> receive(o.id(), o.lines().get(0).id(), 5, 6, null, "req-rcv-039"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.PUR_QC_QTY_INVALID);

        assertThat(data.balanceRowExists(skuA, warehouseId, "")).isFalse();
        assertThat(data.ledgerCount(o.orderNo())).isZero();
        assertThat(purchaseOrders.receipts(o.id())).isEmpty();
        assertThat(purchaseOrders.require(o.id()).lines().get(0).receivedQty()).isZero();
    }

    @Test
    @CaseId("TC-PUR-040")
    @DisplayName("合格数量为负：400 PUR_QC_QTY_INVALID，原单保持 APPROVED")
    void receive_negativeQcQty_thenRejected() {
        PurchaseOrder o = createApprovedOrder("req-qc-040", 5, 1000L);

        assertThatThrownBy(() -> receive(o.id(), o.lines().get(0).id(), 5, -1, null, "req-rcv-040"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.PUR_QC_QTY_INVALID);
        assertThat(purchaseOrders.require(o.id()).status()).isEqualTo(DocumentStatus.APPROVED);
    }

    @Test
    @CaseId("TC-PUR-041")
    @DisplayName("多行混合质检：合格行进库存，全损行不入库，应付只按合格金额")
    void receive_multiLineMixedQc() {
        PurchaseOrder o = createApprovedOrder("req-qc-041", 10, 1000L, 5, 2000L);
        long lineA = o.lines().get(0).id();
        long lineB = o.lines().get(1).id();

        PurchaseOrder after = purchaseOrders.receive(o.id(), new ReceiveCommand(List.of(
                new ReceiveLineCommand(lineA, 10, "A", null, null, 7, null),
                new ReceiveLineCommand(lineB, 5, "B", null, null, 0, "全损")),
                "req-rcv-041"));

        assertThat(after.status()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(data.onHand(skuA, warehouseId, "A")).isEqualTo(7);
        assertThat(data.available(skuA, warehouseId, "A")).isEqualTo(7);
        assertThat(data.balanceRowExists(skuB, warehouseId, "B")).isFalse();
        assertThat(data.onHand(skuB, warehouseId, "B")).isZero();

        PurchaseReceipt receipt = purchaseOrders.receipts(o.id()).get(0);
        assertThat(receipt.totalQty()).isEqualTo(15);
        assertThat(receipt.acceptedQty()).isEqualTo(7);
        assertThat(receipt.rejectedQty()).isEqualTo(8);
        assertThat(receipt.acceptedAmount()).isEqualTo(7_000L);
        assertThat(payables.requireByReceiptId(receipt.id()).originalAmount()).isEqualTo(7_000L);
        assertThat(inventory.checkLedgerConsistency()).isEmpty();
    }

    @Test
    @CaseId("TC-PUR-042")
    @DisplayName("★ 分批质检累计：5 合格 → 5 全损 → 5 合格，行状态按累计口径变 PARTIAL")
    void receive_cumulativeQcStatus() {
        PurchaseOrder o = createApprovedOrder("req-qc-042", 15, 1000L);
        long line = o.lines().get(0).id();

        receive(o.id(), line, 5, 5, "B1", "req-rcv-042a");
        assertThat(purchaseOrders.require(o.id()).lines().get(0).qcStatus())
                .isEqualTo(QcStatus.PASSED);

        receive(o.id(), line, 5, 0, "B1", "req-rcv-042b");
        PurchaseOrder afterSecond = purchaseOrders.require(o.id());
        assertThat(afterSecond.lines().get(0).qcStatus()).isEqualTo(QcStatus.PARTIAL);
        assertThat(afterSecond.lines().get(0).qcQty()).isEqualTo(5);

        receive(o.id(), line, 5, 5, "B1", "req-rcv-042c");
        PurchaseOrder afterThird = purchaseOrders.require(o.id());
        assertThat(afterThird.lines().get(0).qcStatus()).isEqualTo(QcStatus.PARTIAL);
        assertThat(afterThird.lines().get(0).qcQty()).isEqualTo(10);
        assertThat(afterThird.lines().get(0).receivedQty()).isEqualTo(15);
        assertThat(data.onHand(skuA, warehouseId, "B1")).isEqualTo(10);
        assertThat(data.available(skuA, warehouseId, "B1")).isEqualTo(10);
        assertThat(inventory.checkLedgerConsistency()).isEmpty();
    }

    @Test
    @CaseId("TC-PUR-043")
    @DisplayName("入库质检幂等：同 X-Request-Id 重放不重复入库/应付/到货记录")
    void receive_sameRequestId_isIdempotent() {
        PurchaseOrder o = createApprovedOrder("req-qc-043", 10, 1000L);
        long line = o.lines().get(0).id();

        PurchaseOrder first = receive(o.id(), line, 10, 4, "B1", "req-rcv-043");
        PurchaseOrder replay = receive(o.id(), line, 10, 4, "B1", "req-rcv-043");

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(data.onHand(skuA, warehouseId, "B1")).isEqualTo(4);
        assertThat(purchaseOrders.receipts(o.id())).hasSize(1);
        Integer payablesCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM supplier_payables", Integer.class);
        assertThat(payablesCount).isEqualTo(1);
    }

    @Test
    @CaseId("TC-PUR-044")
    @DisplayName("到货记录查询：返回批次、到货量、合格量、不合格量与质检状态")
    void receiptsQuery_returnsQcDetails() {
        PurchaseOrder o = createApprovedOrder("req-qc-044", 10, 1000L);
        receive(o.id(), o.lines().get(0).id(), 6, 4, "B-44", "req-rcv-044");

        List<PurchaseReceipt> receipts = purchaseOrders.receipts(o.id());
        assertThat(receipts).singleElement().satisfies(r -> {
            assertThat(r.receiptNo()).matches("GR\\d{8}-\\d{4,}");
            assertThat(r.lines()).singleElement().satisfies(l -> {
                assertThat(l.batchNo()).isEqualTo("B-44");
                assertThat(l.receivedQty()).isEqualTo(6);
                assertThat(l.qcQty()).isEqualTo(4);
                assertThat(l.rejectedQty()).isEqualTo(2);
                assertThat(l.qcStatus()).isEqualTo(QcStatus.PARTIAL);
                assertThat(l.qcNote()).isEqualTo("质检不合格");
            });
        });
    }

    @Test
    @CaseId("TC-PUR-045")
    @DisplayName("超量到货仍拒绝：不生成到货单、不改累计到货量")
    void receive_overQty_noReceipt() {
        PurchaseOrder o = createApprovedOrder("req-qc-045", 5, 1000L);

        assertThatThrownBy(() -> receive(o.id(), o.lines().get(0).id(), 6, null, null, "req-rcv-045"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.PUR_OVER_RECEIVE);

        assertThat(purchaseOrders.receipts(o.id())).isEmpty();
        assertThat(purchaseOrders.require(o.id()).lines().get(0).receivedQty()).isZero();
    }

    @Test
    @CaseId("TC-PUR-046")
    @DisplayName("同一次入库重复提交同一明细行：400，整批回滚")
    void receive_duplicateLineInSameRequest_thenRejected() {
        PurchaseOrder o = createApprovedOrder("req-qc-046", 10, 1000L);
        long line = o.lines().get(0).id();

        assertThatThrownBy(() -> purchaseOrders.receive(o.id(), new ReceiveCommand(List.of(
                new ReceiveLineCommand(line, 2, null, null, null, 2, null),
                new ReceiveLineCommand(line, 3, null, null, null, 3, null)),
                "req-rcv-046")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SYS_VALIDATION_ERROR);

        assertThat(data.onHand(skuA, warehouseId, "")).isZero();
        assertThat(purchaseOrders.require(o.id()).lines().get(0).receivedQty()).isZero();
    }
}
