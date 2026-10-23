package com.erp.iam.application;

import com.erp.iam.domain.AppUser;
import com.erp.shared.security.ActorContext;
import java.util.Optional;
import java.util.OptionalLong;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * {@link ActorContext} 的实现：把 Spring Security 的上下文翻译成不依赖框架的身份视图。
 *
 * <p><b>为什么每次都回库</b>（与 {@code JwtAuthenticationFilter} 同一考虑）：
 * token 里的角色/权限只是签发时的快照。管理员刚撤销某人的权限，
 * 若这里继续用 token 里的快照，下一个请求仍然放行 —— 那就是权限漏洞。
 * 代价是每请求一次主键查询，在 ERP 这个量级完全可接受。
 *
 * <p><b>空上下文返回 empty，绝不返回"系统账号"</b>：
 * 匿名可作为系统账号是权限系统最危险的默认值（后台任务可以不登录就审批）。
 * 需要"无 HTTP 上下文"的能力（如转办时的目标人权限校验）走右上角的
 * {@link #actorOf(long)} 显式查询，而不是让 {@code current()} 兜底。
 */
@Component
public class SecurityActorContext implements ActorContext {

    private final AuthenticationService authentication;

    public SecurityActorContext(AuthenticationService authentication) {
        this.authentication = authentication;
    }

    @Override
    public Optional<Actor> current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getPrincipal() == null) {
            return Optional.empty();
        }
        String username = String.valueOf(auth.getPrincipal());
        return authentication.resolveUsername(username).map(SecurityActorContext::toActor);
    }

    @Override
    public Optional<Actor> actorOf(long userId) {
        return authentication.resolveId(userId).map(SecurityActorContext::toActor);
    }

    private static Actor toActor(AppUser u) {
        return new Actor(u.id(), u.username(), u.permissions());
    }

    /**
     * 取当前用户 id（供数据权限、审批发起人使用）。
     *
     * <p>空表示未登录 —— 调用方必须自行决定是 401 还是"什么都不许看"，
     * 而不是在这里给一个默认值。
     */
    public OptionalLong currentUserId() {
        return current().stream().mapToLong(Actor::id).findFirst();
    }
}
