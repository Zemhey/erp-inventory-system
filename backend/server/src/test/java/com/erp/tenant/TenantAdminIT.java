package com.erp.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.iam.application.AuthenticationService;
import com.erp.iam.application.TenantAdminService;
import com.erp.iam.repository.TenantMembershipRepository;
import com.erp.iam.security.JwtTokenService;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.tenant.TenantContext;
import com.erp.shared.tenant.TenantId;
import com.erp.support.CaseId;
import com.erp.support.PostgresTestBase;
import com.erp.support.TestDataFactory;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * ★ B1 · 租户 UI / 平台租户管理（FR-TNT-07/08）的接口级验收。
 *
 * <p><b>覆盖四条硬要求</b>：
 * <ol>
 *   <li>登录/`/auth/me` 带当前租户与成员列表，刷新可恢复；</li>
 *   <li>切换租户**重新签发** JWT（tid 真的变了），角色/权限按目标租户装载；</li>
 *   <li>只有平台租户管理员（真的持有 {@code tenant:manage}）能管理租户元数据，
 *       普通租户 ADMIN 即使角色名是 ADMIN 也 403；</li>
 *   <li>平台权限**不**附带跨租户业务数据读取：业务表仍 404。</li>
 * </ol>
 *
 * <p><b>★ 为什么多租户成员在租户 1 是 VIEWER、在租户 2 是 PURCHASER</b>：
 * 若两处角色相同，"按目标租户装载角色"与"把所有租户角色并集"会产生相同结果，
 * 用例就退化成恒真。用不同角色后，登录响应的 roles 能直接区分这两种实现。
 *
 * <p><b>★ 为什么每个用例前后都复位 tenants.enabled</b>：
 * 停用租户是持久状态；某条用例失败在"已停用"中间态时，后续用例会莫名其妙地
 * 登录失败。复位把干扰限制在本用例内。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("clock")
@DisplayName("★ 租户 UI · 成员 / 切换 / 平台管理（B1）")
class TenantAdminIT extends PostgresTestBase {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AuthenticationService authentication;
    @Autowired
    private TenantAdminService tenantAdmin;
    @Autowired
    private TenantMembershipRepository memberships;
    @Autowired
    private JwtTokenService tokens;
    @Autowired
    private PasswordEncoder encoder;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private com.erp.support.TestClockConfig.ControllableClock clock;

    private TestDataFactory data;
    private TenantFixture tenant1;
    private TenantFixture tenant2;
    private long platformUserId;
    private long demoAdminUserId;
    private long multiUserId;
    private long singleUserId;

    @BeforeEach
    void setUp() {
        data = new TestDataFactory(jdbc);
        data.truncateAll();
        clock.set(LocalDateTime.of(2026, 3, 15, 10, 0, 0));

        // 租户启停是持久状态：先把两个租户复位为启用，避免上一条用例的中间态污染
        TenantContext.callAsSystem(() -> jdbc.update("UPDATE tenants SET enabled = TRUE"));

        // 平台租户（id=1）的 ADMIN 在 V17 里被种了 tenant:manage
        platformUserId = data.user("tnt_platform", "Platform1!", "ADMIN", encoder);
        // 普通租户（id=2）的 ADMIN：同样叫 ADMIN，但**没有** tenant:manage
        demoAdminUserId = data.inTenant(TenantId.DEMO).user(
                "tnt_demo_admin", "Demo1!", "ADMIN", encoder);
        // 多租户成员：租户 1 = VIEWER，租户 2 追加 PURCHASER（角色刻意不同）
        multiUserId = data.user("tnt_multi", "Multi1!", "VIEWER", encoder);
        addMembership(multiUserId, TenantId.DEMO);
        assignRole(multiUserId, TenantId.DEMO, "PURCHASER");
        // 单租户成员：只有主租户，前端不应显示切换器
        singleUserId = data.user("tnt_single", "Single1!", "VIEWER", encoder);

        tenant1 = TenantFixture.create(jdbc, data, TenantId.DEFAULT, "CLOSED");
        tenant2 = TenantFixture.create(jdbc, data, TenantId.DEMO, "OPEN");
    }

    // ─────────────────────────── 登录 / 刷新 ───────────────────────────

