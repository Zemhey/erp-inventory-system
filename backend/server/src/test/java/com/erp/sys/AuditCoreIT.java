package com.erp.sys;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.erp.iam.application.AuthenticationService;
import com.erp.support.CaseId;
import com.erp.support.PostgresTestBase;
import com.erp.support.TestDataFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 操作审计的核心语义（FR-SYS-06）：<b>留痕、可还原、不可篡改、贯穿</b>。
 *
 * <p>本类刻意把四条"审计凭什么可信"的性质分开测，而不是合并成一个大用例：
 * <ul>
 *   <li><b>留痕</b>：成功的写操作有记录，失败的写操作<b>也有</b>（后者最常被漏掉）；</li>
 *   <li><b>可还原</b>：{@code before_json} / {@code after_json} 能还原出变更前后的字段值；</li>
 *   <li><b>不可篡改</b>：没有 update/delete 能力的双保险（代码 + 数据库触发器）；</li>
 *   <li><b>贯穿</b>：请求头 → MDC → 响应头 → 审计行，四处同值。</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("审计 · 留痕 / 可还原 / 不可篡改 / traceId 贯穿")
class AuditCoreIT extends PostgresTestBase {

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
    void setUp() {
        data = new TestDataFactory(jdbc);
        data.truncateAll();
        jdbc.execute("TRUNCATE audit_logs RESTART IDENTITY");
        data.user("audit_admin", "AuditAdmin1!", "ADMIN", encoder);
        data.user("audit_viewer", "AuditViewer1!", "VIEWER", encoder);
        adminAuth = "Bearer " + authentication.login("audit_admin", "AuditAdmin1!").token();
        viewerAuth = "Bearer " + authentication.login("audit_viewer", "AuditViewer1!").token();
    }

    // ─────────────────────────── 留痕 ───────────────────────────

