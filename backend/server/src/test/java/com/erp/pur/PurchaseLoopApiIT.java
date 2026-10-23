package com.erp.pur;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.iam.application.AuthenticationService;
import com.erp.pur.application.PurchaseRequisitionApplicationService;
import com.erp.pur.domain.PurchaseOrder;
import com.erp.pur.domain.PurchaseRequisition;
import com.erp.support.CaseId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 采购闭环的 HTTP 契约测试（TC-API-030~039）。
 *
 * <p>服务层已经覆盖业务规则，这里只证明三件 MVC 层的事实：
 * 路径与包络正确；缺幂等键归因成 400；未登录 401 / 无权限 403 / 业务冲突 409
 * 三种失败互不混用。
 */
@AutoConfigureMockMvc
@DisplayName("采购闭环 · HTTP 契约（TC-API-030~039）")
class PurchaseLoopApiIT extends PurchaseLoopTestSupport {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AuthenticationService authentication;
    @Autowired
    private PasswordEncoder encoder;
    @Autowired
    private JdbcTemplate jdbc;

    private String adminAuth;
    private String viewerAuth;

    @BeforeEach
    void setUpUsers() {
        data.user("v22_admin", "Admin1!", "ADMIN", encoder);
        data.user("v22_viewer", "Viewer1!", "VIEWER", encoder);
        adminAuth = "Bearer " + authentication.login("v22_admin", "Admin1!").token();
        viewerAuth = "Bearer " + authentication.login("v22_viewer", "Viewer1!").token();
    }

    private String requisitionBody() {
        return """
                {"supplierId":%d,"warehouseId":%d,"bizDate":"2025-06-01","remark":"api",
                 "lines":[{"skuId":%d,"qty":5,"unitPriceAmount":1000}]}
                """.formatted(supplierId, warehouseId, skuA);
    }

    private PurchaseOrder receivedOrder(String requestId) {
        PurchaseOrder o = createApprovedOrder(requestId, 10, 1000L);
        return receive(o.id(), o.lines().get(0).id(), 10, 10, "B-API", requestId + "-rcv");
    }

