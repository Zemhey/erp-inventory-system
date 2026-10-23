package com.erp.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.iam.application.AuthenticationService;
import com.erp.iam.application.DataScopeAdminService;
import com.erp.iam.application.UserAdminService;
import com.erp.iam.repository.AppUserRepository;
import com.erp.iam.repository.DataScopeRepository;
import com.erp.iam.repository.RoleRepository;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.scope.DataScope;
import com.erp.shared.scope.DataScopeProvider;
import com.erp.support.CaseId;
import com.erp.support.PostgresTestBase;
import com.erp.support.TestDataFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
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
 * 权限深化集成测试（FR-IAM-04/05/06）。
 *
 * <p><b>覆盖四组硬验收</b>：
 * <ol>
 *   <li>登录策略可配置且真的拦住：复杂度不合规拒 / 密码过期强制改 / 连续失败锁定；</li>
 *   <li>用户与角色管理：建/改角色、覆盖式授权、自我保护；</li>
 *   <li><b>授权即时生效</b>：改角色权限后，同一会话的下一个请求即可见（不用重新登录）；</li>
 *   <li>数据权限解析优先级：ALL > WAREHOUSE > SELF > 未配置即全量。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("clock")
@DisplayName("IAM 深化 · 登录策略 / 用户角色 / 授权即时生效 / 数据权限")
class IamDeepeningIT extends PostgresTestBase {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AuthenticationService authentication;
    @Autowired
    private UserAdminService admin;
    @Autowired
    private DataScopeAdminService scopeAdmin;
    @Autowired
    private AppUserRepository users;
    @Autowired
    private RoleRepository roles;
    @Autowired
    private DataScopeRepository dataScopes;
    @Autowired
    private DataScopeProvider dataScopeProvider;
    @Autowired
    private PasswordEncoder encoder;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private com.erp.support.TestClockConfig.ControllableClock clock;

    @BeforeEach
    void setUp() {
        new TestDataFactory(jdbc).truncateAll();
        clock.set(java.time.LocalDateTime.of(2026, 3, 15, 10, 0, 0));
    }

    // ─────────────────────────── FR-IAM-06 登录策略 ───────────────────────────

    @Test
    @CaseId("TC-IAM-027")
    @DisplayName("★ 建账号时复杂度不合规一律拒绝（IAM_PASSWORD_WEAK），与改密用同一套策略")
    void createUser_rejectsWeakPassword() {
        assertThatThrownBy(() -> admin.createUser("weak1", "abcdefgh", "弱口令", true,
                List.of("VIEWER")))
                .as("★ 8 位纯小写：长度够但类别不足，必须拒")
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.IAM_PASSWORD_WEAK)
                .hasMessageContaining("类字符");

