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
}
