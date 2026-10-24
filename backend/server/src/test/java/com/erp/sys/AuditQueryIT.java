package com.erp.sys;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.erp.iam.application.AuthenticationService;
import com.erp.support.CaseId;
import com.erp.support.PostgresTestBase;
import com.erp.support.TestDataFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
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
 * 审计查询（FR-SYS-07）—— 按人 / 单据 / 时间 / 操作类型，<b>只读</b>。
 *
 * <p>本类把"四个查询维度"各写一条，并额外守三件事：
 * <ul>
 *   <li>审计接口<b>只有 GET</b>：任何写方法都不该存在（不可改）；</li>
 *   <li>非法过滤值返回 <b>400 而不是 500</b>（归因正确，前端才能提示）；</li>
 *   <li>无权限返回 <b>403</b>：审计表里有全公司的操作记录，不能谁都能看。</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("审计查询 · 按人/单据/时间/操作类型 + 只读 + 权限")
class AuditQueryIT extends PostgresTestBase {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private PasswordEncoder encoder;
    @Autowired
    private AuthenticationService authentication;

    private String adminAuth;
    private String viewerAuth;
    private TestDataFactory data;

    @BeforeEach
    void setUp() throws Exception {
        data = new TestDataFactory(jdbc);
        data.truncateAll();
        jdbc.execute("TRUNCATE audit_logs RESTART IDENTITY");
        data.user("q_admin", "QAdmin1!", "ADMIN", encoder);
        data.user("q_viewer", "QViewer1!", "VIEWER", encoder);
        adminAuth = "Bearer " + authentication.login("q_admin", "QAdmin1!").token();
        viewerAuth = "Bearer " + authentication.login("q_viewer", "QViewer1!").token();
        seedAudits();
    }

    /** 造三条可区分的审计：两个不同动作 + 两个不同单据号。 */
    private void seedAudits() throws Exception {
        createProduct("Q-P1", "查询商品一");
        createProduct("Q-P2", "查询商品二");
        mvc.perform(post("/api/v1/products/1/disable")
                .header("Authorization", adminAuth)
                .header("X-Request-Id", "query-seed-disable"));
    }

    // ─────────────────────────── 四个查询维度 ───────────────────────────

    @Test
    @CaseId("TC-SYS-051")
    @DisplayName("★ 按人查：actorName 过滤只返回该账号的审计")
    void filterByActor() throws Exception {
        JsonNode data = query("?actorName=q_admin");
        assertThat(data.path("total").asLong()).isGreaterThan(0L);
        assertThat(data.path("items")).allSatisfy(
                item -> assertThat(item.path("actorName").asText()).isEqualTo("q_admin"));
    }

    @Test
    @CaseId("TC-SYS-052")
    @DisplayName("★ 按操作类型查：action=CREATE 只返回建单审计")
    void filterByAction() throws Exception {
        JsonNode data = query("?action=CREATE");
        assertThat(data.path("total").asLong()).isEqualTo(2L);
        assertThat(data.path("items")).allSatisfy(
                item -> assertThat(item.path("action").asText()).isEqualTo("CREATE"));
    }

    @Test
    @CaseId("TC-SYS-053")
    @DisplayName("★ 按单据查：bizRefNo 过滤只返回该单据的审计")
    void filterByBizRefNo() throws Exception {
        String orderNo = seedPurchaseOrder("query-po-001");
        JsonNode data = query("?bizRefNo=" + orderNo);
        assertThat(data.path("total").asLong())
                .as("按单据号查必须精确命中该单的建单审计")
                .isGreaterThanOrEqualTo(1L);
        assertThat(data.path("items")).allSatisfy(
                item -> assertThat(item.path("bizRefNo").asText()).isEqualTo(orderNo));
    }

    @Test
    @CaseId("TC-SYS-054")
    @DisplayName("★ 按时间查：未来时间区间的结果为 0，过去区间的结果 > 0")
    void filterByTimeRange() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        JsonNode future = query("?from=" + now.plusDays(1) + "&to=" + now.plusDays(2));
        assertThat(future.path("total").asLong()).isZero();

