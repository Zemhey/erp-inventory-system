package com.erp.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.erp.iam.application.AuthenticationService;
import com.erp.iam.repository.DataScopeRepository;
import com.erp.iam.security.JwtTokenService;
import com.erp.shared.tenant.TenantId;
import com.erp.support.CaseId;
import com.erp.support.PostgresTestBase;
import com.erp.support.TestDataFactory;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * JWT 的**租户声明贯穿**（FR-TNT-04，S3）与租户 ∧ 数据权限的复合过滤（S5）。
 *
 * <p><b>为什么"token 里有没有 tid"必须单独测</b>：过滤器是从数据库里的账号行
 * 取租户的。若只做这一点，旧 token（没有 tid）照样能通过 —— 而需求明确要求
 * "token 无 tid → 401、tid 与用户归属不一致 → 401"。两条都必须有独立用例，
 * 否则这两个分支永远不会被执行到（覆盖率也抓不到"没写的分支"）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("clock")
@DisplayName("租户 · JWT 声明 / 复合数据权限")
class TenantAuthIT extends PostgresTestBase {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AuthenticationService authentication;
    @Autowired
    private JwtTokenService tokens;
    @Autowired
    private PasswordEncoder encoder;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DataScopeRepository dataScopes;
    @Autowired
    private com.erp.support.TestClockConfig.ControllableClock clock;

    @Value("${erp.iam.jwt-secret}")
    private String secret;

    private TestDataFactory data;
    private TenantFixture tenant1;
    private TenantFixture tenant2;
    private String token2;

    @BeforeEach
    void setUp() {
        data = new TestDataFactory(jdbc);
        data.truncateAll();
        clock.set(LocalDateTime.of(2026, 3, 15, 10, 0, 0));

        data.user("tnt_admin_1", "Admin1!", "ADMIN", encoder);
        data.inTenant(TenantId.DEMO).user("tnt_admin_2", "Admin2!", "ADMIN", encoder);
        tenant1 = TenantFixture.create(jdbc, data, TenantId.DEFAULT, "CLOSED");
        tenant2 = TenantFixture.create(jdbc, data, TenantId.DEMO, "OPEN");
        token2 = authentication.login("tnt_admin_2", "Admin2!").token();
    }

    @Test
    @CaseId("TC-API-125")
    @DisplayName("★ 登录结果与 token 都携带租户：租户 2 用户登录后 tenantId=2，且只能读自己的数据")
    void loginCarriesTenant() throws Exception {
        AuthenticationService.LoginResult login =
                authentication.login("tnt_admin_2", "Admin2!");
        assertThat(login.tenantId()).as("登录响应必须带租户 id").isEqualTo(TenantId.DEMO);
        assertThat(tokens.verifyClaims(login.token()).orElseThrow().tenantId())
                .as("token 的 tid 声明必须等于账号归属")
                .isEqualTo(TenantId.DEMO);
        mvc.perform(get("/api/v1/products/" + tenant2.product)
                        .header("Authorization", "Bearer " + login.token()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isOk());
    }

    @Test
    @CaseId("TC-API-126")
    @DisplayName("★ 没有 tid 声明的 token（旧版本签发）→ 401，不能靠库里的租户兜底")
    void tokenWithoutTidIsRejected() throws Exception {
        String old = forgedToken("tnt_admin_2", null);
        mvc.perform(get("/api/v1/products").header("Authorization", "Bearer " + old))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isUnauthorized());
    }

    @Test
    @CaseId("TC-API-127")
    @DisplayName("★ tid 与账号真实归属不一致 → 401（不能拿别的租户的声明蒙混）")
    void mismatchedTidIsRejected() throws Exception {
        String forged = forgedToken("tnt_admin_2", TenantId.DEFAULT);
        mvc.perform(get("/api/v1/products").header("Authorization", "Bearer " + forged))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isUnauthorized());

        // 对照：同一账号、正确的 tid 必须能进
        mvc.perform(get("/api/v1/products")
                        .header("Authorization", "Bearer " + forgedToken("tnt_admin_2",
                                TenantId.DEMO)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isOk());
    }

    @Test
    @CaseId("TC-API-128")
    @DisplayName("★ 商品/SKU 是最基础的读路径：跨租户 id → 404，同租户 200 有内容")
    void masterDataIsTenantScoped() throws Exception {
        MvcResult crossProduct = mvc.perform(get("/api/v1/products/" + tenant1.product)
                        .header("Authorization", "Bearer " + token2))
                .andReturn();
        assertThat(crossProduct.getResponse().getStatus()).isEqualTo(404);
        assertThat(crossProduct.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .doesNotContain("IAM_FORBIDDEN");

        MvcResult crossSku = mvc.perform(get("/api/v1/skus/" + tenant1.sku)
                        .header("Authorization", "Bearer " + token2))
                .andReturn();
        assertThat(crossSku.getResponse().getStatus()).isEqualTo(404);

        // 同租户正对照
        mvc.perform(get("/api/v1/products/" + tenant2.product)
                        .header("Authorization", "Bearer " + token2))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isOk());
        mvc.perform(get("/api/v1/skus/" + tenant2.sku)
                        .header("Authorization", "Bearer " + token2))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isOk());
    }

    @Test
    @CaseId("TC-API-133")
    @DisplayName("★ 租户 ∧ 数据权限的复合过滤：跨租户 404、同租户跨仓 404、本租户本仓 200")
    void tenantAndWarehouseScopeCompose() throws Exception {
        // 租户 1 的第二个仓（数据权限之外）
        long otherWarehouse = data.warehouse("TNT-WH-2");
        long otherPo = insertDraftPo(otherWarehouse);

        long viewerId = data.user("tnt_scoped_viewer", "View1!", "VIEWER", encoder);
        dataScopes.addUserScope(viewerId, "WAREHOUSE", tenant1.warehouse);
        String viewerToken = authentication.login("tnt_scoped_viewer", "View1!").token();

        // 本租户 + 本仓：200
        mvc.perform(get("/api/v1/purchase-orders/" + tenant1.purchaseOrder)
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isOk());
        // 本租户 + 别仓：404（数据权限）
        mvc.perform(get("/api/v1/purchase-orders/" + otherPo)
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isNotFound());
        // 别租户 + 别仓：同样 404，不泄露存在性
        mvc.perform(get("/api/v1/purchase-orders/" + tenant2.purchaseOrder)
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isNotFound());
    }

    private long insertDraftPo(long warehouseId) {
        jdbc.update("""
                INSERT INTO purchase_orders
                    (order_no, status, supplier_id, warehouse_id, biz_date,
                     total_amount, total_qty, period, created_by, tenant_id)
                VALUES ('PO-TNT-WH2', 'DRAFT', ?, ?, ?, 1000, 1, '2026-03', NULL, ?)
                """, tenant1.supplier, warehouseId, LocalDate.of(2026, 3, 10), TenantId.DEFAULT);
        return jdbc.queryForObject(
                "SELECT id FROM purchase_orders WHERE order_no = 'PO-TNT-WH2'", Long.class);
    }

    /** 用真实密钥签一个可控 tid 的 token（无 tid 传 null）。 */
    private String forgedToken(String username, Long tid) {
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        var builder = Jwts.builder()
                .subject(username)
                .claim("uid", 999)
                .claim("roles", List.of("ADMIN"))
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(3600)))
                .signWith(key, Jwts.SIG.HS256);
        if (tid != null) {
            builder.claim("tid", tid);
        }
        return builder.compact();
    }
}
