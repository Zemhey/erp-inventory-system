package com.erp.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.inv.application.InventoryApplicationService;
import com.erp.iam.application.AuthenticationService;
import com.erp.pur.application.PurchaseOrderApplicationService;
import com.erp.sal.application.SalesOrderApplicationService;
import com.erp.support.CaseId;
import com.erp.support.PostgresTestBase;
import com.erp.support.TestClockConfig.ControllableClock;
import com.erp.support.TestDataFactory;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 报表接口的 HTTP 契约测试（TC-API-018 + TC-RPT-009）。
 *
 * <p>服务层用例（{@code ReportIT}）证明的是"数对"，本类证明的是"接口对"：
 * 状态码归因（参数错误必须是 400 而不是 500）、响应包络的字段名
 * （前端 S5b 直接按这些名字取值）、默认分组维度、以及
 * <b>三次报表调用之后库里一根毛都没动</b>（只读承诺只有在真实 HTTP 栈上才好验证）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("clock")
@DisplayName("接口契约 · 报表 HTTP")
class ReportApiIT extends PostgresTestBase {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AuthenticationService authentication;
    @Autowired
    private PasswordEncoder encoder;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PurchaseOrderApplicationService purchaseOrders;
    @Autowired
    private SalesOrderApplicationService salesOrders;
    @Autowired
    private InventoryApplicationService inventory;
    @Autowired
    private ControllableClock clock;

    private String auth;
    private long skuId;
    private long warehouseId;

    @BeforeEach
    void setUp() {
        var data = new TestDataFactory(jdbc);
        data.truncateAll();
        clock.set(LocalDateTime.of(2026, 3, 5, 10, 0, 0));
        data.user("rpt_admin", "Admin1!", "ADMIN", encoder);
        auth = "Bearer " + authentication.login("rpt_admin", "Admin1!").token();

        long supplierId = data.supplier("SUP-RPT-API");
        long customerId = data.customer("CUS-RPT-API");
        warehouseId = data.warehouse("WH-RPT-API");
        skuId = data.sku(data.product("P-RPT-API", "报表接口商品"), "白色", "L");

        var po = purchaseOrders.create(new PurchaseOrderApplicationService.CreateCommand(
                supplierId, warehouseId, LocalDate.of(2026, 3, 2), "接口备货",
                java.util.List.of(new PurchaseOrderApplicationService.CreateLineCommand(
                        skuId, 10, 1000)), "req-rpt-api-po"));
        purchaseOrders.submit(po.id());
        purchaseOrders.approve(po.id());
        purchaseOrders.receive(po.id(), new PurchaseOrderApplicationService.ReceiveCommand(
                java.util.List.of(new PurchaseOrderApplicationService.ReceiveLineCommand(
                        po.lines().get(0).id(), 10, null, null, null)), "req-rpt-api-recv"));

        var so = salesOrders.create(new SalesOrderApplicationService.CreateCommand(
                customerId, warehouseId, LocalDate.of(2026, 3, 3), "接口出货",
                java.util.List.of(new SalesOrderApplicationService.CreateLineCommand(
                        skuId, 3, 1000, 9000)), "req-rpt-api-so"));
        salesOrders.submit(so.id());
        salesOrders.approve(so.id());
        salesOrders.ship(so.id(), "req-rpt-api-ship");
    }

    @Test
    @CaseId("TC-API-018")
    @DisplayName("★ 三个报表接口全部可达且包络符合契约；默认分组是 supplier/customer；报表零写入")
    void reports_httpEnvelopeAndReadOnly() throws Exception {
        // ① 进货统计：省略 groupBy 时默认按供应商（契约 default: supplier）
        mvc.perform(get("/api/v1/reports/purchase")
                        .param("from", "2026-03-01").param("to", "2026-03-31")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.items[0].key").value("SUP-RPT-API"))
                .andExpect(jsonPath("$.data.items[0].label").value("测试供应商-SUP-RPT-API"))
                .andExpect(jsonPath("$.data.items[0].qty").value(10))
                .andExpect(jsonPath("$.data.items[0].amount").value(10000))
                .andExpect(jsonPath("$.data.items[0].orderCount").value(1));

        // ② 出货统计：默认按客户（契约 default: customer），金额 3×1000×9000/10000 = 2700
        mvc.perform(get("/api/v1/reports/sales")
                        .param("from", "2026-03-01").param("to", "2026-03-31")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].key").value("CUS-RPT-API"))
                .andExpect(jsonPath("$.data.items[0].qty").value(3))
                .andExpect(jsonPath("$.data.items[0].amount").value(2700));

        // ③ 进销存汇总：字段名与契约 InventorySummaryRow 逐字一致
        mvc.perform(get("/api/v1/reports/inventory-summary")
                        .param("period", "2026-03")
                        .param("warehouseId", String.valueOf(warehouseId))
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].skuId").value(skuId))
                .andExpect(jsonPath("$.data.items[0].warehouseId").value(warehouseId))
                .andExpect(jsonPath("$.data.items[0].openingQty").value(0))
                .andExpect(jsonPath("$.data.items[0].inboundQty").value(10))
                .andExpect(jsonPath("$.data.items[0].outboundQty").value(3))
                .andExpect(jsonPath("$.data.items[0].closingQty").value(7));

        // ④ 空区间同样是 200 + 空数组，不是 404/500
        mvc.perform(get("/api/v1/reports/purchase")
                        .param("from", "2020-01-01").param("to", "2020-01-31")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0));

        // ⑤ 未登录 401（报表同样要鉴权）
        mvc.perform(get("/api/v1/reports/purchase")
                        .param("from", "2026-03-01").param("to", "2026-03-31"))
                .andExpect(status().isUnauthorized());

        // ⑥ ★ 只读承诺：跑完上面全部调用，业务表计数与库存一致性都不许变
        assertThatCountsUnchanged();
    }

