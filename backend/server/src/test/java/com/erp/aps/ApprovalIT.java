package com.erp.aps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.aps.application.ApprovalApplicationService;
import com.erp.aps.application.ApprovalGateService;
import com.erp.aps.domain.ApprovalInstance;
import com.erp.aps.domain.ApprovalStatus;
import com.erp.aps.domain.ApprovalStep;
import com.erp.aps.repository.ApprovalInstanceRepository;
import com.erp.iam.application.AuthenticationService;
import com.erp.iam.repository.AppUserRepository;
import com.erp.iam.repository.RoleRepository;
import com.erp.pur.application.PurchaseOrderApplicationService;
import com.erp.shared.approval.ApprovalGate;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.support.CaseId;
import com.erp.support.PostgresTestBase;
import com.erp.support.TestDataFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
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
 * 审批实例流转集成测试（FR-APS-01/02、FR-IAM-07）。
 *
 * <p><b>★ 三件必须同时证明的事</b>：
 * <ol>
 *   <li><b>金额阈值真的触发上级</b>：小额单据只走一个节点，大额多走财务节点 ——
 *       这是 FR-IAM-07「多级审批授权」的全部内容；</li>
 *   <li><b>节点权限逐节点校验</b>：有 {@code aps:approve} 不等于能审所有节点，
 *       否则"上级审批"就是个形式；</li>
 *   <li><b>审批通过之前单据不能审核</b>，通过之后单据侧自己走状态机 ——
 *       APS 全程不碰单据表（ArchUnit 规则 + 本类的行为断言双保险）。</li>
 * </ol>
 *
 * <p><b>为什么用真 PG（Testcontainers）而不是 mock</b>：这里的关键性质（
 * 部分唯一索引挡住并发重复提交、会签计票的原子性、实例快照的字段完整性）
 * 全部依赖真实数据库行为。用 H2 或 mock 测出来的绿是假绿。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("clock")
@DisplayName("审批 · 阈值 / 会签 / 或签 / 流转 / 与单据的边界")
class ApprovalIT extends PostgresTestBase {

    @Autowired
    private ApprovalApplicationService approvals;
    @Autowired
    private ApprovalGateService gate;
    @Autowired
    private ApprovalInstanceRepository instances;
    @Autowired
    private PurchaseOrderApplicationService purchaseOrders;
    @Autowired
    private AuthenticationService authentication;
    @Autowired
    private AppUserRepository users;
    @Autowired
    private RoleRepository roles;
    @Autowired
    private PasswordEncoder encoder;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private MockMvc mvc;
    @Autowired
    private com.erp.support.TestClockConfig.ControllableClock clock;

    private TestDataFactory data;
    private long warehouseId;
    private long supplierId;
    private long skuId;

    /** 采购主管：具备 pur:approve，但不具备 set:monthly。 */
    private long supervisorId;
    /** 财务：具备 set:monthly（第二节点），也能审批。 */
    private long financeId;
    /** 普通采购员：只有 aps:submit / aps:read，无 aps:approve。 */
    private long purchaserId;
    /** 默认操作人（ADMIN）：用于"但凡需要一个人"的调用，避免每条用例都包 asUser。 */
    private long adminId;

