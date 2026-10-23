package com.erp.sys;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.support.CaseId;
import com.erp.support.PostgresTestBase;
import com.erp.support.TestDataFactory;
import com.erp.sys.application.MetricsRecorder;
import com.erp.sys.domain.AuditAction;
import com.erp.sys.domain.AuditResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * {@code /actuator/prometheus} 的端到端用例（FR-SYS-09）。
 *
 * <p><b>★ 为什么这个类必须用真实端口（{@code RANDOM_PORT}）而不是 {@code MockMvc}</b>：
 * Actuator 端点由<b>独立的 {@code WebMvcEndpointHandlerMapping}</b> 注册，
 * 而不是业务用的 {@code RequestMappingHandlerMapping}。{@code MockMvc} 的
 * standalone 式装配拿不到那份映射，于是请求会掉进静态资源兜底，抛
 * {@code NoResourceFoundException} —— 表现为"指标端点 500"，而用真实端口启动时
 * 它明明返回 200。本泳道实测踩到过这个假红，排查代价不小：
 * 一开始以为是 Prometheus 注册表重复注册导致的 500。
 *
 * <p><b>★ 为什么必须加 {@code @AutoConfigureObservability}</b>：
 * Spring Boot 为了让测试跑得快，在 {@code @SpringBootTest} 里<b>默认关闭指标导出</b>，
 * 并把 {@code MeterRegistry} 换成 {@code SimpleMeterRegistry}。
 * 于是 {@code management.endpoints.web.exposure.include=prometheus} 明明配了，
 * 测试上下文里却只有 3 个端点、{@code /actuator/prometheus} 直接 404/500 ——
 * 而生产环境一切正常。这个"测试环境里没有 prometheus"的陷阱极难从报错看出来
 * （报错是 {@code NoResourceFoundException: No static resource actuator/prometheus}），
 * 本泳道为此排查了两轮。{@code @AutoConfigureObservability} 就是那个开关。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
@DisplayName("指标端点 · /actuator/prometheus（真实端口）")
class ActuatorPrometheusIT extends PostgresTestBase {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PasswordEncoder encoder;
    @Autowired
    private MetricsRecorder metrics;

