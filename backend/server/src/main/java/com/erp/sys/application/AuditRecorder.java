package com.erp.sys.application;

import com.erp.sys.domain.AuditAction;
import com.erp.sys.domain.AuditResult;
import com.erp.sys.repository.AuditLogRepository;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 审计写入器（FR-SYS-06）。
 *
 * <p><b>★ 这里有两个"必须"，都曾经是本项目踩过的坑的同族</b>：
 *
 * <ol>
 *   <li><b>必须用 {@code REQUIRES_NEW} 独立事务</b>。业务失败时 Spring 会回滚业务事务，
 *       若审计写在同一事务里，它会跟着一起消失 —— 结果是"失败的写操作永远查不到"，
 *       而失败恰恰是审计最该记录的部分。M1 阶段踩过同族坑：登录失败计数写在业务事务内，
 *       被一次回滚清零，导致"连续失败锁定"从未生效。
 *       独立事务还带来一个额外好处：审计写成功与否和业务提交解耦，
 *       不会因为审计表膨胀、索引重建而拖慢业务提交。</li>
 *   <li><b>必须吞掉自身的所有异常</b>。审计是旁路能力，<b>绝不允许</b>因为
 *       "审计写不进去"而让用户的订单提交失败。失败计数 {@link MetricsRecorder}
 *       会把这种情形暴露出来（{@code erp_audit_write_failed_total}），
 *       由告警去处理，而不是由用户的业务操作去承担。</li>
 * </ol>
 */
@Service
public class AuditRecorder {

    private static final Logger log = LoggerFactory.getLogger(AuditRecorder.class);

    private final AuditLogRepository repository;
    private final MetricsRecorder metrics;

    public AuditRecorder(AuditLogRepository repository, MetricsRecorder metrics) {
        this.repository = repository;
        this.metrics = metrics;
    }

    /**
     * 追加一条审计。
     *
     * <p>本方法<b>永不抛异常</b>。返回值只为测试与诊断服务（{@code -1} 表示写入失败）。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long record(Long actorId, String actorName, AuditAction action, String resourceType,
                       String resourceId, String bizRefNo, String beforeJson, String afterJson,
                       String traceId, String ip, AuditResult result, String errorCode) {
        try {
            // ★ 匿名请求（登录失败、actuator）没有租户上下文；审计是链路证据，不能因此丢失。
            //   这类写入显式归属默认租户（与 V13 的 DEFAULT 1 一致），而不是让 TenantSql
            //   抛 SYS_TENANT_CONTEXT_MISSING。业务写入永远在有绑定租户的请求里，
            //   走 else 分支，按真实租户落库（见 TenantInvariantsIT 的审计归属用例）。
            long id = com.erp.shared.tenant.TenantContext.isBound()
                    ? repository.insert(actorId, actorName, action, resourceType, resourceId,
                            bizRefNo, beforeJson, afterJson, traceId, ip, OffsetDateTime.now(),
                            result, errorCode)
                    : com.erp.shared.tenant.TenantContext.callIn(
                            com.erp.shared.tenant.TenantId.DEFAULT,
                            () -> repository.insert(actorId, actorName, action, resourceType,
                                    resourceId, bizRefNo, beforeJson, afterJson, traceId, ip,
                                    OffsetDateTime.now(), result, errorCode));
            metrics.auditWritten(action, result);
            return id;
        } catch (RuntimeException ex) {
            // ★ 审计失败不影响业务：只记日志 + 计数，绝不向上抛
            log.error("审计写入失败（业务不受影响）action={} resourceType={} resourceId={} "
                            + "traceId={} result={}", action, resourceType, resourceId,
                    traceId, result, ex);
            metrics.auditWriteFailed(action);
            return -1;
        }
    }

    /**
     * 取该资源上一次成功审计的快照，作为本次变更的 {@code before_json}。
     *
     * <p>取不到（首次创建、或资源不存在）返回 {@code null}：
     * <b>这是正常情况，不是错误</b>，因此这里刻意不抛异常，也不写告警日志。
     */
    public String beforeSnapshot(String resourceType, String resourceId) {
        try {
            Optional<String> previous = repository.lastSnapshot(resourceType, resourceId);
            return previous.orElse(null);
        } catch (RuntimeException ex) {
            log.debug("读取 before 快照失败（按 null 处理）resourceType={} resourceId={}",
                    resourceType, resourceId, ex);
            return null;
        }
    }
}
