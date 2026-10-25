package com.erp.iam.domain;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * 登录账号（FR-IAM-01）。
 *
 * @param id             主键
 * @param username       用户名（唯一）
 * @param passwordHash   BCrypt 哈希，**永不出现在对外 DTO 里**
 * @param displayName    显示名
 * @param enabled        是否启用
 * @param failedAttempts 连续登录失败次数
 * @param lockedUntil    锁定截止时刻（null = 未锁定）
 * @param passwordExpiresAt 密码过期时刻（null = 不按时间过期，FR-IAM-06）
 * @param mustChangePassword 是否强制改密（管理员重置密码/首次登录时置真）
 * @param roles          角色编码
 * @param permissions    权限码（角色->权限展开后的并集）
 *
 * <p><b>为什么 lockedUntil 用 {@link Instant} 而不是 LocalDateTime</b>：
 * 数据库列是 {@code TIMESTAMPTZ}（带时区的绝对时刻）。JDBC 驱动**不允许**
 * 把 TIMESTAMPTZ 取成 {@code LocalDateTime} —— 后者没有时区，驱动无法判断该按哪个
 * 时区解释，于是直接抛
 * {@code Cannot convert the column of type TIMESTAMPTZ to requested type java.time.LocalDateTime}。
 * 这个错误被 Spring 包装成 {@code BadSqlGrammarException}（名字极具误导性，
 * 看起来像 SQL 写错了，实际是**类型不匹配**）。
 *
 * <p>M1 开发中真实踩到：只要账号一进入锁定状态，之后每一次"读该账号"都抛异常，
 * 于是锁定期内返回的是 500 而不是 {@code IAM_ACCOUNT_LOCKED}。
 * 用 {@code Instant} 表示"绝对时刻"是唯一语义正确的选择；
 * 与业务时钟（{@code LocalDateTime}）的比较通过显式时区换算完成。
 */
public record AppUser(long id, long tenantId, String username, String passwordHash, String displayName,
                      boolean enabled, int failedAttempts, Instant lockedUntil,
                      Instant passwordExpiresAt, boolean mustChangePassword,
                      List<String> roles, Set<String> permissions) {

    public AppUser {
        roles = roles == null ? List.of() : List.copyOf(roles);
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    }

    /**
     * 账号在给定时刻是否处于锁定状态。
     *
     * <p>判断依据是 {@code lockedUntil > now}，而不是一个布尔字段 ——
     * 这样锁定可以<b>自动过期</b>，不需要定时任务去解锁。
     *
     * @param nowInstant 当前时刻（由 ClockProvider 换算而来）
     */
    public boolean isLockedAt(Instant nowInstant) {
        return lockedUntil != null && lockedUntil.isAfter(nowInstant);
    }

    /** 是否具备某权限。支持 "模块:*" 通配。 */
    public boolean hasPermission(String permission) {
        if (permissions.contains(permission)) {
            return true;
        }
        int idx = permission.indexOf(':');
        if (idx > 0) {
            return permissions.contains(permission.substring(0, idx) + ":*");
        }
        return false;
    }
}
