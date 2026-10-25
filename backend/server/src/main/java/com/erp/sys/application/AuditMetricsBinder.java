package com.erp.sys.application;

import com.erp.sys.domain.AuditAction;
import com.erp.sys.domain.AuditResult;
import com.erp.sys.repository.AuditLogRepository;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * 把"审计表累计行数"绑成 Gauge（FR-SYS-09）。
 *
 * <p>为什么需要它：审计链路本身也会坏（表被锁、触发器写错、磁盘满）。
 * 一个 {@code erp.audit.logs.total} 指标如果长时间不涨，运维就能立刻发现
 * "业务在跑但审计停了" —— 这比任何日志告警都直接。
 *
 * <p>用 {@link PostConstruct} 在启动时注册一次：Gauge 持有的是<b>读取函数</b>，
 * 每次 scrape 时现算，因此数值总是最新，也不需要定时任务。
 */
@Component
public class AuditMetricsBinder {

    private final AuditLogRepository repository;
    private final MetricsRecorder metrics;

    public AuditMetricsBinder(AuditLogRepository repository, MetricsRecorder metrics) {
        this.repository = repository;
        this.metrics = metrics;
    }

    @PostConstruct
    void bind() {
        try {
            metrics.bindAuditLogsTotal("all", ignored ->
                    com.erp.shared.tenant.TenantContext.callAsSystem(repository::countAll),
                    repository);
        } catch (RuntimeException ex) {
            // 指标注册失败（如重复注册）不能拖垮启动
            org.slf4j.LoggerFactory.getLogger(AuditMetricsBinder.class)
                    .warn("审计行数指标注册失败（不影响业务）", ex);
        }
    }

    /**
     * 保留导出引用：{@code MeterRegistry} 与 {@code AuditAction} 在别处使用，
     * 这里显式声明一次避免 IDE 提示未使用（与 ArchitectureTest 的 TIME_TYPES 同风格）。
     */
    @SuppressWarnings("unused")
    private static final Class<?>[] REFS = {MeterRegistry.class, AuditAction.class, AuditResult.class};
}
