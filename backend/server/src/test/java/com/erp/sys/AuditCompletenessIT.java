package com.erp.sys;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.erp.support.CaseId;
import com.erp.support.PostgresTestBase;
import com.erp.support.TestDataFactory;
import com.erp.sys.application.AuditRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * ★ INV-15 审计完备性门禁（FR-SYS-06）—— 本泳道<b>最重要的一个测试</b>。
 *
 * <p><b>它解决的问题</b>：审计切面是"自动"的，所以它有沉默失效的风险 ——
 * 有人新增了一个写接口，但该接口的路由不在切面覆盖范围内（换了个 Controller 包、
 * 用了函数式端点、或切面表达式被改窄），CI 依旧全绿，而那个接口的写操作从此不留痕。
 * 这类漏洞上线后几乎不可能被发现：审计表里"没有记录"和"没有发生"长得一模一样。
 *
 * <p><b>做法</b>：不解析源码文本（会漏掉组合路径、条件映射），而是直接从 Spring 的
 * {@link RequestMappingHandlerMapping} 取<b>实际注册</b>的处理器 —— 注册表就是运行时
 * 真正接受请求的那份事实。然后对每个写接口实际发一次请求，断言
 * {@code audit_logs} 里出现了至少一条对应 {@code (resourceType, action)} 的记录。
 *
 * <p><b>反向价值</b>：将来任何人加了写接口却忘了让切面覆盖，本用例会红，
 * 并在失败信息里点名那个接口。这正是"用测试守架构"最典型的形态。
 *
 * <p><b>三个刻意的设计选择</b>：
 * <ol>
 *   <li>每个接口发的是<b>真实请求</b>而不是"假设它会被切面命中"：
 *       只有真实经过 MVC 管线（含安全过滤器、参数绑定、异常处理）的调用才证明切面在链路上；</li>
 *   <li>断言用 {@code (resourceType, action)} 而不是精确比对 {@code audit_logs} 行数：
 *       行数会因路径参数不同而抖动，语义键才稳定；</li>
 *   <li>请求<b>允许失败</b>（404/400/403/500 都行）：INV-15 要求的是"每个写接口至少产生
 *       1 条审计"，失败的写操作同样必须留痕 —— 断言"必须 2xx"会让大量接口
 *       因为缺少前置数据而被判成"没审计"，红得没有意义。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("★ INV-15 · 审计完备性（每个写接口必须留痕）")
class AuditCompletenessIT extends PostgresTestBase {

    /**
     * 只在 test profile 存在的后门接口前缀 —— 必须排除。
     *
     * <p>它们写的是"测试夹具"而不是业务数据，给它们留痕只会让审计表变成
     * 一张被随机数据淹掉的表，反而降低审计的可用性。
     * 排除前缀与 {@code AuditAspect.TEST_BACKDOOR_PREFIX} 保持一致。
     */
    private static final String TEST_BACKDOOR_PREFIX = "/__test__";

    private static final Set<RequestMethod> WRITE_METHODS =
            Set.of(RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE);

    /** 无请求体也能安全触发的写接口需要的最小 JSON（其余接口发空对象，允许失败）。 */
    private static final Map<String, String> BODY_BY_PATH_KEYWORD = Map.of(
            "/inventory/reserve", """
                    {"skuId": 999999, "warehouseId": 999999, "qty": 1}""",
            "/inventory/receipts", """
                    {"skuId": 999999, "warehouseId": 999999, "qty": 1, "bizRefNo": "INV-15-PROBE"}""",
            "/inventory/shipments", """
                    {"skuId": 999999, "warehouseId": 999999, "qty": 1, "bizRefNo": "INV-15-PROBE"}""",
            "/auth/login", """
                    {"username": "inv15-probe", "password": "Inv15Probe1!"}""",
            "/nl/command", """
                    {"command": "help"}""");

    @Autowired
    private MockMvc mvc;
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private PasswordEncoder encoder;
    @Autowired
    private AuditRecorder recorder;
    @Autowired
    private com.erp.iam.application.AuthenticationService authentication;

    private String adminAuth;

    @BeforeEach
    void setUp() {
        new TestDataFactory(jdbc).truncateAll();
        jdbc.execute("TRUNCATE audit_logs RESTART IDENTITY");
        TestDataFactory data = new TestDataFactory(jdbc);
        data.user("inv15_admin", "Inv15Admin1!", "ADMIN", encoder);
        adminAuth = token("inv15_admin", "Inv15Admin1!");
    }

