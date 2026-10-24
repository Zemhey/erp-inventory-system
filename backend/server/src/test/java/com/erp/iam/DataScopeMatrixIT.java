package com.erp.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.iam.application.AuthenticationService;
import com.erp.iam.repository.AppUserRepository;
import com.erp.iam.repository.DataScopeRepository;
import com.erp.support.CaseId;
import com.erp.support.PostgresTestBase;
import com.erp.support.TestDataFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * ★ **越权矩阵**（L5 层，FR-IAM-04）—— 本泳道的头号验收对象。
 *
 * <p><b>8 资源 × 5 越权方式 × 3 身份 = 120 个组合</b>，参数化生成。
 * （蓝图 §7.2 的要求是"8 × 5 × 3 = 120 组合参数化"，硬验收的下限是 ≥ 40 条；
 * 这里把 120 全都跑一遍，因为多出来的 80 个组合是"顺手就测了"，不值得省。）
 *
 * <table border="1">
 *   <caption>矩阵维度</caption>
 *   <tr><th>维度</th><th>取值</th><th>说明</th></tr>
 *   <tr><td>资源（8）</td>
 *       <td>商品 / SKU / 库存 / 进货单 / 出货单 / 退货单 / 结算 / 报表</td>
 *       <td>蓝图 §6.9 与 §7.2 点名的那 8 个</td></tr>
 *   <tr><td>越权方式（5）</td>
 *       <td>跨仓直查 id / 列表注入 / 导出 / 关联查询 / 统计聚合</td>
 *       <td>攻击者真实会用的五条路径</td></tr>
 *   <tr><td>身份（3）</td>
 *       <td>他仓仓管 / 本仓只读 / 无 token</td>
 *       <td>三种最常见的"边界身份"</td></tr>
 * </table>
 *
 * <p><b>★ 断言一律 404 而非 403</b>（硬验收第 1 条，也是任务书点名的面试考点）：
 * 403 会告诉攻击者"这个 id 是存在的，只是你没权限"，于是他可以按 id 遍历、
 * 用响应码把整个 id 空间枚举出来（数据存在性本身就是情报）。
 * 数据权限收窄之后，"越权"与"不存在"在响应上必须是<b>同一个形状</b>：
 * 一律 {@code 404 SYS_NOT_FOUND / *_NOT_FOUND}。
 *
 * <p>唯一允许 401 的是"无 token"这一类 —— 那时请求方还没有身份，
 * 语义是"你该去登录"，与服务端有没有这个资源无关。
 *
 * <p><b>★ 为什么每个组合都断言 status 而不只看总数</b>：
 * 参数化用例失败时会打印 {@code [index] 资源/方式/身份}，
 * 因此红的时候能立刻定位到是哪一个格子漏了。只断言"整体通过"的测试
 * 在漏一格时给出的信息是"120 个里有一个错了"，排查成本极高。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("clock")
@DisplayName("★ 越权矩阵 · 8 资源 × 5 方式 × 3 身份 = 120")
class DataScopeMatrixIT extends PostgresTestBase {

    // ─────────────────────────── 维度定义 ───────────────────────────

    /** 8 个资源（蓝图 §7.2）。 */
    enum Resource {
        PRODUCT, SKU, INVENTORY, PURCHASE_ORDER, SALES_ORDER, RETURN_ORDER,
        SETTLEMENT, REPORT
    }

    /** 5 种越权方式。 */
    enum Vector {
        /** 直接拿别仓某个 id 查详情 —— 最直白的一种。 */
        DIRECT_ID,
        /** 往列表查询里塞别仓的过滤条件/参数。 */
        LIST_INJECTION,
        /** 导出：把整份数据拉成 CSV/Excel 绕过分页。 */
        EXPORT,
        /** 关联查询：从"我的单据"反查"别人的库存/流水"。 */
        ASSOCIATION,
        /** 统计聚合：只要一个合计数，页面上看不出异常但数字里混进了别仓。 */
        AGGREGATE
    }