    @Test
    @CaseId("TC-API-145")
    @DisplayName("★ 登录响应带当前租户与成员列表（默认主租户），角色取自主租户")
    void loginCarriesTenantAndMemberships() throws Exception {
        MvcResult res = loginRequest("tnt_multi", "Multi1!", null);
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        String json = body(res);

        assertThat(longAt(json, "$.data.tenantId")).isEqualTo(TenantId.DEFAULT);
        assertThat(stringAt(json, "$.data.tenantCode")).isEqualTo("default");
        assertThat(stringAt(json, "$.data.tenantName")).isEqualTo("默认租户");
        assertThat(codeList(json)).containsExactly("default", "demo");

        // 角色取自主租户（VIEWER），不是"所有租户的角色并集"（否则会混进 PURCHASER）
        assertThat(roles(json)).containsExactly("VIEWER");
        assertThat(permissions(json)).doesNotContain("pur:create");

        // token 的 tid 与响应一致
        assertThat(tokens.verifyClaims(tokenOf(res)).orElseThrow().tenantId())
                .isEqualTo(TenantId.DEFAULT);
    }

    @Test
    @CaseId("TC-API-146")
    @DisplayName("★ 登录可选 tenantCode：切到 demo 后角色/权限/ tid 全部来自 demo（不混入主租户角色）")
    void loginWithTenantCodeUsesTargetTenantRoles() throws Exception {
        MvcResult res = loginRequest("tnt_multi", "Multi1!", "demo");
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        String json = body(res);

        assertThat(longAt(json, "$.data.tenantId")).isEqualTo(TenantId.DEMO);
        assertThat(stringAt(json, "$.data.tenantCode")).isEqualTo("demo");
        assertThat(codeList(json)).containsExactly("default", "demo");
        assertThat(roles(json)).containsExactly("PURCHASER");
        assertThat(permissions(json)).contains("pur:create");
        assertThat(tokens.verifyClaims(tokenOf(res)).orElseThrow().tenantId())
                .isEqualTo(TenantId.DEMO);
    }

    @Test
    @CaseId("TC-API-147")
    @DisplayName("★ 非成员/未知租户编码登录 → 403（同一错误码）；密码错仍 401，租户校验在密码之后")
    void loginRejectsNonMemberTenant() throws Exception {
        // 单租户用户选 demo：不是成员
        MvcResult nonMember = loginRequest("tnt_single", "Single1!", "demo");
        assertThat(nonMember.getResponse().getStatus()).isEqualTo(403);
        assertThat(stringAt(body(nonMember), "$.code")).isEqualTo("IAM_TENANT_NOT_MEMBER");

        // 未知编码返回**同一个**错误码，不泄露"系统里有哪些租户"
        MvcResult unknown = loginRequest("tnt_single", "Single1!", "tnt-nope");
        assertThat(unknown.getResponse().getStatus()).isEqualTo(403);
        assertThat(stringAt(body(unknown), "$.code")).isEqualTo("IAM_TENANT_NOT_MEMBER");

        // 密码错误 + 合法租户编码：租户校验在密码之后 → 仍是 401（不扩大枚举面）
        MvcResult wrongPassword = loginRequest("tnt_single", "Wrong1!", "demo");
        assertThat(wrongPassword.getResponse().getStatus()).isEqualTo(401);
        assertThat(stringAt(body(wrongPassword), "$.code")).isEqualTo("IAM_BAD_CREDENTIALS");

        // 不存在的用户名 + 租户编码：同样 401，不因租户报错而暴露用户名是否存在
        MvcResult noUser = loginRequest("tnt_nobody", "Nobody1!", "demo");
        assertThat(noUser.getResponse().getStatus()).isEqualTo(401);
        assertThat(stringAt(body(noUser), "$.code")).isEqualTo("IAM_BAD_CREDENTIALS");
    }

