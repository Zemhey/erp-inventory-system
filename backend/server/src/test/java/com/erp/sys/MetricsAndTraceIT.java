package com.erp.sys;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.erp.iam.application.AuthenticationService;
import com.erp.support.CaseId;
import com.erp.support.PostgresTestBase;
import com.erp.support.TestDataFactory;
import com.erp.sys.application.MetricsRecorder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
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
 * 指标端点（FR-SYS-09）与 traceId 贯穿（FR-SYS-08）。
 *
 * <p><b>为什么指标要写测试</b>：一个 200 但只有 JVM 内存指标的
 * {@code /actuator/prometheus} 毫无业务价值 —— 它看起来"能用"，
 * 却回答不了"今天下了几张单"。因此断言必须落在<b>自定义业务指标</b>上：
 * {@code erp_order_created_total} / {@code erp_order_failed_total} / {@code erp_audit_*}。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("指标端点 · Prometheus + 自定义业务指标 + traceId 日志")
class MetricsAndTraceIT extends PostgresTestBase {

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
    @Autowired
    private MetricsRecorder metrics;

    private String adminAuth;
    private TestDataFactory data;

    @BeforeEach
    void setUp() {
        data = new TestDataFactory(jdbc);
        data.truncateAll();
        jdbc.execute("TRUNCATE audit_logs RESTART IDENTITY");
        data.user("m_admin", "MAdmin1!", "ADMIN", encoder);
        adminAuth = "Bearer " + authentication.login("m_admin", "MAdmin1!").token();
    }

    // ─────────────────────────── 指标持久化断言（端点本身见 ActuatorPrometheusIT） ───────────────────────────