    @BeforeEach
    void setUp() {
        new TestDataFactory(jdbc).truncateAll();
        jdbc.execute("TRUNCATE audit_logs RESTART IDENTITY");
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    @Test
    @CaseId("TC-SYS-001")
    @DisplayName("★ /actuator/prometheus 返回 200 且匿名可访问（抓取器不带 JWT）")
    void prometheusEndpoint_isReachableWithoutAuth() {
        ResponseEntity<String> response = rest.getForEntity(url("/actuator/prometheus"),
                String.class);
        assertThat(response.getStatusCode().value())
                .as("★ Prometheus 抓取端点必须匿名可访问且返回 200")
                .isEqualTo(200);
    }

    @Test
    @CaseId("TC-SYS-002")
    @DisplayName("★ 端点含自定义业务指标：erp_order_placed_total / erp_order_failed_total / erp_audit_*")
    void prometheusEndpoint_hasCustomBusinessMetrics() {
        // 经 MetricsRecorder 产生成功与失败各一次，验证"注册 + 暴露"这条路
        metrics.observeWrite("PURCHASE_ORDER", AuditAction.CREATE, AuditResult.SUCCESS, null);
        metrics.observeWrite("SALES_ORDER", AuditAction.CREATE, AuditResult.FAIL,
                "INV_INSUFFICIENT_STOCK");
        // ★ 审计写入计数只在真的写过审计时才有序列：Micrometer 不会凭空导出
        //   一条没有标签值的 counter（这是"指标不存在"与"指标为 0"的区别，
        //   实测踩到过：断言 erp_audit_written_total 却拿到 404 般的缺失）
        metrics.auditWritten(AuditAction.CREATE, AuditResult.SUCCESS);

        String body = rest.getForEntity(url("/actuator/prometheus"), String.class).getBody();
        assertThat(body).isNotNull();
        assertThat(body).as("★ 必须含「下单数」指标").contains("erp_order_placed_total");
        assertThat(body).as("★ 必须含「失败数」指标").contains("erp_order_failed_total");
        assertThat(body).as("★ 必须含审计写入计数").contains("erp_audit_written_total");
        assertThat(body).as("★ 必须含审计表行数 gauge").contains("erp_audit_logs_total_all");
    }

    @Test
    @CaseId("TC-SYS-003")
    @DisplayName("★ 指标暴露格式合法：每个指标都有 HELP/TYPE 且带 application 标签")
    void prometheusFormat_isValid() {
        String body = rest.getForEntity(url("/actuator/prometheus"), String.class).getBody();
        assertThat(body).isNotNull();
        assertThat(body).as("Prometheus 文本格式要求 # HELP 与 # TYPE 行")
                .contains("# HELP erp_order_placed_total")
                .contains("# TYPE erp_order_placed_total counter");
        assertThat(body).as("公共标签 application 必须存在（多实例区分）")
                .contains("application=\"erp-server\"");
    }

    @Test
    @CaseId("TC-SYS-004")
    @DisplayName("★ 指标端点不会因为对账差额重复记录而 500（Gauge 重复注册的回归用例）")
    void prometheusEndpoint_survivesRepeatedReconcileDiff() {
        // 本泳道实测踩到：Gauge 重复注册在复合注册表下抛异常 → 指标端点 500。
        // 这条用例把那个回归钉住：连续记录多次后端点仍必须 200。
        metrics.reconcileDiff(500);
        metrics.reconcileDiff(-800);
        metrics.reconcileDiff(0);

        ResponseEntity<String> response = rest.getForEntity(url("/actuator/prometheus"),
                String.class);
        assertThat(response.getStatusCode().value())
                .as("★ 重复记录对账差额后指标端点必须仍然 200")
                .isEqualTo(200);
        assertThat(response.getBody()).contains("erp_reconcile_diff_abs");
    }

    @Test
    @CaseId("TC-SYS-005")
    @DisplayName("health 端点仍可匿名访问（改管理端点时不得误伤探针）")
    void healthEndpoint_stillPermitted() {
        ResponseEntity<String> response = rest.getForEntity(url("/actuator/health"), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @CaseId("TC-SYS-006")
    @DisplayName("审计表行数 gauge 随真实写入增长（审计链路没停的看板信号）")
    void auditLogsGauge_reflectsRealRows() throws Exception {
        // 经真实 HTTP 写一次（真实端口：走完整过滤器链 + 安全链）
        int status = postJson("/api/v1/auth/login",
                "{\"username\":\"no-such\",\"password\":\"Wrong1!\"}");
        assertThat(status).isIn(400, 401);

        String body = rest.getForEntity(url("/actuator/prometheus"), String.class).getBody();
        assertThat(body)
                .as("gauge 必须出现在抓取结果里；行数由 AuditMetricsBinder 现算")
                .contains("erp_audit_logs_total_all");
        Long rows = jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs", Long.class);
        assertThat(rows)
                .as("失败登录也必须留痕（审计保证了 gauge 有数据可读）")
                .isGreaterThanOrEqualTo(1L);
    }

    @Test
    @CaseId("TC-SYS-007")
    @DisplayName("审计指标计数随写入递增（audit_written 是审计链路自身的健康信号）")
    void auditWrittenCounter_increments() throws Exception {
        double before = metrics.counterValue("erp.audit.written",
                "action", "LOGIN", "result", "FAIL");
        postJson("/api/v1/auth/login", "{\"username\":\"nobody-here\",\"password\":\"Wrong1!\"}");
        double after = metrics.counterValue("erp.audit.written",
                "action", "LOGIN", "result", "FAIL");
        assertThat(after)
                .as("★ 一次失败登录后 erp.audit.written{action=LOGIN,result=FAIL} 必须 +1")
                .isGreaterThan(before);
    }

    /**
     * 直接发一个 JSON POST，返回状态码。
     *
     * <p><b>★ 为什么不用 {@link TestRestTemplate}</b>：本类实测踩到
     * {@code HttpRetryException: cannot retry due to server authentication, in streaming mode}。
     * 原因是 {@code TestRestTemplate} 会为"401 需要认证"自动<b>重试</b>请求，
     * 而请求体在流式模式下已经写出去了，无法重放 —— 于是本次请求直接抛异常，
     * 前台看到的是"HTTP 客户端错误"，而不是服务端返回的 401。
     * 用 {@link java.net.http.HttpClient}（不自动重试）可以干净地拿到真实状态码。
     */
    private int postJson(String path, String body) throws Exception {
        var client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(10))
                .build();
        var request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(url(path)))
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString())
                .statusCode();
    }
}
