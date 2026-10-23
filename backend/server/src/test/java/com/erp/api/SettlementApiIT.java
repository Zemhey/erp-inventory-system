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

/**
 * 结算接口契约测试（HTTP 层）。
 *
 * <p><b>为什么结算特别需要走一次真实 MVC</b>：这一层是三件事的交汇处 ——
 * V5 种的权限码（{@code set:daily} / {@code set:monthly} / {@code set:reconcile}、
 * 新角色 FINANCE）、{@code PeriodGuard} 从单据接口冒出来的 409、
 * 以及契约里那三个响应包络的字段名。任何一件在服务层测不到：
 * 权限码拼错只会静默 403，锁账漏接只会静默放行，字段名错一个前端就显示空白。
 *
 * <p>计划书 §6 坑 11 明确要求"权限种子漏掉会以 403 形式伪装成接口坏了"，
 * 所以这里 ADMIN / FINANCE / VIEWER 三种角色各跑一次。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("接口契约 · 结算 HTTP")
class SettlementApiIT extends PostgresTestBase {

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

    private String adminAuth;
    private String financeAuth;
    private String viewerAuth;
    private long warehouseId;
    private long customerId;
    private long supplierId;
    private long skuId;

    @BeforeEach
    void setUp() throws Exception {
        TestDataFactory data = new TestDataFactory(jdbc);
        data.truncateAll();
        data.user("set_admin", "Admin1!", "ADMIN", encoder);
        data.user("set_finance", "Fin1!", "FINANCE", encoder);
        data.user("set_viewer", "Viewer1!", "VIEWER", encoder);
        adminAuth = "Bearer " + authentication.login("set_admin", "Admin1!").token();
        financeAuth = "Bearer " + authentication.login("set_finance", "Fin1!").token();
        viewerAuth = "Bearer " + authentication.login("set_viewer", "Viewer1!").token();

        supplierId = data.supplier("SUP-SET-API");
        warehouseId = data.warehouse("WH-SET-API");
        customerId = data.customer("CUS-SET-API");
        long productId = data.product("P-SET-API", "结算接口商品");
        skuId = data.sku(productId, "白色", "L");

        // 真实链路造一天的账：入库 10 件 / 10000 分，出货 4 件 @1000 分
        var po = purchaseOrders.create(new PurchaseOrderApplicationService.CreateCommand(
                supplierId, warehouseId, BIZ_DATE, "接口备货",
                List.of(new PurchaseOrderApplicationService.CreateLineCommand(skuId, 10, 1000L)),
                "req-set-api-po"));
        purchaseOrders.submit(po.id());
        purchaseOrders.approve(po.id());
        purchaseOrders.receive(po.id(), new PurchaseOrderApplicationService.ReceiveCommand(
                List.of(new PurchaseOrderApplicationService.ReceiveLineCommand(
                        po.lines().get(0).id(), 10, null, null, null)), "req-set-api-recv"));

        var so = salesOrders.create(new SalesOrderApplicationService.CreateCommand(
                customerId, warehouseId, BIZ_DATE, "接口出货",
                List.of(new SalesOrderApplicationService.CreateLineCommand(
                        skuId, 4, 1000L, null)), "req-set-api-so"));
        salesOrders.submit(so.id());
        salesOrders.approve(so.id());
        salesOrders.ship(so.id(), "req-set-api-ship");
    }

    @Test
    @CaseId("TC-API-016")
    @DisplayName("★ 结算 HTTP 端到端：日清 items/totals → 月结 CLOSED → 对账 balanced，字段符合契约")
    void settlement_httpFlow() throws Exception {
        mvc.perform(post("/api/v1/settlements/daily")
                        .header("Authorization", adminAuth)
                        .header("X-Request-Id", "req-api-set-daily")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                Map.of("bizDate", BIZ_DATE.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.bizDate").value("2026-10-08"))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].warehouseId").value(warehouseId))
                .andExpect(jsonPath("$.data.items[0].purchaseQty").value(10))
                .andExpect(jsonPath("$.data.items[0].purchaseAmount").value(10000))
                .andExpect(jsonPath("$.data.items[0].salesQty").value(4))
                .andExpect(jsonPath("$.data.items[0].salesAmount").value(4000))
                .andExpect(jsonPath("$.data.items[0].netAmount").value(4000))
                .andExpect(jsonPath("$.data.totals.purchaseAmount").value(10000))
                .andExpect(jsonPath("$.data.totals.returnQty").value(0))
                .andExpect(jsonPath("$.data.totals.returnAmount").value(0));

        // 指定不存在的仓库必须是干净的 404，而不是返回一份空结果
        mvc.perform(post("/api/v1/settlements/daily")
                        .header("Authorization", adminAuth)
                        .header("X-Request-Id", "req-api-set-daily-404")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "bizDate", BIZ_DATE.toString(), "warehouseId", 999999))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MDM_WAREHOUSE_NOT_FOUND"));

        mvc.perform(post("/api/v1/settlements/monthly")
                        .header("Authorization", financeAuth)
                        .header("X-Request-Id", "req-api-set-monthly")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("period", PERIOD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.period").value(PERIOD))
                .andExpect(jsonPath("$.data.status").value("CLOSED"))
                .andExpect(jsonPath("$.data.duplicate").value(false))
                .andExpect(jsonPath("$.data.closedAt").isString())
                .andExpect(jsonPath("$.data.purchaseAmount").value(10000))
                .andExpect(jsonPath("$.data.salesAmount").value(4000))
                .andExpect(jsonPath("$.data.netAmount").value(4000));

        mvc.perform(get("/api/v1/settlements/reconcile")
                        .param("period", PERIOD)
                        .header("Authorization", financeAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.period").value(PERIOD))
                .andExpect(jsonPath("$.data.generalTotal").value(4000))
                .andExpect(jsonPath("$.data.detailTotal").value(4000))
                .andExpect(jsonPath("$.data.diff").value(0))
                .andExpect(jsonPath("$.data.balanced").value(true));

        mvc.perform(post("/api/v1/settlements/monthly")
                        .header("Authorization", adminAuth)
                        .header("X-Request-Id", "req-api-set-monthly-again")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("period", PERIOD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.duplicate").value(true));

        Integer periodRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM settlement_periods", Integer.class);
        Integer dailyRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM daily_settlements", Integer.class);
        org.assertj.core.api.Assertions.assertThat(periodRows)
                .as("幂等重跑不得新增账期行").isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(dailyRows)
                .as("一个 (日期, 仓库) 只有一行").isEqualTo(1);
    }

    @Test
    @CaseId("TC-API-017")
    @DisplayName("★★ 结算守卫：锁账后开单 409；FINANCE 可结算不可开单；缺幂等键/非法账期是 400 不是 500")
    void settlement_guards() throws Exception {
        String purchaseBody = json.writeValueAsString(Map.of(
                "supplierId", supplierId,
                "warehouseId", warehouseId,
                "bizDate", LocalDate.of(2026, 10, 20).toString(),
                "lines", List.of(Map.of("skuId", skuId, "qty", 1, "unitPriceAmount", 1000))));

        // 未登录 / 只读角色 / 幂等键缺失 / 账期非法
        mvc.perform(post("/api/v1/settlements/daily")
                        .header("X-Request-Id", "req-api-set-anon")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("bizDate", BIZ_DATE.toString()))))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/v1/settlements/daily")
                        .header("Authorization", viewerAuth)
                        .header("X-Request-Id", "req-api-set-viewer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("bizDate", BIZ_DATE.toString()))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("IAM_FORBIDDEN"));

        mvc.perform(post("/api/v1/settlements/daily")
                        .header("Authorization", financeAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("bizDate", BIZ_DATE.toString()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SYS_VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value(containsString("X-Request-Id")));

        mvc.perform(post("/api/v1/settlements/daily")
                        .header("Authorization", financeAuth)
                        .header("X-Request-Id", "req-api-set-no-date")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SYS_VALIDATION_ERROR"));

        mvc.perform(get("/api/v1/settlements/reconcile")
                        .param("period", "2026-13")
                        .header("Authorization", adminAuth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SYS_VALIDATION_ERROR"));

        // 没月结过的账期不能对账（"还没结账" ≠ "账是平的"）
        mvc.perform(get("/api/v1/settlements/reconcile")
                        .param("period", "2026-09")
                        .header("Authorization", adminAuth))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SET_PERIOD_NOT_CLOSED"))
                .andExpect(jsonPath("$.details.period").value("2026-09"));

        // 职责分离（计划书 §7 决策 4）：FINANCE 能结算，但不能开单
        mvc.perform(post("/api/v1/purchase-orders")
                        .header("Authorization", financeAuth)
                        .header("X-Request-Id", "req-api-set-finance-po")
                        .contentType(MediaType.APPLICATION_JSON).content(purchaseBody))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("IAM_FORBIDDEN"));

        mvc.perform(post("/api/v1/settlements/monthly")
                        .header("Authorization", financeAuth)
                        .header("X-Request-Id", "req-api-set-lock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("period", PERIOD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CLOSED"));

        // 锁账经 HTTP 冒出来的必须是 409 + SET_PERIOD_CLOSED（前端要能按码提示，而不是笼统 500）
        mvc.perform(post("/api/v1/purchase-orders")
                        .header("Authorization", adminAuth)
                        .header("X-Request-Id", "req-api-set-locked-po")
                        .contentType(MediaType.APPLICATION_JSON).content(purchaseBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SET_PERIOD_CLOSED"))
                .andExpect(jsonPath("$.details.period").value(PERIOD));

        Integer purchaseRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM purchase_orders", Integer.class);
        org.assertj.core.api.Assertions.assertThat(purchaseRows)
                .as("403 与 409 两次开单都不能落库，只剩夹具那一张").isEqualTo(1);
    }
}
