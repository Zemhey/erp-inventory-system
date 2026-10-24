package com.erp.sys.application;

import com.erp.sys.domain.AuditAction;
import com.erp.sys.domain.AuditResult;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.ToDoubleFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 业务指标（FR-SYS-09）。
 *
 * <p><b>★ 为什么自定义指标，而不是只用 HTTP 指标</b>：
 * "接口 200 率 99.9%" 并不能回答"今天下了几张单""失败了几次"这类业务问题。
 * 本项目要的是可答辩的<b>业务可观测性</b>：下单数、失败数、对账差额。
 * 三者都从审计链路自然产生，不需要在各业务模块里到处埋点 ——
 * 这也正是"审计与可观测同属一期"的原因。
 *
 * <p><b>为什么用注入的 {@link MeterRegistry} 而不是静态全局</b>：
 * 静态注册表在测试里会跨上下文累积（同一指标被注册两次 → 启动报错或数值串味）。
 * 由 Spring 管理生命周期，测试上下文隔离时计数一起重建，断言才可重复。
 *
 * <p>指标清单：
 * <ul>
 *   <li>{@code erp_audit_written_total} —— 审计写入条数（action / result 维度）</li>
 *   <li>{@code erp_audit_write_failed_total} —— 审计自身写入失败数（应恒为 0，非 0 即告警）</li>
 *   <li>{@code erp_order_created_total} —— 下单数（orderType 维度）</li>
 *   <li>{@code erp_order_failed_total} —— 写操作失败数（orderType / errorCode 维度）</li>
 *   <li>{@code erp_reconcile_diff_abs} —— 最近一次对账的差额绝对值（收敛性看板）</li>
 *   <li>{@code erp_audit_logs_total} —— 审计表累计行数（gauge，验证审计链路没停）</li>
 * </ul>
 */
@Component
public class MetricsRecorder {

    private static final Logger log = LoggerFactory.getLogger(MetricsRecorder.class);

    /** 单据类资源：只有它们计入"下单数"。键同时包含单数与复数形式（路径兜底取到的是复数）。 */
    private static final Map<String, String> ORDER_TYPES = Map.ofEntries(
            Map.entry("PURCHASE_ORDER", "purchase"),
            Map.entry("SALES_ORDER", "sales"),
            Map.entry("RETURN_ORDER", "return"),
            Map.entry("INVENTORY", "inventory"),
            Map.entry("SETTLEMENT", "settlement"),
            Map.entry("PRODUCT", "product"),
            Map.entry("SKU", "sku"),
            Map.entry("SUPPLIER", "supplier"),
            Map.entry("CUSTOMER", "customer"),
            Map.entry("WAREHOUSE", "warehouse"),
            // 复数形式：AuditSafetyNetFilter 在处理器未被调用时按路径首段推导资源类型，
            // 拿到的是 /products → PRODUCTS。若不做这一步映射，"参数校验失败"这类
            // 最常见失败会落到 other 维度，失败数看板就失去了按业务域分组的能力。
            Map.entry("PRODUCTS", "product"),
            Map.entry("SKUS", "sku"),
            Map.entry("SUPPLIERS", "supplier"),
            Map.entry("CUSTOMERS", "customer"),
            Map.entry("WAREHOUSES", "warehouse"),
            Map.entry("PURCHASE_ORDERS", "purchase"),
            Map.entry("SALES_ORDERS", "sales"),
            Map.entry("RETURN_ORDERS", "return"),
            Map.entry("SETTLEMENTS", "settlement"));

    private final MeterRegistry registry;

    /** 对账差额用 Gauge 暴露：Gauge 需要"能随时读到的当前值"，故用 AtomicLong 持有。 */
    private final AtomicLong reconcileDiffAbs = new AtomicLong(0);
    private final Map<String, AtomicLong> auditLogsTotal = new ConcurrentHashMap<>();

    public MetricsRecorder(MeterRegistry registry) {
        this.registry = registry;
    }

    /** 审计写入成功/失败计数。 */
    public void auditWritten(AuditAction action, AuditResult result) {
        try {
            registry.counter("erp.audit.written",
                    "action", action.name(),
                    "result", result.name()).increment();
        } catch (RuntimeException ex) {
            // 指标绝不能影响主流程（与审计同一原则）
            log.debug("指标自增失败：erp.audit.written", ex);
        }
    }

    public void auditWriteFailed(AuditAction action) {
        try {
            registry.counter("erp.audit.write.failed", "action", action.name()).increment();
        } catch (RuntimeException ex) {
            log.debug("指标自增失败：erp.audit.write.failed", ex);
        }
    }

