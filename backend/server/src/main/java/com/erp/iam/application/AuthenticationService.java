package com.erp.iam.application;

import com.erp.iam.domain.AppUser;
import com.erp.iam.domain.PasswordPolicy;
import com.erp.iam.repository.AppUserRepository;
import com.erp.iam.repository.TenantMembershipRepository;
import com.erp.iam.security.JwtTokenService;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.tenant.TenantContext;
import com.erp.shared.tenant.TenantDirectory;
import com.erp.shared.tenant.TenantRef;
import com.erp.shared.time.ClockProvider;
import com.erp.sys.application.LoginLogRecorder;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 认证应用服务（FR-IAM-01 / FR-TNT-07）：登录、切换租户、登出（JWT 无状态，
 * 登出即客户端丢弃 token）。
 *
 * <p><b>密码错误锁定</b>：连续失败 {@code maxFailedAttempts} 次后锁定
 * {@code lockMinutes} 分钟。计数与锁定时间存在数据库里，因此多实例部署时共享、
 * 服务重启也不会被绕过 —— 放在内存里的计数器只要重启一次就清零，爆破者很容易利用。
 *
 * <p><b>防用户名枚举</b>："用户不存在"与"密码错误"对外返回<b>同一个</b>错误码
 * （{@code IAM_BAD_CREDENTIALS}），否则攻击者可以先测出哪些用户名存在，
 * 再针对性地爆破。区别只在内部计数上：不存在的用户无从计数，但对外表现一致。
 * <b>租户编码的校验刻意放在密码校验之后</b>：否则"用户名存在 + 租户编码错误"
 * 会与"用户名不存在"产生不同响应，把枚举面从用户名扩大到"用户名 × 租户"。
 *
 * <p><b>FR-IAM-06 的登录策略（自上而下依次判定）</b>：
 * <ol>
 *   <li>账号停用 → {@code IAM_ACCOUNT_DISABLED}；</li>
 *   <li>账号锁定中 → {@code IAM_ACCOUNT_LOCKED}（不看密码对错，先挡住）；</li>
 *   <li>密码校验 → 失败计数 + 达到阈值即锁；</li>
 *   <li><b>密码过期 / 强制改密 → 仍然签发 token</b>，但登录响应带
 *       {@code mustChangePassword=true}，且除改密与 {@code /auth/me} 外的所有请求
 *       被 {@code PasswordExpiryFilter} 拦成 {@code IAM_PASSWORD_EXPIRED}；</li>
 *   <li><b>租户校验（B1）</b>：目标租户必须是该账号的成员且处于启用状态；
 *       缺省用主租户，显式传 {@code tenantCode} 则切到该租户。</li>
 * </ol>
 *
 * <p><b>★ 主租户（home）与行动租户（acting）是两件事</b>：
 * {@code app_users.tenant_id} 永远是主租户；登录/切换只决定"本次会话以哪个租户
 * 的身份行动"（JWT 的 {@code tid}，以及从哪个租户装载角色）。账号级的读写
 * （失败计数、密码过期、改密）始终落在主租户；业务读写落在行动租户。
 */
@Service
public class AuthenticationService {

    private final AppUserRepository users;
    private final TenantDirectory tenants;
    private final TenantMembershipRepository memberships;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService tokens;
    private final ClockProvider clock;
    private final PasswordPolicy passwordPolicy;
    private final int maxFailedAttempts;
    private final int lockMinutes;
    private final LoginLogRecorder loginLog;

    public AuthenticationService(AppUserRepository users,
                                 TenantDirectory tenants,
                                 TenantMembershipRepository memberships,
                                 PasswordEncoder passwordEncoder,
                                 JwtTokenService tokens,
                                 ClockProvider clock,
                                 LoginLogRecorder loginLog,
                                 @Value("${erp.iam.max-failed-attempts:5}") int maxFailedAttempts,
                                 @Value("${erp.iam.lock-minutes:15}") int lockMinutes,
                                 @Value("${erp.iam.password.min-length:8}") int minLength,
                                 @Value("${erp.iam.password.min-char-classes:3}") int minCharClasses,
                                 @Value("${erp.iam.password.max-age-days:90}") int maxAgeDays) {
        this.users = users;
        this.tenants = tenants;
        this.memberships = memberships;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
        this.clock = clock;
        this.loginLog = loginLog;
        this.maxFailedAttempts = maxFailedAttempts;
        this.lockMinutes = lockMinutes;
        this.passwordPolicy = new PasswordPolicy(minLength, minCharClasses, maxAgeDays);
    }

