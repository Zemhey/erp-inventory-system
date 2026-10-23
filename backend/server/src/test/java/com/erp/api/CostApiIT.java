package com.erp.api;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.iam.application.AuthenticationService;
import com.erp.pur.application.PurchaseOrderApplicationService;
import com.erp.sal.application.SalesOrderApplicationService;
import com.erp.support.CaseId;
import com.erp.support.PostgresTestBase;
import com.erp.support.TestClockConfig;
import com.erp.support.TestDataFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 成本毛利 HTTP 契约测试（TC-API-040~046）。
 *
 * <p>端点尚未写入 openapi.yaml（V2-4 只提申请单），但本测试直接打真实 MVC，
 * 证明路径、权限码、响应字段与幂等头在服务端真的成立 ——
 * 契约映射门禁负责把"未落契约"登记为显式债务。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("clock")
@DisplayName("接口契约 · 成本毛利 HTTP")
class CostApiIT extends PostgresTestBase {

    private static final LocalDate BIZ_DATE = LocalDate.of(2026, 10, 8);
    private static final String PERIOD = "2026-10";

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
    private TestClockConfig.ControllableClock clock;

    private String adminAuth;
    private String financeAuth;
    private String purchaserAuth;
    private long warehouseId;
    private long customerId;
    private long supplierId;
    private long skuId;

    @BeforeEach
    void setUp() throws Exception {
        // 先拨时钟再登录：JWT 的 exp 也由 ClockProvider 签发/校验，
        // 登录后再把时钟拨快一年，第一张 token 会立刻过期（本类曾因此 401）。
        clock.set(LocalDateTime.of(2026, 10, 8, 9, 0));

        TestDataFactory data = new TestDataFactory(jdbc);
        data.truncateAll();
        data.user("cst_admin", "Admin1!", "ADMIN", encoder);
        data.user("cst_finance", "Fin1!", "FINANCE", encoder);
        data.user("cst_purchaser", "Pur1!", "PURCHASER", encoder);
        adminAuth = "Bearer " + authentication.login("cst_admin", "Admin1!").token();
        financeAuth = "Bearer " + authentication.login("cst_finance", "Fin1!").token();
        purchaserAuth = "Bearer " + authentication.login("cst_purchaser", "Pur1!").token();

        supplierId = data.supplier("SUP-CST-API");
        warehouseId = data.warehouse("WH-CST-API");
        customerId = data.customer("CUS-CST-API");
        long productId = data.product("P-CST-API", "接口毛利商品");
        skuId = data.sku(productId, "蓝色", "XL");

        var po = purchaseOrders.create(new PurchaseOrderApplicationService.CreateCommand(
                supplierId, warehouseId, BIZ_DATE, "成本接口备货",
                List.of(new PurchaseOrderApplicationService.CreateLineCommand(
                        skuId, 10, 1000L)), "req-cst-api-po"));
        purchaseOrders.submit(po.id());
        purchaseOrders.approve(po.id());
        purchaseOrders.receive(po.id(), new PurchaseOrderApplicationService.ReceiveCommand(
                List.of(new PurchaseOrderApplicationService.ReceiveLineCommand(
                        po.lines().get(0).id(), 10, null, null, null)), "req-cst-api-recv"));

        var so = salesOrders.create(new SalesOrderApplicationService.CreateCommand(
                customerId, warehouseId, BIZ_DATE, "成本接口出货",
                List.of(new SalesOrderApplicationService.CreateLineCommand(
                        skuId, 4, 2000L, 10000)), "req-cst-api-so"));
        salesOrders.submit(so.id());
        salesOrders.approve(so.id());
        salesOrders.ship(so.id(), "req-cst-api-ship");
    }

