package com.erp.iam.application;

import com.erp.iam.domain.AppUser;
import com.erp.iam.domain.PasswordPolicy;
import com.erp.iam.repository.AppUserRepository;
import com.erp.iam.security.JwtTokenService;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.time.ClockProvider;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 认证应用服务（FR-IAM-01）：登录、登出（JWT 无状态，登出即客户端丢弃 token）。
 *
 * <p><b>密码错误锁定</b>：连续失败 {@code maxFailedAttempts} 次后锁定
 * {@code lockMinutes} 分钟。计数与锁定时间存在数据库里，因此多实例部署时共享、
 * 服务重启也不会被绕过 —— 放在内存里的计数器只要重启一次就清零，爆破者很容易利用。
 *
 * <p><b>防用户名枚举</b>："用户不存在"与"密码错误"对外返回<b>同一个</b>错误码
 * （{@code IAM_BAD_CREDENTIALS}），否则攻击者可以先测出哪些用户名存在，
 * 再针对性地爆破。区别只在内部计数上：不存在的用户无从计数，但对外表现一致。
 *
 * <p><b>FR-IAM-06 的登录策略（自上而下依次判定）</b>：
 * <ol>
 *   <li>账号停用 → {@code IAM_ACCOUNT_DISABLED}；</li>
 *   <li>账号锁定中 → {@code IAM_ACCOUNT_LOCKED}（不看密码对错，先挡住）；</li>
 *   <li>密码校验 → 失败计数 + 达到阈值即锁；</li>
 *   <li><b>密码过期 / 强制改密 → 仍然签发 token</b>，但登录响应带
 *       {@code mustChangePassword=true}，且除改密与 {@code /auth/me} 外的所有请求
 *       被 {@code PasswordExpiryFilter} 拦成 {@code IAM_PASSWORD_EXPIRED}。</li>
 * </ol>
 * 第 4 条的取舍：若登录直接拒绝过期用户，他就无法调用改密接口（改密要登录）
 * —— 死锁。所以必须"发 token 但不放行业务接口"。
 */
@Service
public class AuthenticationService {

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService tokens;
    private final ClockProvider clock;
    private final PasswordPolicy passwordPolicy;
    private final int maxFailedAttempts;
    private final int lockMinutes;

    public AuthenticationService(AppUserRepository users,
                                 PasswordEncoder passwordEncoder,
                                 JwtTokenService tokens,
                                 ClockProvider clock,
                                 @Value("${erp.iam.max-failed-attempts:5}") int maxFailedAttempts,
                                 @Value("${erp.iam.lock-minutes:15}") int lockMinutes,
                                 @Value("${erp.iam.password.min-length:8}") int minLength,
                                 @Value("${erp.iam.password.min-char-classes:3}") int minCharClasses,
                                 @Value("${erp.iam.password.max-age-days:90}") int maxAgeDays) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
        this.clock = clock;
        this.maxFailedAttempts = maxFailedAttempts;
        this.lockMinutes = lockMinutes;
        this.passwordPolicy = new PasswordPolicy(minLength, minCharClasses, maxAgeDays);
    }

    /**
     * 登录结果。
     *
     * @param token              JWT
     * @param username           用户名
     * @param displayName        显示名
     * @param roles              角色编码
     * @param permissions        权限码
     * @param mustChangePassword 是否必须先改密（FR-IAM-06）。前端据此直接跳改密页，
     *                           而不是等他点开业务页面收到一串 403 才反应过来
     * @param passwordExpiresAt  密码过期时刻（ISO-8601；null = 不过期）
     * @param passwordRules      当前生效的密码规则（前端展示在改密页，免得用户反复试错）
     */
    public record LoginResult(String token, String username, String displayName,
                              List<String> roles, List<String> permissions,
                              boolean mustChangePassword, String passwordExpiresAt,
                              List<String> passwordRules) {
    }

    /**
     * 登录。
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
    public LoginResult login(String username, String rawPassword) {
        String name = username == null ? "" : username.trim();
        if (name.isEmpty() || rawPassword == null || rawPassword.isEmpty()) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR, "用户名与密码不能为空");
        }

        AppUser user = users.findByUsername(name).orElse(null);
        if (user == null) {
            // 不做计数（无主可记），但仍走一次密码编码以拉平响应时间差异，
            // 避免通过"响应快慢"反推用户名是否存在。
            passwordEncoder.matches(rawPassword, "$2a$10$abcdefghijklmnopqrstuv");
            throw new BusinessException(ErrorCode.IAM_BAD_CREDENTIALS, "用户名或密码错误");
        }
        if (!user.enabled()) {
            throw new BusinessException(ErrorCode.IAM_ACCOUNT_DISABLED, "账号已停用：" + name);
        }
        if (user.isLockedAt(nowInstant())) {
            throw new BusinessException(ErrorCode.IAM_ACCOUNT_LOCKED,
                    "账号已锁定，请稍后重试：%s（解锁时间 %s）"
                            .formatted(name, user.lockedUntil()));
        }

        if (!passwordEncoder.matches(rawPassword, user.passwordHash())) {
            var now = clock.now();
            AppUser after = users.recordLoginFailure(user.id(), maxFailedAttempts,
                    now.plusMinutes(lockMinutes), now);
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
        AppUser fresh = users.findById(user.id()).orElse(user);
        boolean mustChange = passwordPolicy.mustChange(fresh, nowInstant()).isPresent();
        return new LoginResult(tokens.issue(fresh), fresh.username(), fresh.displayName(),
                fresh.roles(), fresh.permissions().stream().sorted().toList(),
                mustChange,
                fresh.passwordExpiresAt() == null ? null : fresh.passwordExpiresAt().toString(),
                passwordPolicy.describe());
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
     * @param userId      当前用户
     * @param oldPassword 旧密码（校验用）
     * @param newPassword 新密码（按策略校验）
     */
    @Transactional
    public void changePassword(long userId, String oldPassword, String newPassword) {
        AppUser user = users.findById(userId)
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
        users.updatePassword(userId, passwordEncoder.encode(newPassword),
                passwordPolicy.expiryOf(nowInstant()), false);
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
     */
    public Optional<String> mustChangePasswordReason(long userId) {
        return users.findById(userId)
                .flatMap(u -> passwordPolicy.mustChange(u, nowInstant()));
    }

    /** 按 token 解析当前用户（每次请求都回库取角色，禁用/改权限立即生效）。 */
    @Transactional(readOnly = true)
    public java.util.Optional<AppUser> resolve(String token) {
        return tokens.verify(token).flatMap(users::findByUsername);
    }

    /** 按用户名取用户（供权限切面使用，保证以数据库权限为准而非 token 快照）。 */
    @Transactional(readOnly = true)
    public java.util.Optional<AppUser> resolveUsername(String username) {
        return users.findByUsername(username);
    }

    /** 按 id 取用户（供 {@code ActorContext} 校验"转办目标人是否具备节点权限"）。 */
    @Transactional(readOnly = true)
    public java.util.Optional<AppUser> resolveId(long userId) {
        return users.findById(userId);
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
