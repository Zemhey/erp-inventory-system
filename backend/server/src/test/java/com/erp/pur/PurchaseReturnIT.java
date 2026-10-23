package com.erp.pur;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.pur.application.PurchaseReturnService;
import com.erp.pur.application.PurchaseReturnService.CreateCommand;
import com.erp.pur.application.PurchaseReturnService.CreateLineCommand;
import com.erp.pur.application.SupplierPayableService;
import com.erp.pur.domain.PurchaseOrder;
import com.erp.pur.domain.PurchaseReturn;
import com.erp.pur.domain.PurchaseReturnStatus;
import com.erp.pur.domain.SupplierPayable;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.support.CaseId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 采购退货（FR-PUR-06）：冲减库存与应付。
 *
 * <p>关键断言：草稿零副作用；执行时库存负流水 + 应付退货额同增；
 * 超退/超应付/库存不足都必须整单回滚（不留半成品）。
 */
@DisplayName("采购 · 退货冲减库存与应付（FR-PUR-06）")
class PurchaseReturnIT extends PurchaseLoopTestSupport {

    private static final long PRICE = 1000L;

    private PurchaseOrder receivedOrder(String requestId, int qty, int qcQty) {
        PurchaseOrder o = createApprovedOrder(requestId, qty, PRICE);
        return receive(o.id(), o.lines().get(0).id(), qty, qcQty, "B-RET", requestId + "-rcv");
    }

    private PurchaseReturn createDraft(PurchaseOrder o, long receiptLineId, int qty, String requestId) {
        return returns.create(new CreateCommand(o.id(), BIZ_DATE, "退货测试",
                List.of(new CreateLineCommand(receiptLineId, qty)), requestId));
    }

    @Test
    @CaseId("TC-PUR-047")
    @DisplayName("建退货草稿：不扣库存、不冲应付、不写退货流水")
    void createDraft_hasNoSideEffects() {
        PurchaseOrder o = receivedOrder("req-ret-047", 10, 10);
        long receiptLineId = latestReceiptLineId(o.id());
        long payableId = payableIdOfReceipt(latestReceiptId(o.id()));

        PurchaseReturn r = createDraft(o, receiptLineId, 3, "req-ret-047-create");

        assertThat(r.status()).isEqualTo(PurchaseReturnStatus.DRAFT);
        assertThat(r.totalQty()).isEqualTo(3);
        assertThat(r.totalAmount()).isEqualTo(3 * PRICE);
        assertThat(data.onHand(skuA, warehouseId, "B-RET")).isEqualTo(10);
        assertThat(inventory.ledgerEntries(r.returnNo(), "PURCHASE_RETURN")).isEmpty();
        SupplierPayable p = payables.require(payableId);
        assertThat(p.returnedAmount()).isZero();
        assertThat(p.balanceAmount()).isEqualTo(10 * PRICE);
    }

    @Test
    @CaseId("TC-PUR-048")
    @DisplayName("建退货草稿幂等：同 requestId 返回同一张单")
    void createDraft_isIdempotent() {
        PurchaseOrder o = receivedOrder("req-ret-048", 10, 10);
        long receiptLineId = latestReceiptLineId(o.id());

        PurchaseReturn first = createDraft(o, receiptLineId, 2, "req-ret-048-create");
        PurchaseReturn second = createDraft(o, receiptLineId, 2, "req-ret-048-create");

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(returns.page(null, null, null, 1, 20).total()).isEqualTo(1);
    }

    @Test
    @CaseId("TC-PUR-049")
    @DisplayName("★ 执行退货：扣实存、写 PURCHASE_RETURN 负流水、应付退货额与余额同步变化")
    void approve_deductsStockAndPayable() {
        PurchaseOrder o = receivedOrder("req-ret-049", 10, 10);
        long receiptLineId = latestReceiptLineId(o.id());
        long receiptId = latestReceiptId(o.id());
        long payableId = payableIdOfReceipt(receiptId);
        PurchaseReturn r = createDraft(o, receiptLineId, 4, "req-ret-049-create");

        PurchaseReturn done = returns.approve(r.id(), "req-ret-049-approve");

        assertThat(done.status()).isEqualTo(PurchaseReturnStatus.COMPLETED);
        assertThat(data.onHand(skuA, warehouseId, "B-RET")).isEqualTo(6);
        assertThat(data.available(skuA, warehouseId, "B-RET")).isEqualTo(6);
        assertThat(inventory.ledgerEntries(r.returnNo(), "PURCHASE_RETURN"))
                .singleElement()
                .satisfies(e -> assertThat(e.changeQty()).isEqualTo(-4));
        assertThat(inventory.checkLedgerConsistency()).isEmpty();

        SupplierPayable p = payables.require(payableId);
        assertThat(p.returnedAmount()).isEqualTo(4 * PRICE);
        assertThat(p.balanceAmount()).isEqualTo(6 * PRICE);
        assertThat(p.status()).isEqualTo("PARTIAL");
        assertThat(payables.reconcile(supplierId).returnAmount()).isEqualTo(4 * PRICE);
        assertThat(payables.reconcile(supplierId).balance()).isEqualTo(6 * PRICE);
    }

