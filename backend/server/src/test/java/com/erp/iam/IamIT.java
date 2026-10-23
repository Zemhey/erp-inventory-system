package com.erp.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.iam.application.AuthenticationService;
import com.erp.iam.repository.AppUserRepository;
import com.erp.iam.security.JwtTokenService;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.support.CaseId;
import com.erp.support.PostgresTestBase;
import com.erp.support.TestClockConfig.ControllableClock;
import com.erp.support.TestDataFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 认证与权限集成测试（FR-IAM-001 登录/锁定、FR-IAM-002 RBAC 403）。
 *
 * <p>用 {@link ControllableClock} 让"时间快进"，从而在毫秒内验证
 * token 过期与账号锁定到期，而不是 sleep 十几分钟。
 *
 * <p><b>本类刻意不加 {@code @Transactional}</b>：登录是"失败也要留下计数"的操作，
 * 若测试方法本身包在事务里并回滚，失败计数就会被一起回滚，
 * 于是"连续失败 N 次触发锁定"永远测不出来（M1 开发中真实踩到：
 * 用例一直报"剩余尝试 4 次"，因为每次都从 0 重新计数）。
 * 隔离性改由每个用例前的 {@code truncateAll()} 保证。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("clock")
@DisplayName("认证 · 登录 / token / 锁定 / RBAC")
class IamIT extends PostgresTestBase {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AuthenticationService authentication;
    @Autowired
    private JwtTokenService tokens;
    @Autowired
    private AppUserRepository users;
    @Autowired
    private PasswordEncoder encoder;
    @Autowired
    private ControllableClock clock;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper json;

    private TestDataFactory data;

    @BeforeEach
    void setUp() {
        data = new TestDataFactory(jdbc);
        data.truncateAll();
        clock.set(java.time.LocalDateTime.of(2025, 6, 1, 9, 0, 0));
    }

    // ─────────────────────────── FR-IAM-01 登录 ───────────────────────────

