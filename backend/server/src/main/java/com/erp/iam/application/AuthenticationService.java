package com.erp.iam.application;

import com.erp.iam.domain.AppUser;
import com.erp.iam.repository.AppUserRepository;
import com.erp.iam.security.JwtTokenService;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.time.ClockProvider;
import java.util.List;
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
 */
@Service
public class AuthenticationService {

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService tokens;
    private final ClockProvider clock;
    private final int maxFailedAttempts;
    private final int lockMinutes;

    public AuthenticationService(AppUserRepository users,
                                 PasswordEncoder passwordEncoder,
                                 JwtTokenService tokens,
                                 ClockProvider clock,
                                 @Value("${erp.iam.max-failed-attempts:5}") int maxFailedAttempts,
                                 @Value("${erp.iam.lock-minutes:15}") int lockMinutes) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
        this.clock = clock;
        this.maxFailedAttempts = maxFailedAttempts;
        this.lockMinutes = lockMinutes;
    }

    /**
     * 登录结果。
     *
     * @param token        JWT
     * @param username     用户名
     * @param displayName  显示名
     * @param roles        角色编码
     * @param permissions  权限码
     */
    public record LoginResult(String token, String username, String displayName,
                              List<String> roles, List<String> permissions) {
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
        return new LoginResult(tokens.issue(fresh), fresh.username(), fresh.displayName(),
                fresh.roles(), fresh.permissions().stream().sorted().toList());
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