    @Test
    @CaseId("TC-API-148")
    @DisplayName("★ /auth/me 刷新后仍恢复当前租户与成员列表；不同 tid 的 token 给出不同租户")
    void meRestoresTenantContext() throws Exception {
        String homeToken = loginToken("tnt_multi", "Multi1!");
        String homeMe = body(mvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + homeToken))
                .andExpect(status().isOk()).andReturn());
        assertThat(longAt(homeMe, "$.data.tenantId")).isEqualTo(TenantId.DEFAULT);
        assertThat(stringAt(homeMe, "$.data.tenantCode")).isEqualTo("default");
        assertThat(codeList(homeMe)).containsExactly("default", "demo");
        assertThat(roles(homeMe)).containsExactly("VIEWER");

        String demoTenantToken = tokenOf(loginRequest("tnt_multi", "Multi1!", "demo"));
        String demoMe = body(mvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + demoTenantToken))
                .andExpect(status().isOk()).andReturn());
        assertThat(longAt(demoMe, "$.data.tenantId")).isEqualTo(TenantId.DEMO);
        assertThat(stringAt(demoMe, "$.data.tenantCode")).isEqualTo("demo");
        assertThat(roles(demoMe)).containsExactly("PURCHASER");
        assertThat(permissions(demoMe)).contains("pur:create");
        assertThat(codeList(demoMe)).containsExactly("default", "demo");
    }

    // ─────────────────────────── 切换租户 ───────────────────────────

    @Test
    @CaseId("TC-API-149")
    @DisplayName("★ 切换租户重新签发 JWT：新 token tid=2、旧 token tid=1；角色/权限换成目标租户")
    void switchTenantReissuesJwt() throws Exception {
        String homeToken = loginToken("tnt_multi", "Multi1!");
        MvcResult switched = mvc.perform(post("/api/v1/auth/switch-tenant")
                        .header("Authorization", "Bearer " + homeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantCode\":\"demo\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tenantId").value(2))
                .andExpect(jsonPath("$.data.tenantCode").value("demo"))
                .andExpect(jsonPath("$.data.roles[0]").value("PURCHASER"))
                .andReturn();

        String newToken = tokenOf(switched);
        assertThat(newToken).as("切换必须重新签发 token，不能沿用旧 token").isNotEqualTo(homeToken);
        assertThat(tokens.verifyClaims(newToken).orElseThrow().tenantId())
                .as("新 token 的 tid 必须变成目标租户").isEqualTo(TenantId.DEMO);
        assertThat(tokens.verifyClaims(homeToken).orElseThrow().tenantId())
                .as("JWT 无状态：旧 token 仍指向旧租户（因此前端必须整体覆盖）")
                .isEqualTo(TenantId.DEFAULT);
        assertThat(codeList(body(switched))).containsExactly("default", "demo");

        // 新 token 的会话是租户 2；旧 token 的会话仍是租户 1（同一账号，两个有效 token）
        String switchedMe = body(mvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + newToken))
                .andExpect(status().isOk()).andReturn());
        assertThat(longAt(switchedMe, "$.data.tenantId")).isEqualTo(TenantId.DEMO);
        String homeMe = body(mvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + homeToken))
                .andExpect(status().isOk()).andReturn());
        assertThat(longAt(homeMe, "$.data.tenantId")).isEqualTo(TenantId.DEFAULT);
    }

    @Test
    @CaseId("TC-API-150")
    @DisplayName("★ 非成员/未知编码切换 → 403；空编码 → 400；租户 2 管理员切租户 1 同样被拒")
    void switchTenantRejectsNonMember() throws Exception {
        String singleToken = loginToken("tnt_single", "Single1!");

        MvcResult nonMember = mvc.perform(post("/api/v1/auth/switch-tenant")
                        .header("Authorization", "Bearer " + singleToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantCode\":\"demo\"}"))
                .andReturn();
        assertThat(nonMember.getResponse().getStatus()).isEqualTo(403);
        assertThat(stringAt(body(nonMember), "$.code")).isEqualTo("IAM_TENANT_NOT_MEMBER");

        MvcResult unknown = mvc.perform(post("/api/v1/auth/switch-tenant")
                        .header("Authorization", "Bearer " + singleToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantCode\":\"tnt-nope\"}"))
                .andReturn();
        assertThat(unknown.getResponse().getStatus()).isEqualTo(403);
        assertThat(stringAt(body(unknown), "$.code")).isEqualTo("IAM_TENANT_NOT_MEMBER");

        MvcResult blank = mvc.perform(post("/api/v1/auth/switch-tenant")
                        .header("Authorization", "Bearer " + singleToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantCode\":\"\"}"))
                .andReturn();
        assertThat(blank.getResponse().getStatus()).isEqualTo(400);
        assertThat(stringAt(body(blank), "$.code")).isEqualTo("SYS_VALIDATION_ERROR");

        // 普通租户管理员 home=2，切到平台租户 1 同样不是成员
        String demoAdminToken = loginToken("tnt_demo_admin", "Demo1!");
        MvcResult upward = mvc.perform(post("/api/v1/auth/switch-tenant")
                        .header("Authorization", "Bearer " + demoAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantCode\":\"default\"}"))
                .andReturn();
        assertThat(upward.getResponse().getStatus()).isEqualTo(403);
        assertThat(stringAt(body(upward), "$.code")).isEqualTo("IAM_TENANT_NOT_MEMBER");
    }

    @Test
    @CaseId("TC-API-151")
    @DisplayName("★ 停用租户：登录/切换被拒、既有 token 下一请求 401；主租户登录不受影响，启用后恢复")
    void disabledTenantRejectsLoginSwitchAndExistingToken() throws Exception {
        String platformToken = loginToken("tnt_platform", "Platform1!");
        String demoToken = tokenOf(loginRequest("tnt_multi", "Multi1!", "demo"));

        mvc.perform(post("/api/v1/tenants/2/enabled")
                        .header("Authorization", "Bearer " + platformToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false));

        // 登录到停用租户被拒
        MvcResult loginDisabled = loginRequest("tnt_multi", "Multi1!", "demo");
        assertThat(loginDisabled.getResponse().getStatus()).isEqualTo(403);
        assertThat(stringAt(body(loginDisabled), "$.code")).isEqualTo("IAM_TENANT_DISABLED");

        // 从主租户切换到停用租户被拒（主租户 token 本身仍有效）
        String homeToken = loginToken("tnt_multi", "Multi1!");
        MvcResult switchDisabled = mvc.perform(post("/api/v1/auth/switch-tenant")
                        .header("Authorization", "Bearer " + homeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantCode\":\"demo\"}"))
                .andReturn();
        assertThat(switchDisabled.getResponse().getStatus()).isEqualTo(403);
        assertThat(stringAt(body(switchDisabled), "$.code")).isEqualTo("IAM_TENANT_DISABLED");

        // 停用前签发的 demo token：下一个请求就 401，不需要等 token 过期
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + demoToken))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/products").header("Authorization", "Bearer " + demoToken))
                .andExpect(status().isUnauthorized());

        // 主租户（default）登录不受影响
        MvcResult homeLogin = loginRequest("tnt_multi", "Multi1!", null);
        assertThat(homeLogin.getResponse().getStatus()).isEqualTo(200);

        // 重新启用后恢复
        mvc.perform(post("/api/v1/tenants/2/enabled")
                        .header("Authorization", "Bearer " + platformToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true));
        assertThat(loginRequest("tnt_multi", "Multi1!", "demo").getResponse().getStatus())
                .isEqualTo(200);
    }

    // ─────────────────────────── 平台租户管理 / 权限边界 ───────────────────────────

    @Test
    @CaseId("TC-API-152")
    @DisplayName("★ 普通租户 ADMIN 看 /tenants → 403；平台租户 ADMIN → 200（adminBypass=false 的关键回归）")
    void onlyPlatformAdminCanManageTenants() throws Exception {
        String demoAdminToken = loginToken("tnt_demo_admin", "Demo1!");
        String viewerToken = loginToken("tnt_single", "Single1!");

        // 普通租户的 ADMIN 角色名字也是 ADMIN；若 PermissionAspect 的 ADMIN 短路生效，
        // 这里会 200 —— 那正是本用例要钉住的回归。
        MvcResult forbiddenList = mvc.perform(get("/api/v1/tenants")
                        .header("Authorization", "Bearer " + demoAdminToken))
                .andReturn();
        assertThat(forbiddenList.getResponse().getStatus()).isEqualTo(403);
        assertThat(stringAt(body(forbiddenList), "$.code")).isEqualTo("IAM_FORBIDDEN");

        MvcResult forbiddenWrite = mvc.perform(post("/api/v1/tenants/2/enabled")
                        .header("Authorization", "Bearer " + demoAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andReturn();
        assertThat(forbiddenWrite.getResponse().getStatus()).isEqualTo(403);

        // 普通只读用户更不可能有平台权限
        mvc.perform(get("/api/v1/tenants").header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isForbidden());

        // 平台租户 ADMIN：可以列出元数据
        String platformToken = loginToken("tnt_platform", "Platform1!");
        mvc.perform(get("/api/v1/tenants").header("Authorization", "Bearer " + platformToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].id").value(1))
                .andExpect(jsonPath("$.data.items[0].code").value("default"))
                .andExpect(jsonPath("$.data.items[1].code").value("demo"))
                .andExpect(jsonPath("$.data.items[1].enabled").value(true));
    }

    @Test
    @CaseId("TC-API-153")
    @DisplayName("★ 平台管理员只读租户元数据：跨租户业务数据仍 404，同租户正对照 200，且不能切到非成员租户")
    void platformAdminCannotReadCrossTenantBusinessData() throws Exception {
        String platformToken = loginToken("tnt_platform", "Platform1!");

        // 租户 2 的 id 是"真实存在但属于另一个租户"的探针（TenantFixture 造）
        for (String path : List.of(
                "/api/v1/products/" + tenant2.product,
                "/api/v1/purchase-orders/" + tenant2.purchaseOrder,
                "/api/v1/sales-orders/" + tenant2.salesOrder,
                "/api/v1/audits/" + tenant2.auditId)) {
            MvcResult cross = mvc.perform(get(path)
                            .header("Authorization", "Bearer " + platformToken))
                    .andReturn();
            assertThat(cross.getResponse().getStatus())
                    .as("平台管理员访问租户 2 的 %s 必须仍是 404（tenant:manage 不等于业务超管）", path)
                    .isEqualTo(404);
            assertThat(body(cross)).doesNotContain("IAM_FORBIDDEN");
        }

        // 同租户正对照：证明 404 是隔离而不是"平台管理员什么都读不了"
        mvc.perform(get("/api/v1/products/" + tenant1.product)
                        .header("Authorization", "Bearer " + platformToken))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/purchase-orders/" + tenant1.purchaseOrder)
                        .header("Authorization", "Bearer " + platformToken))
                .andExpect(status().isOk());

        // 平台管理员不是租户 2 的成员 → 不能用切换绕过隔离
        MvcResult switchDemo = mvc.perform(post("/api/v1/auth/switch-tenant")
                        .header("Authorization", "Bearer " + platformToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantCode\":\"demo\"}"))
                .andReturn();
        assertThat(switchDemo.getResponse().getStatus()).isEqualTo(403);
        assertThat(stringAt(body(switchDemo), "$.code")).isEqualTo("IAM_TENANT_NOT_MEMBER");
    }

    @Test
    @CaseId("TC-API-154")
    @DisplayName("★ 租户启停：列表/启停成功；停平台租户或当前所在租户 409；未知 id 404；启用可重复")
    void tenantEnableDisableGuards() throws Exception {
        String platformToken = loginToken("tnt_platform", "Platform1!");

        // 停用租户 2（不是平台、不是当前租户）→ 成功；启用可重复（幂等）
        mvc.perform(post("/api/v1/tenants/2/enabled")
                        .header("Authorization", "Bearer " + platformToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false));
        mvc.perform(post("/api/v1/tenants/2/enabled")
                        .header("Authorization", "Bearer " + platformToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true));
        mvc.perform(post("/api/v1/tenants/2/enabled")
                        .header("Authorization", "Bearer " + platformToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk());

        // 停用平台租户（id=1）→ 409
        MvcResult platformGuard = mvc.perform(post("/api/v1/tenants/1/enabled")
                        .header("Authorization", "Bearer " + platformToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andReturn();
        assertThat(platformGuard.getResponse().getStatus()).isEqualTo(409);
        assertThat(stringAt(body(platformGuard), "$.code")).isEqualTo("TENANT_STATE_ILLEGAL");

        // 未知 id → 404
        MvcResult missing = mvc.perform(post("/api/v1/tenants/9999/enabled")
                        .header("Authorization", "Bearer " + platformToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andReturn();
        assertThat(missing.getResponse().getStatus()).isEqualTo(404);
        assertThat(stringAt(body(missing), "$.code")).isEqualTo("TENANT_NOT_FOUND");

        // 停用"当前所在租户"的服务层保护（HTTP 路径下平台管理员永远以 1 行动，
        // 这里显式绑定租户 2 调服务，才能覆盖这条分支）
        assertThatThrownBy(() -> TenantContext.runIn(TenantId.DEMO,
                () -> tenantAdmin.setEnabled(TenantId.DEMO, false)))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.TENANT_STATE_ILLEGAL));
    }

    // ─────────────────────────── 成员关系 ───────────────────────────

    @Test
    @CaseId("TC-TNT-068")
    @DisplayName("★ 成员关系唯一由数据库约束保证：重复 (user, tenant) 插入失败，行数不变")
    void membershipIsUnique() {
        assertThat(countMembership(multiUserId, TenantId.DEMO)).isEqualTo(1);

        assertThatThrownBy(() -> TenantContext.runIn(TenantId.DEMO, () -> jdbc.update(
                "INSERT INTO app_user_tenants (user_id, tenant_id) VALUES (?, ?)",
                multiUserId, TenantId.DEMO)))
                .as("重复的额外成员关系必须被 PK(user_id, tenant_id) 拒绝")
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        assertThatThrownBy(() -> TenantContext.runIn(TenantId.DEFAULT, () -> jdbc.update(
                "INSERT INTO app_user_tenants (user_id, tenant_id) VALUES (?, ?)",
                multiUserId, TenantId.DEFAULT)))
                .as("主租户成员关系同样只有一行")
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        assertThat(countMembership(multiUserId, TenantId.DEMO)).isEqualTo(1);
        assertThat(countMembership(multiUserId, TenantId.DEFAULT)).isEqualTo(1);
    }

    @Test
    @CaseId("TC-TNT-069")
    @DisplayName("★ 用户插入即获得主租户成员关系（含 HTTP 建号路径）；多成员用户成员列表 2 条")
    void homeMembershipIsCreatedAutomatically() throws Exception {
        assertThat(memberships.isMember(platformUserId, TenantId.DEFAULT)).isTrue();
        assertThat(memberships.isMember(demoAdminUserId, TenantId.DEMO)).isTrue();
        assertThat(memberships.isMember(singleUserId, TenantId.DEFAULT)).isTrue();
        assertThat(memberships.membershipsOf(multiUserId)).hasSize(2);
        assertThat(memberships.membershipsOf(singleUserId)).hasSize(1);

        // 走真实建号接口：触发器必须覆盖 UserAdminService.createUser 这条路径
        String platformToken = loginToken("tnt_platform", "Platform1!");
        MvcResult created = mvc.perform(post("/api/v1/iam/users")
                        .header("Authorization", "Bearer " + platformToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"tnt_created","password":"Created1!",
                                 "displayName":"新账号","roles":["VIEWER"]}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        long createdId = longAt(body(created), "$.data.id");

        assertThat(memberships.isMember(createdId, TenantId.DEFAULT))
                .as("通过 /iam/users 创建的新账号必须自动成为主租户成员")
                .isTrue();
        assertThat(memberships.membershipsOf(createdId)).hasSize(1);
    }

    @Test
    @CaseId("TC-TNT-070")
    @DisplayName("★ 成员关系表也受租户过滤：默认租户只见 3 行、demo 只见 2 行、全局 5 行")
    void membershipTableIsTenantScoped() {
        Long defaultVisible = TenantContext.callIn(TenantId.DEFAULT, () -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM app_user_tenants", Long.class));
        Long demoVisible = TenantContext.callIn(TenantId.DEMO, () -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM app_user_tenants", Long.class));
        Long globalVisible = TenantContext.callAsSystem(() -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM app_user_tenants", Long.class));

        // 用户：platform(1) + demoAdmin(2) + multi(1+2) + single(1)
        assertThat(defaultVisible).isEqualTo(3L);
        assertThat(demoVisible).isEqualTo(2L);
        assertThat(globalVisible).as("跨租户合计用于守恒对照").isEqualTo(5L);

        // 多租户用户的每一行只在其所属租户可见：同一条 SQL 在两个租户各见 1 行、
        // 全局才是 2 行。若成员表漏进 TenantSql 的改写范围，这里会得到 2/2/2。
        Integer multiInDefault = TenantContext.callIn(TenantId.DEFAULT, () ->
                jdbc.queryForObject("SELECT COUNT(*) FROM app_user_tenants WHERE user_id = ?",
                        Integer.class, multiUserId));
        Integer multiInDemo = TenantContext.callIn(TenantId.DEMO, () ->
                jdbc.queryForObject("SELECT COUNT(*) FROM app_user_tenants WHERE user_id = ?",
                        Integer.class, multiUserId));
        Integer multiGlobal = TenantContext.callAsSystem(() ->
                jdbc.queryForObject("SELECT COUNT(*) FROM app_user_tenants WHERE user_id = ?",
                        Integer.class, multiUserId));
        assertThat(multiInDefault).isEqualTo(1);
        assertThat(multiInDemo).isEqualTo(1);
        assertThat(multiGlobal).isEqualTo(2);
    }

    @Test
    @CaseId("TC-TNT-071")
    @DisplayName("★ tenant:manage 只种在平台租户 ADMIN；普通租户 ADMIN 的权限清单里没有它")
    void platformPermissionIsNotLeakedToOrdinaryTenant() {
        assertThat(TenantContext.callAsSystem(() -> jdbc.queryForObject("""
                SELECT COUNT(*) FROM app_role_permissions rp
                  JOIN app_roles r ON r.id = rp.role_id
                 WHERE r.tenant_id = 1 AND r.code = 'ADMIN' AND rp.permission = 'tenant:manage'
                """, Long.class)))
                .as("平台租户 ADMIN 必须恰好有一条 tenant:manage")
                .isEqualTo(1L);
        assertThat(TenantContext.callAsSystem(() -> jdbc.queryForObject("""
                SELECT COUNT(*) FROM app_role_permissions rp
                  JOIN app_roles r ON r.id = rp.role_id
                 WHERE r.tenant_id <> 1 AND r.code = 'ADMIN' AND rp.permission = 'tenant:manage'
                """, Long.class)))
                .as("★ 平台权限不得下放到普通租户（V17 自检的同一条不变量）")
                .isZero();

        assertThat(authentication.login("tnt_platform", "Platform1!").permissions())
                .contains("tenant:manage");
        assertThat(authentication.login("tnt_demo_admin", "Demo1!").permissions())
                .doesNotContain("tenant:manage");
    }

    // ─────────────────────────── 辅助 ───────────────────────────

    /** 在指定租户上下文里追加一条成员关系（模拟运维/种子给用户加租户）。 */
    private void addMembership(long userId, long tenantId) {
        TenantContext.runIn(tenantId, () -> jdbc.update(
                "INSERT INTO app_user_tenants (user_id, tenant_id) VALUES (?, ?)"
                        + " ON CONFLICT DO NOTHING",
                userId, tenantId));
    }

    /** 在指定租户里给用户分配角色（显式 tenant_id，避免依赖 ON CONFLICT 目标改写）。 */
    private void assignRole(long userId, long tenantId, String roleCode) {
        TenantContext.runIn(tenantId, () -> jdbc.update("""
                INSERT INTO app_user_roles (user_id, role_id, tenant_id)
                SELECT ?, r.id, ? FROM app_roles r
                 WHERE r.tenant_id = ? AND r.code = ?
                ON CONFLICT DO NOTHING
                """, userId, tenantId, tenantId, roleCode));
    }

    private int countMembership(long userId, long tenantId) {
        Integer n = TenantContext.callAsSystem(() -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM app_user_tenants WHERE user_id = ? AND tenant_id = ?",
                Integer.class, userId, tenantId));
        return n == null ? 0 : n;
    }

    private MvcResult loginRequest(String username, String password, String tenantCode)
            throws Exception {
        String payload = tenantCode == null
                ? "{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password)
                : "{\"username\":\"%s\",\"password\":\"%s\",\"tenantCode\":\"%s\"}"
                        .formatted(username, password, tenantCode);
        return mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andReturn();
    }

    private String loginToken(String username, String password) throws Exception {
        MvcResult res = loginRequest(username, password, null);
        assertThat(res.getResponse().getStatus())
                .as("登录失败，无法继续：%s", body(res))
                .isEqualTo(200);
        return tokenOf(res);
    }

    private static String body(MvcResult res) throws Exception {
        return res.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static String tokenOf(MvcResult res) throws Exception {
        return JsonPath.read(body(res), "$.data.token");
    }

    private static long longAt(String json, String path) {
        Number n = JsonPath.read(json, path);
        return n.longValue();
    }

    private static String stringAt(String json, String path) {
        return JsonPath.read(json, path);
    }

    private static List<String> codeList(String json) {
        return JsonPath.read(json, "$.data.tenants[*].code");
    }

    private static List<String> roles(String json) {
        return JsonPath.read(json, "$.data.roles");
    }

    private static List<String> permissions(String json) {
        return JsonPath.read(json, "$.data.permissions");
    }
}