    @Test
    @CaseId("TC-SYS-033")
    @DisplayName("★ 成功的写操作留痕：建商品 → 1 条 CREATE/PRODUCT/SUCCESS")
    void successfulWrite_isAudited() throws Exception {
        long productId = createProduct("AUD-P1", "审计测试商品");

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT actor_name, action, resource_type, resource_id, result, error_code
                  FROM audit_logs WHERE resource_type = 'PRODUCT' AND action = 'CREATE'
                 ORDER BY id DESC LIMIT 1
                """);
        assertThat(row.get("actor_name")).isEqualTo("audit_admin");
        assertThat(row.get("result")).isEqualTo("SUCCESS");
        assertThat(row.get("error_code")).isNull();
        assertThat(String.valueOf(row.get("resource_id"))).isEqualTo(String.valueOf(productId));
    }

    @Test
    @CaseId("TC-SYS-034")
    @DisplayName("★ 失败的写操作必须留痕：业务 404 也要有 1 条 result=FAIL 的审计")
    void failedWrite_isAudited() throws Exception {
        mvc.perform(put("/api/v1/products/999999").header("Authorization", adminAuth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name": "不存在的商品"}"""));
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT action, result, error_code, actor_name
                  FROM audit_logs WHERE result = 'FAIL' ORDER BY id DESC LIMIT 1
                """);
        assertThat(row.get("result")).isEqualTo("FAIL");
        assertThat(row.get("action")).isEqualTo("UPDATE");
        // 错误码与 GlobalExceptionHandler 返回的码一致，而不是编一个假的
        assertThat(row.get("error_code")).isEqualTo("SYS_NOT_FOUND");
    }

    /**
     * ★ 本泳道最容易踩的坑的直接证据：<b>业务回滚不得把审计一起回滚</b>。
     *
     * <p>做法：发一个"会失败并抛异常"的写请求（PUT 不存在的商品 → 应用服务抛
     * BusinessException）。如果审计写在业务事务内，这条审计会被一起回滚，
     * 断言就会拿到 0 条 —— 于是"失败的写操作永远查不到"。
     * 这条用例因此是 {@code REQUIRES_NEW} 的行为级证明，而不只是代码注释。
     */
    @Test
    @CaseId("TC-SYS-035")
    @DisplayName("★ 业务失败回滚后审计仍在（REQUIRES_NEW 独立事务的行为级证明）")
    void auditSurvivesBusinessRollback() throws Exception {
        long productId = createProduct("AUD-P2", "回滚测试商品");

        // 一次必然失败的写操作（商品名超长 → 数据库约束/校验抛异常）
        mvc.perform(put("/api/v1/products/" + productId).header("Authorization", adminAuth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"" + "超长".repeat(200) + "\"}"));

        // 业务侧确实回滚了：商品名没变
        String name = jdbc.queryForObject("SELECT name FROM products WHERE id = ?",
                String.class, productId);
        assertThat(name).isEqualTo("回滚测试商品");

        // 但审计在：说明它跑在独立事务里，没有被业务回滚带走
        Long failed = jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_logs
                 WHERE resource_type = 'PRODUCT' AND resource_id = ?
                   AND result = 'FAIL'
                """, Long.class, String.valueOf(productId));
        assertThat(failed)
                .as("★ 业务回滚后审计必须仍在（REQUIRES_NEW）；若为 0，说明审计被业务事务带走了")
                .isGreaterThan(0L);
    }

    @Test
    @CaseId("TC-SYS-036")
    @DisplayName("越权被拒（403）也必须留痕 —— 那是安全事件")
    void forbiddenWrite_isAudited() throws Exception {
        mvc.perform(post("/api/v1/products").header("Authorization", viewerAuth)
                .header("X-Request-Id", "audit-forbidden-001")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"code": "AUD-P3", "name": "越权商品"}"""));
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT actor_name, action, resource_type, result, error_code
                  FROM audit_logs WHERE result = 'FAIL' AND error_code = 'IAM_FORBIDDEN'
                 ORDER BY id DESC LIMIT 1
                """);
        assertThat(row.get("actor_name")).isEqualTo("audit_viewer");
        assertThat(row.get("action")).isEqualTo("CREATE");
        assertThat(row.get("resource_type")).isEqualTo("PRODUCT");
    }

    @Test
    @CaseId("TC-SYS-037")
    @DisplayName("匿名写操作留痕：actorId 为空但 actorName 有值")
    void anonymousWrite_isAudited() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"username": "no-such-user", "password": "WrongPass1!"}"""));
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT actor_id, actor_name, action, resource_type, result, error_code
                  FROM audit_logs WHERE action = 'LOGIN' ORDER BY id DESC LIMIT 1
                """);
        assertThat(row.get("actor_id")).isNull();
        assertThat(row.get("action")).isEqualTo("LOGIN");
        assertThat(row.get("result")).isEqualTo("FAIL");
        assertThat(row.get("error_code")).isEqualTo("IAM_BAD_CREDENTIALS");
    }

    // ─────────────────────────── 可还原 ───────────────────────────

    /**
     * ★ 前后值可还原：更新商品后，能从 {@code before_json} / {@code after_json}
     * 读出变更前后的具体字段值。
     *
     * <p>这是审计"能被审计"的最低标准：只记"某人更新了商品 3"没有用，
     * 必须能回答"从什么改成了什么"。
     */
    @Test
    @CaseId("TC-SYS-038")
    @DisplayName("★ 前后值可还原：before/after 能读出商品名的变更前后值")
    void beforeAndAfter_canRestoreFieldValues() throws Exception {
        long productId = createProduct("AUD-P4", "改名前商品");

        mvc.perform(put("/api/v1/products/" + productId).header("Authorization", adminAuth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name": "改名后商品", "enabled": true}"""));

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT before_json::text AS before_json, after_json::text AS after_json
                  FROM audit_logs
                 WHERE resource_type = 'PRODUCT' AND resource_id = ? AND action = 'UPDATE'
                   AND result = 'SUCCESS'
                 ORDER BY id DESC LIMIT 1
                """, String.valueOf(productId));

        JsonNode before = json.readTree(String.valueOf(row.get("before_json")));
        JsonNode after = json.readTree(String.valueOf(row.get("after_json")));

        assertThat(before.path("name").asText())
                .as("before_json 必须能还原出变更前的商品名")
                .isEqualTo("改名前商品");
        assertThat(after.path("name").asText())
                .as("after_json 必须能还原出变更后的商品名")
                .isEqualTo("改名后商品");
        assertThat(before.path("id").asLong())
                .as("两次快照必须指向同一个资源")
                .isEqualTo(after.path("id").asLong());
    }

    /**
     * 首次创建没有 before：{@code before_json} 为 null 是正常情况，不是缺陷。
     *
     * <p>把它写成显式用例，避免下一个人"为了不看到 null"去编一个空对象 ——
     * 那会让"从未存在过"与"存在但为空"再也分不开。
     */
    @Test
    @CaseId("TC-SYS-039")
    @DisplayName("首次创建：before_json 为 null（如实表达「之前不存在」）")
    void firstCreate_hasNullBefore() throws Exception {
        long productId = createProduct("AUD-P5", "全新商品");
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT before_json::text AS before_json, after_json::text AS after_json
                  FROM audit_logs
                 WHERE resource_type = 'PRODUCT' AND resource_id = ? AND action = 'CREATE'
                 ORDER BY id DESC LIMIT 1
                """, String.valueOf(productId));
        assertThat(row.get("before_json")).isNull();
        assertThat(row.get("after_json")).isNotNull();
        JsonNode after = json.readTree(String.valueOf(row.get("after_json")));
        assertThat(after.path("code").asText()).isEqualTo("AUD-P5");
    }

    /**
     * 多次变更形成可读的<b>快照片段</b>：上一次的 after 就是这一次的 before。
     *
     * <p>这条性质让"某个字段何时被改过"可以沿着审计链回溯，
     * 而不需要在业务表上加影子列。
     */
    @Test
    @CaseId("TC-SYS-040")
    @DisplayName("★ 快照片段首尾相接：第 2 次变更的 before == 第 1 次变更的 after")
    void snapshots_chainAcrossUpdates() throws Exception {
        long productId = createProduct("AUD-P6", "第一次名字");
        updateProduct(productId, "第二次名字");
        updateProduct(productId, "第三次名字");

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT before_json::text AS before_json, after_json::text AS after_json
                  FROM audit_logs
                 WHERE resource_type = 'PRODUCT' AND resource_id = ? AND action = 'UPDATE'
                   AND result = 'SUCCESS'
                 ORDER BY id ASC
                """, String.valueOf(productId));
        assertThat(rows).hasSizeGreaterThanOrEqualTo(2);

        for (int i = 1; i < rows.size(); i++) {
            String previousAfter = String.valueOf(rows.get(i - 1).get("after_json"));
            String currentBefore = String.valueOf(rows.get(i).get("before_json"));
            assertThat(currentBefore)
                    .as("第 %d 次变更的 before 必须等于第 %d 次的 after（快照片段首尾相接）",
                            i + 1, i)
                    .isEqualTo(previousAfter);
        }
    }

    // ─────────────────────────── 不可篡改 ───────────────────────────

    /**
     * ★ 数据库触发器：任何人（包括手写 SQL）都改不了、删不了审计行。
     *
     * <p>这是"不可篡改"的<b>最后一层</b>：代码层的 ArchUnit 规则挡不住 psql，
     * 只有数据库触发器能挡住"绕过应用的手工运维操作"。
     */
    @Test
    @CaseId("TC-SYS-041")
    @DisplayName("★ 不可篡改：UPDATE audit_logs 被数据库触发器拒绝")
    void updateAuditRow_isRejectedByDatabase() throws Exception {
        createProduct("AUD-P7", "不可篡改商品");
        Long id = jdbc.queryForObject("SELECT max(id) FROM audit_logs", Long.class);
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE audit_logs SET result = 'FAIL' WHERE id = ?", id))
                .as("★ 审计行的 UPDATE 必须被数据库拒绝；放行就意味着审计可以被人改掉")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("追加型");
    }

    @Test
    @CaseId("TC-SYS-042")
    @DisplayName("★ 不可篡改：DELETE audit_logs 被数据库触发器拒绝")
    void deleteAuditRow_isRejectedByDatabase() throws Exception {
        createProduct("AUD-P8", "不可删除商品");
        Long id = jdbc.queryForObject("SELECT max(id) FROM audit_logs", Long.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_logs WHERE id = ?", id))
                .as("★ 审计行的 DELETE 必须被数据库拒绝")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("追加型");
        // 行仍在
        Long still = jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE id = ?",
                Long.class, id);
        assertThat(still).isEqualTo(1L);
    }

    // ─────────────────────────── traceId 贯穿 ───────────────────────────

    /**
     * ★ FR-SYS-08：请求头 {@code X-Trace-Id} → MDC → 响应头 → 审计行，四处同值。
     *
     * <p>这条是"链路断言"：只有四处同值，测试失败时才能用响应头里的 traceId
     * 直接捞到该请求的全部日志与审计。
     */
    @Test
    @CaseId("TC-SYS-043")
    @DisplayName("★ traceId 贯穿：请求头 → 响应头 → 审计行，三处同值")
    void traceId_propagatesFromHeaderToResponseAndAudit() throws Exception {
        String given = "trace-fixed-0001";
        MvcResult result = mvc.perform(post("/api/v1/products")
                        .header("Authorization", adminAuth)
                        .header("X-Trace-Id", given)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code": "AUD-P9", "name": "贯穿测试商品"}"""))
                .andReturn();

        assertThat(result.getResponse().getHeader("X-Trace-Id"))
                .as("响应头必须回传 traceId")
                .isEqualTo(given);

        String auditedTrace = jdbc.queryForObject("""
                SELECT trace_id FROM audit_logs
                 WHERE resource_type = 'PRODUCT' AND action = 'CREATE'
                 ORDER BY id DESC LIMIT 1
                """, String.class);
        assertThat(auditedTrace)
                .as("审计行必须带同一个 traceId（请求头 → MDC → 审计）")
                .isEqualTo(given);
    }

    @Test
    @CaseId("TC-SYS-044")
    @DisplayName("无 X-Trace-Id 时自动生成，且响应头与审计行一致")
    void traceId_generatedWhenAbsent() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/products")
                        .header("Authorization", adminAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code": "AUD-P10", "name": "自动 traceId 商品"}"""))
                .andReturn();
        String generated = result.getResponse().getHeader("X-Trace-Id");
        assertThat(generated).isNotBlank();

        String auditedTrace = jdbc.queryForObject("""
                SELECT trace_id FROM audit_logs
                 WHERE resource_type = 'PRODUCT' AND action = 'CREATE'
                 ORDER BY id DESC LIMIT 1
                """, String.class);
        assertThat(auditedTrace).isEqualTo(generated);
    }

    /**
     * 按 traceId 能捞出该请求的<b>全部</b>审计（一次请求只产生一条审计时的最强断言）。
     *
     * <p>用查询接口而不是直接 SQL：这样同时验证了 FR-SYS-07 的 {@code /by-trace} 入口。
     */
    @Test
    @CaseId("TC-SYS-045")
    @DisplayName("按 traceId 能捞出该请求的全部审计（查询接口 /audits/by-trace）")
    void auditsAreRetrievableByTraceId() throws Exception {
        String given = "trace-lookup-0002";
        mvc.perform(post("/api/v1/products")
                .header("Authorization", adminAuth)
                .header("X-Trace-Id", given)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"code": "AUD-P11", "name": "按 trace 捞商品"}"""));

        MvcResult result = mvc.perform(get("/api/v1/audits/by-trace")
                        .header("Authorization", adminAuth)
                        .param("traceId", given))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("data")).hasSize(1);
        assertThat(body.path("data").get(0).path("traceId").asText()).isEqualTo(given);
    }

    // ─────────────────────────── 敏感字段脱敏 ───────────────────────────

    /**
     * ★ 登录接口的审计<b>不得</b>把密码明文写进审计表。
     *
     * <p>审计切面挂在所有写接口上，包括 {@code POST /auth/login}。
     * 如果不脱敏，审计表就成了一个密码明文库 —— 而审计表是"给很多人看"的表。
     * 这条用例守的是"脱敏确实生效"，而不是"我记得脱敏了"。
     */
    @Test
    @CaseId("TC-SYS-046")
    @DisplayName("★ 审计不得记录密码明文（/auth/login 的 after_json 里没有 password 原文）")
    void loginAudit_mustNotContainPlaintextPassword() throws Exception {
        String secret = "SuperSecret1!";
        data.user("audit_pwd", secret, "VIEWER", encoder);
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"username": "audit_pwd", "password": "%s"}""".formatted(secret)));

        List<String> afterTexts = jdbc.queryForList("""
                SELECT coalesce(after_json::text, '') FROM audit_logs
                 WHERE action = 'LOGIN' AND result = 'SUCCESS'
                """, String.class);
        for (String after : afterTexts) {
            assertThat(after)
                    .as("★ 审计里出现了密码明文：审计表会变成密码库")
                    .doesNotContain(secret);
        }
    }

    // ─────────────────────────── 审计动作归类 ───────────────────────────

    @Test
    @CaseId("TC-SYS-047")
    @DisplayName("动作按路径语义归类：/disable → DISABLE，而不是 POST → CREATE")
    void action_isDerivedFromPathSemantics() throws Exception {
        long productId = createProduct("AUD-P12", "停用测试商品");
        mvc.perform(post("/api/v1/products/" + productId + "/disable")
                .header("Authorization", adminAuth)
                .header("X-Request-Id", "audit-disable-001"));

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT action, resource_type, resource_id FROM audit_logs
                 WHERE resource_type = 'PRODUCT' AND resource_id = ? AND action = 'DISABLE'
                 ORDER BY id DESC LIMIT 1
                """, String.valueOf(productId));
        assertThat(row.get("action")).isEqualTo("DISABLE");
    }

    @Test
    @CaseId("TC-SYS-048")
    @DisplayName("单据动作归类：进货单 submit/approve/receive 各有独立动作，不混成 CREATE")
    void documentActions_areDistinguished() throws Exception {
        long supplierId = data.supplier("SUP-AUD");
        long warehouseId = data.warehouse("WH-AUD");
        long productId = data.product("P-AUD", "单据动作商品");
        long skuId = data.sku(productId, "黑色", "M");

        MvcResult created = mvc.perform(post("/api/v1/purchase-orders")
                        .header("Authorization", adminAuth)
                        .header("X-Request-Id", "audit-po-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"supplierId": %d, "warehouseId": %d,
                                 "lines": [{"skuId": %d, "qty": 5, "unitPriceAmount": 1000}]}"""
                                .formatted(supplierId, warehouseId, skuId)))
                .andReturn();
        long orderId = json.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        mvc.perform(post("/api/v1/purchase-orders/" + orderId + "/submit")
                .header("Authorization", adminAuth).header("X-Request-Id", "audit-po-002"));
        mvc.perform(post("/api/v1/purchase-orders/" + orderId + "/approve")
                .header("Authorization", adminAuth).header("X-Request-Id", "audit-po-003"));

        List<String> actions = jdbc.queryForList("""
                SELECT action FROM audit_logs
                 WHERE resource_type = 'PURCHASE_ORDER' AND resource_id = ?
                 ORDER BY id ASC
                """, String.class, String.valueOf(orderId));
        assertThat(actions)
                .as("同一张进货单的建单/提交/审核必须各自留下可区分的动作")
                .containsSubsequence("CREATE", "SUBMIT", "APPROVE");
    }

    @Test
    @CaseId("TC-SYS-049")
    @DisplayName("biz_ref_no 从响应体取得：建单审计能挂上单据号")
    void bizRefNo_isCapturedFromResponse() throws Exception {
        long supplierId = data.supplier("SUP-AUD2");
        long warehouseId = data.warehouse("WH-AUD2");
        long productId = data.product("P-AUD2", "单号商品");
        long skuId = data.sku(productId, "白色", "L");

        MvcResult created = mvc.perform(post("/api/v1/purchase-orders")
                        .header("Authorization", adminAuth)
                        .header("X-Request-Id", "audit-po-010")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"supplierId": %d, "warehouseId": %d,
                                 "lines": [{"skuId": %d, "qty": 3, "unitPriceAmount": 2000}]}"""
                                .formatted(supplierId, warehouseId, skuId)))
                .andReturn();
        String orderNo = json.readTree(created.getResponse().getContentAsString())
                .path("data").path("orderNo").asText();
        assertThat(orderNo).isNotBlank();

        String audited = jdbc.queryForObject("""
                SELECT biz_ref_no FROM audit_logs
                 WHERE resource_type = 'PURCHASE_ORDER' AND action = 'CREATE'
                 ORDER BY id DESC LIMIT 1
                """, String.class);
        assertThat(audited)
                .as("audit_logs.biz_ref_no 必须等于单据号，否则「按单据追溯」失效")
                .isEqualTo(orderNo);
    }

    /**
     * ★ 取不到 biz_ref_no 时写 null，<b>绝不抛异常</b>（用户点名的第二个坑）。
     *
     * <p>做法：调一个"响应体里没有单据号"的写接口（商品停用），
     * 断言审计仍产生、{@code biz_ref_no} 为 null、业务正常成功。
     */
    @Test
    @CaseId("TC-SYS-050")
    @DisplayName("★ 取不到 biz_ref_no 时写 null 且业务不受影响（审计绝不影响业务）")
    void missingBizRefNo_isNullAndHarmless() throws Exception {
        long productId = createProduct("AUD-P13", "无单号商品");
        MvcResult result = mvc.perform(post("/api/v1/products/" + productId + "/disable")
                        .header("Authorization", adminAuth)
                        .header("X-Request-Id", "audit-noref-001"))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as("业务必须成功：取不到 biz_ref_no 不能影响业务")
                .isEqualTo(200);

        String bizRefNo = jdbc.queryForObject("""
                SELECT biz_ref_no FROM audit_logs
                 WHERE resource_type = 'PRODUCT' AND resource_id = ? AND action = 'DISABLE'
                 ORDER BY id DESC LIMIT 1
                """, String.class, String.valueOf(productId));
        assertThat(bizRefNo).isNull();
    }

    // ─────────────────────────── 辅助 ───────────────────────────

    private long createProduct(String code, String name) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/products")
                        .header("Authorization", adminAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code": "%s", "name": "%s"}""".formatted(code, name)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return json.readTree(result.getResponse().getContentAsString())
                .path("data").path("id").asLong();
    }

    private void updateProduct(long id, String name) throws Exception {
        mvc.perform(put("/api/v1/products/" + id).header("Authorization", adminAuth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name": "%s", "enabled": true}""".formatted(name)));
    }
}