    @Test
    @CaseId("TC-API-030")
    @DisplayName("请购单 HTTP：缺幂等键 400；建单 201；列表/详情包络与字段正确")
    void requisitionHttp() throws Exception {
        mvc.perform(post("/api/v1/purchase-requisitions")
                        .header("Authorization", adminAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requisitionBody()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SYS_VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value(containsString("X-Request-Id")));

        mvc.perform(post("/api/v1/purchase-requisitions")
                        .header("Authorization", adminAuth)
                        .header("X-Request-Id", "req-api-030")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requisitionBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.orderNo").value(startsWith("PR")))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.lines[0].qty").value(5));

        mvc.perform(get("/api/v1/purchase-requisitions").header("Authorization", adminAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].totalAmount").value(5000));

        Long id = jdbc.queryForObject(
                "SELECT id FROM purchase_orders WHERE doc_type = 'REQUISITION'", Long.class);
        mvc.perform(get("/api/v1/purchase-requisitions/" + id)
                        .header("Authorization", adminAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id));
    }

    @Test
    @CaseId("TC-API-031")
    @DisplayName("请购转单 HTTP：超量 409；正常 200；同键重放同一订单；换键 409")
    void requisitionConvertHttp() throws Exception {
        PurchaseRequisition r = createApprovedRequisition("req-api-031", 5, 1000L);
        String over = """
                {"bizDate":"2025-06-01","lines":[{"lineId":%d,"qty":6}]}
                """.formatted(r.lines().get(0).id());
        String ok = """
                {"bizDate":"2025-06-01","lines":[{"lineId":%d,"qty":4}]}
                """.formatted(r.lines().get(0).id());

        mvc.perform(post("/api/v1/purchase-requisitions/" + r.id() + "/convert")
                        .header("Authorization", adminAuth)
                        .header("X-Request-Id", "req-api-031-a")
                        .contentType(MediaType.APPLICATION_JSON).content(over))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PUR_REQ_CONVERT_EXCEED"));

        mvc.perform(post("/api/v1/purchase-requisitions/" + r.id() + "/convert")
                        .header("Authorization", adminAuth)
                        .header("X-Request-Id", "req-api-031-b")
                        .contentType(MediaType.APPLICATION_JSON).content(ok))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderNo").value(startsWith("PO")))
                .andExpect(jsonPath("$.data.totalQty").value(4));

        mvc.perform(post("/api/v1/purchase-requisitions/" + r.id() + "/convert")
                        .header("Authorization", adminAuth)
                        .header("X-Request-Id", "req-api-031-b")
                        .contentType(MediaType.APPLICATION_JSON).content(ok))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalQty").value(4));

        mvc.perform(post("/api/v1/purchase-requisitions/" + r.id() + "/convert")
                        .header("Authorization", adminAuth)
                        .header("X-Request-Id", "req-api-031-c")
                        .contentType(MediaType.APPLICATION_JSON).content(ok))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PUR_REQ_STATUS_ILLEGAL"));
    }

    @Test
    @CaseId("TC-API-032")
    @DisplayName("到货质检 HTTP：部分合格入库；响应带 qc 字段；到货记录可查")
    void receiveQcHttp() throws Exception {
        PurchaseOrder o = createApprovedOrder("req-api-032", 10, 1000L);
        String body = """
                {"lines":[{"lineId":%d,"qty":10,"batchNo":"B-API32","qcQty":4,"qcNote":"抽检"}]}
                """.formatted(o.lines().get(0).id());

        mvc.perform(post("/api/v1/purchase-orders/" + o.id() + "/receive")
                        .header("Authorization", adminAuth)
                        .header("X-Request-Id", "req-api-032-rcv")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.lines[0].receivedQty").value(10))
                .andExpect(jsonPath("$.data.lines[0].qcQty").value(4))
                .andExpect(jsonPath("$.data.lines[0].qcStatus").value("PARTIAL"));

        assertThat(data.available(skuA, warehouseId, "B-API32"))
                .as("不合格的 6 件不得进入可用库存")
                .isEqualTo(4);

        mvc.perform(get("/api/v1/purchase-orders/" + o.id() + "/receipts")
                        .header("Authorization", adminAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].acceptedQty").value(4))
                .andExpect(jsonPath("$.data[0].rejectedQty").value(6))
                .andExpect(jsonPath("$.data[0].lines[0].qcStatus").value("PARTIAL"));
    }

    @Test
    @CaseId("TC-API-033")
    @DisplayName("质检数量越界 HTTP：400 PUR_QC_QTY_INVALID，返回码不是 500")
    void invalidQcHttp() throws Exception {
        PurchaseOrder o = createApprovedOrder("req-api-033", 5, 1000L);
        String body = """
                {"lines":[{"lineId":%d,"qty":5,"qcQty":6}]}
                """.formatted(o.lines().get(0).id());

        mvc.perform(post("/api/v1/purchase-orders/" + o.id() + "/receive")
                        .header("Authorization", adminAuth)
                        .header("X-Request-Id", "req-api-033-rcv")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PUR_QC_QTY_INVALID"));
    }

    @Test
    @CaseId("TC-API-034")
    @DisplayName("采购退货 HTTP：缺幂等键 400；建单 201；执行 200；作废 200")
    void purchaseReturnHttp() throws Exception {
        PurchaseOrder o = receivedOrder("req-api-034");
        long receiptLineId = latestReceiptLineId(o.id());
        String body = """
                {"purchaseOrderId":%d,"bizDate":"2025-06-01",
                 "lines":[{"receiptLineId":%d,"qty":2}]}
                """.formatted(o.id(), receiptLineId);

        mvc.perform(post("/api/v1/purchase-returns")
                        .header("Authorization", adminAuth)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SYS_VALIDATION_ERROR"));

        String created = mvc.perform(post("/api/v1/purchase-returns")
                        .header("Authorization", adminAuth)
                        .header("X-Request-Id", "req-api-034-create")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.returnNo").value(startsWith("RT")))
                .andReturn().getResponse().getContentAsString();
        long returnId = Long.parseLong(created.replaceAll(".*\"id\":(\\d+).*", "$1"));

        mvc.perform(post("/api/v1/purchase-returns/" + returnId + "/approve")
                        .header("Authorization", adminAuth)
                        .header("X-Request-Id", "req-api-034-approve"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
        assertThat(data.onHand(skuA, warehouseId, "B-API")).isEqualTo(8);

        String draft = mvc.perform(post("/api/v1/purchase-returns")
                        .header("Authorization", adminAuth)
                        .header("X-Request-Id", "req-api-034-create2")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn()
                .getResponse().getContentAsString();
        long draftId = Long.parseLong(draft.replaceAll(".*\"id\":(\\d+).*", "$1"));

        mvc.perform(post("/api/v1/purchase-returns/" + draftId + "/void")
                        .header("Authorization", adminAuth)
                        .header("X-Request-Id", "req-api-034-void"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("VOIDED"));
    }

    @Test
    @CaseId("TC-API-035")
    @DisplayName("应付 HTTP：列表/详情/核对；余额与恒等式字段齐全")
    void payableHttp() throws Exception {
        PurchaseOrder o = receivedOrder("req-api-035");
        long payableId = payableIdOfReceipt(latestReceiptId(o.id()));

        mvc.perform(get("/api/v1/supplier-payables")
                        .param("supplierId", String.valueOf(supplierId))
                        .header("Authorization", adminAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].balance").value(10000))
                .andExpect(jsonPath("$.data.items[0].status").value("OPEN"));

        mvc.perform(get("/api/v1/supplier-payables/" + payableId)
                        .header("Authorization", adminAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderNo").value(o.orderNo()))
                .andExpect(jsonPath("$.data.allocations").isEmpty());

        mvc.perform(get("/api/v1/supplier-payables/reconcile")
                        .param("supplierId", String.valueOf(supplierId))
                        .header("Authorization", adminAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.purchaseAmount").value(10000))
                .andExpect(jsonPath("$.data.balance").value(10000))
                .andExpect(jsonPath("$.data.balanced").value(true))
                .andExpect(jsonPath("$.data.conserved").value(true));
    }

    @Test
    @CaseId("TC-API-036")
    @DisplayName("付款 HTTP：部分核销 201；超额核销 409（明确不是 401）")
    void paymentHttp() throws Exception {
        PurchaseOrder o = receivedOrder("req-api-036");
        long payableId = payableIdOfReceipt(latestReceiptId(o.id()));
        String partial = """
                {"supplierId":%d,"amount":4000,"bizDate":"2025-06-01",
                 "allocations":[{"payableId":%d,"amount":4000}]}
                """.formatted(supplierId, payableId);

        mvc.perform(post("/api/v1/supplier-payments")
                        .header("Authorization", adminAuth)
                        .header("X-Request-Id", "req-api-036-pay")
                        .contentType(MediaType.APPLICATION_JSON).content(partial))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.paymentNo").value(startsWith("PY")))
                .andExpect(jsonPath("$.data.allocations[0].amount").value(4000));

        String over = """
                {"supplierId":%d,"amount":7000,"bizDate":"2025-06-01",
                 "allocations":[{"payableId":%d,"amount":7000}]}
                """.formatted(supplierId, payableId);
        mvc.perform(post("/api/v1/supplier-payments")
                        .header("Authorization", adminAuth)
                        .header("X-Request-Id", "req-api-036-over")
                        .contentType(MediaType.APPLICATION_JSON).content(over))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PUR_PAYMENT_EXCEED_PAYABLE"));
    }

    @Test
    @CaseId("TC-API-037")
    @DisplayName("★ 失败语义分离：未登录 401；VIEWER 无付款权限 403；缺幂等键 400")
    void failureStatusSeparation() throws Exception {
        PurchaseOrder o = receivedOrder("req-api-037");
        long payableId = payableIdOfReceipt(latestReceiptId(o.id()));
        String body = """
                {"supplierId":%d,"amount":1000,"bizDate":"2025-06-01",
                 "allocations":[{"payableId":%d,"amount":1000}]}
                """.formatted(supplierId, payableId);

        mvc.perform(post("/api/v1/supplier-payments")
                        .header("X-Request-Id", "req-api-037-noauth")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("IAM_UNAUTHENTICATED"));

        mvc.perform(post("/api/v1/supplier-payments")
                        .header("Authorization", viewerAuth)
                        .header("X-Request-Id", "req-api-037-forbidden")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("IAM_FORBIDDEN"));

        mvc.perform(post("/api/v1/supplier-payments")
                        .header("Authorization", adminAuth)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SYS_VALIDATION_ERROR"));

        assertThat(payables.require(payableId).paidAmount()).isZero();
    }

    @Test
    @CaseId("TC-API-038")
    @DisplayName("参数校验 HTTP：非法 id 类型 400；数量为 0 400；空核销明细 400")
    void validationHttp() throws Exception {
        mvc.perform(get("/api/v1/supplier-payables/not-a-number")
                        .header("Authorization", adminAuth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SYS_VALIDATION_ERROR"));

        mvc.perform(post("/api/v1/purchase-requisitions")
                        .header("Authorization", adminAuth)
                        .header("X-Request-Id", "req-api-038-bad-qty")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"supplierId":%d,"warehouseId":%d,"bizDate":"2025-06-01",
                                 "lines":[{"skuId":%d,"qty":0,"unitPriceAmount":100}]}
                                """.formatted(supplierId, warehouseId, skuA)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SYS_VALIDATION_ERROR"));

        mvc.perform(post("/api/v1/supplier-payments")
                        .header("Authorization", adminAuth)
                        .header("X-Request-Id", "req-api-038-empty-alloc")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"supplierId":%d,"amount":100,
                                 "allocations":[]}
                                """.formatted(supplierId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SYS_VALIDATION_ERROR"));
    }

    @Test
    @CaseId("TC-API-039")
    @DisplayName("404 语义：请购 / 退货 / 应付 / 核销未知单据均返回对应 NOT_FOUND 码")
    void notFoundHttp() throws Exception {
        mvc.perform(get("/api/v1/purchase-requisitions/999999")
                        .header("Authorization", adminAuth))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PUR_REQ_NOT_FOUND"));
        mvc.perform(get("/api/v1/purchase-returns/999999")
                        .header("Authorization", adminAuth))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PUR_RETURN_NOT_FOUND"));
        mvc.perform(get("/api/v1/supplier-payables/999999")
                        .header("Authorization", adminAuth))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PUR_PAYABLE_NOT_FOUND"));
        mvc.perform(post("/api/v1/supplier-payments")
                        .header("Authorization", adminAuth)
                        .header("X-Request-Id", "req-api-039-pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"supplierId":%d,"amount":100,"bizDate":"2025-06-01",
                                 "allocations":[{"payableId":999999,"amount":100}]}
                                """.formatted(supplierId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PUR_PAYABLE_NOT_FOUND"));
    }
}
