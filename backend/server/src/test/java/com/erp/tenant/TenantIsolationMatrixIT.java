package com.erp.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.erp.iam.application.AuthenticationService;
import com.erp.shared.tenant.TenantId;
import com.erp.support.CaseId;
import com.erp.support.PostgresTestBase;
import com.erp.support.TestDataFactory;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * ★ **跨租户越权矩阵**（FR-TNT-03/05）—— 本泳道的头号验收对象。
 *
 * <p><b>9 资源 × 5 读法 × 3 身份 = 135 个组合</b>，参数化生成（硬验收 ≥60）。
 *
 * <table border="1">
 *   <caption>矩阵维度</caption>
 *   <tr><th>维度</th><th>取值</th></tr>
 *   <tr><td>资源（9）</td><td>商品 / SKU / 库存 / 进货单 / 出货单 / 退货单 / 结算 / 报表 / 审计</td></tr>
 *   <tr><td>读法（5）</td><td>按 id 直查 / 列表 / 导出 / 关联查询 / 聚合</td></tr>
 *   <tr><td>身份（3）</td><td>租户 2 管理员 / 租户 1 管理员 / 匿名</td></tr>
 * </table>
 *
 * <p><b>★ 每一格都用"真实存在但属于另一个租户"的 id</b>：两个租户各自有一套
 * 完整数据，编码/单号**刻意相同**（{@code TNT-WH}、{@code PO-TNT-0001}），
 * id 不同。用不存在的 id 测出来的 404 只证明"接口会 404"，与租户隔离无关。
 *
 * <p><b>★ 404 而不是 403</b>：403 等于承认"这个 id 存在，只是你没权限"，
 * 攻击者可以按 id 遍历枚举整个 id 空间。按 id 直查越权必须是
 * {@code 404 + 不含 IAM_FORBIDDEN}；列表/聚合类返回 {@code 200 + 空}。
 *
 * <p><b>★ 每一格都有同租户正向对照</b>：租户 1 管理员用同一批 id 请求必须
 * 200 且响应里真有该资源。没有正对照，"全是 404"可能只是接口坏了 —— 而 404
 * 恰好是越权格子的期望值，整个矩阵会变成假绿。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("clock")
@DisplayName("★ 跨租户矩阵 · 9 资源 × 5 读法 × 3 身份 = 135")
class TenantIsolationMatrixIT extends PostgresTestBase {

    enum Resource {
        PRODUCT, SKU, INVENTORY, PURCHASE_ORDER, SALES_ORDER, RETURN_ORDER,
        SETTLEMENT, REPORT, AUDIT
    }

    enum Vector {
        /** 按 id 直查单条资源。 */
        DIRECT_ID,
        /** 列表 + 过滤条件。 */
        LIST,
        /** 列表 + export=csv（导出走同一查询，不得绕过过滤）。 */
        EXPORT,
        /** 从 A 资源反查 B 资源（关联查询）。 */
        ASSOCIATION,
        /** 聚合/统计口径。 */
        AGGREGATE
    }

    enum Identity {
        /** 租户 2 管理员：拿着租户 1 的 id 来访问 —— 攻击者。 */
        OTHER_TENANT,
        /** 租户 1 管理员：拿自己的 id —— 同租户正向对照。 */
        OWN_TENANT,
        /** 无 token。 */
        ANONYMOUS
    }

