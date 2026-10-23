package com.erp.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.erp.iam.application.AuthenticationService;
import com.erp.pur.application.PurchaseOrderApplicationService;
import com.erp.rma.application.ReturnOrderApplicationService;
import com.erp.sal.application.SalesOrderApplicationService;
import com.erp.sal.domain.SalesOrder;
import com.erp.support.CaseId;
import com.erp.support.PostgresTestBase;
import com.erp.support.TestDataFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.nio.charset.StandardCharsets;
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
 * 可退出货单查询接口（{@code GET /return-orders/returnable-orders}）的 HTTP 层用例。
 *
 * <p><b>这批用例盯的是两个真实缺陷</b>（M2-S5a 登记，计划书 §6 坑 13 / 坑 14）：
 * <ul>
 *   <li>退货建单页原先要跨模块读 {@code /sales-orders}（{@code sal:read}），
 *       而 V3 给销售员的种子里没有它 —— 只有 {@code rma:*} 的销售员打开建单弹窗
 *       看到空下拉，页面长得像"系统里没有可退的单"，真相是没权限。
 *       TC-RMA-013 用<b>真的只有一个 {@code rma:read} 权限的临时账号</b>跑，
 *       并顺手证明同一账号读 {@code /sales-orders} 仍然 403（缺口确实存在，
 *       新接口不是变相开了个出货域的读口子）。</li>
 *   <li>没有批量取名与"只返回可退单"的接口，前端只能 PARTIAL/COMPLETED 各取最近 200 条。
 *       TC-RMA-017 证明 {@code total} 是可信的单据张数、翻页拿得到后面的单，
 *       以及"200 只是 size 上限、不是全量"。</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("接口契约 · 可退出货单查询 HTTP")
class ReturnableOrderApiIT extends PostgresTestBase {

    /** 本用例专用的临时角色与账号：只发 {@code rma:read}，一条 {@code sal:*} 都没有。 */
    private static final String RMA_ONLY_ROLE = "TC_RMA_ONLY_READ";
    private static final String RMA_ONLY_USER = "s5ret-rmaonly";
    private static final String CUSTOMER_CODE = "CUS-S5RET";
    private static final String PRODUCT_NAME = "可退接口商品";
    private static final LocalDate BIZ_DATE = LocalDate.of(2026, 10, 2);
    private static final LocalDate RETURN_DATE = LocalDate.of(2026, 10, 3);
    private static final long UNIT_PRICE = 1000L;
    private static final int NO_DISCOUNT = 10000;

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

    private String adminAuth;
    private String rmaOnlyAuth;
    private long customerId;
    private long warehouseId;
    private long skuA;
    private long skuB;

    /** 已出货、一件都没退：应当出现在列表里。 */
    private SalesOrder untouched;
    /** 两行明细、其中一行退了一部分：应当出现，且 returnableQty 要能对上。 */
    private SalesOrder partlyReturned;
    /** 整单退完：应当从列表里消失（坑 13 之外，这也是"下拉白列一堆建不了的单"的病）。 */
    private SalesOrder fullyReturned;
    /** 未出货的三种状态：都不得出现。 */
    private SalesOrder draft;
    private SalesOrder submitted;
    private SalesOrder approved;

    @BeforeEach
    void setUp() {
        var data = new TestDataFactory(jdbc);
        data.truncateAll();
        data.user("s5ret_admin", "Admin1!", "ADMIN", encoder);
        adminAuth = "Bearer " + authentication.login("s5ret_admin", "Admin1!").token();
        createRmaOnlyAccount(data);

        long supplierId = data.supplier("SUP-S5RET");
        warehouseId = data.warehouse("WH-S5RET");
        customerId = data.customer(CUSTOMER_CODE);
        long productId = data.product("P-S5RET", PRODUCT_NAME);
        skuA = data.sku(productId, "白色", "L");
        skuB = data.sku(productId, "白色", "M");

        // 预占在建单时就发生，库存必须罩得住下面所有出货单（含未出货的那三张）
        receive(supplierId, skuA, 20, "a");
        receive(supplierId, skuB, 10, "b");

        untouched = ship("clean", List.of(skuA), List.of(6), NO_DISCOUNT);
        partlyReturned = ship("part", List.of(skuA, skuB), List.of(4, 3), 9000);
        fullyReturned = ship("full", List.of(skuB), List.of(5), NO_DISCOUNT);
        draft = createOnly("draft", skuA, 2);
        submitted = submitOnly("submitted", skuA, 2);
        approved = approveOnly("approved", skuA, 2);

        returnAll("full", fullyReturned);
        returnQty("part", partlyReturned, partlyReturned.lines().get(0).id(), 2);
    }