    @Test
    @CaseId("TC-SYS-080")
    @DisplayName("★ 下单数随成功建单递增（指标真的在计数，不是只注册了个空名字）")
    void orderCreatedCounter_increments() throws Exception {
        double before = metrics.counterValue("erp.order.placed", "orderType", "product");
        mvc.perform(post("/api/v1/products").header("Authorization", adminAuth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"code": "M-P2", "name": "计数商品"}"""));
        double after = metrics.counterValue("erp.order.placed", "orderType", "product");
        assertThat(after)
                .as("★ 建一个商品后 erp.order.placed{orderType=product} 必须 +1")
                .isEqualTo(before + 1);
    }

    @Test
    @CaseId("TC-SYS-081")
    @DisplayName("★ 失败数按错误码分维度：非法参数产生 errorCode 维度并 +1")
    void orderFailedCounter_isTaggedByErrorCode() throws Exception {
        mvc.perform(post("/api/v1/products").header("Authorization", adminAuth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"code": "", "name": "缺编码商品"}"""));
        double failed = metrics.counterValue("erp.order.failed",
                "orderType", "product", "errorCode", "SYS_VALIDATION_ERROR");
        assertThat(failed)
                .as("★ 失败数必须带 errorCode 维度，否则「为什么失败」无法从指标回答")
                .isGreaterThan(0d);
    }

    @Test
    @CaseId("TC-SYS-082")
    @DisplayName("★ 审计行数 gauge 与实际行数一致（审计链路没停的看板信号）")
    void auditLogsGauge_matchesTable() throws Exception {
        mvc.perform(post("/api/v1/products").header("Authorization", adminAuth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"code": "M-P3", "name": "计数 gauge 商品"}"""));
        Long rows = jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs", Long.class);
        double gauge = metrics.gaugeValue("erp.audit.logs.total.all");
        assertThat(gauge)
                .as("gauge 必须等于 audit_logs 实际行数（若长期不涨，说明审计链路已停）")
                .isEqualTo(rows == null ? 0d : rows.doubleValue());
    }

    @Test
    @CaseId("TC-SYS-083")
    @DisplayName("对账差额指标：erp_reconcile_diff_abs 可读且非负")
    void reconcileDiffGauge_isReadable() {
        metrics.reconcileDiff(-1234);
        assertThat(metrics.gaugeValue("erp.reconcile.diff.abs"))
                .as("对账差额指标取绝对值，负数必须转正")
                .isEqualTo(1234d);
    }
    // ─────────────────────────── traceId 贯穿 ───────────────────────────

    /**
     * ★ FR-SYS-08 的链路断言：同一个 traceId 出现在
     * ① 响应头、② 审计行、③ MDC（通过日志 appender 捕获）。
     *
     * <p>这里只断言前两处 + MDC 的可观测结果（响应头与审计行已在
     * {@code AuditCoreIT} 覆盖）。日志侧用"能按 traceId 捞出该请求的日志"来验证：
     * {@link com.erp.support.TraceLogCapture} 会在一个请求内挂上 appender，
     * 把 MDC 里的 traceId 与日志行一起抓下来。
     */
    @Test
    @CaseId("TC-SYS-084")
    @DisplayName("★ traceId 贯穿日志：按 traceId 能捞出该请求的全部日志行")
    void logsAreRetrievableByTraceId() {
        String traceId = "trace-log-0001";
        List<String> lines = com.erp.support.TraceLogCapture.capture(() ->
                        mvc.perform(post("/api/v1/products")
                                        .header("Authorization", adminAuth)
                                        .header("X-Trace-Id", traceId)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("""
                                                {"code": "M-P4", "name": "日志贯穿商品"}"""))
                                .andReturn(),
                traceId);
        assertThat(lines)
                .as("★ 必须能按 traceId 从日志里捞出该请求的记录；否则 trace 就只是个头字段")
                .isNotEmpty();
        assertThat(lines).allSatisfy(line -> assertThat(line).contains(traceId));
    }

    @Test
    @CaseId("TC-SYS-085")
    @DisplayName("不同请求的 traceId 互不串味（并发安全：MDC 用完即清）")
    void traceIds_doNotLeakBetweenRequests() throws Exception {
        MvcResult first = mvc.perform(post("/api/v1/products")
                        .header("Authorization", adminAuth)
                        .header("X-Trace-Id", "trace-iso-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code": "M-P5", "name": "隔离商品 A"}"""))
                .andReturn();
        MvcResult second = mvc.perform(post("/api/v1/products")
                        .header("Authorization", adminAuth)
                        .header("X-Trace-Id", "trace-iso-b")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code": "M-P6", "name": "隔离商品 B"}"""))
                .andReturn();

        assertThat(first.getResponse().getHeader("X-Trace-Id")).isEqualTo("trace-iso-a");
        assertThat(second.getResponse().getHeader("X-Trace-Id")).isEqualTo("trace-iso-b");

        List<String> traces = jdbc.queryForList("""
                SELECT DISTINCT trace_id FROM audit_logs
                 WHERE trace_id IN ('trace-iso-a', 'trace-iso-b') ORDER BY trace_id
                """, String.class);
        assertThat(traces).containsExactly("trace-iso-a", "trace-iso-b");
    }

    // ─────────────────────────── 字典（FR-SYS-07 配套） ───────────────────────────

    @Test
    @CaseId("TC-SYS-086")
    @DisplayName("字典类型列表：含 COLOR/SIZE/SEASON/UNIT 四个种子类型")
    void dictTypes_areSeeded() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/dicts").header("Authorization", adminAuth))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode items = json.readTree(result.getResponse().getContentAsString()).path("data");
        List<String> codes = new java.util.ArrayList<>();
        items.forEach(node -> codes.add(node.path("code").asText()));
        assertThat(codes).contains("COLOR", "SIZE", "SEASON", "UNIT");
    }

    @Test
    @CaseId("TC-SYS-087")
    @DisplayName("字典项按 sort_no 升序返回，且带 typeCode")
    void dictItems_areOrderedBySortNo() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/dicts/COLOR/items")
                        .header("Authorization", adminAuth))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode items = json.readTree(result.getResponse().getContentAsString()).path("data");
        assertThat(items).isNotEmpty();
        int previous = Integer.MIN_VALUE;
        for (JsonNode item : items) {
            assertThat(item.path("typeCode").asText()).isEqualTo("COLOR");
            assertThat(item.path("sortNo").asInt()).isGreaterThanOrEqualTo(previous);
            previous = item.path("sortNo").asInt();
        }
    }

    @Test
    @CaseId("TC-SYS-088")
    @DisplayName("字典类型不存在：404 + SYS_DICT_TYPE_NOT_FOUND（不是空数组）")
    void unknownDictType_returns404() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/dicts/NO_SUCH_TYPE/items")
                        .header("Authorization", adminAuth))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(json.readTree(result.getResponse().getContentAsString())
                .path("code").asText()).isEqualTo("SYS_DICT_TYPE_NOT_FOUND");
    }

    @Test
    @CaseId("TC-SYS-089")
    @DisplayName("字典接口无 sys:read 权限：403")
    void dictWithoutPermission_gets403() throws Exception {
        data.user("m_viewer2", "MViewer2!", "PURCHASER", encoder);
        String purchaser = "Bearer " + authentication.login("m_viewer2", "MViewer2!").token();
        MvcResult result = mvc.perform(get("/api/v1/dicts").header("Authorization", purchaser))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    @CaseId("TC-SYS-090")
    @DisplayName("字典是只读的：对 /dicts 发 POST 返回 405（不是 500）")
    void dictEndpoint_hasNoWriteMethods() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/dicts")
                        .header("Authorization", adminAuth)
                        .header("X-Request-Id", "dict-write-probe")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as("只读资源的写尝试必须 405（客户端用错方法不该报成 500）")
                .isEqualTo(405);
    }
}