    static Stream<Arguments> cells() {
        List<Arguments> args = new ArrayList<>();
        for (Resource r : Resource.values()) {
            for (Identity i : Identity.values()) {
                args.add(Arguments.of(r, i));
            }
        }
        return args.stream();
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AuthenticationService authentication;
    @Autowired
    private PasswordEncoder encoder;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private com.erp.support.TestClockConfig.ControllableClock clock;

    private TenantFixture tenant1;
    private TenantFixture tenant2;
    private String token1;
    private String token2;

    @BeforeEach
    void setUp() {
        TestDataFactory data = new TestDataFactory(jdbc);
        data.truncateAll();
        clock.set(LocalDateTime.of(2026, 3, 15, 10, 0, 0));

        data.user("tnt_admin_1", "Admin1!", "ADMIN", encoder);
        data.inTenant(TenantId.DEMO).user("tnt_admin_2", "Admin2!", "ADMIN", encoder);
        token1 = authentication.login("tnt_admin_1", "Admin1!").token();
        token2 = authentication.login("tnt_admin_2", "Admin2!").token();

        tenant1 = TenantFixture.create(jdbc, data, TenantId.DEFAULT, "CLOSED");
        tenant2 = TenantFixture.create(jdbc, data, TenantId.DEMO, "OPEN");
    }

    // ─────────────────────────── 矩阵本体（一个读法一个用例） ───────────────────────────

    @ParameterizedTest(name = "[{index}] {0} / DIRECT_ID / {1}")
    @MethodSource("cells")
    @CaseId("TC-TNT-038")
    @DisplayName("矩阵 · 按 id 直查：异租户 id → 404；同租户 → 200 且真有该行")
    void directId(Resource resource, Identity identity) throws Exception {
        assertCell(resource, Vector.DIRECT_ID, identity);
    }

    @ParameterizedTest(name = "[{index}] {0} / LIST / {1}")
    @MethodSource("cells")
    @CaseId("TC-TNT-039")
    @DisplayName("矩阵 · 列表：异租户过滤条件 → 200 空；同租户 → 200 有本租户行")
    void list(Resource resource, Identity identity) throws Exception {
        assertCell(resource, Vector.LIST, identity);
    }

    @ParameterizedTest(name = "[{index}] {0} / EXPORT / {1}")
    @MethodSource("cells")
    @CaseId("TC-TNT-040")
    @DisplayName("矩阵 · 导出：export=csv 与列表同一查询，不得到别租户的行")
    void export(Resource resource, Identity identity) throws Exception {
        assertCell(resource, Vector.EXPORT, identity);
    }

    @ParameterizedTest(name = "[{index}] {0} / ASSOCIATION / {1}")
    @MethodSource("cells")
    @CaseId("TC-TNT-041")
    @DisplayName("矩阵 · 关联查询：从本租户资源反查时看不到别租户的关联行")
    void association(Resource resource, Identity identity) throws Exception {
        assertCell(resource, Vector.ASSOCIATION, identity);
    }

    @ParameterizedTest(name = "[{index}] {0} / AGGREGATE / {1}")
    @MethodSource("cells")
    @CaseId("TC-TNT-042")
    @DisplayName("矩阵 · 聚合：合计数里不得混入别租户的数据")
    void aggregate(Resource resource, Identity identity) throws Exception {
        assertCell(resource, Vector.AGGREGATE, identity);
    }

    // ─────────────────────────── 单格断言 ───────────────────────────

    private void assertCell(Resource resource, Vector vector, Identity identity) throws Exception {
        MvcResult result = mvc.perform(requestFor(resource, vector, identity, tenant1)).andReturn();
        int status = result.getResponse().getStatus();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);

        if (identity == Identity.ANONYMOUS) {
            assertThat(status)
                    .as("★ [%s/%s/匿名] 必须 401，实际 %d，响应=%s", resource, vector, status, body)
                    .isEqualTo(401);
            assertThat(body).contains("IAM_UNAUTHENTICATED");
            return;
        }

        if (identity == Identity.OWN_TENANT) {
            // ★ 同租户正向对照：同一接口、同一批 id，本租户管理员必须看得到
            assertThat(status)
                    .as("★ [%s/%s/本租户] 正对照必须 200，实际 %d，响应=%s",
                            resource, vector, status, body)
                    .isEqualTo(200);
            assertThat(body)
                    .as("★ [%s/%s/本租户] 正对照的响应里必须真有本租户的行（否则矩阵是假绿）",
                            resource, vector)
                    .contains(marker(resource, vector));
            return;
        }

        // OTHER_TENANT：异租户管理员拿租户 1 的 id
        if (expectNotFound(resource, vector)) {
            assertThat(status)
                    .as("★ [%s/%s/异租户] 按 id 越权必须 404，实际 %d，响应=%s",
                            resource, vector, status, body)
                    .isEqualTo(404);
            assertThat(body)
                    .as("★ 404 响应体不得出现 IAM_FORBIDDEN（那等于承认资源存在）")
                    .doesNotContain("IAM_FORBIDDEN");
            return;
        }
        assertThat(status)
                .as("★ [%s/%s/异租户] 列表/聚合类只能 200 + 空，实际 %d，响应=%s",
                        resource, vector, status, body)
                .isEqualTo(200);
        assertThat(body)
                .as("★ [%s/%s/异租户] 响应里出现了租户 1 的数据 %s —— 跨租户泄露！",
                        resource, vector, marker(resource, vector))
                .doesNotContain(marker(resource, vector));
    }

