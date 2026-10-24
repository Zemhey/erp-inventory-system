package com.erp.shared.security;

import java.util.Optional;
import java.util.Set;

/**
 * 当前请求方的身份视图（供审批、数据权限等跨模块能力使用）。
 *
 * <p><b>为什么不像其它地方那样直接读 {@code SecurityContextHolder}</b>：
 * 审批模块（{@code com.erp.aps}）需要一个"谁在操作"的抽象，
 * 而直接读 Spring Security 的上下文会带来两个问题：
 * ① 领域/应用层单测必须伪造一个 {@code Authentication} 塞进 ThreadLocal，
 * 忘清理就污染后续用例；② {@code aps} 会绑死在 Servlet 线程上，
 * 将来 PDA/小程序走上来的批处理入口无法复用。
 *
 * <p>把接口放 {@code shared}、实现放 {@code iam}，依赖方向保持单向
 * （与 {@code shared.scope.DataScopeProvider}、{@code shared.period.PeriodGuard} 同一做法）。
 *
 * <p><b>权限码用字符串集合</b>（如 {@code pur:approve}），与 {@code @RequirePermission}
 * 和数据库 {@code app_role_permissions.permission} 是同一套取值，
 * 不引入第二套主体体系。
 */
public interface ActorContext {

    /**
     * 当前操作人。
     *
     * @return 未登录 / 匿名请求时为空 —— 调用方必须按"拒绝"处理，
     *         绝不能把空当成"系统任务"而放行
     */
    Optional<Actor> current();

    /**
     * 按 id 取操作人（供"转办目标人是否具备节点权限"这类校验）。
     *
     * <p>刻意<b>不</b>提供 {@code currentOrSystem()} 之类的便捷方法：
     * 那会让"没有上下文时当作系统账号"变成一个随手可写的默认值。
     *
     * @param userId 用户 id
     * @return 用户不存在时为空
     */
    Optional<Actor> actorOf(long userId);

    /**
     * 操作人。
     *
     * @param id          用户 id
     * @param username    用户名
     * @param permissions 权限码并集（由角色展开）
     */
    record Actor(long id, String username, Set<String> permissions) {

        public Actor {
            permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
        }

        /** 是否具备某权限码，支持 {@code 模块:*} 通配（与 {@code AppUser#hasPermission} 同语义）。 */
        public boolean hasPermission(String permission) {
            if (permissions.contains(permission)) {
                return true;
            }
            int idx = permission.indexOf(':');
            return idx > 0 && permissions.contains(permission.substring(0, idx) + ":*");
        }
    }
}