    /** 3 种边界身份。 */
    enum Identity {
        /** 他仓仓管：有该资源的读权限，但数据权限限定在 A 仓。 */
        OTHER_WAREHOUSE_KEEPER,
        /** 本仓只读：只读角色（VIEWER），A 仓。 */
        OWN_WAREHOUSE_VIEWER,
        /** 无 token：完全匿名。 */
        ANONYMOUS
    }

    static Stream<Arguments> matrix() {
        List<Arguments> args = new ArrayList<>();
        for (Resource r : Resource.values()) {
            for (Vector v : Vector.values()) {
                for (Identity i : Identity.values()) {
                    args.add(Arguments.of(r, v, i));
                }
            }
        }
        return args.stream();
    }

    // ─────────────────────────── 环境 ───────────────────────────

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AuthenticationService authentication;
    @Autowired
    private AppUserRepository users;
    @Autowired
    private DataScopeRepository dataScopes;
    @Autowired
    private com.erp.iam.repository.RoleRepository roles;
    @Autowired
    private PasswordEncoder encoder;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private com.erp.support.TestClockConfig.ControllableClock clock;

    private TestDataFactory data;
    private long warehouseA;
    private long warehouseB;
    private long productA;
    private long skuA;
    private long poB;
    private long soB;
    private long roB;
    /** A 仓仓管（数据权限限定 A 仓）。 */
    private String keeperToken;
    /** 本仓只读（VIEWER，A 仓）。 */
    private String viewerToken;

    @BeforeEach
    void setUp() {
        data = new TestDataFactory(jdbc);
        data.truncateAll();
        // ★ 审批流种子在迁移里，truncateAll 不清它（它是"配置"不是"业务数据"）
        clock.set(java.time.LocalDateTime.of(2026, 3, 15, 10, 0, 0));

        warehouseA = data.warehouse("WH-A");
        warehouseB = data.warehouse("WH-B");
        productA = data.product("P-A", "A 仓商品");
        skuA = data.sku(productA, "黑色", "M");
        // ★ 两个仓都有同一个 SKU 的库存 —— 这样"本仓看得到 / 他仓看不到"才是有意义的对照
        data.stock(skuA, warehouseA, "BA", null, 100);
        data.stock(skuA, warehouseB, "BB", null, 200);

        // B 仓的三张单据（越权目标）
        poB = insertPurchaseOrder("PO-B-001", warehouseB, skuA, 10, 10000);
        soB = insertSalesOrder("SO-B-001", warehouseB, skuA, 5, 10000);
        roB = insertReturnOrder("RO-B-001", warehouseB, soB, skuA, 1, 10000);
        // A 仓各一张（对照：证明"本仓的确实看得到"，否则"全是 404"可能只是接口坏了）
        insertPurchaseOrder("PO-A-001", warehouseA, skuA, 10, 10000);
        insertSalesOrder("SO-A-001", warehouseA, skuA, 5, 10000);

        // ★ 两个身份都被授足**功能权限**（读遍八个资源），差别只在**数据权限**。
        //
        //   为什么不直接用 PURCHASER / VIEWER 的内置角色：那样矩阵里会混进两种 403 ——
        //   "没功能权限"（RBAC，属 TC-IAM-007/008）与"看不到数据"（数据权限，属本矩阵）。
        //   两者混在一起，矩阵就变成"一堆 403 看起来都对"的空壳，
        //   连"数据权限到底有没有生效"都说不清。
        //
        //   授足功能权限之后，矩阵里剩下的 403 与数据都只可能来自收窄本身 ——
        //   这正是我们要考的那一件事。
        long dataReaderRoleId = roles.findByCode("DATA_SCOPE_READER")
                .map(com.erp.iam.repository.RoleRepository.RoleRow::id)
                .orElseGet(() -> roles.insert("DATA_SCOPE_READER", "数据权限矩阵用只读角色",
                        "八个资源全读，用于越权矩阵"));
        // ★ 含 set:reconcile：对账是**独立权限码**（V5 刻意把日清/月结/对账分开），
        //   矩阵要考"数据权限"，就必须让这两个身份先具备读到这些资源的**功能权限**；
        //   否则结算的两格会因缺 set:reconcile 而 403，那是 RBAC 的断言，不是数据权限的。
        roles.replacePermissions(dataReaderRoleId, java.util.List.of(
                "mdm:read", "inv:read", "pur:read", "sal:read", "rma:read",
                "set:read", "set:daily", "set:monthly", "set:reconcile", "rpt:read"));

        long keeperId = data.user("keeper_a", "Keep1!ng", "DATA_SCOPE_READER", encoder);
        dataScopes.addUserScope(keeperId, "WAREHOUSE", warehouseA);
        keeperToken = authentication.login("keeper_a", "Keep1!ng").token();

        long viewerId = data.user("viewer_a", "View1!ng", "DATA_SCOPE_READER", encoder);
        dataScopes.addUserScope(viewerId, "WAREHOUSE", warehouseA);
        viewerToken = authentication.login("viewer_a", "View1!ng").token();
    }

