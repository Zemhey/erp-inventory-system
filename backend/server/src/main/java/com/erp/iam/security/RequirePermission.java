package com.erp.iam.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明某接口所需的权限码（FR-IAM-02）。
 *
 * <p>为什么用注解而不在 {@link SecurityConfig} 里写 URL 匹配规则：
 * URL 规则把"谁能做什么"从业务代码里剥离出去，改了 controller 却忘了改配置
 * 就是权限漏洞，而且很难在 code review 时发现。注解贴在方法上，
 * 增删接口时权限声明会跟着走。
 *
 * <p>权限码格式 {@code 模块:动作}，例如 {@code pur:receive}、{@code mdm:read}，
 * 支持 {@code 模块:*} 通配（见 {@code AppUser#hasPermission}）。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePermission {
    /** 所需权限码。当前用户拥有其中任意一个即放行。 */
    String[] value();

    /**
     * 是否允许 {@code ADMIN} 角色短路放行（默认 true，保持既有语义）。
     *
     * <p><b>为什么需要关掉它的开关</b>：{@code PermissionAspect} 一直把
     * {@code ROLE_ADMIN} 当"全权"，否则每新增一个权限码都要改一次 ADMIN 的授权数据。
     * 但平台级权限 {@code tenant:manage} 不能享受这条捷径 —— 每个租户都有自己的
     * ADMIN，短路会让"普通租户管理员也调得通 /tenants"，而那正是 B1 要禁止的。
     * 关掉后，ADMIN 也必须<b>真的持有</b>该权限码（只有平台租户的 ADMIN 在 V17 里被种了它）。
     */
    boolean adminBypass() default true;
}
