package com.erp.sys.domain;

import java.time.OffsetDateTime;

/**
 * 部门（组织架构树节点，FR-V3-23）。
 *
 * @param id            主键
 * @param parentId     父部门 id（根部门为 null）
 * @param code         部门编码（租户内唯一）
 * @param name         部门名称
 * @param sortNo       同级排序
 * @param leaderUserId 部门负责人（app_users.id，可空）
 * @param enabled      是否启用
 * @param createdAt    创建时间
 * @param updatedAt    更新时间
 */
public record Department(
        long id,
        Long parentId,
        String code,
        String name,
        int sortNo,
        Long leaderUserId,
        boolean enabled,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
