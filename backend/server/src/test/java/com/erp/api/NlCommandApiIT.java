package com.erp.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.iam.application.AuthenticationService;
import com.erp.pur.application.PurchaseOrderApplicationService;
import com.erp.shared.time.ClockProvider;
import com.erp.support.CaseId;
import com.erp.support.PostgresTestBase;
import com.erp.support.TestDataFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
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
 * 自然语言操作入口的 HTTP 层用例（FR-SYS-04，契约 {@code POST /nl/command}）。
 *
 * <p>本类刻意覆盖四种"NL 特有的失败方式"：
 * <ol>
 *   <li>解析失败（422）与参数失败（400）要分开，不能让用户以为是自己说错了还是系统坏了；</li>
 *   <li>意图级权限必须精确 —— 只有 {@code rpt:read} 的账号能查汇总，但读库存/主数据、建单都要 403；</li>
 *   <li>写意图的幂等键就是 HTTP 的 {@code X-Request-Id}，同键重放只能有一张单；</li>
 *   <li>业务错误（如单据不存在 404）要原样透出，不能被 NL 层包成 500。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("接口契约 · 自然语言操作 HTTP")
class NlCommandApiIT extends PostgresTestBase {

    private static final String RPT_ONLY_ROLE = "TC_NL_RPT_ONLY";
    private static final String RPT_ONLY_USER = "tc-nl-rpt";

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
    private ClockProvider clock;

    private String adminAuth;
    private String rptOnlyAuth;
    private long supplierId;
    private long warehouseId;
    private long skuId;
    private long draftPoId;
    private String draftPoNo;
    private LocalDate bizDate;

    @BeforeEach
    void setUp() {
        TestDataFactory data = new TestDataFactory(jdbc);
        data.truncateAll();
        data.user("nl_admin", "NlAdmin1!", "ADMIN", encoder);
        adminAuth = "Bearer " + authentication.login("nl_admin", "NlAdmin1!").token();
        createRptOnlyAccount(data);

        supplierId = data.supplier("SUP-NL");
        warehouseId = data.warehouse("WH-NL");
        long productId = data.product("P-NL", "NL 测试商品");
        skuId = data.sku(productId, "黑色", "M");
        bizDate = clock.today();

        // 一笔真实入库（10 件 @1000 分）—— 库存与进销存汇总都来自这条业务链路，不手工插余额
        var received = purchaseOrders.create(new PurchaseOrderApplicationService.CreateCommand(
                supplierId, warehouseId, bizDate, "NL 用例入库",
                List.of(new PurchaseOrderApplicationService.CreateLineCommand(skuId, 10, 1000L)),
                "req-nl-seed-recv"));
        purchaseOrders.submit(received.id());
        purchaseOrders.approve(received.id());
        purchaseOrders.receive(received.id(), new PurchaseOrderApplicationService.ReceiveCommand(
                List.of(new PurchaseOrderApplicationService.ReceiveLineCommand(
                        received.lines().get(0).id(), 10, null, null, null)), "req-nl-seed-receive"));

        // 一张草稿用于列表筛选与详情
        var draft = purchaseOrders.create(new PurchaseOrderApplicationService.CreateCommand(
                supplierId, warehouseId, bizDate, "NL 用例草稿",
                List.of(new PurchaseOrderApplicationService.CreateLineCommand(skuId, 2, 1200L)),
                "req-nl-seed-draft"));
        draftPoId = draft.id();
        draftPoNo = draft.orderNo();
    }

    @AfterEach
    void dropTemporaryRole() {
        jdbc.update("DELETE FROM app_users WHERE username = ?", RPT_ONLY_USER);
        Long roleId = jdbc.query("SELECT id FROM app_roles WHERE code = ?",
                rs -> rs.next() ? rs.getLong(1) : null, "TC_NL_RPT_ONLY");
        if (roleId != null) {
            jdbc.update("DELETE FROM app_role_permissions WHERE role_id = ?", roleId);
            jdbc.update("DELETE FROM app_roles WHERE id = ?", roleId);
        }
    }