    /**
     * 登录结果 / 会话快照。
     *
     * @param token              JWT（切换租户后是**新签发**的 token，tid 已变）
     * @param tenantId           本次行动租户 id（= JWT 的 tid）
     * @param tenantCode         行动租户编码
     * @param tenantName         行动租户显示名（顶栏展示）
     * @param tenants            该账号的全部租户成员关系（含停用项；前端据此决定是否显示切换器）
     * @param username           用户名
     * @param displayName        显示名
     * @param roles              行动租户内的角色编码
     * @param permissions        行动租户内的权限码（已排序）
     * @param mustChangePassword 是否必须先改密（账号级，与租户无关）
     * @param passwordExpiresAt  密码过期时刻（ISO-8601；null = 不过期）
     * @param passwordRules      当前生效的密码规则
     */
    public record LoginResult(String token, long tenantId, String tenantCode, String tenantName,
                              List<TenantRef> tenants, String username, String displayName,
                              List<String> roles, List<String> permissions,
                              boolean mustChangePassword, String passwordExpiresAt,
                              List<String> passwordRules) {
    }

    /** 会话里的租户信息（{@code /auth/me} 用；没有 token 字段）。 */
    public record SessionTenant(long tenantId, String tenantCode, String tenantName,
                                List<TenantRef> tenants) {
    }

    /** 兼容旧调用：不带租户编码 = 使用账号的主租户。 */
    public LoginResult login(String username, String rawPassword) {
        return login(username, rawPassword, null);
    }

