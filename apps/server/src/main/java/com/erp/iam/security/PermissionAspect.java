package com.erp.iam.security;

import com.erp.iam.application.AuthenticationService;
import com.erp.iam.domain.AppUser;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * {@link RequirePermission} 的执行点（FR-IAM-02）。
 *
 * <p>用 AOP 而不是拦截器：注解可以贴在单个方法上，
 * 授权判断与业务方法在同一个类里可见，评审时一眼能看见"这个接口要什么权限"。
 *
 * <p>失败语义严格区分（契约要求）：
 * <ul>
 *   <li>拿不到身份 → {@code 401 IAM_UNAUTHENTICATED}；</li>
 *   <li>有身份但没权限 → {@code 403 IAM_FORBIDDEN}。</li>
 * </ul>
 * 两者都返回 403 会让前端不知道该跳登录页还是提示无权限。
 */
@Aspect
@Component
public class PermissionAspect {

    private final AuthenticationService authentication;

    public PermissionAspect(AuthenticationService authentication) {
        this.authentication = authentication;
    }

    @Around("@annotation(com.erp.iam.security.RequirePermission) "
            + "|| @within(com.erp.iam.security.RequirePermission)")
    public Object check(ProceedingJoinPoint pjp) throws Throwable {
        String[] required = resolveRequirement(pjp);
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getPrincipal() == null) {
            throw new BusinessException(ErrorCode.IAM_UNAUTHENTICATED,
                    "未登录或登录已过期，请重新登录");
        }

        // 角色 ADMIN 视为全权（避免每新增一个权限码就要改一次 ADMIN 的授权数据）
        boolean isAdmin = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        if (isAdmin) {
            return pjp.proceed();
        }

        // 以数据库为准确认权限（token 里的角色只是签发时的快照）
        String username = String.valueOf(auth.getPrincipal());
        AppUser user = authentication.resolveUsername(username)
                .orElseThrow(() -> new BusinessException(ErrorCode.IAM_UNAUTHENTICATED,
                        "账号不存在或已失效：" + username));
        for (String permission : required) {
            if (user.hasPermission(permission)) {
                return pjp.proceed();
            }
        }
        throw new BusinessException(ErrorCode.IAM_FORBIDDEN,
                "无权执行该操作：需要权限 %s，当前角色 %s"
                        .formatted(String.join(" | ", required), user.roles()));
    }

    private String[] resolveRequirement(ProceedingJoinPoint pjp) {
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        RequirePermission onMethod = sig.getMethod().getAnnotation(RequirePermission.class);
        if (onMethod != null) {
            return onMethod.value();
        }
        RequirePermission onClass = pjp.getTarget().getClass().getAnnotation(RequirePermission.class);
        if (onClass != null) {
            return onClass.value();
        }
        // 理论上不会到这里（切点已限定），保守起见拒绝而非放行
        throw new BusinessException(ErrorCode.IAM_FORBIDDEN, "权限声明缺失，拒绝访问");
    }
}
