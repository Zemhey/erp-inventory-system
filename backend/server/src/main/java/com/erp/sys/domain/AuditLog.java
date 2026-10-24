package com.erp.sys.domain;

import java.time.OffsetDateTime;

/**
 * 一条审计记录（FR-SYS-06）。
 *
 * <p>字段顺序即"排障时最想先看到的信息"顺序：谁 → 什么时间 → 做了什么 → 对哪个对象 →
 * 结果如何 → 怎么把这次操作的全部日志捞出来。
 *
 * @param id           自增主键
 * @param actorId      操作者 id（匿名操作为 null）
 * @param actorName    操作者用户名（快照，账号改名后历史记录仍能读懂）
 * @param action       动作语义
 * @param resourceType 资源类型（PRODUCT / PURCHASE_ORDER / ...）
 * @param resourceId   资源主键（字符串，兼容非数字主键）
 * @param bizRefNo     业务单据号
 * @param beforeJson   变更前快照（JSON 文本；null 表示无可还原的旧值）
 * @param afterJson    变更后快照（JSON 文本）
 * @param traceId      贯穿标识
 * @param ip           来源 IP
 * @param occurredAt   发生时刻
 * @param result       结果
 * @param errorCode    失败时的业务错误码
 */
public record AuditLog(long id, Long actorId, String actorName, AuditAction action,
                       String resourceType, String resourceId, String bizRefNo,
                       String beforeJson, String afterJson, String traceId, String ip,
                       OffsetDateTime occurredAt, AuditResult result, String errorCode) {
}
