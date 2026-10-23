package com.erp.nl.application;

import com.erp.iam.application.AuthenticationService;
import com.erp.iam.domain.AppUser;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 意图级权限校验（FR-SYS-04）。
 *
 * <p><b>为什么不直接在 Controller 上贴 {@code @RequirePermission}？</b>
 * 切面语义是"满足任一权限即放行"，而 NL 端点同时承载库存 / 单据 / 报表 / 主数据 / 建单，
 * 一个静态清单会让只有 {@code rpt:read} 的账号也被放进建单意图。
 * 精确判断必须发生在知道 intent 之后。
 *
 * <p><b>没有另造权限模型</b>：判定逻辑与 {@code PermissionAspect} 一致 ——
 * ROLE_ADMIN 短路；否则以数据库为准调用 {@link AppUser#hasPermission(String)}。
 * 这样"在页面上点按钮"与"用自然语言说一句话"走的是同一套 RBAC。
 */
@Component
public class NlAccessGuard {

    private final AuthenticationService authentication;

    public NlAccessGuard(AuthenticationService authentication) {
        this.authentication = authentication;
    }

    public void require(String permission) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getPrincipal() == null) {
            throw new BusinessException(ErrorCode.IAM_UNAUTHENTICATED,
                    "未登录或登录已过期，请重新登录");
        }
        boolean isAdmin = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        if (isAdmin) {
            return;
        }
        String username = String.valueOf(auth.getPrincipal());
        AppUser user = authentication.resolveUsername(username)
                .orElseThrow(() -> new BusinessException(ErrorCode.IAM_UNAUTHENTICATED,
                        "账号不存在或已失效：" + username));
        if (!user.hasPermission(permission)) {
            throw new BusinessException(ErrorCode.IAM_FORBIDDEN,
                    "自然语言指令被拒绝：需要权限 %s，当前角色 %s"
                            .formatted(permission, user.roles()));
        }
    }
}