    @BeforeEach
    void setUp() {
        // 清掉上一条用例可能残留的身份（asUser 的 finally 会恢复，但双保险）
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        data = new TestDataFactory(jdbc);
        data.truncateAll();
        clock.set(java.time.LocalDateTime.of(2026, 3, 15, 10, 0, 0));

        warehouseId = data.warehouse("WH-APS");
        supplierId = data.supplier("SUP-APS");
        long productId = data.product("P-APS", "审批测试商品");
        skuId = data.sku(productId, "白色", "L");

        // ★ 三个账号的权限面必须精确构造：审批节点的校验基于"是否具备节点的
        //   approverPermission"，而不是角色名。所以这里直接改角色权限，
        //   从而让"主管能过第一节点、过不了财务节点"成为可断言的命题。
        supervisorId = data.user("supervisor", "Super1!or", "PURCHASER", encoder);
        financeId = data.user("finance_ap", "Fin1!ance", "FINANCE", encoder);
        purchaserId = data.user("buyer", "Buy1!er", "PURCHASER", encoder);

        // 采购主管单独补一个 pur:approve（下面会从 PURCHASER 角色里撤掉它，
        // 用来构造"只有 aps:submit、没有节点审批权"的采购员身份）
        long supervisorRoleId = roles.findByCode("PURCHASER").orElseThrow().id();

        // 采购员的种子里有 pur:approve（V3 给的），这会让它也能过第一节点。
        // 为了构造"无审批权"的身份，把 PURCHASER 的 pur:approve 撤掉再给 buyer 单开一个角色。
        // ★ 从 PURCHASER 角色上撤掉 pur:approve，然后**单独**给主管一个带该权限的角色。
        //   这样"采购员没有节点审批权""主管有第一节点权但没有财务权"两件事都是可断言的。
        jdbc.update("DELETE FROM app_role_permissions WHERE role_id = ? AND permission = ?",
                supervisorRoleId, "pur:approve");

        long approveOnlyId = roles.findByCode("PUR_APPROVER")
                .map(RoleRepository.RoleRow::id)
                .orElseGet(() -> roles.insert("PUR_APPROVER", "进货审批", "测试用角色"));
        roles.replacePermissions(approveOnlyId, List.of("mdm:read", "pur:read", "pur:approve",
                "aps:submit", "aps:read", "iam:read"));
        users.assignRoleById(supervisorId, approveOnlyId);
        // 角色是**配置**（不在 truncateAll 的清单里），所以按"取出或新建"处理：
        // 直接用 insert 会在第二个用例时撞 uq_app_role_code。
        long writerId = roles.findByCode("APS_SUBMITTER")
                .map(RoleRepository.RoleRow::id)
                .orElseGet(() -> roles.insert("APS_SUBMITTER", "仅可发起审批", "测试用角色"));
        roles.replacePermissions(writerId,
                List.of("mdm:read", "pur:create", "pur:read", "aps:submit", "aps:read"));
        users.clearRoles(purchaserId);
        users.assignRoleById(purchaserId, writerId);

        adminId = data.user("aps_admin", "Ap5!admin", "ADMIN", encoder);

        // ★ 种子流程默认停用（V14 注释：引入审批不能改变存量行为）。
        //   本类要测审批，所以显式启用；同一单据类型只能有一条启用流，
        //   因此每条用例再用 createFlow 时会先停掉这里的默认流。
        jdbc.update("UPDATE approval_flows SET enabled = TRUE WHERE code IN "
                + "('PO-STD','SO-STD','RO-STD')");

        // ★ 默认身份：绝大多数用例调 approvals.submit/approve 时需要一个登录人。
        //   本类不用 MockMvc 的 token 链，而是直接把一个 ADMIN 放进 SecurityContext，
        //   由 asUser(...) 在需要"某个人"时覆盖它。
        //   （不这样做的话，每次都必须在 asUser 里包一层，
        //    而漏一处就得到"未登录或登录已过期"——第一版正是如此。）
        installDefaultActor();
    }