    @AfterEach
    void dropTemporaryRole() {
        // app_roles / app_role_permissions 不在 truncateAll 清单里：不清就会跟着复用容器漏给下一条用例
        jdbc.update("DELETE FROM app_users WHERE username = ?", RMA_ONLY_USER);
        jdbc.update("DELETE FROM app_roles WHERE code = ?", RMA_ONLY_ROLE);
    }

    @Test
    @CaseId("TC-RMA-013")
    @DisplayName("★ 只有 rma:read 的临时账号也能拿到非空可退列表；同一账号读 /sales-orders 仍然 403")
    void returnableOrders_worksWithoutSalRead() throws Exception {
        JsonNode data = getJson("/api/v1/return-orders/returnable-orders", rmaOnlyAuth, Map.of());

        assertThat(orderNos(data))
                .as("可退列表 = 已出货且还有可退数量的单，整单退完与未出货的都不在")
                .containsExactlyInAnyOrder(partlyReturned.orderNo(), untouched.orderNo());
        assertThat(data.path("total").asLong()).isEqualTo(2);

        JsonNode clean = findByOrderNo(data, untouched.orderNo());
        assertThat(clean.path("orderId").asLong()).isEqualTo(untouched.id());
        assertThat(clean.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(clean.path("bizDate").asText()).isEqualTo("2026-10-02");
        assertThat(clean.path("customerId").asLong()).isEqualTo(customerId);
        assertThat(clean.path("customerName").asText())
                .as("客户名由服务端批量补齐，前端不必再拉主数据字典（坑 14）")
                .isEqualTo("测试客户-" + CUSTOMER_CODE);
        assertThat(clean.path("warehouseId").asLong()).isEqualTo(warehouseId);
        assertThat(clean.path("lines").size()).as("未出货的行不出现，本单只有一行").isEqualTo(1);
        assertThat(clean.path("lines").get(0).path("skuName").asText())
                .as("SKU 展示名带商品名，避免多个商品都有白色-L 时撞名")
                .isEqualTo(PRODUCT_NAME + " · 白色-L");

        // 缺口确实存在：这个账号读出货域接口必须被拒 —— 新接口没有变相放行 sal:read
        mvc.perform(get("/api/v1/sales-orders").header("Authorization", rmaOnlyAuth))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("IAM_FORBIDDEN"));
    }

