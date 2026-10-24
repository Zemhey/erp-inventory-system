package com.erp.pur;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.pur.application.PurchaseReturnService.CreateCommand;
import com.erp.pur.application.PurchaseReturnService.CreateLineCommand;
import com.erp.pur.application.SupplierPayableService;
import com.erp.pur.domain.PurchaseOrder;
import com.erp.pur.domain.PurchaseReturn;
import com.erp.pur.domain.SupplierPayable;
import com.erp.support.CaseId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * 供应商应付（FR-PUR-07）。
 *
 * <p>余额是生成列；本类同时验证应用层公式与数据库层兜底。
 */
@DisplayName("采购 · 供应商应付与余额不变量（FR-PUR-07）")
class SupplierPayableIT extends PurchaseLoopTestSupport {

    @Test
    @CaseId("TC-PUR-059")
    @DisplayName("★ 质检合格到货生成应付：原值 = 合格量×单价，余额 = 原值，状态 OPEN")
    void receipt_generatesPayableWithBalance() {
        PurchaseOrder o = createApprovedOrder("req-ap-059", 10, 1000L);
        receive(o.id(), o.lines().get(0).id(), 10, 8, "B-AP", "req-ap-059-rcv");

        long payableId = payableIdOfReceipt(latestReceiptId(o.id()));
        SupplierPayable p = payables.require(payableId);

        assertThat(p.payableNo()).matches("AP\\d{8}-\\d{4,}");
        assertThat(p.originalAmount()).isEqualTo(8_000L);
        assertThat(p.returnedAmount()).isZero();
        assertThat(p.paidAmount()).isZero();
        assertThat(p.balanceAmount()).isEqualTo(8_000L);
        assertThat(p.status()).isEqualTo("OPEN");
        assertThat(p.orderNo()).isEqualTo(o.orderNo());

        Long generated = jdbc.queryForObject("""
                SELECT balance_amount FROM supplier_payables WHERE id = ?
                """, Long.class, payableId);
        assertThat(generated).isEqualTo(8_000L);
    }

    @Test
    @CaseId("TC-PUR-060")
    @DisplayName("★ 应付余额恒等式可重复校验：Σ余额 == Σ进货 − Σ退货 − Σ付款，两次查询一致")
    void reconcile_isRepeatable() {
        PurchaseOrder o = createApprovedOrder("req-ap-060", 10, 1000L);
        receive(o.id(), o.lines().get(0).id(), 6, 6, "B1", "req-ap-060-a");
        receive(o.id(), o.lines().get(0).id(), 4, 4, "B2", "req-ap-060-b");

        SupplierPayableService.ReconcileResult first = payables.reconcile(supplierId);
        SupplierPayableService.ReconcileResult second = payables.reconcile(supplierId);

        assertThat(first).isEqualTo(second);
        assertThat(first.purchaseAmount()).isEqualTo(10_000L);
        assertThat(first.returnAmount()).isZero();
        assertThat(first.paidAmount()).isZero();
        assertThat(first.balance()).isEqualTo(10_000L);
        assertThat(first.balance()).isEqualTo(
                first.purchaseAmount() - first.returnAmount() - first.paidAmount());
        assertThat(first.balanced()).isTrue();
        assertThat(first.conserved()).isTrue();
        assertThat(payables.page(supplierId, null, 1, 20).total()).isEqualTo(2);
    }

    @Test
    @CaseId("TC-PUR-061")
    @DisplayName("应付详情包含来源订单号与（初始为空的）核销明细")
    void detail_containsOrderNoAndEmptyAllocations() {
        PurchaseOrder o = createApprovedOrder("req-ap-061", 5, 200L);
        receive(o.id(), o.lines().get(0).id(), 5, 5, null, "req-ap-061-rcv");
        long payableId = payableIdOfReceipt(latestReceiptId(o.id()));

        SupplierPayable p = payables.require(payableId);

        assertThat(p.orderNo()).isEqualTo(o.orderNo());
        assertThat(p.balanceAmount()).isEqualTo(1_000L);
        assertThat(p.allocations()).isEmpty();
    }

    @Test
    @CaseId("TC-PUR-062")
    @DisplayName("应付列表按供应商与状态过滤：OPEN 有 1 条，SETTLED 为 0，其它供应商为 0")
    void page_filtersBySupplierAndStatus() {
        PurchaseOrder o = createApprovedOrder("req-ap-062", 5, 200L);
        receive(o.id(), o.lines().get(0).id(), 5, 5, null, "req-ap-062-rcv");

        assertThat(payables.page(supplierId, "OPEN", 1, 20).total()).isEqualTo(1);
        assertThat(payables.page(supplierId, "SETTLED", 1, 20).total()).isZero();
        assertThat(payables.page(supplierId, "PARTIAL", 1, 20).total()).isZero();
        assertThat(payables.page(999_999L, null, 1, 20).total()).isZero();
    }

    @Test
    @CaseId("TC-PUR-063")
    @DisplayName("全部质检不合格：不生成应付单，余额核对为 0")
    void allRejected_noPayable() {
        PurchaseOrder o = createApprovedOrder("req-ap-063", 5, 200L);
        receive(o.id(), o.lines().get(0).id(), 5, 0, null, "req-ap-063-rcv");

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM supplier_payables", Integer.class);
        assertThat(count).isZero();
        assertThat(payables.reconcile(supplierId).purchaseAmount()).isZero();
        assertThat(payables.reconcile(supplierId).balanced()).isTrue();
    }

    @Test
    @CaseId("TC-PUR-064")
    @DisplayName("采购退货冲减应付：退货额累加、余额下降、状态变 PARTIAL")
    void purchaseReturn_lowersPayable() {
        PurchaseOrder o = createApprovedOrder("req-ap-064", 10, 1000L);
        receive(o.id(), o.lines().get(0).id(), 10, 10, "B-AP", "req-ap-064-rcv");
        long receiptId = latestReceiptId(o.id());
        long payableId = payableIdOfReceipt(receiptId);
        PurchaseReturn r = returns.create(new CreateCommand(o.id(), BIZ_DATE, null,
                List.of(new CreateLineCommand(latestReceiptLineId(o.id()), 3)),
                "req-ap-064-ret"));
        returns.approve(r.id(), "req-ap-064-ret-approve");

        SupplierPayable p = payables.require(payableId);
        assertThat(p.returnedAmount()).isEqualTo(3_000L);
        assertThat(p.balanceAmount()).isEqualTo(7_000L);
        assertThat(p.status()).isEqualTo("PARTIAL");
        assertThat(payables.reconcile(supplierId).returnAmount()).isEqualTo(3_000L);
        assertThat(payables.reconcile(supplierId).balance()).isEqualTo(7_000L);
    }

    @Test
    @CaseId("TC-PUR-065")
    @DisplayName("★ 数据库兜底：退货 + 付款超过原值时 CHECK 约束拒绝，余额不可能为负")
    void databaseRejectsNegativeBalance() {
        PurchaseOrder o = createApprovedOrder("req-ap-065", 5, 200L);
        receive(o.id(), o.lines().get(0).id(), 5, 5, null, "req-ap-065-rcv");
        long payableId = payableIdOfReceipt(latestReceiptId(o.id()));

        assertThatThrownBy(() -> jdbc.update("""
                UPDATE supplier_payables SET paid_amount = original_amount + 1 WHERE id = ?
                """, payableId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(payables.require(payableId).balanceAmount()).isEqualTo(1_000L);
    }
}
