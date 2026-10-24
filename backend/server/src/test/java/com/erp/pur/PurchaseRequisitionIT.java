package com.erp.pur;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.pur.application.PurchaseRequisitionApplicationService;
import com.erp.pur.application.PurchaseRequisitionApplicationService.ConvertCommand;
import com.erp.pur.application.PurchaseRequisitionApplicationService.ConvertLineCommand;
import com.erp.pur.application.PurchaseRequisitionApplicationService.CreateCommand;
import com.erp.pur.application.PurchaseRequisitionApplicationService.CreateLineCommand;
import com.erp.pur.domain.PurchaseOrder;
import com.erp.pur.domain.PurchaseRequisition;
import com.erp.shared.document.DocumentStatus;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.support.CaseId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 请购单（FR-PUR-04）：申请 → 审核 → 转订单。
 *
 * <p>重点不是 CRUD，而是三条硬约束：行不丢、数量不超、转化后不可再改。
 */
@DisplayName("采购 · 请购单与转订单（FR-PUR-04）")
class PurchaseRequisitionIT extends PurchaseLoopTestSupport {

    @Test
    @CaseId("TC-PUR-024")
    @DisplayName("建请购单：单号 PR 前缀、DRAFT、金额由明细汇总、明细完整")
    void create_shouldBuildDraftWithTotals() {
        PurchaseRequisition r = requisitions.create(new CreateCommand(
                supplierId, warehouseId, BIZ_DATE, "请购测试",
                List.of(new CreateLineCommand(skuA, 10, 1000L),
                        new CreateLineCommand(skuB, 5, 2000L)),
                "req-req-024"));

        assertThat(r.orderNo()).matches("PR\\d{8}-\\d{4,}");
        assertThat(r.status()).isEqualTo(DocumentStatus.DRAFT);
        assertThat(r.totalQty()).isEqualTo(15);
        assertThat(r.totalAmount()).isEqualTo(20000L);
        assertThat(r.lines()).hasSize(2);
        assertThat(r.period()).isEqualTo(PERIOD);
    }