    /**
     * ★ 核心用例：枚举全部写接口，逐个发请求，断言每个都留下了审计。
     *
     * <p><b>判据是"每个接口让 audit_logs 至少多一行"</b>，而不是"出现了新的
     * {@code (resourceType, action)} 键"。理由：两个不同接口完全可能落到同一个键上
     * （{@code POST /skus} 与 {@code POST /skus/matrix} 都是 CREATE + SKU 语义），
     * 用键集合做判据会把后者误判成"没审计"—— 本泳道实测踩到过这个假红。
     * 行数增量才是"这个请求确实被记录了一次"的直接证据。
     *
     * <p>失败信息会点名缺审计的接口（{@code METHOD /path}），直接指向该修哪里。
     */
    @Test
    @CaseId("TC-SYS-028")
    @DisplayName("★ INV-15：每个写接口至少产生 1 条 audit_logs（漏一个即红）")
    void everyWriteEndpoint_mustProduceAudit() throws Exception {
        List<WriteEndpoint> endpoints = writeEndpoints();

        // 门禁自检：枚举结果异常小说明注入/过滤出了问题，不能让它"静默通过"
        assertThat(endpoints)
                .as("写接口枚举结果过少（当前 %d 个），疑似 RequestMappingHandlerMapping "
                        + "注入失败或包名过滤写错 —— 门禁本身失效了", endpoints.size())
                .hasSizeGreaterThan(30);

        List<String> missing = new ArrayList<>();
        for (WriteEndpoint endpoint : endpoints) {
            long before = auditCount();
            perform(endpoint);
            if (auditCount() <= before) {
                missing.add(endpoint.toString());
            }
        }

        assertThat(missing)
                .as("★ INV-15 审计完备性失败：以下写接口没有产生任何 audit_logs —— "
                        + "有人加了写接口但切面/兜底过滤器没有覆盖它（或被改窄了）。"
                        + "修法：让该 Controller 落在 com.erp..api.. 包下，"
                        + "或在 AuditAspect 的切入点里显式覆盖它")
                .isEmpty();
    }

    /**
     * 完备性的另一半：计数必须对得上。
     *
     * <p>为什么需要它：上面那条用例只要"出现新键"就算过，如果两个接口映射到同一个
     * {@code (resourceType, action)} 键（例如 {@code POST /products} 与
     * {@code POST /customers} 都是 {@code (PRODUCT/CUSTOMER, CREATE)}，还好；
     * 但 {@code POST /purchase-orders/{id}/submit} 与 {@code /approve} 若被错误归类成同一动作），
     * 就会出现"少审计了一个接口却看不出来"。用"审计行数 ≥ 写接口数"做下界，
     * 把这种情形也堵上。
     */
    @Test
    @CaseId("TC-SYS-029")
    @DisplayName("★ INV-15 下界：审计行数必须 ≥ 枚举出的写接口数")
    void auditRowCount_mustBeAtLeastWriteEndpointCount() throws Exception {
        List<WriteEndpoint> endpoints = writeEndpoints();
        long before = auditCount();
        for (WriteEndpoint endpoint : endpoints) {
            perform(endpoint);
        }
        long added = auditCount() - before;
        assertThat(added)
                .as("★ %d 个写接口只产生了 %d 条审计 —— 有接口被切面漏掉了",
                        endpoints.size(), added)
                .isGreaterThanOrEqualTo(endpoints.size());
    }

    /**
     * 枚举器本身的正确性：不能把读接口算进来，也不能漏掉真正的写接口。
     *
     * <p>为什么这条也必须写：如果枚举器把 GET 也算成写接口，上面两条用例会因为
     * "GET 不产生审计"而永远红；反之若它只枚举了一部分写接口，门禁就是纸糊的。
     * 这条用例把枚举器的两端钉住，让前两条的红/绿有意义。
     */
    @Test
    @CaseId("TC-SYS-030")
    @DisplayName("★ 枚举器自检：只枚举写方法、排除 __test__ 后门、不遗漏已知写接口")
    void writeEndpointEnumerator_mustBeCorrect() {
        List<WriteEndpoint> endpoints = writeEndpoints();

        assertThat(endpoints)
                .as("枚举器不得包含 GET/HEAD/OPTIONS 等读方法")
                .allSatisfy(e -> assertThat(WRITE_METHODS).contains(e.method()));

        assertThat(endpoints)
                .as("枚举器不得包含 test-profile 后门 %s", TEST_BACKDOOR_PREFIX)
                .noneSatisfy(e -> assertThat(e.path()).startsWith(TEST_BACKDOOR_PREFIX));

        // 抽样核对：这些接口真实存在且是写接口，枚举器必须包含它们
        Set<String> keys = new LinkedHashSet<>();
        endpoints.forEach(e -> keys.add(e.toString()));
        assertThat(keys)
                .as("枚举器漏掉了已知的写接口")
                .contains(
                        "POST /api/v1/products",
                        "PUT /api/v1/products/{}",
                        "POST /api/v1/products/{}/disable",
                        "POST /api/v1/purchase-orders/{}/receive",
                        "POST /api/v1/sales-orders/{}/void",
                        "POST /api/v1/inventory/reserve",
                        "POST /api/v1/settlements/daily",
                        "POST /api/v1/auth/login");
    }

    /**
     * 后门接口确实不产生审计（排除规则生效，而不是"碰巧没调用到"）。
     */
    @Test
    @CaseId("TC-SYS-031")
    @DisplayName("后门前缀 /__test__/* 的写请求不产生审计")
    void testBackdoorWrite_mustNotBeAudited() throws Exception {
        long before = auditCount();
        // 直接调一个必然 404 的 __test__ 路径：即使它没被注册，切面也不应留痕
        mvc.perform(post("/__test__/no-such-backdoor").contentType(MediaType.APPLICATION_JSON)
                .content("{}"));
        assertThat(auditCount())
                .as("__test__ 后门的写请求不得进入审计表（否则审计会被测试夹具淹没）")
                .isEqualTo(before);
    }

