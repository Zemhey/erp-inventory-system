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
import org.springframework.test.web.servlet.MvcResult;
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
@org.springframework.context.annotation.Import(
        AuditCompletenessIT.BackdoorProbeConfig.class)
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
    /** ★ 后门探针 Bean：本用例临时映射到 {@code POST /__test__/probe}，用完注销。 */
    @Autowired
    private BackdoorProbe backdoorProbe;
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
     * ★ 后门接口确实不产生审计 —— <b>而且这条用例真的能证伪</b>。
     *
     * <p><b>为什么必须真的注册一个后门</b>（本条用例首版是「空断言」，由指挥会话复核发现）：
     * 首版写法是请求 {@code POST /__test__/no-such-backdoor} 再断言行数不变。
     * 这条断言<b>无论排除逻辑在不在都必然通过</b> —— 该路径没有映射，Spring 直接 404，
     * 请求根本没走到审计判断。它测的是"不存在的路径不产生审计"，
     * 与我们要证明的"排除逻辑生效"毫无关系。
     *
     * <p>更关键的事实：{@code /__test__} 后门<b>在 main 源码里根本不存在</b>
     * （{@code git grep 'Profile("test")'} 无命中），所以首版排除的是一个从来不存在
     * 的路径 —— 典型的假绿。
     *
     * <p>本版改为用 {@code RequestMappingHandlerMapping.registerMapping} 在<b>用例内
     * 临时注册</b>一个真实的 {@code POST /__test__/probe}（{@code finally} 里注销，
     * 不污染其它测试上下文，也不会被组件扫描带进 {@code ApiContractMappingIT}），然后：
     * <ol>
     *   <li>断言它<b>返回 200</b> —— 证明处理器真的被调用了（不是 404 缺席），
     *       因此"没有审计"不可能是"没走到"造成的；</li>
     *   <li>断言 {@code audit_logs} <b>没有</b>新增 —— 这才是排除逻辑生效的证据。
     *       删掉 {@link com.erp.sys.application.AuditSafetyNetFilter} 里的
     *       {@code TEST_BACKDOOR_PREFIX} 判断，本用例立刻变红。</li>
     * </ol>
     *
     * <p>切面那一半的排除逻辑另由 {@code AuditAspectTest} 的单元用例钉住 ——
     * 切面切入点只覆盖 {@code com.erp..api..}，本探针不在该包内，故此处只能验证过滤器层。
     */
    @Test
    @CaseId("TC-SYS-031")
    @DisplayName("★ 后门前缀 /__test__/* 的写请求不产生审计（注册真实后门，删掉排除逻辑即红）")
    void testBackdoorWrite_mustNotBeAudited() throws Exception {
        String probePath = TEST_BACKDOOR_PREFIX + "/probe";
        RequestMappingInfo info = RequestMappingInfo.paths(probePath)
                .methods(RequestMethod.POST).build();
        handlerMapping.registerMapping(info, backdoorProbe,
                BackdoorProbe.class.getMethod("probe"));
        try {
            long before = auditCount();
            MvcResult handled = mvc.perform(post(probePath)
                            .header("Authorization", adminAuth)
                            .header("X-Request-Id", "backdoor-probe-001")
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andReturn();

            // ① 探针自检：证明后门真的存在且被调用 —— 否则本用例又退化成空断言
            assertThat(handled.getResponse().getStatus())
                    .as("★ 后门探针必须真的被处理（200）；若是 404 说明它没注册，"
                            + "本用例就退化成了「不存在的路径不产生审计」的空断言")
                    .isEqualTo(200);
            assertThat(backdoorProbe.invocations())
                    .as("★ 探针必须真的被调用过（计数=1）—— 这是「处理器执行了」的硬证据；"
                            + "只靠状态码 200 不够，MockMvc 下的响应体可能为空")
                    .isEqualTo(1);

            // ② 真正的断言：被真实调用过，但没有留痕
            assertThat(auditCount())
                    .as("★ /__test__ 后门即使被真实调用过，也绝不能进审计表 —— "
                            + "若行数增加，说明 AuditSafetyNetFilter 的排除逻辑已失效")
                    .isEqualTo(before);

            // ③ 枚举器也必须把它排除（否则 INV-15 会要求后门留痕，与排除逻辑自相矛盾）
            assertThat(writeEndpoints())
                    .as("★ INV-15 的枚举器必须排除真实存在的后门；"
                            + "否则 INV-15 与排除逻辑互相矛盾")
                    .noneSatisfy(e -> assertThat(e.path()).startsWith(TEST_BACKDOOR_PREFIX));
        } finally {
            handlerMapping.unregisterMapping(info);
        }
    }

    /**
     * 临时后门探针（由 {@link BackdoorProbeConfig} 注册成 Bean）。
     *
     * <p><b>刻意不加 {@code @RestController}</b>：加了它会被 {@code com.erp} 的组件扫描
     * 在<b>所有</b>测试上下文里拾取，于是 {@code POST /__test__/probe} 会变成
     * {@code ApiContractMappingIT} 眼里的"已实现但未写入契约的接口"而让它变红。
     * 这里只作为普通 Bean 存在，映射由用例在 {@code try/finally} 里显式注册与注销。
     */
    static class BackdoorProbe {

        /** 被调用次数：证明"处理器真的执行了"，不依赖响应体（MockMvc 下响应体可能为空）。 */
        private final java.util.concurrent.atomic.AtomicInteger invocations =
                new java.util.concurrent.atomic.AtomicInteger();

        public String probe() {
            invocations.incrementAndGet();
            return "backdoor-probe";
        }

        public int invocations() {
            return invocations.get();
        }
    }

    /** 把探针注册成 Bean（过滤器层会经过它）。 */
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class BackdoorProbeConfig {

        @org.springframework.context.annotation.Bean
        BackdoorProbe backdoorProbe() {
            return new BackdoorProbe();
        }
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
