package com.erp.sys.domain;

import java.time.OffsetDateTime;

/**
 * 审计查询条件（FR-SYS-07）。
 *
 * <p><b>为什么放在 domain 而不是 repository</b>：它会被 api 层的 Controller 直接构造
 * （Controller 把 HTTP 参数翻译成查询条件），而分层规则禁止 api 依赖 repository。
 * 搬到 domain 后依赖方向重新变成 {@code api → domain ← repository}，
 * ArchUnit 的「控制器不得直连仓储」规则才不会被踩线。
 *
 * <p>六个维度全部可空，{@code null} 表示不参与过滤。刻意做成 record 而不是一堆同名重载：
 * 六参数的组合重载会迅速变成 {@code page(a,b,c,d,e,f,g)} 这种没人敢调的形状。
 *
 * @param actorId      操作者 id
 * @param actorName    操作者用户名
 * @param action       动作语义
 * @param resourceType 资源类型
 * @param resourceId   资源主键
 * @param bizRefNo     业务单据号
 * @param result       结果（SUCCESS / FAIL）
 * @param from         时间区间起点（含）
 * @param to           时间区间终点（不含）
 */
public record AuditQueryFilter(Long actorId, String actorName, AuditAction action,
                               String resourceType, String resourceId, String bizRefNo,
                               AuditResult result, OffsetDateTime from, OffsetDateTime to) {

    /** 空条件：不参与任何过滤。 */
    public static AuditQueryFilter none() {
        return new AuditQueryFilter(null, null, null, null, null, null, null, null, null);
    }
}
