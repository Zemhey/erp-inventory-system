package com.erp.sys.domain;

import java.time.OffsetDateTime;

/**
 * 登录日志（FR-V3-29）：成功 / 失败均记录。
 *
 * @param id          主键
 * @param userId      用户 id（失败且用户名不存在时为 null）
 * @param username    登录用户名
 * @param ip          来源 IP
 * @param userAgent   浏览器 / 客户端标识
 * @param loginAt     登录时间
 * @param success     是否成功
 * @param failReason  失败原因（成功为 null）
 */
public record LoginLog(
        long id,
        Long userId,
        String username,
        String ip,
        String userAgent,
        OffsetDateTime loginAt,
        boolean success,
        String failReason) {
}
