package com.erp.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.erp.iam.application.AuthenticationService;
import com.erp.pur.application.PurchaseOrderApplicationService;
import com.erp.sal.application.SalesOrderApplicationService;
import com.erp.support.CaseId;
import com.erp.support.PostgresTestBase;
import com.erp.support.TestDataFactory;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 账期查询接口（{@code GET /settlements/periods}）的 HTTP 层用例（计划书 §6 坑 15）。
 *
 * <p><b>这批用例盯的是一个"页面能进、数据为空"的真实缺陷</b>：S5b 上线结算页后实测 ——
 * 日清/月结是 POST、对账要 {@code set:reconcile}，于是只有 {@code set:read} 的账号
 * （VIEWER，以及 V5 里的 FINANCE）进得了 {@code /settlements} 却拿不到任何数据；
 * 更糟的是<b>月结结果无法回查</b>：{@code status / closedAt / duplicate} 只活在那一次
 * POST 的响应里，刷新即失。锁没锁账这个财务最关心的事实，页面上没有任何地方能再确认。
 *
 * <p>因此本类刻意用<b>真的只有 {@code set:read} 的临时账号</b>跑主用例
 * （不能用 ADMIN —— {@code PermissionAspect} 对 ROLE_ADMIN 直接短路，等于没测权限；
 * 也不能只用 VIEWER —— 它还带着 {@code sal:read} 等一堆码，遮蔽了"set:read 够不够"这个问题）。
 * 同时断言同一账号 POST 日清仍然 403：补读出口不许顺手把写口子也开大。
 *
 * <p>"月结结果能不能刷新后还在"这条（TC-SET-015）是直接对着 {@code POST /monthly}
 * 的响应逐字段比对的 —— 那才是坑 15 真正的病灶，而不是"列表里有没有行"。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("接口契约 · 账期查询 HTTP")
class SettlementPeriodApiIT extends PostgresTestBase {

    /** 本用例专用的临时角色与账号：只发 {@code set:read}，一个写动作码都没有。 */
    private static final String SET_READ_ROLE = "TC_SET_READ_ONLY";
    private static final String SET_READ_USER = "s5q-setread";

    private static final String CLOSED_PERIOD = "2026-10";
    private static final String OPEN_PERIOD = "2026-09";
    /** 直接落库的第二个已锁账期：用来验证 {@code closedBy} 与七个汇总字段逐字映射。 */
    private static final String SEALED_PERIOD = "2026-08";
    private static final LocalDate BIZ_DATE = LocalDate.of(2026, 10, 8);

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
    private String setReadAuth;
    private long adminId;

    @BeforeEach
    void setUp() {
        TestDataFactory data = new TestDataFactory(jdbc);
        data.truncateAll();
        adminId = data.user("s5q_admin", "Admin1!", "ADMIN", encoder);
        adminAuth = "Bearer " + authentication.login("s5q_admin", "Admin1!").token();
        createSetReadAccount(data);

        long supplierId = data.supplier("SUP-S5Q");
        long warehouseId = data.warehouse("WH-S5Q");
        long customerId = data.customer("CUS-S5Q");
        long productId = data.product("P-S5Q", "账期查询商品");
        long skuId = data.sku(productId, "白色", "L");

        // 真实链路造一天的账：入库 10 件 / 10000 分，出货 4 件 @1000 分 → 净额 4000 分
        var po = purchaseOrders.create(new PurchaseOrderApplicationService.CreateCommand(
                supplierId, warehouseId, BIZ_DATE, "补口备货",
                List.of(new PurchaseOrderApplicationService.CreateLineCommand(skuId, 10, 1000L)),
                "req-s5q-po"));
        purchaseOrders.submit(po.id());
        purchaseOrders.approve(po.id());
        purchaseOrders.receive(po.id(), new PurchaseOrderApplicationService.ReceiveCommand(
                List.of(new PurchaseOrderApplicationService.ReceiveLineCommand(
                        po.lines().get(0).id(), 10, null, null, null)), "req-s5q-recv"));

        var so = salesOrders.create(new SalesOrderApplicationService.CreateCommand(
                customerId, warehouseId, BIZ_DATE, "补口出货",
                List.of(new SalesOrderApplicationService.CreateLineCommand(skuId, 4, 1000L, null)),
                "req-s5q-so"));
        salesOrders.submit(so.id());
        salesOrders.approve(so.id());
        salesOrders.ship(so.id(), "req-s5q-ship");
    }