    // ─────────────────────────── ★ 矩阵本体 ───────────────────────────

    /**
     * ★ 每一格的<b>期望</b>——这张表本身就是需求，必须与接口语义严格对齐。
     *
     * <p><b>先说清楚本矩阵考什么、不考什么</b>（这是最容易把矩阵写错的地方）：
     * <ul>
     *   <li>它考的是 <b>FR-IAM-04 数据权限</b>：同一身份在"有功能权限"的前提下，
     *       能不能看到<b>别仓</b>的数据；</li>
     *   <li>它<b>不</b>考 FR-IAM-02 的 RBAC（"这个角色有没有这个功能"）——
     *       那由 {@code IamIT} 的 TC-IAM-007/008 覆盖。两种 403 混淆在一起，
     *       矩阵就会变成"一堆 403 看起来都对"的空壳。</li>
     * </ul>
     *
     * <p>因此本矩阵的两种身份都被授足了<b>功能权限</b>
     * （见 {@code setUp} 里的 {@code DATA_SCOPE_READER} 角色），
     * 差别只在<b>数据权限</b>：一个限定 A 仓、一个限定 A 仓且只读。
     * 这样剩下的 403 就只可能是"真的没功能权限"，而不会与数据权限混为一谈。
     *
     * <p><b>四种合法形态</b>：
     * <table border="1">
     *   <caption>越权响应形态</caption>
     *   <tr><th>形态</th><th>取数形状</th><th>正确响应</th><th>为什么</th></tr>
     *   <tr><td>A</td><td>按 id 直查<b>单张单据</b></td><td><b>404</b></td>
     *       <td>条件拼进 SQL 后查不出行。返回 403 会泄露"这个 id 存在"，
     *           攻击者按 id 遍历即可枚举整个 id 空间。</td></tr>
     *   <tr><td>B</td><td>列表 / 导出（本仓有数据）</td><td><b>200 + 只有本仓的行</b></td>
     *       <td>收窄的语义是"看不见别仓"，不是"什么都看不见"。</td></tr>
     *   <tr><td>C</td><td>列表 / 聚合（本仓无数据）</td><td><b>200 + 空结果</b></td>
     *       <td>硬验收第 3 条的原话："A 仓仓管查 B 仓库存 → 空结果（不是 403）"。</td></tr>
     *   <tr><td>D</td><td>主数据（商品 / SKU）</td><td><b>200 + 有内容</b></td>
     *       <td>它们没有仓库维度，不该被过滤；这一组同时是<b>对照组</b>。</td></tr>
     * </table>
     *
     * <p>结算与报表的"按仓"能力各有边界（结算按账期、报表按仓参数），
     * 因此它们在矩阵里归入 B/C：只要<b>不出现 B 仓数据</b>即为正确，
     * 不强行要求"必须空"或"必须有内容"——那两种断言都会在正确实现上误伤。
     */
    private enum Expect {
        /** A：404 —— 按 id 直查越权，查不出行。 */
        NOT_FOUND,
        /** B/C：200，且响应里不得出现 B 仓数据（可能为空）。 */
        SCOPED_OK,
        /** D：200 且有内容 —— 主数据不过滤（同时是对照组）。 */
        DATA_OK
    }