        assertThatThrownBy(() -> admin.createUser("weak2", "Admin@123", "弱口令", true,
                List.of("VIEWER")))
                .as("★ 常见弱口令（大小写不敏感）必须拒")
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.IAM_PASSWORD_WEAK)
                .hasMessageContaining("弱口令");

        // 合规密码可以通过
        long id = admin.createUser("strong1", "Str0ng!pass", "合规", true, List.of("VIEWER"));
        assertThat(users.findById(id).orElseThrow().enabled()).isTrue();
    }

    @Test
    @CaseId("TC-IAM-028")
    @DisplayName("★ 新建账号一律置强制改密：管理员知道初始密码，用户必须先改掉")
    void newUser_mustChangePassword() {
        long id = admin.createUser("newbie", "Init1!pass", "新人", true, List.of("VIEWER"));
        var user = users.findById(id).orElseThrow();
        assertThat(user.mustChangePassword())
                .as("★ 初始密码只用于交接，必须强制改密")
                .isTrue();
        assertThat(user.passwordExpiresAt())
                .as("按策略应写入过期时刻（maxAgeDays=90）")
                .isNotNull();

        // 登录响应必须明确告诉前端"先去改密"
        var result = authentication.login("newbie", "Init1!pass");
        assertThat(result.mustChangePassword()).isTrue();
        assertThat(result.passwordRules()).hasSize(3);
    }

    @Test
    @CaseId("TC-IAM-029")
    @DisplayName("★ 密码过期后：业务接口被拦成 IAM_PASSWORD_EXPIRED，但改密接口仍然可用")
    void expiredPassword_blocksBusinessEndpointsButAllowsChangePassword() throws Exception {
        long id = admin.createUser("expired", "Exp1!ired", "过期用户", true, List.of("VIEWER"));
        String token = authentication.login("expired", "Exp1!ired").token();

        // 未改密前：任何业务接口都应被拦
        mvc.perform(get("/api/v1/products").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("IAM_PASSWORD_EXPIRED"));
        mvc.perform(get("/api/v1/inventory").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("IAM_PASSWORD_EXPIRED"));

        // ★ 但 /auth/me 与 /auth/change-password 必须放行 —— 否则用户死锁
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mustChangePassword").value(true));

        mvc.perform(post("/api/v1/auth/change-password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "oldPassword", "Exp1!ired", "newPassword", "N3w!passw0rd"))))
                .andExpect(status().isOk());

        // 改密后同一 token 立刻恢复业务访问（标记已清零）
        mvc.perform(get("/api/v1/products").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        assertThat(users.findById(id).orElseThrow().mustChangePassword()).isFalse();
    }

    @Test
    @CaseId("TC-IAM-030")
    @DisplayName("★ 改密必须验旧密码；新旧相同被拒；新密码不合规被拒")
    void changePassword_rules() {
        long id = admin.createUser("chpwd", "Old1!pass", "改密用户", true, List.of("VIEWER"));
        // 清掉强制改密标记，单独测这三条规则
        jdbc.update("UPDATE app_users SET must_change_password = FALSE WHERE id = ?", id);

        assertThatThrownBy(() -> authentication.changePassword(id, "Wrong1!pass", "N3w!passw0rd"))
                .as("旧密码不对必须拒")
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.IAM_BAD_CREDENTIALS);

        assertThatThrownBy(() -> authentication.changePassword(id, "Old1!pass", "Old1!pass"))
                .as("新旧相同必须拒")
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.IAM_PASSWORD_WEAK)
                .hasMessageContaining("旧密码");

        assertThatThrownBy(() -> authentication.changePassword(id, "Old1!pass", "weakweak"))
                .as("新密码不合规必须拒")
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.IAM_PASSWORD_WEAK);

        // 合规改密成功
        authentication.changePassword(id, "Old1!pass", "N3w!passw0rd");
        assertThat(authentication.login("chpwd", "N3w!passw0rd").token()).isNotBlank();
        assertThatThrownBy(() -> authentication.login("chpwd", "Old1!pass"))
                .as("旧密码必须失效").isInstanceOf(BusinessException.class);
    }

    @Test
    @CaseId("TC-IAM-031")
    @DisplayName("★ 连续失败锁定：阈值内提示剩余次数，到阈值锁 IAM_ACCOUNT_LOCKED，到期自动解锁")
    void failedLoginLock_isEnforcedAndAutoExpires() {
        admin.createUser("locky", "Lock1!pass", "锁定用户", true, List.of("VIEWER"));
        jdbc.update("UPDATE app_users SET must_change_password = FALSE WHERE id = ("
                + "SELECT id FROM app_users WHERE username = 'locky')");
        int max = authentication.maxFailedAttempts();

        for (int i = 1; i < max; i++) {
            assertThatThrownBy(() -> authentication.login("locky", "wrong"))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ErrorCode.IAM_BAD_CREDENTIALS)
                    .hasMessageContaining("剩余尝试");
        }
        assertThatThrownBy(() -> authentication.login("locky", "wrong"))
                .as("★ 第 %d 次必须触发锁定", max)
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.IAM_ACCOUNT_LOCKED);

        // 锁定期间即使密码正确也拒
        assertThatThrownBy(() -> authentication.login("locky", "Lock1!pass"))
                .hasFieldOrPropertyWithValue("code", ErrorCode.IAM_ACCOUNT_LOCKED);

        clock.advance(Duration.ofMinutes(authentication.lockMinutes() + 1));
        assertThat(authentication.login("locky", "Lock1!pass").token())
                .as("★ 到期自动解锁（锁在数据库列里，不需要定时任务）")
                .isNotBlank();
    }

    @Test
    @CaseId("TC-IAM-032")
    @DisplayName("★ 管理员重置密码后必须强制改密；重置也走同一套复杂度策略")
    void adminResetPassword_forcesChangeAndChecksPolicy() {
        long id = admin.createUser("resetme", "Or1g!inal", "被重置", true, List.of("VIEWER"));
        assertThatThrownBy(() -> admin.resetPassword(id, "weak"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.IAM_PASSWORD_WEAK);

        admin.resetPassword(id, "Res3t!pass");
        assertThat(users.findById(id).orElseThrow().mustChangePassword())
                .as("★ 重置后必须强制改密 —— 否则管理员永远知道对方的密码")
                .isTrue();
        assertThat(authentication.login("resetme", "Res3t!pass").mustChangePassword()).isTrue();
    }

    // ─────────────────────────── FR-IAM-05 授权即时生效 ───────────────────────────

    @Test
    @CaseId("TC-IAM-033")
    @DisplayName("★ 授权即时生效：改角色权限后，同一会话的下一个请求立刻按新权限判定（无需重新登录）")
    void permissionChange_takesEffectOnNextRequestWithoutRelogin() throws Exception {
        long userId = admin.createUser("live", "L1ve!pass", "即时生效", true, List.of("VIEWER"));
        jdbc.update("UPDATE app_users SET must_change_password = FALSE WHERE id = ?", userId);
        String token = authentication.login("live", "L1ve!pass").token();

        // VIEWER 没有 mdm:write → 建商品被拒
        mvc.perform(post("/api/v1/products")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "code", "P-LIVE-1", "name", "授权前"))))
                .andExpect(status().isForbidden());

        // ★ 在同一会话（同一个 token）下，给该角色加上 mdm:write
        long viewerRoleId = roles.findByCode("VIEWER").orElseThrow().id();
        List<String> perms = new java.util.ArrayList<>(roles.permissionsOf(viewerRoleId));
        perms.add("mdm:write");
        admin.replacePermissions(viewerRoleId, perms);

        // ★ 不重新登录，同一个 token 的下一个请求就必须放行
        mvc.perform(post("/api/v1/products")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "code", "P-LIVE-2", "name", "授权后"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("OK"));

        // 再撤掉 → 立刻恢复 403
        admin.replacePermissions(viewerRoleId, List.of("mdm:read", "inv:read",
                "pur:read", "sal:read", "aps:read", "iam:read"));
        mvc.perform(post("/api/v1/products")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "code", "P-LIVE-3", "name", "撤权后"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @CaseId("TC-IAM-034")
    @DisplayName("★ 数据权限约束变更同样即时生效：改 scope 后同一个 token 的下一次查询立刻变窄")
    void dataScopeChange_takesEffectImmediately() throws Exception {
        long whA = new TestDataFactory(jdbc).warehouse("WH-LIVE-A");
        long whB = new TestDataFactory(jdbc).warehouse("WH-LIVE-B");
        long productId = new TestDataFactory(jdbc).product("P-LIVE", "即时生效商品");
        long sku = new TestDataFactory(jdbc).sku(productId, "红色", "S");
        new TestDataFactory(jdbc).stock(sku, whA, "LA", null, 10);
        new TestDataFactory(jdbc).stock(sku, whB, "LB", null, 20);

        long userId = admin.createUser("scoped", "Sc0pe!pass", "数据权限", true,
                List.of("ADMIN"));
        jdbc.update("UPDATE app_users SET must_change_password = FALSE WHERE id = ?", userId);
        String token = authentication.login("scoped", "Sc0pe!pass").token();

        // 未配数据权限 → 全量（两仓都看得到）
        String all = mvc.perform(get("/api/v1/inventory")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(all).contains("\"warehouseId\":" + whA).contains("\"warehouseId\":" + whB);

        // ★ 改成只 A 仓 —— 同一个 token，下一个请求立刻只有 A 仓
        scopeAdmin.replaceUserScopes(userId, List.of("WAREHOUSE:" + whA));
        String onlyA = mvc.perform(get("/api/v1/inventory")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(onlyA).contains("\"warehouseId\":" + whA)
                .as("★ 改 scope 后必须立刻生效，不需要重新登录")
                .doesNotContain("\"warehouseId\":" + whB);
    }

    @Test
    @CaseId("TC-IAM-035")
    @DisplayName("★ 角色管理自我保护：不能清空自己的角色、不能停用自己（否则把自己锁在系统外）")
    void adminCannotLockThemselvesOut() {
        long meId = admin.createUser("selfadmin", "S3lf!admin", "自保管理员", true,
                List.of("ADMIN"));
        assertThatThrownBy(() -> admin.updateRoles(meId, List.of(), meId))
                .as("★ 不能清空自己的全部角色")
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("锁在系统外");
        assertThatThrownBy(() -> admin.setEnabled(meId, false, meId))
                .as("★ 不能停用自己")
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("停用自己");

        // 改别人的角色是允许的
        long otherId = admin.createUser("other", "Oth3r!pass", "别人", true, List.of("VIEWER"));
        admin.updateRoles(otherId, List.of("PURCHASER", "VIEWER"), meId);
        assertThat(users.findById(otherId).orElseThrow().roles())
                .containsExactlyInAnyOrder("PURCHASER", "VIEWER");
    }

    @Test
    @CaseId("TC-IAM-036")
    @DisplayName("★ 角色权限是覆盖式：取消勾选必须真的生效（不是增量追加）")
    void replacePermissions_isOverwriteNotAppend() {
        long roleId = admin.createRole("TEMP_ROLE", "临时角色", "测试");
        admin.replacePermissions(roleId, List.of("mdm:read", "inv:read"));
        assertThat(admin.permissionsOfRole(roleId))
                .containsExactlyInAnyOrder("mdm:read", "inv:read");

        // 缩减到一个 —— 另一个必须消失
        admin.replacePermissions(roleId, List.of("mdm:read"));
        assertThat(admin.permissionsOfRole(roleId))
                .as("★ 覆盖式：取消的权限必须真的消失")
                .containsExactly("mdm:read");

        // 全清
        admin.replacePermissions(roleId, List.of());
        assertThat(admin.permissionsOfRole(roleId)).isEmpty();
    }

    @Test
    @CaseId("TC-IAM-037")
    @DisplayName("用户接口：列表可搜关键字；响应绝不包含 passwordHash")
    void userApi_neverLeaksPasswordHash() throws Exception {
        admin.createUser("leaky", "Le4ky!pass", "敏感字段测试", true, List.of("VIEWER"));
        jdbc.update("UPDATE app_users SET must_change_password = FALSE WHERE username = 'leaky'");
        // VIEWER 的种子里有 iam:read，所以要让"读不到用户清单"这个断言成立，
        // 必须用一个**没有** iam:read 的角色。这也更接近真实场景：
        // 业务角色（采购/销售）不该看到账号清单。
        long noIamRoleId = admin.createRole("NO_IAM_ROLE", "无权限管理", "测试用");
        admin.replacePermissions(noIamRoleId, List.of("mdm:read", "inv:read"));
        long noIamUserId = admin.createUser("noiam", "N0!iampass", "无权限管理账号", true,
                List.of("NO_IAM_ROLE"));
        jdbc.update("UPDATE app_users SET must_change_password = FALSE WHERE id = ?", noIamUserId);
        String token = authentication.login("noiam", "N0!iampass").token();
        // 该账号没有 iam:read（VIEWER 的种子里有 iam:read，为避免依赖它，直接用 ADMIN）
        long adminId = admin.createUser("apiadmin", "Ap1!admin", "接口管理员", true,
                List.of("ADMIN"));
        String adminToken = authentication.login("apiadmin", "Ap1!admin").token();

        String body = mvc.perform(get("/api/v1/iam/users")
                        .param("keyword", "leaky")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).contains("leaky");
        assertThat(body)
                .as("★ 响应绝不能包含密码哈希（$2a$ / $2b$ 前缀）")
                .doesNotContain("$2a$").doesNotContain("$2b$")
                .doesNotContain("passwordHash");

        // 没有 iam:read 的账号读不到用户清单
        mvc.perform(get("/api/v1/iam/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    // ─────────────────────────── FR-IAM-04 数据权限解析 ───────────────────────────

    @Test
    @CaseId("TC-IAM-038")
    @DisplayName("★ 数据权限解析优先级：未配置=全量 / 用户直配 > 角色继承 / ALL 覆盖 WAREHOUSE")
    void scopeResolution_precedence() {
        long wh1 = new TestDataFactory(jdbc).warehouse("WH-P1");
        long wh2 = new TestDataFactory(jdbc).warehouse("WH-P2");

        // ① 未配置 → 全量
        long plainId = admin.createUser("plain", "Pl4in!pass", "无配置", true, List.of("VIEWER"));
        assertThat(dataScopeProvider.scopeOf(plainId).isAll())
                .as("★ 未配置数据权限 = 全量（存量 251 条用例依赖这条默认值）")
                .isTrue();

        // ② 用户直配 WAREHOUSE → 只有那些仓
        long scopedId = admin.createUser("scoped2", "Sc0ped!2", "配仓库", true, List.of("VIEWER"));
        scopeAdmin.replaceUserScopes(scopedId, List.of("WAREHOUSE:" + wh1));
        DataScope scope = dataScopeProvider.scopeOf(scopedId);
        assertThat(scope.type()).isEqualTo(DataScope.ScopeType.WAREHOUSE);
        assertThat(scope.warehouseIds()).containsExactly(wh1);

        // ③ 角色继承也生效
        long roleId = admin.createRole("SCOPE_ROLE", "范围角色", "测试");
        scopeAdmin.replaceRoleScopes(roleId, List.of("WAREHOUSE:" + wh2));
        long inheritedId = admin.createUser("inherited", "Inh3r!it", "继承范围", true,
                List.of("SCOPE_ROLE"));
        assertThat(dataScopeProvider.scopeOf(inheritedId).warehouseIds())
                .as("★ 角色上的范围配置对角色下用户生效")
                .containsExactly(wh2);

        // ④ ALL 覆盖 WAREHOUSE（显式放开是更强的授权，不该被收窄顶掉）
        scopeAdmin.replaceUserScopes(scopedId, List.of("WAREHOUSE:" + wh1, "ALL"));
        assertThat(dataScopeProvider.scopeOf(scopedId).isAll())
                .as("★ 同时配 ALL 与 WAREHOUSE 时 ALL 优先 —— 否则加一个只读角色会莫名收窄全量权限")
                .isTrue();
    }

    @Test
    @CaseId("TC-IAM-039")
    @DisplayName("★ SELF 范围：只看本人创建的行（历史行 created_by 为空 → 看不到）")
    void selfScope_seesOnlyOwnRows() {
        long wh = new TestDataFactory(jdbc).warehouse("WH-SELF");
        long productId = new TestDataFactory(jdbc).product("P-SELF", "本人范围商品");
        long sku = new TestDataFactory(jdbc).sku(productId, "蓝色", "M");

        long meId = admin.createUser("me_self", "M3!selfpass", "本人", true, List.of("VIEWER"));
        long otherId = admin.createUser("other_self", "Oth3r!self", "别人", true,
                List.of("VIEWER"));
        scopeAdmin.replaceUserScopes(meId, List.of("SELF"));

        // 两张单据：一张我建、一张别人建；一张历史单据（created_by 为 null）
        insertPo("PO-SELF-MINE", wh, sku, meId);
        insertPo("PO-SELF-OTHER", wh, sku, otherId);
        insertPo("PO-SELF-LEGACY", wh, sku, null);

        DataScope scope = dataScopeProvider.scopeOf(meId);
        assertThat(scope.type()).isEqualTo(DataScope.ScopeType.SELF);

        java.util.List<Object> args = new java.util.ArrayList<>();
        String predicate = scope.creatorPredicate("created_by", args);
        Long visible = jdbc.queryForObject("SELECT COUNT(*) FROM purchase_orders WHERE 1=1"
                + predicate, Long.class, args.toArray());
        assertThat(visible)
                .as("★ SELF 只能看到自己建的那一张（别人的与自己为 null 的历史行都不可见）")
                .isEqualTo(1);
        Long mine = jdbc.queryForObject("SELECT COUNT(*) FROM purchase_orders WHERE 1=1"
                + predicate + " AND order_no = 'PO-SELF-MINE'", Long.class, args.toArray());
        assertThat(mine).isEqualTo(1);
    }

    @Test
    @CaseId("TC-IAM-040")
    @DisplayName("★ 数据权限配置非法一律 400：WAREHOUSE 缺 id / 未知类型 / 数值非数字")
    void invalidScopeConfig_isRejected() {
        long id = admin.createUser("badscope", "B4d!scope", "错配置", true, List.of("VIEWER"));
        assertThatThrownBy(() -> scopeAdmin.replaceUserScopes(id, List.of("WAREHOUSE")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.SYS_VALIDATION_ERROR)
                .hasMessageContaining("仓库 id");
        assertThatThrownBy(() -> scopeAdmin.replaceUserScopes(id, List.of("WAREHOUSE:abc")))
                .hasFieldOrPropertyWithValue("code", ErrorCode.SYS_VALIDATION_ERROR)
                .hasMessageContaining("数字");
        assertThatThrownBy(() -> scopeAdmin.replaceUserScopes(id, List.of("EVERYTHING")))
                .hasFieldOrPropertyWithValue("code", ErrorCode.SYS_VALIDATION_ERROR)
                .hasMessageContaining("范围类型非法");
        assertThatThrownBy(() -> scopeAdmin.replaceUserScopes(id, List.of("ALL:7")))
                .hasFieldOrPropertyWithValue("code", ErrorCode.SYS_VALIDATION_ERROR)
                .hasMessageContaining("不接受取值");
    }

    @Test
    @CaseId("TC-IAM-041")
    @DisplayName("★ 空仓库集合 = 什么都不许看（AND 1=0），而不是回退到全量")
    void emptyWarehouseSet_deniesEverything() {
        DataScope empty = DataScope.warehouses(List.of());
        java.util.List<Object> args = new java.util.ArrayList<>();
        assertThat(empty.warehousePredicate("warehouse_id", args))
                .as("★ 配置残缺时必须保守收紧，绝不放大成「全量」")
                .isEqualTo(" AND 1 = 0");
        assertThat(args).isEmpty();

        // 正常仓库集合 → = ANY(?)
        DataScope some = DataScope.warehouses(List.of(1L, 2L));
        java.util.List<Object> args2 = new java.util.ArrayList<>();
        assertThat(some.warehousePredicate("warehouse_id", args2))
                .isEqualTo(" AND warehouse_id = ANY (?)");
        assertThat(args2).containsExactly(1L, 2L);

        // ALL → 不加谓词
        java.util.List<Object> args3 = new java.util.ArrayList<>();
        assertThat(DataScope.all().warehousePredicate("warehouse_id", args3)).isEmpty();
    }

    @Test
    @CaseId("TC-IAM-042")
    @DisplayName("数据权限管理接口：需 iam:scope:write，无权限 403")
    void scopeApi_requiresWritePermission() throws Exception {
        long target = admin.createUser("apitarget", "Ap1!target", "接口目标", true,
                List.of("VIEWER"));
        long viewerId = admin.createUser("apiviewer", "Ap1!viewer", "接口只读", true,
                List.of("VIEWER"));
        String viewerToken = authentication.login("apiviewer", "Ap1!viewer").token();

        // VIEWER 只有 iam:read：能读，不能写
        mvc.perform(get("/api/v1/iam/users/" + target + "/data-scopes")
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk());
        mvc.perform(put("/api/v1/iam/users/" + target + "/data-scopes")
                        .header("Authorization", "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("scopes", List.of("ALL")))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("IAM_FORBIDDEN"));

        // ADMIN 可以写
        long adminId = admin.createUser("scope_writer", "Sc0pe!writ", "范围管理员", true,
                List.of("ADMIN"));
        String adminToken = authentication.login("scope_writer", "Sc0pe!writ").token();
        mvc.perform(put("/api/v1/iam/users/" + target + "/data-scopes")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("scopes", List.of("SELF")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.direct[0]").value("SELF"));
        assertThat(dataScopes.directRowsForUser(target)).hasSize(1);
    }

    // ─────────────────────────── 辅助 ───────────────────────────

    private void insertPo(String orderNo, long warehouseId, long skuId, Long createdBy) {
        long supplierId = new TestDataFactory(jdbc).supplier("SUP-" + orderNo);
        jdbc.update("""
                INSERT INTO purchase_orders
                    (order_no, status, supplier_id, warehouse_id, biz_date,
                     total_amount, total_qty, period, created_by)
                VALUES (?, 'DRAFT', ?, ?, DATE '2026-03-10', 10000, 1, '2026-03', ?)
                """, orderNo, supplierId, warehouseId, createdBy);
        Long poId = jdbc.queryForObject(
                "SELECT id FROM purchase_orders WHERE order_no = ?", Long.class, orderNo);
        jdbc.update("""
                INSERT INTO purchase_order_lines
                    (order_id, line_no, sku_id, qty, received_qty, unit_price_amount, amount)
                VALUES (?, 1, ?, 1, 0, 10000, 10000)
                """, poId, skuId);
    }
}