    @Test
    @CaseId("TC-PUR-025")
    @DisplayName("建请购单幂等：同 requestId 返回同一张单，不新增记录")
    void create_withSameRequestId_isIdempotent() {
        PurchaseRequisition first = requisitions.create(new CreateCommand(
                supplierId, warehouseId, BIZ_DATE, null,
                List.of(new CreateLineCommand(skuA, 3, 1000L)), "req-req-025"));
        PurchaseRequisition second = requisitions.create(new CreateCommand(
                supplierId, warehouseId, BIZ_DATE, null,
                List.of(new CreateLineCommand(skuA, 3, 1000L)), "req-req-025"));

        assertThat(second.id()).isEqualTo(first.id());
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM purchase_orders WHERE doc_type = 'REQUISITION'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    @CaseId("TC-PUR-026")
    @DisplayName("建请购单校验：空明细 / 数量非正 / 单价为负全部拒绝")
    void create_withInvalidLines_thenRejected() {
        assertThatThrownBy(() -> requisitions.create(new CreateCommand(
                supplierId, warehouseId, BIZ_DATE, null, List.of(), "req-req-026a")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SYS_VALIDATION_ERROR);

        assertThatThrownBy(() -> requisitions.create(new CreateCommand(
                supplierId, warehouseId, BIZ_DATE, null,
                List.of(new CreateLineCommand(skuA, 0, 100L)), "req-req-026b")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SYS_VALIDATION_ERROR);

        assertThatThrownBy(() -> requisitions.create(new CreateCommand(
                supplierId, warehouseId, BIZ_DATE, null,
                List.of(new CreateLineCommand(skuA, 1, -1L)), "req-req-026c")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SYS_VALIDATION_ERROR);
    }

    @Test
    @CaseId("TC-PUR-027")
    @DisplayName("请购状态机：DRAFT → SUBMITTED → APPROVED")
    void statusFlow_happyPath() {
        PurchaseRequisition r = requisitions.create(new CreateCommand(
                supplierId, warehouseId, BIZ_DATE, null,
                List.of(new CreateLineCommand(skuA, 2, 100L)), "req-req-027"));

        assertThat(requisitions.submit(r.id()).status()).isEqualTo(DocumentStatus.SUBMITTED);
        assertThat(requisitions.approve(r.id()).status()).isEqualTo(DocumentStatus.APPROVED);
    }

    @Test
    @CaseId("TC-PUR-028")
    @DisplayName("请购非法转移：DRAFT 不能直接审核，APPROVED 不能回退提交")
    void illegalTransitions_areRejected() {
        PurchaseRequisition r = requisitions.create(new CreateCommand(
                supplierId, warehouseId, BIZ_DATE, null,
                List.of(new CreateLineCommand(skuA, 2, 100L)), "req-req-028"));

        assertThatThrownBy(() -> requisitions.approve(r.id()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.PUR_REQ_STATUS_ILLEGAL);

        requisitions.submit(r.id());
        requisitions.approve(r.id());
        assertThatThrownBy(() -> requisitions.submit(r.id()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.PUR_REQ_STATUS_ILLEGAL);
    }

    @Test
    @CaseId("TC-PUR-029")
    @DisplayName("★ 转订单：行不丢、数量可下调、请购单置 COMPLETED 并关联订单")
    void convert_shouldKeepAllLinesAndCompleteRequisition() {
        PurchaseRequisition r = requisitions.create(new CreateCommand(
                supplierId, warehouseId, BIZ_DATE, "转单",
                List.of(new CreateLineCommand(skuA, 10, 1000L),
                        new CreateLineCommand(skuB, 5, 2000L)),
                "req-req-029"));
        requisitions.submit(r.id());
        requisitions.approve(r.id());

        PurchaseOrder order = requisitions.convert(r.id(), new ConvertCommand(
                BIZ_DATE, "转单生成",
                List.of(new ConvertLineCommand(r.lines().get(0).id(), 8),
                        new ConvertLineCommand(r.lines().get(1).id(), 5)),
                "req-conv-029"));

        assertThat(order.lines()).hasSize(2);
        assertThat(order.totalQty()).isEqualTo(13);
        assertThat(order.totalAmount()).isEqualTo(8 * 1000L + 5 * 2000L);
        assertThat(order.status()).isEqualTo(DocumentStatus.DRAFT);
        assertThat(order.supplierId()).isEqualTo(supplierId);
        assertThat(order.warehouseId()).isEqualTo(warehouseId);

        PurchaseRequisition after = requisitions.require(r.id());
        assertThat(after.status()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(after.convertedOrderId()).isEqualTo(order.id());
        Long source = jdbc.queryForObject(
                "SELECT source_requisition_id FROM purchase_orders WHERE id = ?",
                Long.class, order.id());
        assertThat(source).isEqualTo(r.id());
    }

    @Test
    @CaseId("TC-PUR-030")
    @DisplayName("转单允许部分数量：申请 10 转 4，订单行数量为 4")
    void convert_canReduceQty() {
        PurchaseRequisition r = createApprovedRequisition("req-req-030", 10, 1000L);

        PurchaseOrder order = requisitions.convert(r.id(), new ConvertCommand(
                BIZ_DATE, null,
                List.of(new ConvertLineCommand(r.lines().get(0).id(), 4)),
                "req-conv-030"));

        assertThat(order.totalQty()).isEqualTo(4);
        assertThat(order.lines()).singleElement()
                .satisfies(l -> assertThat(l.qty()).isEqualTo(4));
        assertThat(requisitions.require(r.id()).converted()).isTrue();
    }

    @Test
    @CaseId("TC-PUR-031")
    @DisplayName("★ 转单数量超过申请数量必须拒绝，且不生成订单")
    void convert_overQty_thenRejected() {
        PurchaseRequisition r = createApprovedRequisition("req-req-031", 10, 1000L);

        assertThatThrownBy(() -> requisitions.convert(r.id(), new ConvertCommand(
                BIZ_DATE, null,
                List.of(new ConvertLineCommand(r.lines().get(0).id(), 11)),
                "req-conv-031")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.PUR_REQ_CONVERT_EXCEED);

        Integer orders = jdbc.queryForObject("""
                SELECT COUNT(*) FROM purchase_orders
                 WHERE source_requisition_id = ? AND doc_type = 'PURCHASE'
                """, Integer.class, r.id());
        assertThat(orders).isZero();
        assertThat(requisitions.require(r.id()).status()).isEqualTo(DocumentStatus.APPROVED);
    }

    @Test
    @CaseId("TC-PUR-032")
    @DisplayName("转单漏行 / 多行 / 未知行全部拒绝（行不丢）")
    void convert_missingOrUnknownLines_thenRejected() {
        PurchaseRequisition r = requisitions.create(new CreateCommand(
                supplierId, warehouseId, BIZ_DATE, null,
                List.of(new CreateLineCommand(skuA, 3, 100L),
                        new CreateLineCommand(skuB, 4, 100L)),
                "req-req-032"));
        requisitions.submit(r.id());
        requisitions.approve(r.id());

        assertThatThrownBy(() -> requisitions.convert(r.id(), new ConvertCommand(
                BIZ_DATE, null,
                List.of(new ConvertLineCommand(r.lines().get(0).id(), 1)),
                "req-conv-032a")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SYS_VALIDATION_ERROR);

        assertThatThrownBy(() -> requisitions.convert(r.id(), new ConvertCommand(
                BIZ_DATE, null, List.of(), "req-conv-032b")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SYS_VALIDATION_ERROR);

        assertThatThrownBy(() -> requisitions.convert(r.id(), new ConvertCommand(
                BIZ_DATE, null,
                List.of(new ConvertLineCommand(r.lines().get(0).id(), 1),
                        new ConvertLineCommand(999_999L, 1)),
                "req-conv-032c")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SYS_NOT_FOUND);
    }

    @Test
    @CaseId("TC-PUR-033")
    @DisplayName("★ 转单幂等：同键重放返回同一订单；换键再转 → PUR_REQ_STATUS_ILLEGAL")
    void convert_idempotentAndDuplicateKey() {
        PurchaseRequisition r = createApprovedRequisition("req-req-033", 5, 100L);
        List<ConvertLineCommand> lines = List.of(
                new ConvertLineCommand(r.lines().get(0).id(), 5));

        PurchaseOrder first = requisitions.convert(r.id(), new ConvertCommand(
                BIZ_DATE, null, lines, "req-conv-033"));
        PurchaseOrder replay = requisitions.convert(r.id(), new ConvertCommand(
                BIZ_DATE, null, lines, "req-conv-033"));

        assertThat(replay.id()).isEqualTo(first.id());
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM purchase_orders
                 WHERE source_requisition_id = ? AND doc_type = 'PURCHASE'
                """, Integer.class, r.id());
        assertThat(count).isEqualTo(1);

        assertThatThrownBy(() -> requisitions.convert(r.id(), new ConvertCommand(
                BIZ_DATE, null, lines, "req-conv-033-new")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.PUR_REQ_STATUS_ILLEGAL);
    }

    @Test
    @CaseId("TC-PUR-034")
    @DisplayName("★ 转化后请购单不可再改：提交/审核/再次转化全部拒绝")
    void convertedRequisition_isImmutable() {
        PurchaseRequisition r = createApprovedRequisition("req-req-034", 5, 100L);
        requisitions.convert(r.id(), new ConvertCommand(BIZ_DATE, null,
                List.of(new ConvertLineCommand(r.lines().get(0).id(), 5)), "req-conv-034"));

        assertThatThrownBy(() -> requisitions.submit(r.id()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.PUR_REQ_STATUS_ILLEGAL);
        assertThatThrownBy(() -> requisitions.approve(r.id()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.PUR_REQ_STATUS_ILLEGAL);
        assertThatThrownBy(() -> requisitions.convert(r.id(), new ConvertCommand(
                BIZ_DATE, null,
                List.of(new ConvertLineCommand(r.lines().get(0).id(), 5)),
                "req-conv-034b")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.PUR_REQ_STATUS_ILLEGAL);
    }

    @Test
    @CaseId("TC-PUR-035")
    @DisplayName("★ 请购单与进货单隔离：不进进货列表、不能走进货详情/入库")
    void requisition_isIsolatedFromPurchaseOrders() {
        PurchaseRequisition r = createApprovedRequisition("req-req-035", 5, 100L);

        assertThat(purchaseOrders.page(null, null, 1, 20).total()).isZero();
        assertThatThrownBy(() -> purchaseOrders.require(r.id()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.PUR_ORDER_NOT_FOUND);

        Integer visible = jdbc.queryForObject("""
                SELECT COUNT(*) FROM purchase_orders
                 WHERE id = ? AND doc_type = 'PURCHASE'
                """, Integer.class, r.id());
        assertThat(visible).isZero();
    }
}