    @AfterEach
    void dropTemporaryRole() {
        // app_roles / app_role_permissions 不在 truncateAll 清单里：不清就会跟着复用容器漏给下一条用例
        jdbc.update("DELETE FROM app_users WHERE username = ?", SET_READ_USER);
        jdbc.update("DELETE FROM app_roles WHERE code = ?", SET_READ_ROLE);
    }

    @Test
    @CaseId("TC-SET-015")
    @DisplayName("★ 只有 set:read 的临时账号能拿到非空账期列表，且月结结果刷新后逐字段还在；同一账号 POST 日清仍 403")
    void periods_surviveRefreshForSetReadOnlyAccount() throws Exception {
        // 先经月结把 2026-10 真的锁上，并留下这次月结的响应作为比对基准
        MvcResult monthlyResult = mvc.perform(post("/api/v1/settlements/monthly")
                        .header("Authorization", adminAuth)
                        .header("X-Request-Id", "req-s5q-monthly")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("period", CLOSED_PERIOD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CLOSED"))
                .andExpect(jsonPath("$.data.duplicate").value(false))
                .andReturn();
        JsonNode monthly = json.readTree(
                        monthlyResult.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data");

        JsonNode data = getJson("/api/v1/settlements/periods", setReadAuth, Map.of());
        assertThat(periods(data)).as("set:read 账号拿到的是非空列表，而不是坑 15 里那片空白")
                .contains(CLOSED_PERIOD);
        JsonNode row = findByPeriod(data, CLOSED_PERIOD);
        assertThat(row.path("status").asText())
                .as("锁账状态可以刷新后回查（坑 15 的主症状）").isEqualTo("CLOSED");
        // 按毫秒对齐而不是逐字相等：注入时钟给到纳秒，而 TIMESTAMPTZ 只存微秒，
        // 写入时会被四舍五入（实测 .868212600 → .868213）。差不到 1µs 不等于"锁账时刻丢了"，
        // 但逐字比较会让这条用例随机红 —— 那才是真的会把人教成"忽略断言"。
        assertThat(Math.abs(instantOf(row.path("closedAt").asText()).toEpochMilli()
                - instantOf(monthly.path("closedAt").asText()).toEpochMilli()))
                .as("closedAt 刷新后仍是同一次月结的时刻")
                .isLessThanOrEqualTo(1L);

        // 七个汇总字段逐字对齐契约 SettlementTotals，且与月结响应一模一样
        for (String field : List.of("purchaseQty", "purchaseAmount", "salesQty", "salesAmount",
                "returnQty", "returnAmount", "netAmount")) {
            assertThat(row.path(field).asLong())
                    .as("字段 %s 必须与 POST /monthly 的响应逐字相同", field)
                    .isEqualTo(monthly.path(field).asLong());
        }
        assertThat(row.path("purchaseQty").asLong()).isEqualTo(10);
        assertThat(row.path("purchaseAmount").asLong()).isEqualTo(10000);
        assertThat(row.path("salesQty").asLong()).isEqualTo(4);
        assertThat(row.path("salesAmount").asLong()).isEqualTo(4000);
        assertThat(row.path("returnQty").asLong()).isZero();
        assertThat(row.path("netAmount").asLong()).isEqualTo(4000);

        // 补读口子不许顺手把写口子开大：同一账号执行日清必须还是 403
        mvc.perform(post("/api/v1/settlements/daily")
                        .header("Authorization", setReadAuth)
                        .header("X-Request-Id", "req-s5q-setread-daily")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("bizDate", BIZ_DATE.toString()))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("IAM_FORBIDDEN"));

        // 只读不等于免鉴权
        mvc.perform(get("/api/v1/settlements/periods"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @CaseId("TC-SET-016")
    @DisplayName("★ OPEN 账期的 closedAt/closedBy 为 null；CLOSED 行如实带出 closedBy 与期间汇总，字段名与契约一致")
    void periods_openRowHasNoCloseTimestamp() throws Exception {
        insertPeriod(OPEN_PERIOD, "OPEN", null, null, 0, 0, 0, 0, 0, 0);
        // 已锁的第二行：净额 = 销售 7000 − 退货 1200 = 5800，与 ck_period_net_amount 同口径
        insertPeriod(SEALED_PERIOD, "CLOSED", adminId, "2026-09-05T10:00:00Z",
                3, 3000, 9, 7000, 2, 1200);

        JsonNode data = getJson("/api/v1/settlements/periods", setReadAuth, Map.of());
        assertThat(periods(data)).as("按 period 倒序：未锁的 2026-09 排在已锁的 2026-08 之前")
                .containsExactly(OPEN_PERIOD, SEALED_PERIOD);

        JsonNode open = findByPeriod(data, OPEN_PERIOD);
        assertThat(open.path("status").asText()).isEqualTo("OPEN");
        assertThat(open.path("closedAt").isNull())
                .as("OPEN 行不得凭空造出锁账时间：%s", open)
                .isTrue();
        assertThat(open.path("closedBy").isNull())
                .as("OPEN 行不得凭空造出锁账操作人").isTrue();

        JsonNode sealed = findByPeriod(data, SEALED_PERIOD);
        assertThat(sealed.path("status").asText()).isEqualTo("CLOSED");
        // 比时刻而不是比字符串：OffsetDateTime.toString() 会省略秒为 0 的部分，
        // 而 pgjdbc 回读的偏移量取决于会话时区 —— 字符串相等是个假命题。
        assertThat(instantOf(sealed.path("closedAt").asText()))
                .isEqualTo(Instant.parse("2026-09-05T10:00:00Z"));
        assertThat(sealed.path("closedBy").asLong()).isEqualTo(adminId);
        assertThat(sealed.path("purchaseQty").asLong()).isEqualTo(3);
        assertThat(sealed.path("purchaseAmount").asLong()).isEqualTo(3000);
        assertThat(sealed.path("salesQty").asLong()).isEqualTo(9);
        assertThat(sealed.path("salesAmount").asLong()).isEqualTo(7000);
        assertThat(sealed.path("returnQty").asLong()).isEqualTo(2);
        assertThat(sealed.path("returnAmount").asLong()).isEqualTo(1200);
        assertThat(sealed.path("netAmount").asLong())
                .as("netAmount 是按契约派生出来的第 7 个字段，库里没有单独赋值")
                .isEqualTo(5800);

        // 月结路径本身不写 closed_by：2026-10 那一行必须如实为 null，而不是接口偷偷补一个值
        mvc.perform(post("/api/v1/settlements/monthly")
                        .header("Authorization", adminAuth)
                        .header("X-Request-Id", "req-s5q-monthly-016")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("period", CLOSED_PERIOD))))
                .andExpect(status().isOk());
        JsonNode after = getJson("/api/v1/settlements/periods", setReadAuth, Map.of());
        assertThat(findByPeriod(after, CLOSED_PERIOD).path("closedBy").isNull())
                .as("读出口只负责如实呈现：月结没写操作人，这里就不该有值")
                .isTrue();
    }

    @Test
    @CaseId("TC-SET-017")
    @DisplayName("★ status 筛选生效：CLOSED 只给已锁账期、OPEN 只给未锁，非法取值是 400 而不是 500")
    void periods_filtersByStatus() throws Exception {
        insertPeriod(OPEN_PERIOD, "OPEN", null, null, 0, 0, 0, 0, 0, 0);
        insertPeriod(SEALED_PERIOD, "CLOSED", adminId, "2026-09-05T10:00:00Z",
                0, 0, 1, 1000, 0, 0);

        assertThat(periods(getJson("/api/v1/settlements/periods", adminAuth,
                Map.of("status", "CLOSED"))))
                .as("status=CLOSED 只给已锁账的那一行，OPEN 行不得混进来")
                .containsExactly(SEALED_PERIOD);
        assertThat(periods(getJson("/api/v1/settlements/periods", adminAuth,
                Map.of("status", "OPEN"))))
                .containsExactly(OPEN_PERIOD);
        assertThat(periods(getJson("/api/v1/settlements/periods", adminAuth,
                Map.of("status", "open"))))
                .as("大小写不敏感（与报表 groupBy 同一套做法）")
                .containsExactly(OPEN_PERIOD);
        assertThat(periods(getJson("/api/v1/settlements/periods", adminAuth, Map.of())))
                .as("省略 status = 全部账期")
                .containsExactly(OPEN_PERIOD, SEALED_PERIOD);

        // 空串按"不筛选"处理，而不是 400：前端"全部"页签常以空参数提交
        assertThat(periods(getJson("/api/v1/settlements/periods", adminAuth,
                Map.of("status", ""))))
                .containsExactly(OPEN_PERIOD, SEALED_PERIOD);

        mvc.perform(get("/api/v1/settlements/periods")
                        .param("status", "LOCKED")
                        .header("Authorization", adminAuth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SYS_VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("OPEN | CLOSED")));

        // 筛选不影响账本：非法参数与三次查询都不许改动任何一行
        assertThat(count("settlement_periods")).isEqualTo(2);
    }

    @Test
    @CaseId("TC-SET-018")
    @DisplayName("★ 分页口径：total 是账期行数、按 period 倒序翻页，size 越界只钳制；GET 无副作用也不要求幂等键")
    void periods_pagesWithoutSideEffects() throws Exception {
        insertPeriod("2026-07", "CLOSED", null, "2026-08-05T00:00:00Z", 0, 0, 1, 100, 0, 0);
        insertPeriod(OPEN_PERIOD, "OPEN", null, null, 0, 0, 0, 0, 0, 0);
        insertPeriod(SEALED_PERIOD, "CLOSED", adminId, "2026-09-05T10:00:00Z", 0, 0, 2, 200, 0, 0);

        Map<String, String> params = Map.of("page", "1", "size", "1");
        JsonNode first = getJson("/api/v1/settlements/periods", adminAuth, params);
        assertThat(first.path("total").asLong()).isEqualTo(3);
        assertThat(first.path("size").asInt()).isEqualTo(1);
        assertThat(first.path("page").asInt()).isEqualTo(1);
        assertThat(periods(first)).containsExactly(OPEN_PERIOD);

        assertThat(periods(getJson("/api/v1/settlements/periods", adminAuth,
                Map.of("page", "2", "size", "1")))).containsExactly(SEALED_PERIOD);
        assertThat(periods(getJson("/api/v1/settlements/periods", adminAuth,
                Map.of("page", "3", "size", "1")))).containsExactly("2026-07");

        JsonNode beyond = getJson("/api/v1/settlements/periods", adminAuth,
                Map.of("page", "9", "size", "1"));
        assertThat(periods(beyond)).isEmpty();
        assertThat(beyond.path("total").asLong())
                .as("翻过头只给空页，total 仍是 3 —— 前端据此判断「可能没列全」")
                .isEqualTo(3);

        JsonNode clamped = getJson("/api/v1/settlements/periods", adminAuth, Map.of("size", "500"));
        assertThat(clamped.path("size").asInt())
                .as("size 上限 200 是钳制值而不是全量：越界要如实回显真实 size")
                .isEqualTo(200);
        assertThat(periods(clamped)).containsExactly(OPEN_PERIOD, SEALED_PERIOD, "2026-07");

        // 只读：不要求 X-Request-Id（那是写操作的规则），也不得留下任何写痕迹
        int periodsBefore = count("settlement_periods");
        int dailyBefore = count("daily_settlements");
        int ledgerBefore = count("inventory_ledger");
        int keysBefore = count("idempotency_keys");

        mvc.perform(get("/api/v1/settlements/periods").header("Authorization", setReadAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));

        assertThat(count("settlement_periods")).isEqualTo(periodsBefore);
        assertThat(count("daily_settlements")).isEqualTo(dailyBefore);
        assertThat(count("inventory_ledger")).isEqualTo(ledgerBefore);
        assertThat(count("idempotency_keys")).isEqualTo(keysBefore);
    }

    // ─────────────────────────── 造数辅助 ───────────────────────────

    /**
     * 建一个<b>只有 {@code set:read}</b> 的临时角色 + 账号并登录。
     *
     * <p>不能拿 VIEWER 顶替：它还带着一堆 {@code mdm/inv/pur/sal} 的读码，
     * 而"坑 15 只卡在 set:read 没有读出口"这件事必须在权限集合干净时才测得准。
     */
    private void createSetReadAccount(TestDataFactory data) {
        jdbc.update("""
                INSERT INTO app_roles (code, name, description)
                VALUES (?, '仅结算只读', 'TC-SET-015 专用：验证账期列表只依赖 set:read')
                ON CONFLICT (code) DO NOTHING
                """, SET_READ_ROLE);
        long roleId = jdbc.queryForObject("SELECT id FROM app_roles WHERE code = ?",
                Long.class, SET_READ_ROLE);
        jdbc.update("INSERT INTO app_role_permissions (role_id, permission) VALUES (?, 'set:read')",
                roleId);
        data.user(SET_READ_USER, "Setq1!", SET_READ_ROLE, encoder);
        setReadAuth = "Bearer " + authentication.login(SET_READ_USER, "Setq1!").token();
    }

    /**
     * 直接落一行账期。
     *
     * <p>刻意绕过月结：本接口读的就是"账上已经写了什么"，而 OPEN 行与带 {@code closed_by}
     * 的 CLOSED 行都不是当前月结路径能产出的形状（月结只写 CLOSED 且不写操作人）。
     * {@code net_amount} 按 {@code ck_period_net_amount} 的等式由销售减退货算出，
     * 免得夹具自己先违反库层不变量。
     */
    private void insertPeriod(String period, String status, Long closedBy, String closedAt,
                              long purchaseQty, long purchaseAmount, long salesQty,
                              long salesAmount, long returnQty, long returnAmount) {
        jdbc.update("""
                INSERT INTO settlement_periods
                    (period, status, purchase_qty, purchase_amount, sales_qty, sales_amount,
                     return_qty, return_amount, net_amount, closed_at, closed_by, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                """, period, status, purchaseQty, purchaseAmount, salesQty, salesAmount,
                returnQty, returnAmount, salesAmount - returnAmount,
                closedAt == null ? null : OffsetDateTime.parse(closedAt), closedBy);
    }

    // ─────────────────────────── 断言辅助 ───────────────────────────

    private JsonNode getJson(String path, String authorization, Map<String, String> params)
            throws Exception {
        MockHttpServletRequestBuilder request = get(path).header("Authorization", authorization);
        params.forEach(request::param);
        MvcResult result = mvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andReturn();
        // 显式按 UTF-8 解码：MockHttpServletResponse 的默认字符集是 ISO-8859-1
        return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data");
    }

    /**
     * 把响应的 date-time 字符串换成 {@link Instant} 再比较。
     *
     * <p>直接比字符串是个假命题：{@code OffsetDateTime.toString()} 在秒为 0 时会省略秒
     * （{@code 10:00:00Z} → {@code 10:00Z}），而 pgjdbc 回读 TIMESTAMPTZ 时的偏移量
     * 取决于会话时区。同一个时刻的两种写法都会让断言随机红。
     */
    private static Instant instantOf(String raw) {
        return OffsetDateTime.parse(raw).toInstant();
    }

    private static List<String> periods(JsonNode data) {
        List<String> out = new ArrayList<>();
        data.path("items").forEach(item -> out.add(item.path("period").asText()));
        return out;
    }

    private static JsonNode findByPeriod(JsonNode data, String period) {
        for (JsonNode item : data.path("items")) {
            if (period.equals(item.path("period").asText())) {
                return item;
            }
        }
        throw new AssertionError("响应里没有账期 " + period + "：" + data.path("items"));
    }

    private int count(String table) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return n == null ? 0 : n;
    }
}