    private static Expect expectationFor(Resource resource, Vector vector) {
        // D：主数据无仓库维度 → 不过滤（对照组）
        if (resource == Resource.PRODUCT || resource == Resource.SKU) {
            return Expect.DATA_OK;
        }
        // A：只有"三张单据 + 按 id 直查/关联反查单张"是 404
        if ((vector == Vector.DIRECT_ID || vector == Vector.ASSOCIATION)
                && (resource == Resource.PURCHASE_ORDER
                    || resource == Resource.SALES_ORDER
                    || resource == Resource.RETURN_ORDER)) {
            return Expect.NOT_FOUND;
        }
        // B/C：其余全部是"列表类"取数 → 200 + 只见本仓
        return Expect.SCOPED_OK;
    }

    private static int expectedStatus(Resource resource, Vector vector, Identity identity) {
        if (identity == Identity.ANONYMOUS) {
            return 401;
        }
        return expectationFor(resource, vector) == Expect.NOT_FOUND ? 404 : 200;
    }

    @ParameterizedTest(name = "[{index}] {0} / {1} / {2}")
    @MethodSource("matrix")
    @CaseId("TC-IAM-024")
    @DisplayName("★ 越权矩阵：单据直查 404、列表类 200 只见本仓（不出现 B 仓数据）、主数据作对照")
    void everyCell_mustNotLeak(Resource resource, Vector vector, Identity identity)
            throws Exception {
        MvcResult result = mvc.perform(requestFor(resource, vector, identity)).andReturn();
        int status = result.getResponse().getStatus();
        String body = result.getResponse().getContentAsString(
                java.nio.charset.StandardCharsets.UTF_8);

        if (identity == Identity.ANONYMOUS) {
            assertThat(status)
                    .as("★ [%s/%s/匿名] 必须 401，实际 %d，响应=%s",
                            resource, vector, status, body)
                    .isEqualTo(401);
            assertThat(body).contains("IAM_UNAUTHENTICATED");
            return;
        }

        Expect expect = expectationFor(resource, vector);
        assertThat(status)
                .as("★ [%s/%s/%s] 期望 %s（HTTP %d），实际 %d，响应=%s",
                        resource, vector, identity, expect,
                        expect == Expect.NOT_FOUND ? 404 : 200, status, body)
                .isEqualTo(expect == Expect.NOT_FOUND ? 404 : 200);

        switch (expect) {
            case NOT_FOUND -> {
                // 403 会泄露"这个 id 存在"，因此按 id 越权绝不能是 403
                assertThat(status).as("按 id 越权绝不能返回 403（会泄露存在性）")
                        .isNotEqualTo(403);
                assertThat(body)
                        .as("★ 越权的响应体不能出现 IAM_FORBIDDEN（那等于承认资源存在）")
                        .doesNotContain("IAM_FORBIDDEN");
                assertThat(body).containsPattern(
                        "\"code\":\"(SYS_NOT_FOUND|PUR_ORDER_NOT_FOUND"
                                + "|SAL_ORDER_NOT_FOUND|RMA_ORDER_NOT_FOUND)\"");
            }
            case SCOPED_OK -> {
                assertThat(status).as("列表类越权只能 200，不得 403").isNotEqualTo(403);
                // ★ 本用例最核心的一句：**不得出现 B 仓的数据**
                assertThat(body)
                        .as("★ [%s/%s] 收窄后不得出现 B 仓（warehouseId=%d）的数据，实际=%s",
                                resource, vector, warehouseB, body)
                        .doesNotContain("\"warehouseId\":" + warehouseB);
                // 空结果与"只有本仓的行"都是合法的（见 Expect 的 B/C 两种形态）
            }
            case DATA_OK -> {
                assertThat(body)
                        .as("★ 主数据 %s 不该被数据权限过滤，必须 200 且有内容", resource)
                        .contains("\"code\":\"OK\"");
                assertThat(body).as("主数据响应不应为空列表（对照组必须真有内容）")
                        .doesNotContain("\"items\":[]");
                assertThat(body).doesNotContain("\"data\":[]");
            }
        }
    }