        JsonNode past = query("?from=" + now.minusDays(1) + "&to=" + now.plusDays(1));
        assertThat(past.path("total").asLong()).isGreaterThan(0L);
    }

    @Test
    @CaseId("TC-SYS-055")
    @DisplayName("组合过滤：action=DISABLE 且 result=SUCCESS 同时生效")
    void filterCombination() throws Exception {
        JsonNode data = query("?action=DISABLE&result=SUCCESS");
        assertThat(data.path("total").asLong()).isEqualTo(1L);
        JsonNode item = data.path("items").get(0);
        assertThat(item.path("action").asText()).isEqualTo("DISABLE");
        assertThat(item.path("result").asText()).isEqualTo("SUCCESS");
    }

    @Test
    @CaseId("TC-SYS-056")
    @DisplayName("按资源类型与资源 id 过滤：能定位到具体某个商品的全部审计")
    void filterByResource() throws Exception {
        JsonNode data = query("?resourceType=PRODUCT&resourceId=1");
        assertThat(data.path("total").asLong()).isGreaterThanOrEqualTo(1L);
        assertThat(data.path("items")).allSatisfy(item -> {
            assertThat(item.path("resourceType").asText()).isEqualTo("PRODUCT");
            assertThat(item.path("resourceId").asText()).isEqualTo("1");
        });
    }

    // ─────────────────────────── 单据轨迹 / 详情 ───────────────────────────

    @Test
    @CaseId("TC-SYS-057")
    @DisplayName("单据轨迹按时间正序：建单在提交之前")
    void timeline_isChronological() throws Exception {
        String orderNo = seedPurchaseOrder("query-po-002");
        MvcResult result = mvc.perform(get("/api/v1/audits/timeline")
                        .header("Authorization", adminAuth)
                        .param("bizRefNo", orderNo))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode items = json.readTree(result.getResponse().getContentAsString()).path("data");
        assertThat(items).isNotEmpty();
        assertThat(items.get(0).path("action").asText())
                .as("轨迹第一条必须是建单（正序）")
                .isEqualTo("CREATE");
    }

    @Test
    @CaseId("TC-SYS-058")
    @DisplayName("审计详情：按 id 取回单条，字段完整")
    void detailById() throws Exception {
        long id = jdbc.queryForObject("SELECT min(id) FROM audit_logs", Long.class);
        MvcResult result = mvc.perform(get("/api/v1/audits/" + id)
                        .header("Authorization", adminAuth))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode item = json.readTree(result.getResponse().getContentAsString()).path("data");
        assertThat(item.path("id").asLong()).isEqualTo(id);
        assertThat(item.path("resourceType").asText()).isNotBlank();
        assertThat(item.path("occurredAt").asText()).isNotBlank();
    }

    @Test
    @CaseId("TC-SYS-059")
    @DisplayName("审计详情不存在：404 + SYS_AUDIT_NOT_FOUND（不是 500）")
    void detailNotFound() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/audits/999999999")
                        .header("Authorization", adminAuth))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("code").asText()).isEqualTo("SYS_AUDIT_NOT_FOUND");
    }

    // ─────────────────────────── 归因与权限 ───────────────────────────

    @Test
    @CaseId("TC-SYS-060")
    @DisplayName("非法 action 取值：400 + SYS_AUDIT_QUERY_INVALID（客户端问题不报成 500）")
    void invalidAction_returns400() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/audits")
                        .header("Authorization", adminAuth)
                        .param("action", "NOT_AN_ACTION"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(json.readTree(result.getResponse().getContentAsString())
                .path("code").asText()).isEqualTo("SYS_AUDIT_QUERY_INVALID");
    }

    @Test
    @CaseId("TC-SYS-061")
    @DisplayName("非法 result 取值：400 + SYS_AUDIT_QUERY_INVALID")
    void invalidResult_returns400() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/audits")
                        .header("Authorization", adminAuth)
                        .param("result", "MAYBE"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(json.readTree(result.getResponse().getContentAsString())
                .path("code").asText()).isEqualTo("SYS_AUDIT_QUERY_INVALID");
    }

    @Test
    @CaseId("TC-SYS-062")
    @DisplayName("时间区间倒置（from >= to）：400 + SYS_AUDIT_QUERY_INVALID")
    void invertedTimeRange_returns400() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        MvcResult result = mvc.perform(get("/api/v1/audits")
                        .header("Authorization", adminAuth)
                        .param("from", now.toString())
                        .param("to", now.minusDays(1).toString()))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(json.readTree(result.getResponse().getContentAsString())
                .path("code").asText()).isEqualTo("SYS_AUDIT_QUERY_INVALID");
    }

    @Test
    @CaseId("TC-SYS-063")
    @DisplayName("★ 无 sys:read 权限：403 IAM_FORBIDDEN（审计表里有全公司操作记录）")
    void roleWithoutSysRead_gets403() throws Exception {
        // SALESPERSON 没有任何 sys:* 权限码（V13 只给了 ADMIN / VIEWER / FINANCE）
        data.user("q_sales", "QSales1!", "SALESPERSON", encoder);
        String salesAuth = "Bearer " + authentication.login("q_sales", "QSales1!").token();
        MvcResult result = mvc.perform(get("/api/v1/audits").header("Authorization", salesAuth))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as("★ 没有 sys:read 的角色必须 403 —— 审计表不能被所有人随便看")
                .isEqualTo(403);
    }

    @Test
    @CaseId("TC-SYS-064")
    @DisplayName("未登录访问审计：401 IAM_UNAUTHENTICATED")
    void anonymous_gets401() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/audits")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @CaseId("TC-SYS-065")
    @DisplayName("VIEWER 持有 sys:read：能读审计（只读角色也应有审计可见性）")
    void viewerWithSysRead_canRead() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/audits").header("Authorization", viewerAuth))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as("V13 给 VIEWER 种了 sys:read：只读角色应能查看审计")
                .isEqualTo(200);
    }

    @Test
    @CaseId("TC-SYS-066")
    @DisplayName("★ 审计查询只有 GET：对 /audits 发 POST/PUT/DELETE 一律 405，永不写审计表")
    void auditEndpoint_hasNoWriteMethods() throws Exception {
        long before = countAudits();
        for (var builder : List.of(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/audits"),
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/audits"),
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/audits"))) {
            MvcResult result = mvc.perform(builder.header("Authorization", adminAuth)
                    .header("X-Request-Id", "audit-write-probe")
                    .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn();
            assertThat(result.getResponse().getStatus())
                    .as("审计端点不得接受写方法：只读资源的写尝试必须是 405，"
                            + "不能是 500（客户端用错方法不该被报成服务端故障）")
                    .isEqualTo(405);
        }
        assertThat(countAudits())
                .as("对审计端点的写尝试绝不能改动审计表")
                .isEqualTo(before);
    }

    @Test
    @CaseId("TC-SYS-067")
    @DisplayName("分页：size 超上限被钳制到 200，page 越界被钳制到 1")
    void pagination_isClamped() throws Exception {
        JsonNode data = query("?page=0&size=9999");
        assertThat(data.path("page").asInt()).isEqualTo(1);
        assertThat(data.path("size").asInt()).isEqualTo(200);
    }

    @Test
    @CaseId("TC-SYS-068")
    @DisplayName("列表默认按时间倒序：第一条是最新的审计")
    void list_isDescendingByTime() throws Exception {
        JsonNode data = query("");
        long first = data.path("items").get(0).path("id").asLong();
        Long maxId = jdbc.queryForObject("SELECT max(id) FROM audit_logs", Long.class);
        assertThat(first).isEqualTo(maxId);
    }

    // ─────────────────────────── 辅助 ───────────────────────────

    private JsonNode query(String queryString) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/audits" + queryString)
                        .header("Authorization", adminAuth))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private long countAudits() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs", Long.class);
        return n == null ? 0 : n;
    }

    private long createProduct(String code, String name) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/products").header("X-Request-Id", UUID.randomUUID().toString())
                        .header("Authorization", adminAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code": "%s", "name": "%s"}""".formatted(code, name)))
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString())
                .path("data").path("id").asLong();
    }

    private String seedPurchaseOrder(String requestId) throws Exception {
        long supplierId = data.supplier("SUP-Q");
        long warehouseId = data.warehouse("WH-Q");
        long productId = data.product("P-Q", "查询用商品");
        long skuId = data.sku(productId, "黑色", "M");
        MvcResult result = mvc.perform(post("/api/v1/purchase-orders")
                        .header("Authorization", adminAuth)
                        .header("X-Request-Id", requestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"supplierId": %d, "warehouseId": %d,
                                 "lines": [{"skuId": %d, "qty": 2, "unitPriceAmount": 500}]}"""
                                .formatted(supplierId, warehouseId, skuId)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return json.readTree(result.getResponse().getContentAsString())
                .path("data").path("orderNo").asText();
    }
}