    @Test
    @CaseId("TC-RPT-009")
    @DisplayName("非法参数一律 400：groupBy 取值非法 / 跨报表维度 / from>to / period 格式 / 缺参数")
    void reports_invalidParams_thenBadRequest() throws Exception {
        // groupBy 不在契约 enum 里
        mvc.perform(get("/api/v1/reports/purchase")
                        .param("from", "2026-03-01").param("to", "2026-03-31")
                        .param("groupBy", "profit")
                        .header("Authorization", auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SYS_VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers
                        .containsString("groupBy")));

        // 维度合法但不属于这张报表：进货统计没有 customer
        mvc.perform(get("/api/v1/reports/purchase")
                        .param("from", "2026-03-01").param("to", "2026-03-31")
                        .param("groupBy", "customer")
                        .header("Authorization", auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SYS_VALIDATION_ERROR"));

        // 反过来：出货统计没有 supplier
        mvc.perform(get("/api/v1/reports/sales")
                        .param("from", "2026-03-01").param("to", "2026-03-31")
                        .param("groupBy", "supplier")
                        .header("Authorization", auth))
                .andExpect(status().isBadRequest());

        // from > to：闭区间无意义，必须 400 而不是"静返回空表"
        mvc.perform(get("/api/v1/reports/purchase")
                        .param("from", "2026-03-05").param("to", "2026-03-01")
                        .header("Authorization", auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SYS_VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers
                        .containsString("from")));

        // period 格式：月份越界 / 不是 yyyy-MM
        mvc.perform(get("/api/v1/reports/inventory-summary")
                        .param("period", "2026-13").header("Authorization", auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SYS_VALIDATION_ERROR"));
        mvc.perform(get("/api/v1/reports/inventory-summary")
                        .param("period", "202603").header("Authorization", auth))
                .andExpect(status().isBadRequest());

        // 必填参数缺失
        mvc.perform(get("/api/v1/reports/purchase")
                        .param("to", "2026-03-31").header("Authorization", auth))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/reports/inventory-summary")
                        .header("Authorization", auth))
                .andExpect(status().isBadRequest());

        // 日期格式错误同样是 400（不能落到兜底 500）
        mvc.perform(get("/api/v1/reports/purchase")
                        .param("from", "2026/03/01").param("to", "2026-03-31")
                        .header("Authorization", auth))
                .andExpect(status().isBadRequest());

        // 合法的 day / month 必须通
        mvc.perform(get("/api/v1/reports/sales")
                        .param("from", "2026-03-01").param("to", "2026-03-31")
                        .param("groupBy", "month")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].key").value("2026-03"));

        assertThatCountsUnchanged();
    }

    /** 报表是只读的：任何调用都不得产生单据、流水、余额或结算痕迹。 */
    private void assertThatCountsUnchanged() {
        Integer pos = jdbc.queryForObject("SELECT COUNT(*) FROM purchase_orders", Integer.class);
        Integer sos = jdbc.queryForObject("SELECT COUNT(*) FROM sales_orders", Integer.class);
        Integer ros = jdbc.queryForObject("SELECT COUNT(*) FROM return_orders", Integer.class);
        Integer led = jdbc.queryForObject("SELECT COUNT(*) FROM inventory_ledger", Integer.class);
        Integer bal = jdbc.queryForObject("SELECT COUNT(*) FROM inventory_balances", Integer.class);
        Integer res = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_reservations", Integer.class);

        org.assertj.core.api.Assertions.assertThat(pos).as("进货单不得增加").isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(sos).as("出货单不得增加").isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(ros).as("报表不得产生退货单").isZero();
        org.assertj.core.api.Assertions.assertThat(led)
                .as("流水条数不变（进货 1 条 + 出货 1 条）").isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(bal).as("余额行不得增加").isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(res)
                .as("预占已被出货消费，报表调用不得新增或释放").isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(inventory.checkLedgerConsistency())
                .as("跑完报表后不变量 INV-2 仍成立").isEmpty();
    }
}