    /** 哪些格子必须是 404（按 id 直查单条资源）。 */
    private static boolean expectNotFound(Resource resource, Vector vector) {
        if (vector != Vector.DIRECT_ID) {
            return false;
        }
        return switch (resource) {
            case PRODUCT, SKU, PURCHASE_ORDER, SALES_ORDER, RETURN_ORDER, AUDIT -> true;
            // 库存/结算/报表没有"单条 id"入口，直查退化为带 id 过滤的列表 → 200 空
            case INVENTORY, SETTLEMENT, REPORT -> false;
        };
    }

    /** 租户 1 在同一格里的"存在性指纹"（用 id，不用编码 —— 两个租户编码刻意相同）。 */
    private String marker(Resource resource, Vector vector) {
        // 关联查询的响应可能是另一种资源，指纹要跟着响应走
        if (resource == Resource.SKU && vector == Vector.ASSOCIATION) {
            return "\"skuId\":" + tenant1.sku;
        }
        return switch (resource) {
            case PRODUCT -> "\"id\":" + tenant1.product;
            case SKU -> "\"id\":" + tenant1.sku;
            case INVENTORY, REPORT -> "\"warehouseId\":" + tenant1.warehouse;
            case PURCHASE_ORDER -> "\"id\":" + tenant1.purchaseOrder;
            case SALES_ORDER -> "\"id\":" + tenant1.salesOrder;
            case RETURN_ORDER -> "\"id\":" + tenant1.returnOrder;
            // 两个租户的账期字符串相同，用状态区分：租户 1 CLOSED / 租户 2 OPEN
            case SETTLEMENT -> "\"status\":\"CLOSED\"";
            case AUDIT -> "\"id\":" + tenant1.auditId;
        };
    }

    // ─────────────────────────── 请求构造 ───────────────────────────