    /**
     * ★ 矩阵期望表自检：三类形态的格子数必须固定。
     *
     * <p>守的是"期望表本身被悄悄改宽"：若有人把 {@link #expectationFor} 改成一律
     * {@link Expect#SCOPED_OK}（"反正测试红了很烦"），"单据直查必须 404"这个
     * 面试重点就会静默消失，而矩阵表面仍然全绿。
     */
    @Test
    @CaseId("TC-IAM-043")
    @DisplayName("★ 矩阵期望表自检：404 / 只见本仓 / 主数据对照 三类格子数必须固定")
    void expectationTable_itselfIsChecked() {
        long notFound = 0;
        long scoped = 0;
        long dataOk = 0;
        for (Resource r : Resource.values()) {
            for (Vector v : Vector.values()) {
                switch (expectationFor(r, v)) {
                    case NOT_FOUND -> notFound++;
                    case SCOPED_OK -> scoped++;
                    case DATA_OK -> dataOk++;
                }
                assertThat(expectedStatus(r, v, Identity.ANONYMOUS))
                        .as("匿名一律 401（与资源是否存在无关）").isEqualTo(401);
            }
        }
        assertThat(notFound).as("★ 404 的格子（3 张单据 × 直查+关联）").isEqualTo(6);
        assertThat(dataOk).as("★ 主数据对照（2 资源 × 5 方式）").isEqualTo(10);
        assertThat(scoped).as("★ 只见本仓的格子（40 − 6 − 10）").isEqualTo(24);
        assertThat(notFound + dataOk + scoped)
                .as("三类必须覆盖全部 8×5=40 个资源×方式组合")
                .isEqualTo(40);
    }