    /**
     * 审计写入器在数据库不可用/约束冲突时<b>不抛异常</b>（审计失败不影响业务的直接证据）。
     *
     * <p>注入一个会失败的插入：传入超长 {@code resourceType}（超过 {@code VARCHAR(64)}），
     * 触发数据库报错。断言 {@code record()} 返回 -1 而不是抛异常。
     */
    @Test
    @CaseId("TC-SYS-032")
    @DisplayName("★ 审计写入失败必须返回 -1 且不抛异常（审计绝不影响业务）")
    void auditWriteFailure_mustNotThrow() {
        String tooLong = "X".repeat(200);
        long id = recorder.record(null, "probe", com.erp.sys.domain.AuditAction.CREATE,
                tooLong, "1", null, null, null, "trace-probe", "127.0.0.1",
                com.erp.sys.domain.AuditResult.SUCCESS, null);
        assertThat(id)
                .as("★ 审计写入失败必须被吞掉并返回 -1；若抛异常，业务会被审计拖垮")
                .isEqualTo(-1);
    }

    // ─────────────────────────── 辅助 ───────────────────────────

    /** 一个写接口的运行时标识。{@code path} 已把 {@code {id}} 归一成 {@code {}}。 */
    private record WriteEndpoint(RequestMethod method, String path) {
        @Override
        public String toString() {
            return method.name() + " " + path;
        }
    }

    /**
     * 从 Spring 的注册表枚举写接口。
     *
     * <p>与 {@code ApiContractMappingIT} 用同一手法（枚举运行时事实而非解析源码），
     * 但目的不同：那里查"实现与契约是否一致"，这里查"写接口是否都被审计覆盖"。
     */
    private List<WriteEndpoint> writeEndpoints() {
        Map<String, WriteEndpoint> unique = new LinkedHashMap<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry
                : handlerMapping.getHandlerMethods().entrySet()) {
            HandlerMethod handler = entry.getValue();
            if (!handler.getBeanType().getPackageName().startsWith("com.erp.")) {
                continue;
            }
            RequestMappingInfo info = entry.getKey();
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            for (String pattern : info.getPatternValues()) {
                String normalized = normalizePath(pattern);
                if (normalized.startsWith(TEST_BACKDOOR_PREFIX)) {
                    continue;
                }
                for (RequestMethod method : methods) {
                    if (!WRITE_METHODS.contains(method)) {
                        continue;
                    }
                    WriteEndpoint endpoint = new WriteEndpoint(method, normalized);
                    unique.putIfAbsent(endpoint.toString(), endpoint);
                }
            }
        }
        List<WriteEndpoint> endpoints = new ArrayList<>(unique.values());
        endpoints.sort(Comparator.comparing(WriteEndpoint::toString));
        return endpoints;
    }

    /** 把 {@code {id}} 统一成 {@code {}}，与契约测试用同一套归一化规则。 */
    private static String normalizePath(String path) {
        String normalized = path.replaceAll("\\{[^}]+}", "{}");
        return normalized.length() > 1 && normalized.endsWith("/")
                ? normalized.substring(0, normalized.length() - 1)
                : normalized;
    }

    /**
     * 对一个写接口发一次真实请求。
     *
     * <p>路径里的 {@code {}} 替换成一个不存在的 id（{@code 999999}）：
     * 这样请求会走到 Controller 内部再因为"资源不存在"失败 —— 正是我们要的，
     * 失败的写操作同样必须留痕，而且不需要为每个接口准备前置数据。
     */
    private void perform(WriteEndpoint endpoint) throws Exception {
        String path = endpoint.path().replace("{}", "999999");
        MockHttpServletRequestBuilder builder = switch (endpoint.method()) {
            case POST -> post(path);
            case PUT -> put(path);
            case PATCH -> patch(path);
            case DELETE -> delete(path);
            default -> throw new IllegalStateException("非写方法：" + endpoint.method());
        };
        builder.header("Authorization", adminAuth)
                .header("X-Request-Id", "inv15-" + Math.abs(path.hashCode()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyFor(path));
        // 允许任何响应码：INV-15 断言的是"留痕"，不是"成功"
        mvc.perform(builder).andReturn();
    }

    private String bodyFor(String path) {
        for (Map.Entry<String, String> entry : BODY_BY_PATH_KEYWORD.entrySet()) {
            if (path.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return "{}";
    }

    /** 当前审计表里的 {@code (resourceType, action)} 去重集合。 */
    private Set<String> auditedKeys() {
        List<String> rows = jdbc.queryForList(
                "SELECT resource_type || '|' || action FROM audit_logs", String.class);
        return new TreeSet<>(rows);
    }

    private long auditCount() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs", Long.class);
        return n == null ? 0 : n;
    }

    private String token(String username, String password) {
        return "Bearer " + authentication.login(username, password).token();
    }
}
