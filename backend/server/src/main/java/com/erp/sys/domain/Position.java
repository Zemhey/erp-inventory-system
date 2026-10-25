package com.erp.sys.domain;

import java.time.OffsetDateTime;

/**
 * 岗位（挂在部门下，FR-V3-23）。
 *
 * @param id          主键
 * @param departmentId 所属部门 id
 * @param code        岗位编码（租户内唯一）
 * @param name        岗位名称
 * @param level       岗位级别
 * @param enabled     是否启用
 * @param createdAt   创建时间
 * @param updatedAt   更新时间
 */
public record Position(
        long id,
        long departmentId,
        String code,
        String name,
        int level,
        boolean enabled,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