    @Test
    @CaseId("TC-NL-006")
    @DisplayName("★ 查询库存：三量正确；未登录 401 —— NL 不会成为绕过认证的旁路")
    void queryInventory() throws Exception {
        JsonNode data = command(adminAuth, "查询 SKU %d 在仓库 %d 的库存".formatted(skuId, warehouseId),
                "req-nl-006");
        assertThat(data.path("intent").asText()).isEqualTo("query_inventory");
        assertThat(data.path("write").asBoolean()).isFalse();
        JsonNode items = data.path("data").path("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).path("skuId").asLong()).isEqualTo(skuId);
        assertThat(items.get(0).path("warehouseId").asLong()).isEqualTo(warehouseId);
        assertThat(items.get(0).path("onHandQty").asLong()).isEqualTo(10);
        assertThat(items.get(0).path("reservedQty").asLong()).isZero();
        assertThat(items.get(0).path("availableQty").asLong()).isEqualTo(10);
        assertThat(data.path("description").asText()).contains("实存合计 10").contains("可用合计 10");

        mvc.perform(post("/api/v1/nl/command")
                        .header("X-Request-Id", "req-nl-006-noauth")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("command", "查询库存"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @CaseId("TC-NL-007")
    @DisplayName("★ 单据列表/详情：中文状态与分页生效，详情 id 与列表行一致")
    void listAndGetDocuments() throws Exception {
        JsonNode list = command(adminAuth, "进货单列表 第1页 每页10", "req-nl-007-list");
        assertThat(list.path("intent").asText()).isEqualTo("list_documents");
        assertThat(list.path("data").path("total").asLong()).isEqualTo(2);
        assertThat(list.path("data").path("items")).hasSize(2);

        JsonNode drafts = command(adminAuth, "进货单列表 状态 草稿", "req-nl-007-draft");
        assertThat(drafts.path("data").path("total").asLong()).isEqualTo(1);
        assertThat(drafts.path("data").path("items").get(0).path("status").asText())
                .isEqualTo("DRAFT");
        assertThat(drafts.path("data").path("items").get(0).path("id").asLong())
                .isEqualTo(draftPoId);

        JsonNode detail = command(adminAuth, "查看进货单详情 id=" + draftPoId, "req-nl-007-detail");
        assertThat(detail.path("intent").asText()).isEqualTo("get_document");
        assertThat(detail.path("data").path("id").asLong()).isEqualTo(draftPoId);
        assertThat(detail.path("data").path("orderNo").asText()).isEqualTo(draftPoNo);
        assertThat(detail.path("data").path("lines").get(0).path("qty").asInt()).isEqualTo(2);
    }

    @Test
    @CaseId("TC-NL-008")
    @DisplayName("★ 写意图：建进货草稿成功；同 X-Request-Id 重放只有一张单；缺键 400")
    void createPurchaseOrderIsIdempotent() throws Exception {
        String command = "创建进货单 供应商=%d 仓库=%d SKU=%d 数量=3 单价=1200"
                .formatted(supplierId, warehouseId, skuId);
        JsonNode first = command(adminAuth, command, "req-nl-008-write");
        assertThat(first.path("intent").asText()).isEqualTo("create_purchase_order");
        assertThat(first.path("write").asBoolean()).isTrue();
        long orderId = first.path("data").path("id").asLong();
        assertThat(first.path("data").path("status").asText()).isEqualTo("DRAFT");
        assertThat(first.path("data").path("totalAmount").asLong()).isEqualTo(3600);

        JsonNode replay = command(adminAuth, command, "req-nl-008-write");
        assertThat(replay.path("data").path("id").asLong())
                .as("同一个 X-Request-Id 必须命中建单幂等，而不是生成第二张单")
                .isEqualTo(orderId);

        assertThat(count("SELECT COUNT(*) FROM purchase_orders")).isEqualTo(3);
        assertThat(count("SELECT COUNT(*) FROM purchase_orders WHERE request_id = 'req-nl-008-write'"))
                .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM inventory_ledger")).as("草稿不产生库存流水")
                .isEqualTo(1);

        mvc.perform(post("/api/v1/nl/command")
                        .header("Authorization", adminAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("command", command))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SYS_VALIDATION_ERROR"));
    }

    @Test
    @CaseId("TC-NL-009")
    @DisplayName("★ 进销存汇总：与 REST 报表逐字段一致；缺省账期=当前月")
    void inventorySummaryMatchesRest() throws Exception {
        String period = YearMonth.from(clock.now()).toString();
        JsonNode nl = command(adminAuth, "账期 %s 进销存汇总".formatted(period), "req-nl-009");
        assertThat(nl.path("intent").asText()).isEqualTo("inventory_summary");
        assertThat(nl.path("data").path("period").asText()).isEqualTo(period);

        MvcResult restResult = mvc.perform(get("/api/v1/reports/inventory-summary")
                        .param("period", period)
                        .header("Authorization", adminAuth))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode restItems = json.readTree(
                        restResult.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("items");
        assertThat(nl.path("data").path("items"))
                .as("NL 层只是调度 ReportApplicationService，结果必须与 REST 完全同源")
                .isEqualTo(restItems);
        assertThat(nl.path("data").path("items").get(0).path("closingQty").asLong()).isEqualTo(10);

        JsonNode defaulted = command(adminAuth, "进销存汇总", "req-nl-009-default");
        assertThat(defaulted.path("data").path("period").asText()).isEqualTo(period);
    }

    @Test
    @CaseId("TC-NL-010")
    @DisplayName("★ 意图级权限：只有 rpt:read 的账号能汇总，读库存/主数据与建单全部 403")
    void permissionsAreCheckedPerIntent() throws Exception {
        JsonNode summary = command(rptOnlyAuth, "账期 %s 进销存汇总"
                .formatted(YearMonth.from(clock.now())), "req-nl-010-summary");
        assertThat(summary.path("intent").asText()).isEqualTo("inventory_summary");

        expectForbidden(rptOnlyAuth, "查询库存", "req-nl-010-inv", "inv:read");
        expectForbidden(rptOnlyAuth, "查询商品 关键字 NL", "req-nl-010-mdm", "mdm:read");
        expectForbidden(rptOnlyAuth,
                "创建进货单 供应商=%d 仓库=%d SKU=%d 数量=1 单价=100"
                        .formatted(supplierId, warehouseId, skuId),
                "req-nl-010-write", "pur:create");

        JsonNode help = command(rptOnlyAuth, "帮助", "req-nl-010-help");
        assertThat(help.path("intent").asText()).isEqualTo("help");
        assertThat(help.path("data").path("intents").size()).isGreaterThanOrEqualTo(7);
    }

    @Test
    @CaseId("TC-NL-011")
    @DisplayName("★ 失败语义：无法识别 422、参数非法 400、空指令 400、业务 404 原样透出")
    void errorSemanticsAreStable() throws Exception {
        expectError(adminAuth, "今天天气怎么样", "req-nl-011-unknown",
                422, "NL_COMMAND_UNRECOGNIZED", "支持的意图");
        expectError(adminAuth, "query_inventory skuId=abc", "req-nl-011-arg",
                400, "NL_COMMAND_INVALID_ARGUMENT", "必须是整数");
        expectError(adminAuth, "查看进货单详情 id=999999", "req-nl-011-404",
                404, "PUR_ORDER_NOT_FOUND", "进货单不存在");

        mvc.perform(post("/api/v1/nl/command")
                        .header("Authorization", adminAuth)
                        .header("X-Request-Id", "req-nl-011-blank")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("command", "   "))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SYS_VALIDATION_ERROR"));

        mvc.perform(post("/api/v1/nl/command")
                        .header("Authorization", adminAuth)
                        .header("X-Request-Id", "short")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("command", "帮助"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SYS_VALIDATION_ERROR"));
    }

    // ─────────────────────────── 辅助 ───────────────────────────

    private JsonNode command(String authorization, String command, String requestId) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/nl/command")
                        .header("Authorization", authorization)
                        .header("X-Request-Id", requestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("command", command))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data");
    }

    private void expectForbidden(String authorization, String command, String requestId,
                                 String permission) throws Exception {
        mvc.perform(post("/api/v1/nl/command")
                        .header("Authorization", authorization)
                        .header("X-Request-Id", requestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("command", command))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("IAM_FORBIDDEN"))
                .andExpect(jsonPath("$.message").value(containsString(permission)));
    }

    private void expectError(String authorization, String command, String requestId,
                             int httpStatus, String code, String messagePart) throws Exception {
        mvc.perform(post("/api/v1/nl/command")
                        .header("Authorization", authorization)
                        .header("X-Request-Id", requestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("command", command))))
                .andExpect(status().is(httpStatus))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.message").value(containsString(messagePart)));
    }

    private void createRptOnlyAccount(TestDataFactory data) {
        jdbc.update("""
                INSERT INTO app_roles (code, name, description)
                VALUES (?, '仅报表只读', 'M7 NL 用例：验证意图级权限')
                ON CONFLICT (code) DO NOTHING
                """, RPT_ONLY_ROLE);
        Long roleId = jdbc.queryForObject("SELECT id FROM app_roles WHERE code = ?",
                Long.class, RPT_ONLY_ROLE);
        jdbc.update("""
                INSERT INTO app_role_permissions (role_id, permission) VALUES (?, 'rpt:read')
                ON CONFLICT DO NOTHING
                """, roleId);
        data.user(RPT_ONLY_USER, "NlRpt1!", RPT_ONLY_ROLE, encoder);
        rptOnlyAuth = "Bearer " + authentication.login(RPT_ONLY_USER, "NlRpt1!").token();
    }

    private int count(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }
}