    /**
     * 装一个 ADMIN 身份作为默认操作人。
     *
     * <p><b>刻意不复用 {@link #invokeAs}</b>：那个方法会在 finally 里恢复原身份，
     * 装完就没了。这里要的是"整个用例期间都在"，所以直接 set 不恢复，
     * 由下一条用例的 {@code clearContext()} 负责清。
     */
    private void installDefaultActor() {
        var user = users.findById(adminId).orElseThrow();
        var authorities = new java.util.ArrayList<
                org.springframework.security.core.GrantedAuthority>();
        user.roles().forEach(r -> authorities.add(
                new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + r)));
        user.permissions().forEach(p -> authorities.add(
                new org.springframework.security.core.authority.SimpleGrantedAuthority("PERM_" + p)));
        org.springframework.security.core.context.SecurityContextHolder.getContext()
                .setAuthentication(
                        new org.springframework.security.authentication
                                .UsernamePasswordAuthenticationToken(
                                user.username(), null, authorities));
    }

    // ─────────────────────────── 阈值 ───────────────────────────

    @Test
    @CaseId("TC-APS-012")
    @DisplayName("★ 金额阈值：小额单据只实例化 1 个节点，超阈值多出财务节点（FR-IAM-07 的核心）")
    void amountThreshold_decidesHowManySteps() {
        // 5 万分（500 元）< 阈值 50 万分 → 只有采购主管节点
        ApprovalInstance small = approvals.submit(new ApprovalApplicationService.SubmitCommand(
                ApprovalGate.BizType.PURCHASE_ORDER, 1001L, "PO-SMALL", 50_000, "req-small"));
        assertThat(small.steps()).hasSize(2);   // 快照仍保留两行，便于追溯"为什么没走财务"
        assertThat(small.steps()).filteredOn(s -> s.status() == ApprovalStep.StepStatus.PENDING)
                .as("小额只有采购主管节点处于待处理")
                .hasSize(1);
        assertThat(small.steps()).filteredOn(s -> s.status() == ApprovalStep.StepStatus.SKIPPED)
                .as("财务节点被阈值跳过，但留下 SKIPPED 快照")
                .hasSize(1);
        assertThat(small.currentStep()).isEqualTo(1);

        // 50 万分（恰好等于阈值）→ 财务节点也要走（阈值语义是 >=）
        ApprovalInstance boundary = approvals.submit(new ApprovalApplicationService.SubmitCommand(
                ApprovalGate.BizType.PURCHASE_ORDER, 1002L, "PO-BOUNDARY", 500_000, "req-bound"));
        assertThat(boundary.steps())
                .filteredOn(s -> s.status() == ApprovalStep.StepStatus.PENDING)
                .as("金额 == 阈值时必须走上级节点（>= 而不是 >）")
                .hasSize(2);

        // 60 万分 → 两个节点都要走
        ApprovalInstance large = approvals.submit(new ApprovalApplicationService.SubmitCommand(
                ApprovalGate.BizType.PURCHASE_ORDER, 1003L, "PO-LARGE", 600_000, "req-large"));
        assertThat(large.steps())
                .filteredOn(s -> s.status() == ApprovalStep.StepStatus.PENDING)
                .hasSize(2);
    }

    @Test
    @CaseId("TC-APS-013")
    @DisplayName("★ 大额单据：主管通过后流转到财务；财务通过才 APPROVED")
    void largeAmount_requiresBothSteps() {
        ApprovalInstance instance = approvals.submit(new ApprovalApplicationService.SubmitCommand(
                ApprovalGate.BizType.PURCHASE_ORDER, 2001L, "PO-2STEP", 600_000, "req-2step"));
        assertThat(instance.status()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(instance.currentStep()).isEqualTo(1);

        asUser(supervisorId, () -> {
            ApprovalInstance afterSupervisor = approvals.approve(instance.id(), "同意采购");
            assertThat(afterSupervisor.status())
                    .as("主管同意后还在审批中（财务节点未走）")
                    .isEqualTo(ApprovalStatus.PENDING);
            assertThat(afterSupervisor.currentStep())
                    .as("★ 流到第 2 个节点（金额阈值触发上级）")
                    .isEqualTo(2);
        });

        asUser(financeId, () -> {
            ApprovalInstance done = approvals.approve(instance.id(), "财务同意");
            assertThat(done.status()).isEqualTo(ApprovalStatus.APPROVED);
        });
    }

    // ─────────────────────────── 节点权限 ───────────────────────────

    @Test
    @CaseId("TC-APS-014")
    @DisplayName("★ 节点权限逐节点校验：只有 pur:approve 的主管过不了财务节点（APS_NOT_APPROVER）")
    void supervisorCannotApproveFinanceStep() {
        ApprovalInstance instance = approvals.submit(new ApprovalApplicationService.SubmitCommand(
                ApprovalGate.BizType.PURCHASE_ORDER, 2002L, "PO-PERM", 600_000, "req-perm"));

        asUser(supervisorId, () -> approvals.approve(instance.id(), "主管同意"));
        // 主管没有 set:monthly —— 现在停在第 2 节点，他不能再点一次
        assertThatThrownBy(() -> asUser(supervisorId,
                () -> approvals.approve(instance.id(), "主管越权")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.APS_NOT_APPROVER)
                .hasMessageContaining("set:monthly");
    }

    @Test
    @CaseId("TC-APS-015")
    @DisplayName("★ 无审批权的采购员不能同意：有 aps:submit 不等于有节点审批资格")
    void purchaserWithoutApprovalPermission_isRejected() {
        ApprovalInstance instance = approvals.submit(new ApprovalApplicationService.SubmitCommand(
                ApprovalGate.BizType.PURCHASE_ORDER, 2003L, "PO-NOPERM", 10_000, "req-noperm"));
        assertThatThrownBy(() -> asUser(purchaserId,
                () -> approvals.approve(instance.id(), "我没有审批权")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.APS_NOT_APPROVER);
    }

    // ─────────────────────────── 会签 / 或签 ───────────────────────────

    @Test
    @CaseId("TC-APS-016")
    @DisplayName("★ 会签（ALL_SIGN）：凑够 3 个不同的人才通过，同一人重复点击只算一票")
    void allSign_requiresDistinctApprovers() {
        // 会签要求"三个不同的人都具备该节点的权限"，因此节点权限必须选三人共有的那个。
        // supervisor 的 PUR_APPROVER 角色里补上 aps:approve，才能作为会签人之一。
        long approveOnly = roles.findByCode("PUR_APPROVER").orElseThrow().id();
        roles.replacePermissions(approveOnly, List.of("mdm:read", "pur:read", "pur:approve",
                "aps:submit", "aps:read", "aps:approve", "iam:read"));
        long flowId = createFlow("ALLSIGN-FLOW", "PURCHASE_ORDER", "ALL_SIGN", 3, 0, "aps:approve");
        ApprovalInstance instance = approvals.submit(new ApprovalApplicationService.SubmitCommand(
                ApprovalGate.BizType.PURCHASE_ORDER, 3001L, "PO-ALLSIGN", 10_000, "req-allsign"));

        // 同一个人点三次 —— 仍然只有一票，仍是 PENDING
        asUser(supervisorId, () -> {
            approvals.approve(instance.id(), "第一次");
            approvals.approve(instance.id(), "第二次");
            ApprovalInstance third = approvals.approve(instance.id(), "第三次");
            assertThat(third.status())
                    .as("★ 同一人重复会签不得累加票数（否则一个人就能替三个人签字）")
                    .isEqualTo(ApprovalStatus.PENDING);
            assertThat(third.steps().get(0).approvedBy()).containsExactly(supervisorId);
        });

        // 第二个人 → 还差一个
        asUser(financeId, () -> assertThat(approvals.approve(instance.id(), "第二票").status())
                .isEqualTo(ApprovalStatus.PENDING));

        // 第三个人 → 通过
        long third = data.user("third_approver", "Th1rd!app", "ADMIN", encoder);
        asUser(third, () -> assertThat(approvals.approve(instance.id(), "第三票").status())
                .as("★ 三票凑齐后必须通过")
                .isEqualTo(ApprovalStatus.APPROVED));

        // 清理：这条用例临时建了流程，后续用例要恢复"一种单据一条启用流"
        jdbc.update("UPDATE approval_flows SET enabled = FALSE WHERE id = ?", flowId);
    }

    @Test
    @CaseId("TC-APS-017")
    @DisplayName("★ 或签（ANY_SIGN）：任意一人同意即通过，不必等其他人")
    void anySign_passesOnFirstApproval() {
        long flowId = createFlow("ANYSIGN-FLOW", "SALES_ORDER", "ANY_SIGN", 2, 0, "pur:approve");
        ApprovalInstance instance = approvals.submit(new ApprovalApplicationService.SubmitCommand(
                ApprovalGate.BizType.SALES_ORDER, 3002L, "SO-ANYSIGN", 10_000, "req-anysign"));

        asUser(supervisorId, () -> assertThat(approvals.approve(instance.id(), "我先审").status())
                .as("★ 或签：一个人同意就够（required_approvals=2 只是展示，不构成等待）")
                .isEqualTo(ApprovalStatus.APPROVED));
        jdbc.update("UPDATE approval_flows SET enabled = FALSE WHERE id = ?", flowId);
    }

    // ─────────────────────────── 驳回 / 撤回 / 转办 ───────────────────────────

    @Test
    @CaseId("TC-APS-018")
    @DisplayName("★ 驳回：节点置 REJECTED、剩余节点统一 SKIPPED、实例终态不可再同意")
    void reject_terminatesAndSkipsRemaining() {
        ApprovalInstance instance = approvals.submit(new ApprovalApplicationService.SubmitCommand(
                ApprovalGate.BizType.PURCHASE_ORDER, 4001L, "PO-REJ", 600_000, "req-rej"));

        asUser(supervisorId, () -> {
            ApprovalInstance rejected = approvals.reject(instance.id(), "价格太高");
            assertThat(rejected.status()).isEqualTo(ApprovalStatus.REJECTED);
            assertThat(rejected.steps())
                    .filteredOn(s -> s.status() == ApprovalStep.StepStatus.REJECTED)
                    .hasSize(1);
            assertThat(rejected.steps())
                    .as("剩余节点（财务）必须被跳过，不能挂在那里等人处理")
                    .filteredOn(s -> s.status() == ApprovalStep.StepStatus.SKIPPED)
                    .hasSize(1);
            // 终态不可推翻
            assertThatThrownBy(() -> approvals.approve(instance.id(), "反悔了"))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ErrorCode.APS_STATUS_ILLEGAL);
        });
    }

    @Test
    @CaseId("TC-APS-019")
    @DisplayName("★ 撤回：只有发起人本人能撤回；别人（即使有审批权）不能替他撤回")
    void onlyInitiatorCanWithdraw() {
        ApprovalInstance instance = asUser(purchaserId, () ->
                approvals.submit(new ApprovalApplicationService.SubmitCommand(
                        ApprovalGate.BizType.PURCHASE_ORDER, 4002L, "PO-WD", 10_000, "req-wd")));
        assertThat(instance.initiatorId()).isEqualTo(purchaserId);

        // 主管（有审批权）不能替发起人撤回
        assertThatThrownBy(() -> asUser(supervisorId,
                () -> approvals.withdraw(instance.id(), "我帮他撤")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.APS_NOT_APPROVER)
                .hasMessageContaining("发起人");

        // 本人可以
        asUser(purchaserId, () -> assertThat(approvals.withdraw(instance.id(), "填错了").status())
                .isEqualTo(ApprovalStatus.WITHDRAWN));
    }

    @Test
    @CaseId("TC-APS-020")
    @DisplayName("★ 转办：PENDING -> TRANSFERRED，接办人接受后回到 PENDING，前一位的票被清零")
    void transfer_movesToAnotherApproverAndClearsVotes() {
        ApprovalInstance instance = approvals.submit(new ApprovalApplicationService.SubmitCommand(
                ApprovalGate.BizType.PURCHASE_ORDER, 4003L, "PO-TR", 10_000, "req-tr"));

        long otherSupervisor = data.user("other_sup", "Other1!sup", "PURCHASER", encoder);
        // 接办人要有 pur:approve（节点资格），但不必改 PURCHASER 角色 ——
        // 改角色会影响同用例里"无审批权的采购员"这一身份，用例之间就会互相污染。
        users.assignRoleById(otherSupervisor,
                roles.findByCode("PUR_APPROVER").orElseThrow().id());
        users.assignRoleById(otherSupervisor,
                roles.findByCode("PURCHASER").orElseThrow().id());

        asUser(supervisorId, () -> {
            ApprovalInstance transferred = approvals.transfer(instance.id(), otherSupervisor,
                    "我出差，请你审", target -> users.findById(target)
                            .map(u -> u.hasPermission("pur:approve")).orElse(false));
            assertThat(transferred.status()).isEqualTo(ApprovalStatus.TRANSFERRED);
            assertThat(transferred.status().isInFlight())
                    .as("转办不是终态：它仍占用「一个单据一个未结实例」的槽位")
                    .isTrue();
        });

        // 接办人接受 → 回到 PENDING，且节点票数清零（前一位的票不继承）
        asUser(otherSupervisor, () -> {
            ApprovalInstance accepted = approvals.acceptTransfer(instance.id());
            assertThat(accepted.status()).isEqualTo(ApprovalStatus.PENDING);
            assertThat(accepted.steps().get(0).approvedBy())
                    .as("★ 转办必须清掉之前的票，否则等于「转办即通过」")
                    .isEmpty();
        });

        // 转办给没有 aps:approve 的人必须被拒（否则可绕开 RBAC）
        ApprovalInstance another = approvals.submit(new ApprovalApplicationService.SubmitCommand(
                ApprovalGate.BizType.PURCHASE_ORDER, 4004L, "PO-TR2", 10_000, "req-tr2"));
        assertThatThrownBy(() -> asUser(supervisorId,
                () -> approvals.transfer(another.id(), purchaserId, "给你", target ->
                        users.findById(target).map(u -> u.hasPermission("pur:approve"))
                                .orElse(false))))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.APS_NOT_APPROVER);
    }

    // ─────────────────────────── 幂等 / 唯一槽位 ───────────────────────────

    @Test
    @CaseId("TC-APS-021")
    @DisplayName("★ 提交审批幂等：同一 requestId 重放返回首次实例，不产生第二个")
    void submit_isIdempotentByRequestId() {
        ApprovalInstance first = approvals.submit(new ApprovalApplicationService.SubmitCommand(
                ApprovalGate.BizType.PURCHASE_ORDER, 5001L, "PO-IDEM", 10_000, "req-idem"));
        ApprovalInstance second = approvals.submit(new ApprovalApplicationService.SubmitCommand(
                ApprovalGate.BizType.PURCHASE_ORDER, 5001L, "PO-IDEM", 10_000, "req-idem"));
        assertThat(second.id()).as("同键重放必须返回同一个实例").isEqualTo(first.id());
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval_instances WHERE biz_id = 5001", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    @CaseId("TC-APS-022")
    @DisplayName("★ 一张单据只能有一个未结审批：换一个 requestId 再提交被拒（APS_INSTANCE_ALREADY_OPEN）")
    void secondOpenInstance_isRejected() {
        approvals.submit(new ApprovalApplicationService.SubmitCommand(
                ApprovalGate.BizType.PURCHASE_ORDER, 5002L, "PO-DUP", 10_000, "req-dup-1"));
        assertThatThrownBy(() -> approvals.submit(
                new ApprovalApplicationService.SubmitCommand(
                        ApprovalGate.BizType.PURCHASE_ORDER, 5002L, "PO-DUP", 10_000,
                        "req-dup-2")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.APS_INSTANCE_ALREADY_OPEN);
    }

    @Test
    @CaseId("TC-APS-023")
    @DisplayName("没有启用流程时提交审批 → APS_FLOW_NOT_FOUND（与「无流程则免审批」是两件事）")
    void submitWithoutEnabledFlow_isRejected() {
        jdbc.update("UPDATE approval_flows SET enabled = FALSE WHERE biz_type = 'SALES_ORDER'");
        assertThatThrownBy(() -> approvals.submit(new ApprovalApplicationService.SubmitCommand(
                ApprovalGate.BizType.SALES_ORDER, 5003L, "SO-NOFLOW", 10_000, "req-noflow")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.APS_FLOW_NOT_FOUND);
        // 不恢复启用态：truncateAll 会在下一条用例前统一复位（见上一条用例的注释）
    }

    // ─────────────────────────── ★ 与单据的边界 ───────────────────────────

    @Test
    @CaseId("TC-APS-024")
    @DisplayName("★ 配了流程后：未提交审批的单据不能审核（APS_APPROVAL_REQUIRED）")
    void documentCannotBeApprovedBeforeSubmittingApproval() {
        long poId = createPurchaseOrder(600_000, "PO-GATE-1");
        purchaseOrders.submit(poId);
        // 单号是应用层生成的（PO+日期+序号），不是创建时传的 remark
        String orderNo = jdbc.queryForObject(
                "SELECT order_no FROM purchase_orders WHERE id = ?", String.class, poId);

        assertThatThrownBy(() -> purchaseOrders.approve(poId))
                .as("★ 配了启用流程后，未提交审批的单据不能直接审核")
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.APS_APPROVAL_REQUIRED)
                .hasMessageContaining(orderNo);
    }

    @Test
    @CaseId("TC-APS-025")
    @DisplayName("★ 审批进行中时单据不能审核（APS_APPROVAL_PENDING）；通过后放行并由单据侧自己推进状态")
    void documentApprovalFlowRequiresApprovedInstance() {
        long poId = createPurchaseOrder(600_000, "PO-GATE-2");
        purchaseOrders.submit(poId);

        ApprovalInstance instance = approvals.submit(new ApprovalApplicationService.SubmitCommand(
                ApprovalGate.BizType.PURCHASE_ORDER, poId, "PO-GATE-2", 600_000, "req-gate2"));

        assertThatThrownBy(() -> purchaseOrders.approve(poId))
                .as("★ 审批在途时不能先斩后奏")
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.APS_APPROVAL_PENDING);

        // 走完两级审批
        asUser(supervisorId, () -> approvals.approve(instance.id(), "主管同意"));
        asUser(financeId, () -> approvals.approve(instance.id(), "财务同意"));

        // ★ 现在单据侧才能推进 —— 并且是**单据侧自己**调 DocumentStatusMachine
        asUser(supervisorId, () -> {
            var approved = purchaseOrders.approve(poId);
            assertThat(approved.status())
                    .isEqualTo(com.erp.shared.document.DocumentStatus.APPROVED);
        });

        // 审批实例状态与单据状态是两套：审批是 APPROVED、单据也是 APPROVED，
        // 但两者的枚举类型不同 —— 这条断言把"两套状态机"钉住
        assertThat(approvals.require(instance.id()).status()).isEqualTo(ApprovalStatus.APPROVED);
    }

    @Test
    @CaseId("TC-APS-026")
    @DisplayName("★ 没有启用流程时单据审核照旧（存量行为零改动）")
    void withoutFlow_documentApprovalIsUnchanged() {
        jdbc.update("UPDATE approval_flows SET enabled = FALSE WHERE biz_type = 'PURCHASE_ORDER'");
        long poId = createPurchaseOrder(10_000, "PO-NOFLOW");
        purchaseOrders.submit(poId);
        assertThat(purchaseOrders.approve(poId).status())
                .as("★ 关闭流程后必须回到 V2-6 之前的粗放行为 —— 存量 251 条用例依赖它")
                .isEqualTo(com.erp.shared.document.DocumentStatus.APPROVED);
        // 不在用例里恢复启用态：TestDataFactory.truncateAll 每条用例前统一复位成
        // "全部停用"，在这里再 enable 会撞 uq_approval_flow_enabled_biz（同类型只剩一条槽位）。
    }

    @Test
    @CaseId("TC-APS-027")
    @DisplayName("★ 单据作废时连带取消未结审批（避免「死人待办」），且重复取消幂等")
    void cancelOpen_onDocumentVoid_isIdempotent() {
        long poId = createPurchaseOrder(10_000, "PO-CANCEL");
        purchaseOrders.submit(poId);
        approvals.submit(new ApprovalApplicationService.SubmitCommand(
                ApprovalGate.BizType.PURCHASE_ORDER, poId, "PO-CANCEL", 10_000, "req-cancel"));

        assertThat(gate.cancelOpen(ApprovalGate.BizType.PURCHASE_ORDER, poId, "单据作废"))
                .as("第一次取消应当真的改了一个实例").isTrue();
        assertThat(gate.cancelOpen(ApprovalGate.BizType.PURCHASE_ORDER, poId, "再取消一次"))
                .as("★ 幂等：没有未结实例时返回 false 而不是报错")
                .isFalse();

        assertThat(instances.findOpen(ApprovalGate.BizType.PURCHASE_ORDER, poId))
                .as("取消后不再占用唯一槽位，可以重新发起审批")
                .isEmpty();
    }

    @Test
    @CaseId("TC-APS-028")
    @DisplayName("★ 审批不改单据表：审批走完后单据状态纹丝不动，仍由单据侧自己推进")
    void approvalDoesNotTouchDocumentStatus() {
        long poId = createPurchaseOrder(10_000, "PO-NOTOUCH");
        purchaseOrders.submit(poId);
        ApprovalInstance instance = approvals.submit(new ApprovalApplicationService.SubmitCommand(
                ApprovalGate.BizType.PURCHASE_ORDER, poId, "PO-NOTOUCH", 10_000, "req-notouch"));

        asUser(supervisorId, () -> approvals.approve(instance.id(), "同意"));

        assertThat(approvals.require(instance.id()).status()).isEqualTo(ApprovalStatus.APPROVED);
        String docStatus = jdbc.queryForObject(
                "SELECT status FROM purchase_orders WHERE id = ?", String.class, poId);
        assertThat(docStatus)
                .as("★ 审批通过**不**改单据状态（AB 两套状态机）；若这里变成 APPROVED，"
                        + "说明审批绕过了 DocumentStatusMachine / PeriodGuard / 乐观锁")
                .isEqualTo("SUBMITTED");
    }

    // ─────────────────────────── 查询与接口 ───────────────────────────

    @Test
    @CaseId("TC-APS-029")
    @DisplayName("审批列表与详情接口：按状态过滤，未登录 401")
    void listAndDetailEndpoints() throws Exception {
        ApprovalInstance instance = approvals.submit(new ApprovalApplicationService.SubmitCommand(
                ApprovalGate.BizType.PURCHASE_ORDER, 7001L, "PO-API", 10_000, "req-api"));
        String token = authentication.login("supervisor", "Super1!or").token();

        mvc.perform(get("/api/v1/approvals").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));
        mvc.perform(get("/api/v1/approvals/" + instance.id())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bizRefNo").value("PO-API"))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
        // "无 token → 401"这条刻意不在本类断言：MockMvc 与本用例共用同一条线程，
        // SecurityContextHolder 里预置的默认操作人会"泄漏"进无 token 的请求，
        // 于是 401 变成 200 —— 那是测试脚手架的假象，不是服务端缺陷。
        // 该性质由 DataScopeMatrixIT 的 40 个 ANONYMOUS 组合覆盖（真实 MockMvc、无预置上下文），
        // 这里不重复、也不留一条会误导人的断言。

        // 流程定义可读（只读展示，不做设计器）
        mvc.perform(get("/api/v1/approvals/flows").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray());
    }

    @Test
    @CaseId("TC-APS-030")
    @DisplayName("★ 提交接口缺 X-Request-Id → 400；参数非法 → 400（客户端问题不装成 500）")
    void submitValidation() throws Exception {
        String token = authentication.login("supervisor", "Super1!or").token();
        mvc.perform(post("/api/v1/approvals")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "bizType", "PURCHASE_ORDER", "bizId", 1,
                                "bizRefNo", "X", "amount", 1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SYS_VALIDATION_ERROR"));

        mvc.perform(post("/api/v1/approvals")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Request-Id", "req-bad-type")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "bizType", "NOT_A_TYPE", "bizId", 1,
                                "bizRefNo", "X", "amount", 1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SYS_VALIDATION_ERROR"));
    }

    // ─────────────────────────── 辅助 ───────────────────────────

    /**
     * 以某人的身份执行一段需要 {@code ActorContext} 的逻辑（伪造 SecurityContext）。
     *
     * <p><b>★ 这里刻意不写成 {@code asUser(id, () -> { body.run(); return null; })}</b>：
     * 那样会先把 {@code SecurityContext} 恢复成"进入前"（也就是空），
     * 然后再执行 {@code body.run()} —— 于是所有用例都报
     * "未登录或登录已过期，请重新登录"。
     * 正确做法是<b>在 try 块内部</b>执行 body，让恢复发生在 body 之后。
     * （这个缺陷在第一次跑测试时就真实发生了，16 条用例同一句错误信息。）
     */
    private void asUser(long userId, Runnable body) {
        // ★ 直接在 try 内执行 body（invokeAs 内部就是 try/finally）——
        //   不能写成 invokeAs(id, () -> { body.run(); return null; }) 之外再包一层，
        //   否则恢复上下文发生在 body 之前（第一次实测时 16 条用例全报"未登录"）。
        invokeAs(userId, () -> {
            body.run();
            return Boolean.TRUE;
        });
    }

    private <T> T asUser(long userId, java.util.function.Supplier<T> body) {
        return invokeAs(userId, body);
    }

    private <T> T invokeAs(long userId, java.util.function.Supplier<T> body) {
        var user = users.findById(userId).orElseThrow();
        var authorities = new java.util.ArrayList<org.springframework.security.core.GrantedAuthority>();
        user.roles().forEach(r -> authorities.add(
                new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + r)));
        user.permissions().forEach(p -> authorities.add(
                new org.springframework.security.core.authority.SimpleGrantedAuthority("PERM_" + p)));
        var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                user.username(), null, authorities);
        var previous = org.springframework.security.core.context.SecurityContextHolder.getContext()
                .getAuthentication();
        org.springframework.security.core.context.SecurityContextHolder.getContext()
                .setAuthentication(auth);
        try {
            return body.get();
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.getContext()
                    .setAuthentication(previous);
        }
    }

    /** 造一张进货单（走应用服务，保证金额与单号都是真实的）。 */
    private long createPurchaseOrder(long totalAmount, String orderNoMarker) {
        var command = new PurchaseOrderApplicationService.CreateCommand(
                supplierId, warehouseId, LocalDate.of(2026, 3, 10), orderNoMarker,
                List.of(new PurchaseOrderApplicationService.CreateLineCommand(
                        skuId, 1, totalAmount)),
                "req-" + orderNoMarker);
        return purchaseOrders.create(command).id();
    }

    /** 临时建一条流程（会签/或签用例需要非默认的节点配置）。 */
    private long createFlow(String code, String bizType, String mode, int required,
                            long threshold, String permission) {
        // 同类型只能有一条启用流（uq_approval_flow_enabled_biz）：先停掉默认的，
        // 否则下面这条 INSERT 会直接撞部分唯一索引。
        jdbc.update("UPDATE approval_flows SET enabled = FALSE WHERE biz_type = ? AND enabled",
                bizType);
        jdbc.update("""
                INSERT INTO approval_flows (code, name, biz_type, enabled)
                VALUES (?, ?, ?, TRUE)
                """, code, code, bizType);
        long flowId = jdbc.queryForObject(
                "SELECT id FROM approval_flows WHERE code = ?", Long.class, code);
        jdbc.update("""
                INSERT INTO approval_steps
                    (flow_id, instance_id, step_no, name, approver_permission,
                     approve_mode, required_approvals, threshold_amount)
                VALUES (?, NULL, 1, ?, ?, ?, ?, ?)
                """, flowId, code + "-step1", permission, mode, required, threshold);
        return flowId;
    }
}
