package com.erp.pur;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.mdm.application.SupplierApplicationService;
import com.erp.pur.application.PurchaseOrderApplicationService.CreateCommand;
import com.erp.pur.application.PurchaseOrderApplicationService.CreateLineCommand;
import com.erp.pur.application.SupplierPayableService;
import com.erp.pur.application.SupplierPayableService.AllocationCommand;
import com.erp.pur.application.SupplierPayableService.PaymentCommand;
import com.erp.pur.application.SupplierPayableService.PaymentResult;
import com.erp.pur.domain.PurchaseOrder;
import com.erp.pur.domain.SupplierPayable;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.support.CaseId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 付款与核销（FR-PUR-08）。
 *
 * <p>三条硬验收：部分核销正确；超额核销 409 拒绝；Σ核销明细 == 付款单金额。
 */
@DisplayName("采购 · 付款与核销（FR-PUR-08）")
class SupplierPaymentIT extends PurchaseLoopTestSupport {

    private long payableForNewReceipt(String requestId, int qty, int qcQty) {
        PurchaseOrder o = createApprovedOrder(requestId, qty, 1000L);
        receive(o.id(), o.lines().get(0).id(), qty, qcQty, null, requestId + "-rcv");
        return payableIdOfReceipt(latestReceiptId(o.id()));
    }

    private PaymentResult pay(long supplier, long total, List<AllocationCommand> allocations,
                              String requestId) {
        return payables.pay(new PaymentCommand(supplier, total, BIZ_DATE, "付款测试",
                allocations, requestId));
    }

    @Test
    @CaseId("TC-PUR-066")
    @DisplayName("★ 部分核销：应付 10000 付 4000，余额 6000、状态 PARTIAL、核销明细为 4000")
    void partialPayment_updatesBalanceAndAllocation() {
        long payableId = payableForNewReceipt("req-pay-066", 10, 10);

        PaymentResult result = pay(supplierId, 4_000L,
                List.of(new AllocationCommand(payableId, 4_000L)), "req-pay-066-pay");

        assertThat(result.paymentNo()).matches("PY\\d{8}-\\d{4,}");
        assertThat(result.paymentAmount()).isEqualTo(4_000L);
        assertThat(result.allocations()).singleElement().satisfies(a -> {
            assertThat(a.payableId()).isEqualTo(payableId);
            assertThat(a.amount()).isEqualTo(4_000L);
            assertThat(a.paymentAmount()).isEqualTo(4_000L);
        });
        SupplierPayable p = payables.require(payableId);
        assertThat(p.paidAmount()).isEqualTo(4_000L);
        assertThat(p.balanceAmount()).isEqualTo(6_000L);
        assertThat(p.status()).isEqualTo("PARTIAL");
        assertThat(p.allocations()).hasSize(1);
        assertThat(payables.reconcile(supplierId).paidAmount()).isEqualTo(4_000L);
        assertThat(payables.reconcile(supplierId).balance()).isEqualTo(6_000L);
    }

    @Test
    @CaseId("TC-PUR-067")
    @DisplayName("全额核销：余额归零、状态 SETTLED")
    void fullPayment_settlesPayable() {
        long payableId = payableForNewReceipt("req-pay-067", 5, 5);
        long balance = payables.require(payableId).balanceAmount();

        pay(supplierId, balance, List.of(new AllocationCommand(payableId, balance)),
                "req-pay-067-pay");

        SupplierPayable p = payables.require(payableId);
        assertThat(p.balanceAmount()).isZero();
        assertThat(p.status()).isEqualTo("SETTLED");
        assertThat(payables.reconcile(supplierId).conserved()).isTrue();
    }

    @Test
    @CaseId("TC-PUR-068")
    @DisplayName("★ 一张付款单核销多张应付：明细合计 == 付款金额，两张应付各自减少")
    void paymentAcrossMultiplePayables() {
        long payableA = payableForNewReceipt("req-pay-068a", 6, 6);
        long payableB = payableForNewReceipt("req-pay-068b", 4, 4);

        PaymentResult result = pay(supplierId, 4_000L, List.of(
                new AllocationCommand(payableA, 2_400L),
                new AllocationCommand(payableB, 1_600L)), "req-pay-068-pay");

        assertThat(result.allocations()).hasSize(2);
        assertThat(result.allocations().stream().mapToLong(a -> a.amount()).sum())
                .isEqualTo(result.paymentAmount());
        assertThat(payables.require(payableA).paidAmount()).isEqualTo(2_400L);
        assertThat(payables.require(payableB).paidAmount()).isEqualTo(1_600L);
        assertThat(payables.reconcile(supplierId).paidAmount()).isEqualTo(4_000L);
        assertThat(payables.reconcile(supplierId).conserved()).isTrue();
    }