    @Test
    @CaseId("TC-RMA-014")
    @DisplayName("已全部退完的出货单不出现在可退列表；未出货的 DRAFT/SUBMITTED/APPROVED 也不出现")
    void returnableOrders_exhaustedAndUnshippedAreAbsent() throws Exception {
        JsonNode data = getJson("/api/v1/return-orders/returnable-orders", rmaOnlyAuth, Map.of());
        List<String> nos = orderNos(data);

        assertThat(nos).doesNotContain(fullyReturned.orderNo());
        assertThat(jdbc.queryForObject(
                "SELECT shipped_qty - returned_qty FROM sales_order_lines WHERE order_id = ?",
                Integer.class, fullyReturned.id()))
                .as("fixture 确实退完了：可退 0，所以缺席不是造数没生效")
                .isZero();

        assertThat(nos)
                .as("未出货的单据没有任何已出货数量，退货建单不能选它")
                .doesNotContain(draft.orderNo(), submitted.orderNo(), approved.orderNo());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM sales_orders WHERE status IN ('DRAFT','SUBMITTED','APPROVED')",
                Integer.class))
                .as("库里真有这三张未出货的单")
                .isEqualTo(3);

        mvc.perform(get("/api/v1/return-orders/returnable-orders")
                        .header("Authorization", rmaOnlyAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.items[*].status").value(
                        org.hamcrest.Matchers.everyItem(
                                org.hamcrest.Matchers.anyOf(
                                        org.hamcrest.Matchers.is("PARTIAL"),
                                        org.hamcrest.Matchers.is("COMPLETED")))));
    }

    @Test
    @CaseId("TC-RMA-015")
    @DisplayName("★ returnableQty = shippedQty − returnedQty，且与退货建单预检同口径（超 1 件即 409）")
    void returnableOrders_returnableQtyMatchesCreateGuard() throws Exception {
        JsonNode data = getJson("/api/v1/return-orders/returnable-orders", rmaOnlyAuth, Map.of());
        JsonNode part = findByOrderNo(data, partlyReturned.orderNo());

        assertThat(part.path("lines").size())
                .as("两行都还有可退数量，行序按原单行号")
                .isEqualTo(2);
        for (JsonNode line : part.path("lines")) {
            assertThat(line.path("returnableQty").asInt())
                    .as("行 %d 的剩余可退必须等于 已出货 − 已退货", line.path("lineNo").asInt())
                    .isEqualTo(line.path("shippedQty").asInt() - line.path("returnedQty").asInt());
        }
        JsonNode first = part.path("lines").get(0);
        assertThat(first.path("originLineId").asLong())
                .isEqualTo(partlyReturned.lines().get(0).id());
        assertThat(first.path("shippedQty").asInt()).isEqualTo(4);
        assertThat(first.path("returnedQty").asInt()).isEqualTo(2);
        assertThat(first.path("returnableQty").asInt()).isEqualTo(2);
        assertThat(first.path("skuId").asLong()).isEqualTo(skuA);
        assertThat(first.path("unitPriceAmount").asLong()).isEqualTo(UNIT_PRICE);
        assertThat(first.path("discountRate").asInt())
                .as("折扣按原行原样带出（只读展示，建单请求体不接受它）")
                .isEqualTo(9000);

        long originLineId = first.path("originLineId").asLong();
        // 比可退多 1 件：预检必须给同一个上限口径
        mvc.perform(post("/api/v1/return-orders")
                        .header("Authorization", adminAuth)
                        .header("X-Request-Id", "req-s5ret-exceed")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "originSalesOrderId", part.path("orderId").asLong(),
                                "bizDate", RETURN_DATE.toString(),
                                "lines", List.of(Map.of("originLineId", originLineId, "qty", 3))))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RMA_QTY_EXCEED_ORIGIN"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("最多还能退 2")));

        // 恰好等于 returnableQty：能建成，且建单本身不改变可退数量（入库才登记退货量）
        mvc.perform(post("/api/v1/return-orders")
                        .header("Authorization", adminAuth)
                        .header("X-Request-Id", "req-s5ret-exact")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "originSalesOrderId", part.path("orderId").asLong(),
                                "bizDate", RETURN_DATE.toString(),
                                "lines", List.of(Map.of("originLineId", originLineId, "qty", 2))))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.totalQty").value(2))
                .andExpect(jsonPath("$.data.lines[0].unitPriceAmount").value(UNIT_PRICE))
                .andExpect(jsonPath("$.data.lines[0].discountRate").value(9000))
                .andExpect(jsonPath("$.data.lines[0].amount").value(1800));

        JsonNode after = getJson("/api/v1/return-orders/returnable-orders", rmaOnlyAuth, Map.of());
        assertThat(findByOrderNo(after, partlyReturned.orderNo()).path("lines").get(0)
                .path("returnableQty").asInt())
                .as("草稿退货单尚未入库，可退数量不得提前扣减")
                .isEqualTo(2);
    }

    @Test
    @CaseId("TC-RMA-016")
    @DisplayName("可退列表是只读接口：GET 不要求 X-Request-Id，也不得留下任何写痕迹")
    void returnableOrders_isReadOnlyWithoutIdempotencyKey() throws Exception {
        int returnsBefore = count("return_orders");
        int ledgerBefore = count("inventory_ledger");
        int balancesBefore = count("inventory_balances");

        mvc.perform(get("/api/v1/return-orders/returnable-orders")
                        .param("page", "1").param("size", "20")
                        .header("Authorization", adminAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].orderNo").isNotEmpty());

        // 缺幂等键的 GET 不该被拦成 400（那是写操作的规则）
        mvc.perform(get("/api/v1/return-orders/returnable-orders")
                        .header("Authorization", rmaOnlyAuth))
                .andExpect(status().isOk());

        assertThat(count("return_orders")).isEqualTo(returnsBefore);
        assertThat(count("inventory_ledger")).isEqualTo(ledgerBefore);
        assertThat(count("inventory_balances")).isEqualTo(balancesBefore);

        // 未登录仍然 401：只读不等于免鉴权
        mvc.perform(get("/api/v1/return-orders/returnable-orders"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @CaseId("TC-RMA-017")
    @DisplayName("★ 分页口径：total 是单据张数、翻页取得后面的单，size 越界只钳制不当全量")
    void returnableOrders_pagesInsteadOfCappingAtTwoHundred() throws Exception {
        JsonNode first = getJson("/api/v1/return-orders/returnable-orders", adminAuth,
                Map.of("page", "1", "size", "1"));
        assertThat(first.path("total").asLong()).isEqualTo(2);
        assertThat(first.path("size").asInt()).isEqualTo(1);
        assertThat(orderNos(first)).containsExactly(partlyReturned.orderNo());

        JsonNode second = getJson("/api/v1/return-orders/returnable-orders", adminAuth,
                Map.of("page", "2", "size", "1"));
        assertThat(orderNos(second)).containsExactly(untouched.orderNo());

        JsonNode clamped = getJson("/api/v1/return-orders/returnable-orders", adminAuth,
                Map.of("size", "500"));
        assertThat(clamped.path("size").asInt())
                .as("size 上限 200 是分页钳制值而不是「全量」：越界要如实回显真实 size")
                .isEqualTo(200);
        assertThat(clamped.path("total").asLong()).isEqualTo(2);

        JsonNode beyondLast = getJson("/api/v1/return-orders/returnable-orders", adminAuth,
                Map.of("page", "9", "size", "1"));
        assertThat(orderNos(beyondLast)).isEmpty();
        assertThat(beyondLast.path("total").asLong())
                .as("翻过头只给空页，total 仍是 2 —— 前端据此判断「可能没列全」")
                .isEqualTo(2);
    }

    // ─────────────────────────── 造数辅助 ───────────────────────────

    /**
     * 建一个<b>只有 {@code rma:read}</b> 的临时角色 + 账号并登录。
     *
     * <p>不能用 VIEWER 代替：VIEWER 有 {@code sal:read}，正好把要验证的缺口补掉了；
     * 也不能用 ADMIN（{@code PermissionAspect} 对 ROLE_ADMIN 直接短路放行）。
     */
    private void createRmaOnlyAccount(TestDataFactory data) {
        jdbc.update("""
                INSERT INTO app_roles (code, name, description)
                VALUES (?, '仅退货只读', 'TC-RMA-013 专用：验证可退列表不依赖 sal:read')
                ON CONFLICT (code) DO NOTHING
                """, RMA_ONLY_ROLE);
        long roleId = jdbc.queryForObject("SELECT id FROM app_roles WHERE code = ?",
                Long.class, RMA_ONLY_ROLE);
        jdbc.update("""
                INSERT INTO app_role_permissions (role_id, permission)
                VALUES (?, 'rma:read')
                ON CONFLICT DO NOTHING
                """, roleId);
        data.user(RMA_ONLY_USER, "Rma1!", RMA_ONLY_ROLE, encoder);
        rmaOnlyAuth = "Bearer " + authentication.login(RMA_ONLY_USER, "Rma1!").token();
    }

    private void receive(long supplierId, long skuId, int qty, String tag) {
        var po = purchaseOrders.create(new PurchaseOrderApplicationService.CreateCommand(
                supplierId, warehouseId, LocalDate.of(2026, 10, 1), "泳道备货",
                List.of(new PurchaseOrderApplicationService.CreateLineCommand(skuId, qty, UNIT_PRICE)),
                "req-s5ret-po-" + tag));
        purchaseOrders.submit(po.id());
        purchaseOrders.approve(po.id());
        purchaseOrders.receive(po.id(), new PurchaseOrderApplicationService.ReceiveCommand(
                List.of(new PurchaseOrderApplicationService.ReceiveLineCommand(
                        po.lines().get(0).id(), qty, null, null, null)),
                "req-s5ret-po-recv-" + tag));
    }

    /** 建单 → 提交 → 审核 → 出货，得到 COMPLETED 的出货单。 */
    private SalesOrder ship(String tag, List<Long> skus, List<Integer> qtys, int discountRate) {
        SalesOrder so = createWithLines(tag, skus, qtys, discountRate);
        salesOrders.submit(so.id());
        salesOrders.approve(so.id());
        return salesOrders.ship(so.id(), "req-s5ret-ship-" + tag);
    }

    private SalesOrder createOnly(String tag, long skuId, int qty) {
        return createWithLines(tag, List.of(skuId), List.of(qty), NO_DISCOUNT);
    }

    private SalesOrder submitOnly(String tag, long skuId, int qty) {
        SalesOrder so = createOnly(tag, skuId, qty);
        return salesOrders.submit(so.id());
    }

    private SalesOrder approveOnly(String tag, long skuId, int qty) {
        SalesOrder so = submitOnly(tag, skuId, qty);
        return salesOrders.approve(so.id());
    }

    private SalesOrder createWithLines(String tag, List<Long> skus, List<Integer> qtys,
                                       int discountRate) {
        List<SalesOrderApplicationService.CreateLineCommand> lines = new ArrayList<>();
        for (int i = 0; i < skus.size(); i++) {
            lines.add(new SalesOrderApplicationService.CreateLineCommand(
                    skus.get(i), qtys.get(i), UNIT_PRICE, discountRate));
        }
        return salesOrders.create(new SalesOrderApplicationService.CreateCommand(
                customerId, warehouseId, BIZ_DATE, "泳道出货 " + tag, lines, "req-s5ret-so-" + tag));
    }

    /** 整单退货并入库（推进到 COMPLETED，才会真正累加 returned_qty）。 */
    private void returnAll(String tag, SalesOrder origin) {
        List<ReturnOrderApplicationService.CreateLineCommand> lines = new ArrayList<>();
        origin.lines().forEach(l -> lines.add(new ReturnOrderApplicationService.CreateLineCommand(
                l.id(), l.shippedQty(), null, null, null)));
        runReturn(tag, origin, lines);
    }

    private void returnQty(String tag, SalesOrder origin, long originLineId, int qty) {
        runReturn(tag, origin, List.of(new ReturnOrderApplicationService.CreateLineCommand(
                originLineId, qty, null, null, null)));
    }

    private void runReturn(String tag, SalesOrder origin,
                           List<ReturnOrderApplicationService.CreateLineCommand> lines) {
        var ro = returnOrders.create(new ReturnOrderApplicationService.CreateCommand(
                origin.id(), RETURN_DATE, "泳道退货", lines, "req-s5ret-ret-" + tag));
        returnOrders.submit(ro.id());
        returnOrders.approve(ro.id());
        returnOrders.receive(ro.id(), "req-s5ret-ret-recv-" + tag);
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
        // 显式按 UTF-8 解码：MockHttpServletResponse 的默认字符集是 ISO-8859-1，
        // 不指定的话中文会读成乱码（服务端写出的字节本身是 UTF-8）。
        return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data");
    }

    private static List<String> orderNos(JsonNode data) {
        List<String> nos = new ArrayList<>();
        data.path("items").forEach(item -> nos.add(item.path("orderNo").asText()));
        return nos;
    }

    private static JsonNode findByOrderNo(JsonNode data, String orderNo) {
        for (JsonNode item : data.path("items")) {
            if (orderNo.equals(item.path("orderNo").asText())) {
                return item;
            }
        }
        throw new AssertionError("响应里没有单号 " + orderNo + "：" + data.path("items"));
    }

    private int count(String table) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return n == null ? 0 : n;
    }
}
