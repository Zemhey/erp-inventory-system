package com.erp.sys.domain;

import java.time.OffsetDateTime;

/**
 * 系统参数（FR-V3-24）：按组展示的键值对，可编辑。
 *
 * @param id          主键
 * @param configGroup 参数分组（document / inventory / finance / notice）
 * @param configKey   参数键（分组内唯一）
 * @param configValue 参数值（按 valueType 解释）
 * @param valueType   值类型 STRING / INTEGER / BOOLEAN
 * @param remark      说明
 * @param createdAt   创建时间
 * @param updatedAt   更新时间
 */
public record SystemConfig(
        long id,
        String configGroup,
        String configKey,
        String configValue,
        String valueType,
        String remark,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