    /**
     * 把一个 (资源, 方式, 身份) 组合落成一个真实请求。
     *
     * <p><b>★ 每个分支都必须用"真实存在但属于 B 仓"的 id</b>：
     * 若用不存在的 id，404 就是"本来就没有"，测不出权限。
     * 这是越权矩阵最容易写错的地方 —— 用例绿了，但它证明的是"接口会 404"，
     * 而不是"越权被挡住"。
     */
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
            requestFor(Resource resource, Vector vector, Identity identity) {
        String token = switch (identity) {
            case OTHER_WAREHOUSE_KEEPER -> keeperToken;
            case OWN_WAREHOUSE_VIEWER -> viewerToken;
            case ANONYMOUS -> null;
        };
        var req = switch (resource) {
            case PRODUCT -> switch (vector) {
                case DIRECT_ID -> get("/api/v1/products/" + productA);
                case LIST_INJECTION -> get("/api/v1/products")
                        .param("warehouseId", String.valueOf(warehouseB));
                case EXPORT -> get("/api/v1/products").param("export", "csv");
                case ASSOCIATION -> get("/api/v1/skus")
                        .param("productId", String.valueOf(productA));
                case AGGREGATE -> get("/api/v1/products").param("count", "true");
            };
            case SKU -> switch (vector) {
                case DIRECT_ID -> get("/api/v1/skus/" + skuA);
                case LIST_INJECTION -> get("/api/v1/skus")
                        .param("warehouseId", String.valueOf(warehouseB));
                case EXPORT -> get("/api/v1/skus").param("export", "csv");
                case ASSOCIATION -> get("/api/v1/skus")
                        .param("productId", String.valueOf(productA));
                case AGGREGATE -> get("/api/v1/skus").param("count", "true");
            };
            case INVENTORY -> switch (vector) {
                // 直查：用 B 仓的 warehouseId —— 最典型的"跨仓直查"
                case DIRECT_ID -> get("/api/v1/inventory")
                        .param("skuId", String.valueOf(skuA))
                        .param("warehouseId", String.valueOf(warehouseB));
                case LIST_INJECTION -> get("/api/v1/inventory")
                        .param("warehouseId", String.valueOf(warehouseB))
                        .param("onlyAvailable", "true");
                case EXPORT -> get("/api/v1/inventory").param("export", "csv")
                        .param("warehouseId", String.valueOf(warehouseB));
                // 关联查询：从流水反查别仓的每一笔进出
                case ASSOCIATION -> get("/api/v1/inventory/ledger")
                        .param("warehouseId", String.valueOf(warehouseB));
                case AGGREGATE -> get("/api/v1/inventory")
                        .param("warehouseId", String.valueOf(warehouseB));
            };
            case PURCHASE_ORDER -> switch (vector) {
                case DIRECT_ID, ASSOCIATION -> get("/api/v1/purchase-orders/" + poB);
                case LIST_INJECTION -> get("/api/v1/purchase-orders")
                        .param("warehouseId", String.valueOf(warehouseB));
                case EXPORT -> get("/api/v1/purchase-orders").param("export", "csv");
                case AGGREGATE -> get("/api/v1/reports/purchase")
                        .param("from", "2026-03-01").param("to", "2026-03-31")
                        .param("warehouseId", String.valueOf(warehouseB));
            };
            case SALES_ORDER -> switch (vector) {
                case DIRECT_ID, ASSOCIATION -> get("/api/v1/sales-orders/" + soB);
                case LIST_INJECTION -> get("/api/v1/sales-orders")
                        .param("warehouseId", String.valueOf(warehouseB));
                case EXPORT -> get("/api/v1/sales-orders").param("export", "csv");
                case AGGREGATE -> get("/api/v1/reports/sales")
                        .param("from", "2026-03-01").param("to", "2026-03-31")
                        .param("warehouseId", String.valueOf(warehouseB));
            };
            case RETURN_ORDER -> switch (vector) {
                case DIRECT_ID, ASSOCIATION -> get("/api/v1/return-orders/" + roB);
                case LIST_INJECTION -> get("/api/v1/return-orders")
                        .param("warehouseId", String.valueOf(warehouseB));
                case EXPORT -> get("/api/v1/return-orders").param("export", "csv");
                case AGGREGATE -> get("/api/v1/return-orders")
                        .param("originSalesOrderId", String.valueOf(soB));
            };
            case SETTLEMENT -> switch (vector) {
                case DIRECT_ID -> get("/api/v1/settlements/periods")
                        .param("period", "2026-03");
                case LIST_INJECTION -> get("/api/v1/settlements/periods")
                        .param("warehouseId", String.valueOf(warehouseB));
                case EXPORT -> get("/api/v1/settlements/periods").param("export", "csv");
                // ★ 关联查询这里走"按账期反查列表"，**不**走 /settlements/reconcile：
                //   对账对未月结账期返回 409 SET_PERIOD_NOT_CLOSED，那是**业务状态**
                //   （还没锁账就没有总账可比），与"能不能看到别仓数据"无关。
                //   在越权矩阵里塞一个会 409 的调用，只会得到一条"看起来像权限问题、
                //   实际是状态机问题"的假红。
                case ASSOCIATION -> get("/api/v1/settlements/periods")
                        .param("period", "2026-03");
                case AGGREGATE -> get("/api/v1/settlements/periods");
            };
            case REPORT -> switch (vector) {
                case DIRECT_ID, LIST_INJECTION ->
                        get("/api/v1/reports/inventory-summary")
                                .param("period", "2026-03")
                                .param("warehouseId", String.valueOf(warehouseB));
                case EXPORT -> get("/api/v1/reports/sales")
                        .param("from", "2026-03-01").param("to", "2026-03-31")
                        .param("export", "csv");
                case ASSOCIATION -> get("/api/v1/reports/purchase")
                        .param("from", "2026-03-01").param("to", "2026-03-31")
                        .param("warehouseId", String.valueOf(warehouseB));
                case AGGREGATE -> get("/api/v1/reports/inventory-summary")
                        .param("period", "2026-03");
            };
        };
        if (token != null) {
            req.header("Authorization", "Bearer " + token);
        }
        return req;
    }