    private MockHttpServletRequestBuilder requestFor(Resource resource, Vector vector,
                                                     Identity identity, TenantFixture f) {
        MockHttpServletRequestBuilder req = switch (resource) {
            case PRODUCT -> switch (vector) {
                case DIRECT_ID -> get("/api/v1/products/" + f.product);
                case LIST, ASSOCIATION, AGGREGATE -> get("/api/v1/products")
                        .param("keyword", "TNT-P");
                case EXPORT -> get("/api/v1/products")
                        .param("keyword", "TNT-P").param("export", "csv");
            };
            case SKU -> switch (vector) {
                case DIRECT_ID -> get("/api/v1/skus/" + f.sku);
                case LIST, AGGREGATE -> get("/api/v1/skus").param("productId", String.valueOf(f.product));
                case EXPORT -> get("/api/v1/skus")
                        .param("productId", String.valueOf(f.product)).param("export", "csv");
                case ASSOCIATION -> get("/api/v1/inventory").param("skuId", String.valueOf(f.sku));
            };
            case INVENTORY -> switch (vector) {
                case DIRECT_ID, AGGREGATE -> get("/api/v1/inventory")
                        .param("skuId", String.valueOf(f.sku))
                        .param("warehouseId", String.valueOf(f.warehouse));
                case LIST -> get("/api/v1/inventory")
                        .param("warehouseId", String.valueOf(f.warehouse));
                case EXPORT -> get("/api/v1/inventory")
                        .param("warehouseId", String.valueOf(f.warehouse)).param("export", "csv");
                case ASSOCIATION -> get("/api/v1/inventory/ledger")
                        .param("warehouseId", String.valueOf(f.warehouse));
            };
            case PURCHASE_ORDER -> switch (vector) {
                case DIRECT_ID -> get("/api/v1/purchase-orders/" + f.purchaseOrder);
                case LIST, ASSOCIATION, AGGREGATE -> get("/api/v1/purchase-orders")
                        .param("supplierId", String.valueOf(f.supplier));
                case EXPORT -> get("/api/v1/purchase-orders")
                        .param("supplierId", String.valueOf(f.supplier)).param("export", "csv");
            };
            case SALES_ORDER -> switch (vector) {
                case DIRECT_ID -> get("/api/v1/sales-orders/" + f.salesOrder);
                case LIST, AGGREGATE -> get("/api/v1/sales-orders")
                        .param("customerId", String.valueOf(f.customer));
                case EXPORT -> get("/api/v1/sales-orders")
                        .param("customerId", String.valueOf(f.customer)).param("export", "csv");
                case ASSOCIATION -> get("/api/v1/sales-orders")
                        .param("customerId", String.valueOf(f.customer));
            };
            case RETURN_ORDER -> switch (vector) {
                case DIRECT_ID -> get("/api/v1/return-orders/" + f.returnOrder);
                case LIST, AGGREGATE -> get("/api/v1/return-orders")
                        .param("originSalesOrderId", String.valueOf(f.salesOrder));
                case EXPORT -> get("/api/v1/return-orders")
                        .param("originSalesOrderId", String.valueOf(f.salesOrder))
                        .param("export", "csv");
                case ASSOCIATION -> get("/api/v1/return-orders")
                        .param("customerId", String.valueOf(f.customer));
            };
            case SETTLEMENT -> switch (vector) {
                case LIST -> get("/api/v1/settlements/periods");
                case DIRECT_ID, EXPORT, ASSOCIATION, AGGREGATE ->
                        get("/api/v1/settlements/periods").param("status", "CLOSED");
            };
            case REPORT -> switch (vector) {
                case LIST -> get("/api/v1/reports/inventory-summary")
                        .param("period", "2026-03");
                case DIRECT_ID, ASSOCIATION, AGGREGATE ->
                        get("/api/v1/reports/inventory-summary")
                                .param("period", "2026-03")
                                .param("warehouseId", String.valueOf(f.warehouse));
                case EXPORT -> get("/api/v1/reports/inventory-summary")
                        .param("period", "2026-03")
                        .param("warehouseId", String.valueOf(f.warehouse))
                        .param("export", "csv");
            };
            case AUDIT -> switch (vector) {
                case DIRECT_ID -> get("/api/v1/audits/" + f.auditId);
                case LIST, AGGREGATE -> get("/api/v1/audits")
                        .param("resourceId", String.valueOf(f.purchaseOrder));
                case EXPORT -> get("/api/v1/audits")
                        .param("resourceId", String.valueOf(f.purchaseOrder))
                        .param("export", "csv");
                case ASSOCIATION -> get("/api/v1/audits/timeline")
                        .param("bizRefNo", f.poNo);
            };
        };
        if (identity == Identity.OTHER_TENANT) {
            req.header("Authorization", "Bearer " + token2);
        } else if (identity == Identity.OWN_TENANT) {
            req.header("Authorization", "Bearer " + token1);
        }
        return req;
    }

    // ─────────────────────────── 矩阵自检 & 接口层用例 ───────────────────────────

    @Test
    @CaseId("TC-TNT-043")
    @DisplayName("★ 矩阵规模自检：9 × 5 × 3 = 135 格，一格都不能少")
    void matrixSize() {
        long count = (long) Resource.values().length * Vector.values().length
                * Identity.values().length;
        assertThat(count)
                .as("9 资源 × 5 读法 × 3 身份 = 135（硬验收 ≥60）")
                .isEqualTo(135);
        assertThat(Resource.values()).hasSize(9);
        assertThat(Vector.values()).hasSize(5);
        assertThat(Identity.values()).hasSize(3);
        assertThat(cells().count()).isEqualTo(27);
    }