    @Test
    @CaseId("TC-API-040")
    @DisplayName("POST /cost/recalculate：返回成本层数与守恒标志")
    void recalculateEndpoint() throws Exception {
        mvc.perform(post("/api/v1/cost/recalculate")
                        .header("Authorization", adminAuth)
                        .header("X-Request-Id", "req-api-040"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.layerCount").value(2))
                .andExpect(jsonPath("$.data.inboundCostAmount").value(10000))
                .andExpect(jsonPath("$.data.outboundCostAmount").value(4000))
                .andExpect(jsonPath("$.data.endingCostAmount").value(6000))
                .andExpect(jsonPath("$.data.balanced").value(true));
    }

    @Test
    @CaseId("TC-API-041")
    @DisplayName("POST /cost/monthly：月结字段完整，重复执行 duplicate=true")
    void monthlyEndpointIsIdempotent() throws Exception {
        mvc.perform(post("/api/v1/cost/monthly")
                        .header("Authorization", adminAuth)
                        .header("X-Request-Id", "req-api-041-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("period", PERIOD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.period").value(PERIOD))
                .andExpect(jsonPath("$.data.duplicate").value(false))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].skuId").value(skuId))
                .andExpect(jsonPath("$.data.items[0].openingQty").value(0))
                .andExpect(jsonPath("$.data.items[0].inboundQty").value(10))
                .andExpect(jsonPath("$.data.items[0].outboundQty").value(4))
                .andExpect(jsonPath("$.data.items[0].closingQty").value(6))
                .andExpect(jsonPath("$.data.items[0].closingCostAmount").value(6000))
                .andExpect(jsonPath("$.data.marginRecordCount").value(1));

        mvc.perform(post("/api/v1/cost/monthly")
                        .header("Authorization", adminAuth)
                        .header("X-Request-Id", "req-api-041-b")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("period", PERIOD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.duplicate").value(true));
    }

    @Test
    @CaseId("TC-API-042")
    @DisplayName("GET /cost/monthly：按账期查询月结行")
    void monthlyQueryEndpoint() throws Exception {
        closeMonthly("req-api-042-close");

        mvc.perform(get("/api/v1/cost/monthly")
                        .header("Authorization", adminAuth)
                        .param("period", PERIOD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].closingQty").value(6))
                .andExpect(jsonPath("$.data.items[0].closingCostAmount").value(6000));
    }

    @Test
    @CaseId("TC-API-043")
    @DisplayName("GET /reports/profit：按商品聚合毛利，金额与毛利率字段符合契约申请")
    void profitEndpoint() throws Exception {
        closeMonthly("req-api-043-close");

        mvc.perform(get("/api/v1/reports/profit")
                        .header("Authorization", adminAuth)
                        .param("from", PERIOD)
                        .param("to", PERIOD)
                        .param("groupBy", "product"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].key").value(String.valueOf(skuId)))
                .andExpect(jsonPath("$.data.items[0].qty").value(4))
                .andExpect(jsonPath("$.data.items[0].revenueAmount").value(8000))
                .andExpect(jsonPath("$.data.items[0].costAmount").value(4000))
                .andExpect(jsonPath("$.data.items[0].grossProfitAmount").value(4000))
                .andExpect(jsonPath("$.data.items[0].grossMarginRateBp").value(5000))
                .andExpect(jsonPath("$.data.items[0].orderCount").value(1));
    }

    @Test
    @CaseId("TC-API-044")
    @DisplayName("权限：FINANCE 有 cst:read，PURCHASER 无 cst:read 被 403")
    void permissions() throws Exception {
        mvc.perform(get("/api/v1/cost/monthly")
                        .header("Authorization", financeAuth)
                        .param("period", PERIOD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0));

        mvc.perform(get("/api/v1/cost/monthly")
                        .header("Authorization", purchaserAuth)
                        .param("period", PERIOD))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("IAM_FORBIDDEN"));
    }

    @Test
    @CaseId("TC-API-045")
    @DisplayName("参数校验：非法账期与非法 groupBy 都是 400 SYS_VALIDATION_ERROR")
    void validation() throws Exception {
        mvc.perform(post("/api/v1/cost/monthly")
                        .header("Authorization", adminAuth)
                        .header("X-Request-Id", "req-api-045-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("period", "2026-13"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SYS_VALIDATION_ERROR"));

        mvc.perform(get("/api/v1/reports/profit")
                        .header("Authorization", adminAuth)
                        .param("from", PERIOD)
                        .param("to", PERIOD)
                        .param("groupBy", "unknown"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SYS_VALIDATION_ERROR"));
    }

    @Test
    @CaseId("TC-API-046")
    @DisplayName("写接口缺少 X-Request-Id：400 而不是 500")
    void missingRequestIdHeader() throws Exception {
        mvc.perform(post("/api/v1/cost/recalculate")
                        .header("Authorization", adminAuth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SYS_VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value(containsString("X-Request-Id")));
    }

    private void closeMonthly(String requestId) throws Exception {
        mvc.perform(post("/api/v1/cost/monthly")
                        .header("Authorization", adminAuth)
                        .header("X-Request-Id", requestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("period", PERIOD))))
                .andExpect(status().isOk());
    }
}