    @Test
    @CaseId("TC-IAM-001")
    @DisplayName("登录成功返回 token 与权限；密码错误返回 401（且不区分用户不存在）")
    void login_successAndBadCredentials() {
        data.user("alice", "P@ssw0rd!", "PURCHASER", encoder);

        var result = authentication.login("alice", "P@ssw0rd!");
        assertThat(result.token()).isNotBlank();
        assertThat(result.roles()).containsExactly("PURCHASER");
        assertThat(result.permissions()).contains("pur:create", "pur:receive", "mdm:read");
        assertThat(result.permissions()).as("权限必须按角色正确限制")
                .doesNotContain("sal:ship");

        assertThatThrownBy(() -> authentication.login("alice", "wrong"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.IAM_BAD_CREDENTIALS);

        // 防用户名枚举：不存在的用户与密码错误返回同一个码
        assertThatThrownBy(() -> authentication.login("nobody", "wrong"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.IAM_BAD_CREDENTIALS);
    }

    @Test
    @CaseId("TC-IAM-002")
    @DisplayName("★ 密码连续错误达到阈值后锁定；到期后自动解锁（锁在数据库，重启不失效）")
    void login_lockAfterMaxFailures_thenAutoUnlock() {
        data.user("bob", "Correct1!", "VIEWER", encoder);
        int max = authentication.maxFailedAttempts();

        // 前 max-1 次：普通密码错误
        for (int i = 1; i < max; i++) {
            assertThatThrownBy(() -> authentication.login("bob", "bad"))
                    .as("第 %d 次失败", i)
                    .hasFieldOrPropertyWithValue("code", ErrorCode.IAM_BAD_CREDENTIALS);
        }
        // 第 max 次：触发锁定
        assertThatThrownBy(() -> authentication.login("bob", "bad"))
                .as("第 %d 次失败必须触发锁定", max)
                .hasFieldOrPropertyWithValue("code", ErrorCode.IAM_ACCOUNT_LOCKED);

        // 锁定期间即使密码正确也拒绝
        assertThatThrownBy(() -> authentication.login("bob", "Correct1!"))
                .as("锁定期间正确密码也必须被拒绝")
                .hasFieldOrPropertyWithValue("code", ErrorCode.IAM_ACCOUNT_LOCKED);

        // 锁定信息在数据库里（不是内存计数）
        assertThat(users.findByUsername("bob").orElseThrow().lockedUntil()).isNotNull();

        // 快进到锁定期之后 → 自动解锁
        clock.advance(Duration.ofMinutes(authentication.lockMinutes() + 1));
        var result = authentication.login("bob", "Correct1!");
        assertThat(result.token()).isNotBlank();
        assertThat(users.findByUsername("bob").orElseThrow().failedAttempts())
                .as("登录成功后计数清零").isZero();
    }

    @Test
    @CaseId("TC-IAM-003")
    @DisplayName("停用账号无法登录（IAM_ACCOUNT_DISABLED）")
    void login_whenDisabled_thenReject() {
        long id = data.user("carol", "Correct1!", "VIEWER", encoder);
        jdbc.update("UPDATE app_users SET enabled = FALSE WHERE id = ?", id);

        assertThatThrownBy(() -> authentication.login("carol", "Correct1!"))
                .hasFieldOrPropertyWithValue("code", ErrorCode.IAM_ACCOUNT_DISABLED);
    }

    @Test
    @CaseId("TC-IAM-004")
    @DisplayName("★ token 过期后失效：快进超过有效期，校验必须失败")
    void token_expiresAfterTtl() {
        data.user("dave", "Correct1!", "VIEWER", encoder);
        var result = authentication.login("dave", "Correct1!");

        assertThat(tokens.verify(result.token())).contains("dave");

        clock.advance(tokens.ttl().plusSeconds(1));
        assertThat(tokens.verify(result.token()))
                .as("★ 过期 token 必须无效")
                .isEmpty();
    }

    @Test
    @CaseId("TC-IAM-005")
    @DisplayName("★ 篡改 token 的任意一段都必须失效（签名校验）")
    void token_tamperedSignature_isRejected() {
        data.user("erin", "Correct1!", "VIEWER", encoder);
        String token = authentication.login("erin", "Correct1!").token();

        String[] parts = token.split("\\.");
        assertThat(parts).hasSize(3);

        // ① 改 payload（把角色改成 ADMIN）但保留原签名
        String forgedPayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"sub\":\"erin\",\"roles\":[\"ADMIN\"]}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String forged = parts[0] + "." + forgedPayload + "." + parts[2];
        assertThat(tokens.verify(forged)).as("改 payload 必须破坏签名").isEmpty();

        // ② 改签名
        String badSig = parts[0] + "." + parts[1] + "." + "AAAA" + parts[2].substring(4);
        assertThat(tokens.verify(badSig)).as("改签名必须失败").isEmpty();

        // ③ alg=none（无签名三段式）
        String noneAlg = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"alg\":\"none\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                + "." + parts[1] + ".";
        assertThat(tokens.verify(noneAlg)).as("alg=none 攻击必须失败").isEmpty();
    }

    @Test
    @CaseId("TC-IAM-006")
    @DisplayName("未携带 token 访问受保护接口 → 401；登录接口本身匿名可达")
    void protectedEndpoint_withoutToken_then401() throws Exception {
        mvc.perform(get("/api/v1/products"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("IAM_UNAUTHENTICATED"));

        // 登录接口匿名可达：错误口令返回业务码而不是"未登录"
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", "ghost", "password", "x"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("IAM_BAD_CREDENTIALS"));
    }

    // ─────────────────────────── FR-IAM-02 RBAC ───────────────────────────

    @Test
    @CaseId("TC-IAM-007")
    @DisplayName("★ 无权限接口返回 403：VIEWER 只读可以，写操作必须 403")
    void rbac_viewerCannotWrite_then403() throws Exception {
        data.user("frank", "Correct1!", "VIEWER", encoder);
        String token = authentication.login("frank", "Correct1!").token();

        mvc.perform(get("/api/v1/products").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/products")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("code", "P-X", "name", "测试"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("IAM_FORBIDDEN"));
    }

    @Test
    @CaseId("TC-IAM-008")
    @DisplayName("★ 角色隔离：采购员可建进货单但不能出货；管理员全权")
    void rbac_purchaserCannotShip_adminCanDoEverything() throws Exception {
        data.user("grace", "Correct1!", "PURCHASER", encoder);
        String purchaser = authentication.login("grace", "Correct1!").token();

        // 采购员创建进货单：有 pur:create → 不是 403（业务上会因缺主数据失败）
        mvc.perform(post("/api/v1/purchase-orders")
                        .header("Authorization", "Bearer " + purchaser)
                        .header("X-Request-Id", "req-rbac-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"supplierId":999999,"warehouseId":999999,
                                 "lines":[{"skuId":999999,"qty":1,"unitPriceAmount":100}]}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SYS_NOT_FOUND"));

        // 采购员出货：没有 sal:create → 403
        mvc.perform(post("/api/v1/sales-orders")
                        .header("Authorization", "Bearer " + purchaser)
                        .header("X-Request-Id", "req-rbac-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId":1,"warehouseId":1,
                                 "lines":[{"skuId":1,"qty":1,"unitPriceAmount":100}]}
                                """))
                .andExpect(status().isForbidden());

        // 管理员：mdm:write 放开
        data.user("root", "Correct1!", "ADMIN", encoder);
        String admin = authentication.login("root", "Correct1!").token();
        mvc.perform(post("/api/v1/products")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("code", "P-ADM", "name", "管理员建的商品"))))
                .andExpect(status().isCreated());
    }

    @Test
    @CaseId("TC-IAM-009")
    @DisplayName("权限变更立即生效：撤掉角色后，旧 token 的下一次请求即被拒绝")
    void rbac_roleChangeTakesEffectImmediately() throws Exception {
        long id = data.user("henry", "Correct1!", "ADMIN", encoder);
        String token = authentication.login("henry", "Correct1!").token();

        mvc.perform(post("/api/v1/products")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("code", "P-BEFORE", "name", "撤权前"))))
                .andExpect(status().isCreated());

        // 撤掉全部角色（token 本身仍然有效）
        jdbc.update("DELETE FROM app_user_roles WHERE user_id = ?", id);

        mvc.perform(post("/api/v1/products")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("code", "P-AFTER", "name", "撤权后"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("IAM_FORBIDDEN"));
    }

    @Test
    @CaseId("TC-IAM-010")
    @DisplayName("密码只存 BCrypt 哈希，明文绝不出现在库中")
    void password_isHashedOnly() {
        data.user("ivan", "PlainText1!", "VIEWER", encoder);

        String stored = jdbc.queryForObject(
                "SELECT password_hash FROM app_users WHERE username = 'ivan'", String.class);
        assertThat(stored).as("必须是 BCrypt 哈希").startsWith("$2");
        assertThat(stored).doesNotContain("PlainText1!");
        assertThat(encoder.matches("PlainText1!", stored)).isTrue();
    }

    /**
     * ★ FINANCE 的权限面（V5 的三组码 + V7 补的 {@code mdm:read}）。
     *
     * <p>盯的是计划书 §6 坑 16 的实测症状：结算页与报表页要把仓库/SKU 显示成名字，
     * 而 V5 只给 FINANCE {@code rma/set/rpt} 三组码 → 每个字典请求都是 403，
     * 财务一进页面就挂一串红条。V7 只补 {@code ('FINANCE','mdm:read')} 一行，
     * 所以这条用例同时钉住<b>收益</b>（字典读得到）与<b>边界</b>（读不等于写）。
     *
     * <p>最后一段是<b>刻意登记</b>而不是遗漏：FINANCE 读单据仍然是 403，
     * 那是 M3 工作台在财务登录时报 403 的同一族缺口（坑 16 备注，M3 遗留、本泳道不修）。
     * 将来谁给财务开了 {@code pur:read}，这条会红 —— 那时请把期望一起改掉，别删用例。
     */
    @Test
    @CaseId("TC-IAM-011")
    @DisplayName("★ FINANCE 权限面：V7 后可读主数据字典与账期列表，仍不能写主数据、仍读不到单据")
    void rbac_financePermissionsAfterV7() throws Exception {
        data.user("finance_v7", "Fin1!", "FINANCE", encoder);
        String token = authentication.login("finance_v7", "Fin1!").token();

        // 字典三件套：V7 之前每个都是 403（结算页的仓库下拉、报表页的 SKU/商品名）
        for (String path : new String[] {"/api/v1/warehouses", "/api/v1/products", "/api/v1/skus"}) {
            mvc.perform(get(path).header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("OK"));
        }
        // 坑 15 的读出口对 FINANCE 同样成立（它有 set:read，本来就该拿得到数据）
        mvc.perform(get("/api/v1/settlements/periods").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // 边界：mdm:read 不含任何写权限（V7 的代价只到"能看"为止）
        mvc.perform(post("/api/v1/products")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Request-Id", "req-iam-v7-write")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("code", "P-FIN", "name", "财务建的商品"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("IAM_FORBIDDEN"));

        // 登记不修：财务依然读不到单据（M3 工作台的那 4 个 403 属同一族遗留）
        mvc.perform(get("/api/v1/purchase-orders").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("IAM_FORBIDDEN"));
    }
}
