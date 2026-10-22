package com.erp.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.iam.application.AuthenticationService;
import com.erp.pur.application.PurchaseOrderApplicationService;
import com.erp.rma.application.ReturnOrderApplicationService;
import com.erp.rma.domain.ReturnOrder;
import com.erp.sal.application.SalesOrderApplicationService;
import com.erp.sal.domain.SalesOrder;
import com.erp.support.CaseId;
import com.erp.support.PostgresTestBase;
import com.erp.support.TestDataFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 退货接口契约测试（HTTP 层）。
 *
 * <p><b>为什么要单独测这一层</b>：{@code ApiContractMappingIT} 只证明"路径有实现"，
 * 服务层测试（{@code ReturnOrderIT}）又绕过 Spring MVC。参数校验、
 * 幂等键缺失的归因（400 而不是 500）、权限码到 403 的映射、响应包络的字段名，
 * 都只在真实 MVC 栈里才跑得到。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("接口契约 · 退货单 HTTP")
class ReturnOrderApiIT extends PostgresTestBase {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AuthenticationService authentication;
    @Autowired
    private PasswordEncoder encoder;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PurchaseOrderApplicationService purchaseOrders;
    @Autowired
    private SalesOrderApplicationService salesOrders;
    @Autowired
    private ReturnOrderApplicationService returnOrders;

    private String auth;
    private String viewerAuth;
    private long customerId;
    private long warehouseId;
    private long skuId;
    private SalesOrder origin;

    @BeforeEach
    void setUp() {
        var data = new TestDataFactory(jdbc);
        data.truncateAll();
        data.user("rma_admin", "Admin1!", "ADMIN", encoder);
        data.user("rma_viewer", "Viewer1!", "VIEWER", encoder);
        auth = "Bearer " + authentication.login("rma_admin", "Admin1!").token();
        viewerAuth = "Bearer " + authentication.login("rma_viewer", "Viewer1!").token();

        long supplierId = data.supplier("SUP-RMA-API");
        warehouseId = data.warehouse("WH-RMA-API");
        customerId = data.customer("CUS-RMA-API");
        long productId = data.product("P-RMA-API", "退货接口商品");
        skuId = data.sku(productId, "白色", "L");

        // 走真实链路造一张"已出货 4 件"的出货单，退货才有合法的基数
        var po = purchaseOrders.create(new PurchaseOrderApplicationService.CreateCommand(
                supplierId, warehouseId, LocalDate.of(2026, 10, 1), "接口备货",
                List.of(new PurchaseOrderApplicationService.CreateLineCommand(skuId, 10, 1000L)),
                "req-rma-api-po"));
        purchaseOrders.submit(po.id());
        purchaseOrders.approve(po.id());
        purchaseOrders.receive(po.id(), new PurchaseOrderApplicationService.ReceiveCommand(
                List.of(new PurchaseOrderApplicationService.ReceiveLineCommand(
                        po.lines().get(0).id(), 10, null, null, null)), "req-rma-api-recv"));

        var so = salesOrders.create(new SalesOrderApplicationService.CreateCommand(
                customerId, warehouseId, LocalDate.of(2026, 10, 2), "接口出货",
                List.of(new SalesOrderApplicationService.CreateLineCommand(
                        skuId, 4, 1000L, null)), "req-rma-api-so"));
        salesOrders.submit(so.id());
        salesOrders.approve(so.id());
        origin = salesOrders.ship(so.id(), "req-rma-api-ship");
    }