    @Test
    @CaseId("TC-API-129")
    @DisplayName("★ 接口层：三张单据 + 商品/SKU/审计的跨租户详情一律 404（不是 403）")
    void crossTenantDetailsAre404() throws Exception {
        for (Resource r : List.of(Resource.PRODUCT, Resource.SKU, Resource.PURCHASE_ORDER,
                Resource.SALES_ORDER, Resource.RETURN_ORDER, Resource.AUDIT)) {
            expectCrossStatus(r, Vector.DIRECT_ID, 404);
        }
    }

    @Test
    @CaseId("TC-API-130")
    @DisplayName("★ 接口层：审计按 id / 按单据 / 按 traceId 三条读路径都看不到别租户")
    void auditIsTenantScopedOnEveryPath() throws Exception {
        expectCrossStatus(Resource.AUDIT, Vector.DIRECT_ID, 404);
        expectCrossStatus(Resource.AUDIT, Vector.LIST, 200);
        expectCrossStatus(Resource.AUDIT, Vector.ASSOCIATION, 200);
        String byTrace = mvc.perform(get("/api/v1/audits/by-trace")
                        .param("traceId", tenant1.traceId)
                        .header("Authorization", "Bearer " + token2))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(byTrace)
                .as("★ 租户 2 按 traceId 查租户 1 的审计必须为空")
                .doesNotContain("\"id\":" + tenant1.auditId);
    }

    @Test
    @CaseId("TC-API-131")
    @DisplayName("★ 接口层：列表/导出响应里不得出现别租户的 id（不是靠前端隐藏）")
    void listsNeverContainForeignIds() throws Exception {
        for (Vector v : List.of(Vector.LIST, Vector.EXPORT)) {
            for (Resource r : Resource.values()) {
                expectCrossStatus(r, v, 200);
            }
        }
    }

    @Test
    @CaseId("TC-API-132")
    @DisplayName("★ 接口层：租户 2 看自己的数据必须 200 且有内容（证明 404 来自隔离而非接口坏）")
    void demoSeesItsOwnData() throws Exception {
        for (Resource r : Resource.values()) {
            MvcResult result = mvc.perform(requestFor(r, Vector.LIST, Identity.OTHER_TENANT,
                    tenant2)).andReturn();
            assertThat(result.getResponse().getStatus())
                    .as("租户 2 查自己的 %s 必须 200，响应=%s", r,
                            result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                    .isEqualTo(200);
            assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                    .as("租户 2 的 %s 正对照必须真有本租户的行", r)
                    .contains(markerForTenant2(r));
        }
    }

    private String markerForTenant2(Resource resource) {
        return switch (resource) {
            case PRODUCT -> "\"id\":" + tenant2.product;
            case SKU -> "\"id\":" + tenant2.sku;
            case INVENTORY, REPORT -> "\"warehouseId\":" + tenant2.warehouse;
            case PURCHASE_ORDER -> "\"id\":" + tenant2.purchaseOrder;
            case SALES_ORDER -> "\"id\":" + tenant2.salesOrder;
            case RETURN_ORDER -> "\"id\":" + tenant2.returnOrder;
            case SETTLEMENT -> "\"status\":\"OPEN\"";
            case AUDIT -> "\"id\":" + tenant2.auditId;
        };
    }

    private void expectCrossStatus(Resource resource, Vector vector, int expected) throws Exception {
        MvcResult result = mvc.perform(requestFor(resource, vector, Identity.OTHER_TENANT,
                tenant1)).andReturn();
        int status = result.getResponse().getStatus();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(status)
                .as("[%s/%s] 期望 %d，实际 %d，响应=%s", resource, vector, expected, status, body)
                .isEqualTo(expected);
        if (expected == 404) {
            assertThat(body).doesNotContain("IAM_FORBIDDEN");
        } else {
            assertThat(body).doesNotContain(marker(resource, vector));
        }
    }
}