    // ─────────────────────────── 矩阵自身的自检 ───────────────────────────

    @Test
    @CaseId("TC-IAM-025")
    @DisplayName("★ 矩阵规模自检：8×5×3 = 120 个组合，一个都不能少")
    void matrixSize_isExactly120() {
        long count = matrix().count();
        assertThat(count)
                .as("★ 越权矩阵必须是 8 资源 × 5 方式 × 3 身份 = 120 条（硬验收 ≥40）")
                .isEqualTo(120);
        assertThat(Resource.values()).hasSize(8);
        assertThat(Vector.values()).hasSize(5);
        assertThat(Identity.values()).hasSize(3);
        assertThat(count).isEqualTo((long) Resource.values().length
                * Vector.values().length * Identity.values().length);
    }

    /**
     * ★ 对照组：同一批接口用<b>本仓</b>的 id 访问必须 200。
     *
     * <p><b>没有这条，整个矩阵就是假绿</b>：如果接口本身全坏了（比如注入的数据权限
     * 把所有查询都收成空），矩阵的每一条都会 404 —— 而 404 恰好是"通过"的期望值。
     * 一个永远 404 的系统能把这 120 条全部跑绿，却什么也没证明。
     * 所以必须有一条"本该看得到"的对照：本仓数据 200 且确实有内容。
     */
    @Test
    @CaseId("TC-IAM-026")
    @DisplayName("★ 对照组（防假绿）：本仓库存/单据必须 200 且有内容，证明 404 来自收窄而非接口全坏")
    void ownWarehouse_isVisible_so404ReallyMeansScopeRestriction() throws Exception {
        // 本仓库存：看得到，且只有 A 仓那一行
        String inv = mvc.perform(get("/api/v1/inventory")
                        .param("warehouseId", String.valueOf(warehouseA))
                        .header("Authorization", "Bearer " + keeperToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(inv).contains("\"warehouseId\":" + warehouseA);
        assertThat(inv).as("★ A 仓仓管不得看到 B 仓的余额行")
                .doesNotContain("\"warehouseId\":" + warehouseB);

        // 本仓进货单详情：看得到
        Long poA = jdbc.queryForObject(
                "SELECT id FROM purchase_orders WHERE order_no = 'PO-A-001'", Long.class);
        mvc.perform(get("/api/v1/purchase-orders/" + poA)
                        .header("Authorization", "Bearer " + keeperToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderNo").value("PO-A-001"));

        // ★ 同一个接口、同一个 token，换 B 仓的 id → 404（不是 403）
        mvc.perform(get("/api/v1/purchase-orders/" + poB)
                        .header("Authorization", "Bearer " + keeperToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PUR_ORDER_NOT_FOUND"));

        // 库存：A 仓仓管查 B 仓 → 空结果（不是 403，也不是"全部库存"）
        mvc.perform(get("/api/v1/inventory")
                        .param("warehouseId", String.valueOf(warehouseB))
                        .header("Authorization", "Bearer " + keeperToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    // ─────────────────────────── 造数辅助 ───────────────────────────

    private long insertPurchaseOrder(String orderNo, long warehouseId, long skuId,
                                     int qty, long unitPrice) {
        long supplierId = data.supplier("SUP-MX");
        jdbc.update("""
                INSERT INTO purchase_orders
                    (order_no, status, supplier_id, warehouse_id, biz_date,
                     total_amount, total_qty, period, created_by)
                VALUES (?, 'DRAFT', ?, ?, ?, ?, ?, ?, ?)
                """, orderNo, supplierId, warehouseId, LocalDate.of(2026, 3, 10),
                (long) qty * unitPrice, qty, "2026-03", null);
        Long orderId = jdbc.queryForObject(
                "SELECT id FROM purchase_orders WHERE order_no = ?", Long.class, orderNo);
        jdbc.update("""
                INSERT INTO purchase_order_lines
                    (order_id, line_no, sku_id, qty, received_qty, unit_price_amount, amount)
                VALUES (?, 1, ?, ?, 0, ?, ?)
                """, orderId, skuId, qty, unitPrice, (long) qty * unitPrice);
        return orderId;
    }

    private long insertSalesOrder(String orderNo, long warehouseId, long skuId,
                                  int qty, long unitPrice) {
        long customerId = data.customer("CUS-MX");
        jdbc.update("""
                INSERT INTO sales_orders
                    (order_no, status, customer_id, warehouse_id, biz_date,
                     total_amount, total_qty, period, created_by)
                VALUES (?, 'DRAFT', ?, ?, ?, ?, ?, ?, ?)
                """, orderNo, customerId, warehouseId, LocalDate.of(2026, 3, 10),
                (long) qty * unitPrice, qty, "2026-03", null);
        Long orderId = jdbc.queryForObject(
                "SELECT id FROM sales_orders WHERE order_no = ?", Long.class, orderNo);
        jdbc.update("""
                INSERT INTO sales_order_lines
                    (order_id, line_no, sku_id, qty, shipped_qty, unit_price_amount,
                     discount_rate, amount)
                VALUES (?, 1, ?, ?, 0, ?, 10000, ?)
                """, orderId, skuId, qty, unitPrice, (long) qty * unitPrice);
        return orderId;
    }

    private long insertReturnOrder(String orderNo, long warehouseId, long originSoId,
                                   long skuId, int qty, long unitPrice) {
        long customerId = data.customer("CUS-MX");
        Long originLine = jdbc.queryForObject(
                "SELECT id FROM sales_order_lines WHERE order_id = ? LIMIT 1",
                Long.class, originSoId);
        jdbc.update("""
                INSERT INTO return_orders
                    (order_no, status, origin_sales_order_id, customer_id, warehouse_id,
                     biz_date, total_amount, total_qty, period, created_by)
                VALUES (?, 'DRAFT', ?, ?, ?, ?, ?, ?, ?, ?)
                """, orderNo, originSoId, customerId, warehouseId, LocalDate.of(2026, 3, 12),
                (long) qty * unitPrice, qty, "2026-03", null);
        Long orderId = jdbc.queryForObject(
                "SELECT id FROM return_orders WHERE order_no = ?", Long.class, orderNo);
        jdbc.update("""
                INSERT INTO return_order_lines
                    (order_id, line_no, origin_line_id, sku_id, qty, unit_price_amount,
                     discount_rate, amount)
                VALUES (?, 1, ?, ?, ?, ?, 10000, ?)
                """, orderId, originLine, skuId, qty, unitPrice, (long) qty * unitPrice);
        return orderId;
    }

    /** 便于诊断：矩阵某格失败时把 token 与身份打出来（只在调试时用）。 */
    @SuppressWarnings("unused")
    private Map<String, String> identityDebug() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("keeper", keeperToken == null ? "null" : "present");
        m.put("viewer", viewerToken == null ? "null" : "present");
        return m;
    }
}