    @Test
    @CaseId("TC-PUR-069")
    @DisplayName("★ 超额核销：409 PUR_PAYMENT_EXCEED_PAYABLE（不是 401），应付与明细均不变")
    void overPayment_rejected() {
        long payableId = payableForNewReceipt("req-pay-069", 10, 10);

        assertThatThrownBy(() -> pay(supplierId, 10_001L,
                List.of(new AllocationCommand(payableId, 10_001L)), "req-pay-069-pay"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.PUR_PAYMENT_EXCEED_PAYABLE)
                .extracting(e -> ((BusinessException) e).getCode().httpStatus())
                .isEqualTo(409);

        assertThat(payables.require(payableId).paidAmount()).isZero();
        assertThat(payables.require(payableId).balanceAmount()).isEqualTo(10_000L);
        assertThat(payables.require(payableId).allocations()).isEmpty();
    }

    @Test
    @CaseId("TC-PUR-070")
    @DisplayName("★ 核销守恒：明细合计 ≠ 付款金额 → 400 PUR_PAYMENT_AMOUNT_MISMATCH")
    void allocationSumMismatch_rejected() {
        long payableId = payableForNewReceipt("req-pay-070", 10, 10);

        assertThatThrownBy(() -> pay(supplierId, 5_000L,
                List.of(new AllocationCommand(payableId, 3_000L)), "req-pay-070-pay"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.PUR_PAYMENT_AMOUNT_MISMATCH)
                .extracting(e -> ((BusinessException) e).getCode().httpStatus())
                .isEqualTo(400);
        assertThat(payables.require(payableId).paidAmount()).isZero();
    }

    @Test
    @CaseId("TC-PUR-071")
    @DisplayName("非法核销请求：同一应付重复核销 400；跨供应商核销 400 PUR_PAYMENT_SUPPLIER_MISMATCH")
    void duplicateAndCrossSupplierAllocations_rejected() {
        long payableId = payableForNewReceipt("req-pay-071", 10, 10);
        assertThatThrownBy(() -> pay(supplierId, 4_000L, List.of(
                new AllocationCommand(payableId, 2_000L),
                new AllocationCommand(payableId, 2_000L)), "req-pay-071-a"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.PUR_PAYMENT_AMOUNT_MISMATCH);

        long otherSupplier = supplierService.create(new SupplierApplicationService.CreateCommand(
                "SUP-V22-OTHER", "另一供应商", null, null, null, true)).id();
        PurchaseOrder otherOrder = purchaseOrders.create(new CreateCommand(
                otherSupplier, warehouseId, BIZ_DATE, null,
                List.of(new CreateLineCommand(skuA, 5, 1000L)), "req-pay-071-other"));
        purchaseOrders.submit(otherOrder.id());
        purchaseOrders.approve(otherOrder.id());
        receive(otherOrder.id(), otherOrder.lines().get(0).id(), 5, 5, null,
                "req-pay-071-other-rcv");
        long otherPayable = payableIdOfReceipt(latestReceiptId(otherOrder.id()));

        assertThatThrownBy(() -> pay(supplierId, 1_000L,
                List.of(new AllocationCommand(otherPayable, 1_000L)), "req-pay-071-b"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.PUR_PAYMENT_SUPPLIER_MISMATCH);
        assertThat(payables.require(otherPayable).paidAmount()).isZero();
    }

    @Test
    @CaseId("TC-PUR-072")
    @DisplayName("付款幂等：同 X-Request-Id 重放只核销一次、只生成一组明细")
    void payment_isIdempotent() {
        long payableId = payableForNewReceipt("req-pay-072", 10, 10);
        List<AllocationCommand> allocations = List.of(new AllocationCommand(payableId, 4_000L));

        PaymentResult first = pay(supplierId, 4_000L, allocations, "req-pay-072-pay");
        PaymentResult replay = pay(supplierId, 4_000L, allocations, "req-pay-072-pay");

        assertThat(replay.paymentNo()).isEqualTo(first.paymentNo());
        assertThat(replay.allocations()).hasSize(1);
        assertThat(payables.require(payableId).paidAmount()).isEqualTo(4_000L);
        assertThat(payables.reconcile(supplierId).allocationTotal()).isEqualTo(4_000L);
    }

    @Test
    @CaseId("TC-PUR-073")
    @DisplayName("★ 守恒查询能发现漂移：正常时 conserved=true，篡改明细金额后变 false")
    void conservationCheck_canDetectDrift() {
        long payableA = payableForNewReceipt("req-pay-073a", 5, 5);
        long payableB = payableForNewReceipt("req-pay-073b", 5, 5);
        pay(supplierId, 4_000L, List.of(
                new AllocationCommand(payableA, 2_000L),
                new AllocationCommand(payableB, 2_000L)), "req-pay-073-pay");
        assertThat(payables.reconcile(supplierId).conserved()).isTrue();

        // 人为制造"明细合计 ≠ 付款金额"（模拟绕过应用层的脏写）
        jdbc.update("""
                UPDATE payable_allocations SET amount = amount + 1
                 WHERE payable_id = ? AND payment_no = (
                     SELECT payment_no FROM payable_allocations WHERE payable_id = ? LIMIT 1)
                """, payableA, payableA);

        SupplierPayableService.ReconcileResult drift = payables.reconcile(supplierId);
        assertThat(drift.conserved()).isFalse();
        assertThat(drift.paymentTotal()).isEqualTo(4_000L);
        assertThat(drift.allocationTotal()).isEqualTo(4_001L);
    }
}
