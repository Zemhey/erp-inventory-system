package com.erp.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.inv.application.InventoryApplicationService;
import com.erp.iam.application.AuthenticationService;
import com.erp.pur.application.PurchaseOrderApplicationService;
import com.erp.sal.application.SalesOrderApplicationService;
import com.erp.sal.domain.SalesOrder;
import com.erp.support.CaseId;
import com.erp.support.PostgresTestBase;
import com.erp.support.TestDataFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 红冲接口的 HTTP 层测试（M2.5，FR-SAL-04 / FR-PUR-03）。
 *
 * <p><b>为什么服务层已经测过还要测这一层</b>：{@code ApiContractMappingIT} 只证明
 * "路径有实现"，服务层测试又绕开了 Spring MVC。void 这次新加了<b>必填幂等键</b>
 * （契约早就声明、实现一直没收），缺头必须归因成 400 + {@code SYS_VALIDATION_ERROR}
 * 而不是 500 —— 这类映射只在真实 MVC 栈里跑得到。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("接口契约 · 红冲 HTTP")
class ReversalApiIT extends PostgresTestBase {

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
    private InventoryApplicationService inventory;

    private String auth;
    private long supplierId;
    private long warehouseId;
    private long customerId;
    private long skuId;

    @BeforeEach
    void setUp() {
        var data = new TestDataFactory(jdbc);
        data.truncateAll();
        data.user("m25_admin", "Admin1!", "ADMIN", encoder);
        auth = "Bearer " + authentication.login("m25_admin", "Admin1!").token();

        supplierId = data.supplier("SUP-M25-API");
        warehouseId = data.warehouse("WH-M25-API");
        customerId = data.customer("CUS-M25-API");
        long productId = data.product("P-M25-API", "红冲接口商品");
        skuId = data.sku(productId, "白色", "L");
    }

    @Test
    @CaseId("TC-SAL-024")
    @DisplayName("★ 出货 void：缺幂等键 400；红冲 200 + 库存回补；同键重放 200；新键 409")
    void salesVoid_httpContract() throws Exception {
        SalesOrder shipped = shippedSalesOrder(4, "req-api-m25-so");
        assertThat(inventory.requireBalance(skuId, warehouseId, "").onHandQty()).isEqualTo(6);

        mvc.perform(post("/api/v1/sales-orders/" + shipped.id() + "/void")
                        .header("Authorization", auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SYS_VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value(containsString("X-Request-Id")));

        mvc.perform(post("/api/v1/sales-orders/" + shipped.id() + "/void")
                        .header("Authorization", auth)
                        .header("X-Request-Id", "req-api-m25-so-void"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.status").value("VOIDED"))
                .andExpect(jsonPath("$.data.orderNo").value(shipped.orderNo()));

        assertThat(inventory.requireBalance(skuId, warehouseId, "").onHandQty())
                .as("红冲必须把 4 件回补进实存")
                .isEqualTo(10);

        mvc.perform(post("/api/v1/sales-orders/" + shipped.id() + "/void")
                        .header("Authorization", auth)
                        .header("X-Request-Id", "req-api-m25-so-void"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("VOIDED"));

        mvc.perform(get("/api/v1/sales-orders/" + shipped.id()).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("VOIDED"))
                .andExpect(jsonPath("$.data.lines[0].shippedQty").value(4));

        mvc.perform(post("/api/v1/sales-orders/" + shipped.id() + "/void")
                        .header("Authorization", auth)
                        .header("X-Request-Id", "req-api-m25-so-void-new"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SAL_STATUS_ILLEGAL"));

        assertThat(inventory.ledgerEntries(shipped.orderNo(), "SALES_REVERSAL"))
                .as("两次重放与一次被拒都不该再多写流水")
                .hasSize(1);
        assertThat(inventory.checkLedgerConsistency()).isEmpty();
    }

    @Test
    @CaseId("TC-PUR-023")
    @DisplayName("★ 进货 void：缺幂等键 400；红冲 200 + 库存回退；同键重放 200；新键 409")
    void purchaseVoid_httpContract() throws Exception {
        var po = purchaseOrders.create(new PurchaseOrderApplicationService.CreateCommand(
                supplierId, warehouseId, LocalDate.of(2025, 6, 1), "接口红冲备货",
                List.of(new PurchaseOrderApplicationService.CreateLineCommand(skuId, 7, 1000L)),
                "req-api-m25-po"));
        purchaseOrders.submit(po.id());
        purchaseOrders.approve(po.id());
        var received = purchaseOrders.receive(po.id(), new PurchaseOrderApplicationService.ReceiveCommand(
                List.of(new PurchaseOrderApplicationService.ReceiveLineCommand(
                        po.lines().get(0).id(), 7, "B-API", null, null)), "req-api-m25-po-recv"));
        assertThat(inventory.requireBalance(skuId, warehouseId, "B-API").onHandQty()).isEqualTo(7);

        mvc.perform(post("/api/v1/purchase-orders/" + received.id() + "/void")
                        .header("Authorization", auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SYS_VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value(containsString("X-Request-Id")));

        mvc.perform(post("/api/v1/purchase-orders/" + received.id() + "/void")
                        .header("Authorization", auth)
                        .header("X-Request-Id", "req-api-m25-po-void"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("VOIDED"));

        assertThat(inventory.requireBalance(skuId, warehouseId, "B-API").onHandQty())
                .as("红冲必须把 7 件从实存里退掉")
                .isZero();

        mvc.perform(post("/api/v1/purchase-orders/" + received.id() + "/void")
                        .header("Authorization", auth)
                        .header("X-Request-Id", "req-api-m25-po-void"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("VOIDED"));

        mvc.perform(post("/api/v1/purchase-orders/" + received.id() + "/void")
                        .header("Authorization", auth)
                        .header("X-Request-Id", "req-api-m25-po-void-new"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PUR_STATUS_ILLEGAL"));

        assertThat(inventory.ledgerEntries(received.orderNo(), "PURCHASE_REVERSAL")).hasSize(1);
        assertThat(inventory.checkLedgerConsistency()).isEmpty();
    }

    private SalesOrder shippedSalesOrder(int qty, String requestId) {
        // 先备货 10 件（走真实进货单，保证 INV-2 成立）
        var po = purchaseOrders.create(new PurchaseOrderApplicationService.CreateCommand(
                supplierId, warehouseId, LocalDate.of(2025, 6, 1), "接口备货",
                List.of(new PurchaseOrderApplicationService.CreateLineCommand(skuId, 10, 1000L)),
                requestId + "-po"));
        purchaseOrders.submit(po.id());
        purchaseOrders.approve(po.id());
        purchaseOrders.receive(po.id(), new PurchaseOrderApplicationService.ReceiveCommand(
                List.of(new PurchaseOrderApplicationService.ReceiveLineCommand(
                        po.lines().get(0).id(), 10, null, null, null)), requestId + "-po-recv"));

        SalesOrder so = salesOrders.create(new SalesOrderApplicationService.CreateCommand(
                customerId, warehouseId, LocalDate.of(2025, 6, 1), "接口出货",
                List.of(new SalesOrderApplicationService.CreateLineCommand(skuId, qty, 1000L, null)),
                requestId));
        salesOrders.submit(so.id());
        salesOrders.approve(so.id());
        return salesOrders.ship(so.id(), requestId + "-ship");
    }
}