    /**
     * 业务写操作的成败计数（FR-SYS-09 的"下单数 / 失败数"）。
     *
     * <p>成功 + 动作为 CREATE 才计入"下单数"：审核/入库不是"下了一张单"，
     * 把它们也算进下单数会让经营看板上的单量虚高。
     *
     * <p><b>★ 指标名为什么是 {@code erp.order.placed} 而不是 {@code erp.order.created}</b>
     * （实测踩到的命名陷阱）：Micrometer 的 Prometheus 命名约定会<b>吃掉</b>名字里的
     * {@code created} 片段 —— 注册为 {@code erp.order.created} 的计数器在
     * {@code /actuator/prometheus} 里实际暴露成 {@code erp_order_total}，
     * 与"订单总数"撞名且完全看不出是"新建数"。于是断言 {@code erp_order_created_total}
     * 永远失败，而指标本身其实是对的 —— 这种"名字对不上"最难排查。
     * 换成 {@code placed} 后导出名是 {@code erp_order_placed_total}，表意与字面一致。
     */
    public void observeWrite(String resourceType, AuditAction action, AuditResult result,
                             String errorCode) {
        String type = ORDER_TYPES.getOrDefault(resourceType == null ? "" : resourceType, "other");
        try {
            if (result == AuditResult.SUCCESS && action == AuditAction.CREATE) {
                registry.counter("erp.order.placed", "orderType", type).increment();
            }
            if (result == AuditResult.FAIL) {
                registry.counter("erp.order.failed", "orderType", type,
                        "errorCode", errorCode == null ? "UNKNOWN" : errorCode).increment();
            }
        } catch (RuntimeException ex) {
            log.debug("指标自增失败：erp.order.*", ex);
        }
    }

    /**
     * 记录一次对账差额（绝对值）：差额应长期收敛到 0，非 0 是财务要看的信号。
     *
     * <p><b>★ 注册必须是幂等的</b>：Micrometer 对同名 Gauge 的重复注册<b>不是</b>空操作 ——
     * 在复合注册表下第二次注册会抛 {@code IllegalArgumentException}，
     * 而它发生在 HTTP 线程里就会把整个 {@code /actuator/prometheus} 变成 500
     * （本泳道实测踩到：第二次对账后指标端点直接 500，比没有指标更糟）。
     * 因此这里先用 {@code find} 判存在，只更新原子值、不重复注册。
     */
    public void reconcileDiff(long diff) {
        try {
            reconcileDiffAbs.set(Math.abs(diff));
            if (registry.find("erp.reconcile.diff.abs").gauge() != null) {
                return;
            }
            Gauge.builder("erp.reconcile.diff.abs", reconcileDiffAbs, AtomicLong::doubleValue)
                    .description("最近一次对账的差额绝对值（分）")
                    .register(registry);
        } catch (RuntimeException ex) {
            log.debug("指标记录失败：erp.reconcile.diff.abs", ex);
        }
    }

    /** 审计表行数 gauge（由启动期 {@code AuditMetricsBinder} 注册一次，之后只读不重注册）。 */
    public void bindAuditLogsTotal(String scope, ToDoubleFunction<Object> supplier, Object state) {
        String name = "erp.audit.logs.total." + scope;
        if (registry.find(name).gauge() != null) {
            // 同上：重复注册在复合注册表下会抛异常，而它会污染 /actuator/prometheus
            return;
        }
        Gauge.builder(name, state, supplier)
                .description("审计表累计行数（scope=" + scope + "）")
                .register(registry);
    }

    /** 供测试直接读取某个计数器值（避免测试自己解析 Prometheus 文本）。 */
    public double counterValue(String name, String... tags) {
        var counter = registry.find(name).tags(tags).counter();
        return counter == null ? 0d : counter.count();
    }

    public double gaugeValue(String name) {
        var gauge = registry.find(name).gauge();
        return gauge == null ? 0d : gauge.value();
    }

    /** 测试用：判断指标是否已注册（返回 200 时至少要有一个业务指标存在）。 */
    public boolean containsMetric(String namePart) {
        return registry.getMeters().stream()
                .anyMatch(m -> m.getId().getName().contains(namePart));
    }

    /** 在测试上下文里也可以用 SimpleMeterRegistry 手工构造（单元用例不依赖 Spring）。 */
    public static MetricsRecorder forTests() {
        return new MetricsRecorder(new SimpleMeterRegistry());
    }
}