    /**
     * 登录（B1 追加可选 {@code tenantCode}）。
     *
     * <p><b>★ 本方法刻意不加 {@code @Transactional}</b>（M1 开发中真实踩到的坑）：
     * 登录失败时我们<b>要留下</b>失败计数的写入，然后抛业务异常。
     * 若整个方法包在一个事务里，抛异常会让事务回滚 ——
     * 计数被撤销，{@code failed_attempts} 永远是 0，
     * "连续失败 5 次锁定"这条安全策略<b>永远不会生效</b>，
     * 而单元测试若只断言异常类型也发现不了（它确实抛了 {@code IAM_BAD_CREDENTIALS}）。
     *
     * <p>不加事务后，每条 UPDATE 由 Spring 自动提交，语义正是我们要的：
     * "计一次失败"必须落库，哪怕随后返回错误。
     *
     * <p>代价是"计数"与"读回"不再原子。这不影响安全性 ——
     * 计数用 SQL 自增（{@code failed_attempts + 1}）而非读-改-写，并发爆破时不会丢计数；
     * 极端并发下判定"是否刚触发锁定"可能略有抖动，但锁本身由数据库列保证。
     */
    public LoginResult login(String username, String rawPassword, String tenantCode) {
        String name = username == null ? "" : username.trim();
        if (name.isEmpty() || rawPassword == null || rawPassword.isEmpty()) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR, "用户名与密码不能为空");
        }

        // ★ V2-7 FR-TNT-04 / B1：登录是"唯一一个还不知道租户就要读库"的入口。
        //   用户名是全局唯一的（app_users.username），但**按用户名查行**这一步
        //   本身就要读 app_users，而那张表受租户过滤 —— 于是这里必须显式跨租户。
        //
        //   为什么这不是后门：① 它只读"用户名 → 账号"这一次查找，定位到账号后
        //   **立即**改用该账号自己的租户绑定（见下面的 callIn）；② 它不经过
        //   任何业务接口，只由本方法调用。B1 改为 findByUsernameGlobal：
        //   只取账号行，**不装载角色** —— 角色必须在目标租户下单独装载。
        AppUser user = users.findByUsernameGlobal(name).orElse(null);
        if (user == null) {
            // 不做计数（无主可记），但仍走一次密码编码以拉平响应时间差异，
            // 避免通过"响应快慢"反推用户名是否存在。
            passwordEncoder.matches(rawPassword, "$2a$10$abcdefghijklmnopqrstuv");
            throw new BusinessException(ErrorCode.IAM_BAD_CREDENTIALS, "用户名或密码错误");
        }
        // 账号级校验与计数落在**主租户**；目标租户的选择与角色装载在 verify 之后。
        return TenantContext.callIn(user.tenantId(), () -> {
            AppUser verified = verifyAndRecord(user, rawPassword, name);
            TenantRef target = resolveTargetTenant(verified, tenantCode);
            return buildSession(verified, target);
        });
    }

    /**
     * 账号与密码校验 + 失败/成功计数（调用方保证已绑定该账号的**主租户**）。
     *
     * @return 刚记录完登录成功的账号行（含最新的密码过期信息）
     */
    private AppUser verifyAndRecord(AppUser user, String rawPassword, String name) {
        if (!user.enabled()) {
            loginLog.record(user.id(), name, false, "账号已停用");
            throw new BusinessException(ErrorCode.IAM_ACCOUNT_DISABLED, "账号已停用：" + name);
        }
        if (user.isLockedAt(nowInstant())) {
            loginLog.record(user.id(), name, false, "账号已锁定");
            throw new BusinessException(ErrorCode.IAM_ACCOUNT_LOCKED,
                    "账号已锁定，请稍后重试：%s（解锁时间 %s）"
                            .formatted(name, user.lockedUntil()));
        }

        if (!passwordEncoder.matches(rawPassword, user.passwordHash())) {
            var now = clock.now();
            AppUser after = users.recordLoginFailure(user.id(), maxFailedAttempts,
                    now.plusMinutes(lockMinutes), now);
            loginLog.record(user.id(), name, false, "密码错误");
            if (after.isLockedAt(nowInstant())) {
                throw new BusinessException(ErrorCode.IAM_ACCOUNT_LOCKED,
                        "密码连续错误 %d 次，账号已锁定 %d 分钟：%s"
                                .formatted(maxFailedAttempts, lockMinutes, name));
            }
            throw new BusinessException(ErrorCode.IAM_BAD_CREDENTIALS,
                    "用户名或密码错误（剩余尝试 %d 次）"
                            .formatted(Math.max(0, maxFailedAttempts - after.failedAttempts())));
        }

        users.recordLoginSuccess(user.id(), clock.now());
        loginLog.record(user.id(), name, true, null);
        return users.findByIdGlobal(user.id()).orElse(user);
    }

    /**
     * 选目标租户：缺省主租户；显式编码则必须是成员。
     *
     * <p>未知编码与非成员返回<b>同一个</b>错误码 {@code IAM_TENANT_NOT_MEMBER}：
     * 已登录用户可以据此知道"这个编码不是我的"，但不该能借它遍历系统里有哪些租户。
     */
    private TenantRef resolveTargetTenant(AppUser user, String tenantCode) {
        final TenantRef target;
        if (tenantCode == null || tenantCode.isBlank()) {
            target = tenants.byId(user.tenantId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.IAM_TENANT_DISABLED,
                            "账号的主租户不存在或已停用"));
        } else {
            String code = tenantCode.trim();
            target = tenants.byCode(code)
                    .filter(t -> memberships.isMember(user.id(), t.id()))
                    .orElseThrow(() -> new BusinessException(ErrorCode.IAM_TENANT_NOT_MEMBER,
                            "当前账号不是租户「%s」的成员".formatted(code)));
        }
        if (!target.enabled()) {
            throw new BusinessException(ErrorCode.IAM_TENANT_DISABLED,
                    "租户已停用，请联系平台管理员：%s（%s）".formatted(target.name(), target.code()));
        }
        // 主租户也必须真的有成员关系（V17 的回填/触发器保证；这里再校验一次，
        // 让"有人手工删掉成员行"表现为登录被拒，而不是后续权限装载出空集）。
        if (!memberships.isMember(user.id(), target.id())) {
            throw new BusinessException(ErrorCode.IAM_TENANT_NOT_MEMBER,
                    "当前账号不是租户「%s」的成员".formatted(target.code()));
        }
        return target;
    }

    /**
     * 组装会话：在**目标租户**下装载角色/权限，并签发带该 tid 的 JWT。
     *
     * <p>这是 B1 的核心修正点：旧实现从系统旁路查出的账号上直接取角色，
     * 多租户成员会拿到所有租户的角色并集。现在角色只在目标租户里查，
     * 切换后沿用目标租户的授权。
     */
    private LoginResult buildSession(AppUser account, TenantRef target) {
        AppUser acting = TenantContext.callIn(target.id(),
                () -> users.withRolesAndPermissions(account).actingAs(target.id()));
        List<TenantRef> visibleTenants = memberships.membershipsOf(account.id());
        return new LoginResult(
                tokens.issue(acting),
                acting.tenantId(),
                target.code(),
                target.name(),
                visibleTenants,
                acting.username(),
                acting.displayName(),
                acting.roles(),
                acting.permissions().stream().sorted().toList(),
                passwordPolicy.mustChange(account, nowInstant()).isPresent(),
                account.passwordExpiresAt() == null ? null : account.passwordExpiresAt().toString(),
                passwordPolicy.describe());
    }

    /**
     * 切换租户（B1 · FR-TNT-07）：校验成员关系与租户状态后**重新签发** JWT。
     *
     * <p><b>★ 为什么必须重新签发而不是让前端改显示</b>：租户是 JWT 的 {@code tid}
     * 声明，服务端每次请求都用它绑定上下文。只改前端显示会让"界面写着租户 B、
     * 实际读写租户 A"同时成立 —— 这是最危险的一类假象。新 token 的 tid 变化是
     * 本接口唯一有意义的副作用。
     *
     * <p>旧 token 在有效期内仍然有效（JWT 无状态），指向旧租户；前端必须用响应里的
     * 新 token 覆盖本地存储。服务端不需要、也无法吊销旧 token —— 这和无状态登出
     * 的既定取舍一致。
     */
    public LoginResult switchTenant(long userId, String tenantCode) {
        if (tenantCode == null || tenantCode.isBlank()) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR, "租户编码不能为空");
        }
        AppUser account = users.findByIdGlobal(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.IAM_UNAUTHENTICATED,
                        "账号不存在或已失效，请重新登录"));
        if (!account.enabled()) {
            throw new BusinessException(ErrorCode.IAM_ACCOUNT_DISABLED,
                    "账号已停用：" + account.username());
        }
        TenantRef target = resolveTargetTenant(account, tenantCode);
        return buildSession(account, target);
    }

    /**
     * 会话里的租户信息（{@code /auth/me}）。
     *
     * <p>入参是 {@link #resolve(String)} 返回的"以行动租户身份"的账号。
     */
    public SessionTenant tenancyOf(AppUser acting) {
        TenantRef current = tenants.byId(acting.tenantId())
                .orElseThrow(() -> new BusinessException(ErrorCode.IAM_TENANT_DISABLED,
                        "当前租户不存在或已停用"));
        return new SessionTenant(current.id(), current.code(), current.name(),
                memberships.membershipsOf(acting.id()));
    }

    /**
     * 修改自己的密码（FR-IAM-06）。
     *
     * <p><b>必须校验旧密码</b>：否则任何拿到 token 的人（token 泄露、
     * 共用电脑上没退出的会话）都能直接改掉密码把人踢出去。
     *
     * <p>改密成功即：写新哈希、按策略设新的过期时刻、清强制改密标记、
     * 清零失败计数并解锁（见 {@code AppUserRepository.updatePassword}）。
     *
     * <p><b>★ B1：账号级写回必须落在主租户</b>。切换租户后当前请求绑定的是
     * 行动租户，而 {@code app_users} 行的 {@code tenant_id} 是主租户；
     * 按行动租户 UPDATE 会匹配 0 行（表现为"改密成功但密码没变"）。
     */
    @Transactional
    public void changePassword(long userId, String oldPassword, String newPassword) {
        AppUser user = users.findByIdGlobal(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.IAM_USER_NOT_FOUND,
                        "账号不存在：id=" + userId));
        if (!passwordEncoder.matches(oldPassword, user.passwordHash())) {
            // 用 IAM_BAD_CREDENTIALS 而不是自造码：语义就是"旧密码不对"
            throw new BusinessException(ErrorCode.IAM_BAD_CREDENTIALS, "旧密码不正确");
        }
        if (passwordEncoder.matches(newPassword, user.passwordHash())) {
            throw new BusinessException(ErrorCode.IAM_PASSWORD_WEAK,
                    "新密码不能与旧密码相同");
        }
        passwordPolicy.check(newPassword).ifPresent(reason -> {
            throw new BusinessException(ErrorCode.IAM_PASSWORD_WEAK, reason);
        });
        TenantContext.runIn(user.tenantId(), () ->
                users.updatePassword(userId, passwordEncoder.encode(newPassword),
                        passwordPolicy.expiryOf(nowInstant()), false));
    }

    /** 管理员重置他人密码：按策略校验、置强制改密标记（不记录明文）。 */
    @Transactional
    public void resetPassword(long userId, String newPassword) {
        passwordPolicy.check(newPassword).ifPresent(reason -> {
            throw new BusinessException(ErrorCode.IAM_PASSWORD_WEAK, reason);
        });
        if (!users.resetPassword(userId, passwordEncoder.encode(newPassword),
                passwordPolicy.expiryOf(nowInstant()))) {
            throw new BusinessException(ErrorCode.IAM_USER_NOT_FOUND,
                    "账号不存在：id=" + userId);
        }
    }

    /** 密码策略（供接口把规则透给前端，也供测试断言"可配置"生效）。 */
    public PasswordPolicy passwordPolicy() {
        return passwordPolicy;
    }

    /**
     * 某账号是否必须先改密（供 {@code PasswordExpiryFilter}）。
     *
     * <p>过滤器只需要"要不要拦"，不该自己理解 {@code mustChangePassword} 与
     * {@code password_expires_at} 的组合语义（两处实现必然漂移）。
     *
     * <p>B1：改用全局按 id 读 —— 密码过期是账号级状态，切换租户后仍必须拦截。
     */
    public Optional<String> mustChangePasswordReason(long userId) {
        return users.findByIdGlobal(userId)
                .flatMap(u -> passwordPolicy.mustChange(u, nowInstant()));
    }

    /**
     * 按 token 解析当前用户（每次请求都回库取角色，禁用/改权限立即生效）。
     *
     * <p><b>★ B1 的三道校验（缺一不可）</b>：
     * <ol>
     *   <li>账号存在且启用；</li>
     *   <li>token 的 {@code tid} 是该账号的**成员租户**（主租户或额外成员关系）；</li>
     *   <li>该租户仍然**启用**。</li>
     * </ol>
     * 第 2 条替换了 V2-7 的"tid == app_users.tenant_id"：多租户成员切换后，
     * tid 可以是主租户之外的成员租户。它仍然防住"手工拼 claim 指向任意租户"，
     * 因为成员关系在库里。
     * 第 3 条让"停用租户"立即失效：不必等 token 过期，下一个请求就是 401。
     *
     * <p>角色只在 tid 对应的租户里装载，不会出现跨租户权限并集。
     */
    @Transactional(readOnly = true)
    public Optional<AppUser> resolve(String token) {
        return tokens.verifyClaims(token).flatMap(principal -> {
            if (principal.tenantId() == null) {
                return Optional.empty();
            }
            AppUser account = users.findByUsernameGlobal(principal.username()).orElse(null);
            if (account == null || !account.enabled()) {
                return Optional.empty();
            }
            if (!memberships.isMember(account.id(), principal.tenantId())) {
                return Optional.empty();
            }
            TenantRef tenant = tenants.byId(principal.tenantId()).orElse(null);
            if (tenant == null || !tenant.enabled()) {
                return Optional.empty();
            }
            AppUser acting = TenantContext.callIn(principal.tenantId(),
                    () -> users.withRolesAndPermissions(account).actingAs(principal.tenantId()));
            return Optional.of(acting);
        });
    }

    /**
     * 按用户名取用户（供权限切面 / 审计角色使用，保证以数据库权限为准）。
     *
     * <p>B1：请求可能以"非主租户"身份行动，因此不能按 {@code app_users.tenant_id}
     * 直接查行；改为"全局找账号 + 校验当前行动租户的成员关系 + 在行动租户装载角色"。
     * 当前租户上下文由过滤器绑定，缺失会大声失败（不回落默认租户）。
     */
    @Transactional(readOnly = true)
    public Optional<AppUser> resolveUsername(String username) {
        long acting = TenantContext.require();
        AppUser account = users.findByUsernameGlobal(username).orElse(null);
        if (account == null || !memberships.isMember(account.id(), acting)) {
            return Optional.empty();
        }
        return Optional.of(users.withRolesAndPermissions(account).actingAs(acting));
    }

    /**
     * 按 id 取用户（供 {@code ActorContext} 校验"转办目标人是否具备节点权限"）。
     *
     * <p>与 {@link #resolveUsername} 同一语义：目标人必须是**当前行动租户**的成员，
     * 否则转办会把人带进一个他并不属于的租户。
     */
    @Transactional(readOnly = true)
    public Optional<AppUser> resolveId(long userId) {
        long acting = TenantContext.require();
        AppUser account = users.findByIdGlobal(userId).orElse(null);
        if (account == null || !memberships.isMember(account.id(), acting)) {
            return Optional.empty();
        }
        return Optional.of(users.withRolesAndPermissions(account).actingAs(acting));
    }

    public int maxFailedAttempts() {
        return maxFailedAttempts;
    }

    public int lockMinutes() {
        return lockMinutes;
    }

    /**
     * 把可注入时钟的"本地业务时间"换算成绝对时刻，用于与数据库 TIMESTAMPTZ 比较。
     *
     * <p>业务日期一律来自 ClockProvider（计划书 §3.5），绝不直接用 now()；
     * 这里只做时区换算，仍然以注入时钟为唯一时间源。
     */
    private java.time.Instant nowInstant() {
        return clock.now().atZone(java.time.ZoneId.systemDefault()).toInstant();
    }
}