    @Test
    @CaseId("TC-API-014")
    @DisplayName("★ 退货 HTTP 端到端：建单→提交→审核→入库，响应字段符合契约；出货明细露出 returnedQty")
    void returnOrder_httpFlow() throws Exception {
        String body = json.writeValueAsString(Map.of(
                "originSalesOrderId", origin.id(),
                "bizDate", LocalDate.of(2026, 10, 3).toString(),
                "remark", "客户尺码不合",
                "lines", List.of(Map.of("originLineId", origin.lines().get(0).id(),
                        "qty", 2, "batchNo", "R-API-1"))));

        MvcResult created = mvc.perform(post("/api/v1/return-orders")
                        .header("Authorization", auth)
                        .header("X-Request-Id", "req-api-rma-create")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.orderNo").value(matchesPattern("RO\\d{8}-\\d{4,}")))
                .andExpect(jsonPath("$.data.originSalesOrderId").value(origin.id()))
                .andExpect(jsonPath("$.data.customerId").value(customerId))
                .andExpect(jsonPath("$.data.warehouseId").value(warehouseId))
                .andExpect(jsonPath("$.data.bizDate").value("2026-10-03"))
                .andExpect(jsonPath("$.data.period").value("2026-10"))
                .andExpect(jsonPath("$.data.totalQty").value(2))
                .andExpect(jsonPath("$.data.totalAmount").value(2000))
                .andExpect(jsonPath("$.data.lines[0].originLineId").value(origin.lines().get(0).id()))
                .andExpect(jsonPath("$.data.lines[0].skuId").value(skuId))
                .andExpect(jsonPath("$.data.lines[0].unitPriceAmount").value(1000))
                .andExpect(jsonPath("$.data.lines[0].discountRate").value(10000))
                .andExpect(jsonPath("$.data.lines[0].amount").value(2000))
                .andExpect(jsonPath("$.data.lines[0].batchNo").value("R-API-1"))
                .andReturn();

        long id = json.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        mvc.perform(post("/api/v1/return-orders/" + id + "/submit")
                        .header("Authorization", auth).header("X-Request-Id", "req-api-rma-submit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUBMITTED"));

        mvc.perform(post("/api/v1/return-orders/" + id + "/approve")
                        .header("Authorization", auth).header("X-Request-Id", "req-api-rma-approve"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));

        mvc.perform(post("/api/v1/return-orders/" + id + "/receive")
                        .header("Authorization", auth).header("X-Request-Id", "req-api-rma-receive"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));

        mvc.perform(get("/api/v1/return-orders/" + id).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.lines.length()").value(1));

        mvc.perform(get("/api/v1/return-orders")
                        .param("originSalesOrderId", String.valueOf(origin.id()))
                        .param("status", "COMPLETED")
                        .param("page", "1").param("size", "20")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(id));

        // ★ 入库后出货单响应必须露出累计退货量，退货页才能算"剩余可退"
        mvc.perform(get("/api/v1/sales-orders/" + origin.id()).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lines[0].shippedQty").value(4))
                .andExpect(jsonPath("$.data.lines[0].returnedQty").value(2));

        mvc.perform(get("/api/v1/return-orders/999999").header("Authorization", auth))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RMA_ORDER_NOT_FOUND"));
    }

    @Test
    @CaseId("TC-API-015")
    @DisplayName("退货接口守卫：缺 X-Request-Id → 400（不是 500）；VIEWER 可查不可建；未登录 401")
    void returnOrder_writeGuards() throws Exception {
        String body = json.writeValueAsString(Map.of(
                "originSalesOrderId", origin.id(),
                "lines", List.of(Map.of("originLineId", origin.lines().get(0).id(), "qty", 1))));

        // 建单漏传幂等键
        mvc.perform(post("/api/v1/return-orders")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SYS_VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value(containsString("X-Request-Id")));

        // 状态类动作同样要求幂等键（契约里 8 个写操作全部声明了它）
        ReturnOrder draft = returnOrders.create(new ReturnOrderApplicationService.CreateCommand(
                origin.id(), null, null,
                List.of(new ReturnOrderApplicationService.CreateLineCommand(
                        origin.lines().get(0).id(), 1, null, null, null)),
                "req-api-rma-guard-draft"));
        mvc.perform(post("/api/v1/return-orders/" + draft.id() + "/submit")
                        .header("Authorization", auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SYS_VALIDATION_ERROR"));

        // 只读角色：能看列表，不能建单
        mvc.perform(get("/api/v1/return-orders").header("Authorization", viewerAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));
        mvc.perform(post("/api/v1/return-orders")
                        .header("Authorization", viewerAuth)
                        .header("X-Request-Id", "req-api-rma-viewer")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("IAM_FORBIDDEN"));

        mvc.perform(post("/api/v1/return-orders")
                        .header("X-Request-Id", "req-api-rma-anon")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());

        // 两次被拒的建单都不能留下单据
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM return_orders", Integer.class);
        org.assertj.core.api.Assertions.assertThat(rows)
                .as("守卫失败的请求不得落库").isEqualTo(1);
    }
}