    @Test
    @CaseId("TC-PUR-050")
    @DisplayName("退货数量超过合格到货量：409 PUR_RETURN_QTY_EXCEED，不生成退货单")
    void createDraft_overQcQty_thenRejected() {
        PurchaseOrder o = receivedOrder("req-ret-050", 10, 4);
        long receiptLineId = latestReceiptLineId(o.id());

        assertThatThrownBy(() -> createDraft(o, receiptLineId, 5, "req-ret-050-create"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.PUR_RETURN_QTY_EXCEED);
        assertThat(returns.page(null, null, null, 1, 20).total()).isZero();
    }

    @Test
    @CaseId("TC-PUR-051")
    @DisplayName("★ 累计退货上限：第一次退 3 后，再退 2（只剩 1）必须拒绝")
    void createDraft_cumulativeReturnLimit() {
        PurchaseOrder o = receivedOrder("req-ret-051", 10, 4);
        long receiptLineId = latestReceiptLineId(o.id());
        PurchaseReturn first = createDraft(o, receiptLineId, 3, "req-ret-051-a");
        returns.approve(first.id(), "req-ret-051-approve");

        assertThatThrownBy(() -> createDraft(o, receiptLineId, 2, "req-ret-051-b"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.PUR_RETURN_QTY_EXCEED);
    }

    @Test
    @CaseId("TC-PUR-052")
    @DisplayName("执行退货幂等：同键重放不重复扣库存/冲应付")
    void approve_isIdempotent() {
        PurchaseOrder o = receivedOrder("req-ret-052", 10, 10);
        PurchaseReturn r = createDraft(o, latestReceiptLineId(o.id()), 3, "req-ret-052-create");
        long payableId = payableIdOfReceipt(latestReceiptId(o.id()));

        returns.approve(r.id(), "req-ret-052-approve");
        returns.approve(r.id(), "req-ret-052-approve");

        assertThat(data.onHand(skuA, warehouseId, "B-RET")).isEqualTo(7);
        assertThat(inventory.ledgerEntries(r.returnNo(), "PURCHASE_RETURN")).hasSize(1);
        assertThat(payables.require(payableId).returnedAmount()).isEqualTo(3 * PRICE);
    }

    @Test
    @CaseId("TC-PUR-053")
    @DisplayName("已执行退货不能再执行：换新幂等键 → PUR_RETURN_STATUS_ILLEGAL")
    void approve_afterCompleted_thenRejected() {
        PurchaseOrder o = receivedOrder("req-ret-053", 10, 10);
        PurchaseReturn r = createDraft(o, latestReceiptLineId(o.id()), 2, "req-ret-053-create");
        returns.approve(r.id(), "req-ret-053-approve");

        assertThatThrownBy(() -> returns.approve(r.id(), "req-ret-053-other"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.PUR_RETURN_STATUS_ILLEGAL);
    }

    @Test
    @CaseId("TC-PUR-054")
    @DisplayName("草稿作废：零副作用、同键幂等；已执行退货不能作废")
    void voidDraft_andCompletedCannotVoid() {
        PurchaseOrder o = receivedOrder("req-ret-054", 10, 10);
        long receiptLineId = latestReceiptLineId(o.id());
        PurchaseReturn draft = createDraft(o, receiptLineId, 2, "req-ret-054-a");

        returns.voidReturn(draft.id(), "req-ret-054-void");
        PurchaseReturn replay = returns.voidReturn(draft.id(), "req-ret-054-void");
        assertThat(replay.status()).isEqualTo(PurchaseReturnStatus.VOIDED);
        assertThat(data.onHand(skuA, warehouseId, "B-RET")).isEqualTo(10);

        assertThatThrownBy(() -> returns.voidReturn(draft.id(), "req-ret-054-new"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.PUR_RETURN_STATUS_ILLEGAL);

        PurchaseReturn done = createDraft(o, receiptLineId, 1, "req-ret-054-b");
        returns.approve(done.id(), "req-ret-054-approve");
        assertThatThrownBy(() -> returns.voidReturn(done.id(), "req-ret-054-void2"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.PUR_RETURN_STATUS_ILLEGAL);
    }

    @Test
    @CaseId("TC-PUR-055")
    @DisplayName("★ 已付款部分不能被退货冲掉：PUR_RETURN_EXCEED_PAYABLE，库存与流水回滚")
    void approve_exceedPayable_rollsBackStock() {
        PurchaseOrder o = receivedOrder("req-ret-055", 10, 10);
        long receiptLineId = latestReceiptLineId(o.id());
        long receiptId = latestReceiptId(o.id());
        long payableId = payableIdOfReceipt(receiptId);
        SupplierPayable p = payables.require(payableId);
        payables.pay(new SupplierPayableService.PaymentCommand(
                supplierId, p.balanceAmount(), BIZ_DATE, "全额付款",
                List.of(new SupplierPayableService.AllocationCommand(payableId, p.balanceAmount())),
                "req-ret-055-pay"));
        PurchaseReturn r = createDraft(o, receiptLineId, 5, "req-ret-055-create");

        assertThatThrownBy(() -> returns.approve(r.id(), "req-ret-055-approve"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.PUR_RETURN_EXCEED_PAYABLE);

        assertThat(data.onHand(skuA, warehouseId, "B-RET")).isEqualTo(10);
        assertThat(inventory.ledgerEntries(r.returnNo(), "PURCHASE_RETURN")).isEmpty();
        assertThat(payables.require(payableId).returnedAmount()).isZero();
        assertThat(payables.require(payableId).balanceAmount()).isZero();
        assertThat(returns.require(r.id()).status()).isEqualTo(PurchaseReturnStatus.DRAFT);
    }

    @Test
    @CaseId("TC-PUR-056")
    @DisplayName("★ 货已被卖出、原批次实存不足：INV_NEGATIVE_NOT_ALLOWED，应付不变")
    void approve_insufficientStock_thenRejected() {
        PurchaseOrder o = receivedOrder("req-ret-056", 10, 10);
        long receiptLineId = latestReceiptLineId(o.id());
        long payableId = payableIdOfReceipt(latestReceiptId(o.id()));
        // 前置：模拟 8 件已被销售出库（余额与流水同步调整，保持 INV-2）
        jdbc.update("""
                UPDATE inventory_balances SET on_hand_qty = 2
                 WHERE sku_id = ? AND warehouse_id = ? AND batch_no = 'B-RET'
                """, skuA, warehouseId);
        data.ledger(skuA, warehouseId, "B-RET", -8, "SALES_OUT", "SO-RET-056");
        PurchaseReturn r = createDraft(o, receiptLineId, 5, "req-ret-056-create");

        assertThatThrownBy(() -> returns.approve(r.id(), "req-ret-056-approve"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.INV_NEGATIVE_NOT_ALLOWED);

        assertThat(data.onHand(skuA, warehouseId, "B-RET")).isEqualTo(2);
        assertThat(inventory.ledgerEntries(r.returnNo(), "PURCHASE_RETURN")).isEmpty();
        assertThat(payables.require(payableId).returnedAmount()).isZero();
    }

    @Test
    @CaseId("TC-PUR-057")
    @DisplayName("到货行不属于该进货单：404，不生成退货单")
    void createDraft_lineFromOtherOrder_thenRejected() {
        PurchaseOrder a = receivedOrder("req-ret-057a", 5, 5);
        PurchaseOrder b = createApprovedOrder("req-ret-057b", 5, PRICE);

        assertThatThrownBy(() -> createDraft(b, latestReceiptLineId(a.id()), 1,
                "req-ret-057-create"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SYS_NOT_FOUND);
    }

    @Test
    @CaseId("TC-PUR-058")
    @DisplayName("账期已锁：建退货单被 SET_PERIOD_CLOSED 拒绝，零副作用")
    void createDraft_periodClosed_thenRejected() {
        PurchaseOrder o = receivedOrder("req-ret-058", 10, 10);
        long receiptLineId = latestReceiptLineId(o.id());
        closePeriod(PERIOD);

        assertThatThrownBy(() -> createDraft(o, receiptLineId, 2, "req-ret-058-create"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SET_PERIOD_CLOSED);
        assertThat(returns.page(null, null, null, 1, 20).total()).isZero();
    }
}
