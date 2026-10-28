package com.erp.shared.time;

import java.time.LocalDateTime;

/**
 * 演示/回填用的业务时间覆盖（默认关闭）。
 *
 * <p><b>为什么需要它</b>：库存流水 {@code inventory_ledger.occurred_at}、
 * 应收到期日 {@code customer_receivables.due_date}、成本层与毛利记录的归属时间，
 * 全部来自 {@link ClockProvider#now()}（见 ADR「业务时间必须可注入」）。
 * 也就是说 <b>把单据的 {@code biz_date} 倒签，并不会让这些派生账目回到过去</b> ——
 * 它们仍然落在"服务器现在几点"。结果就是：单据列表按业务日期分散在 12 个月里，
 * 而周转/经营/呆滞/账龄这些<b>按流水时间取数</b>的报表全挤在当月，
 * 曲线退化成一个点。演示库要铺满 12 个月的真实曲线，必须让"这条请求认为现在是什么时候"
 * 可被显式指定。
 *
 * <p><b>为什么不是测试里的 {@code TestClockConfig}</b>：那是 {@code @Profile("clock")}
 * 的单例状态时钟，只在 JUnit 里装配，进程外（seeder 走 HTTP）用不到。
 *
 * <p><b>安全边界（三条，缺一不可）</b>：
 * <ol>
 *   <li>开关 {@code erp.demo.clock-override-enabled} 默认 {@code false}，
 *       生产配置文件完全不提它 —— 未开启时 {@code now()} 恒等于真实系统时间，
 *       行为与改动前一个字都不差；</li>
 *   <li>只在<b>单个请求线程</b>内生效（{@link ThreadLocal}），请求结束即清，
 *       不会把时间"留在"任何后台线程上；</li>
 *   <li>设置它的 {@code DemoClockFilter} 排在 Spring Security 之后，
 *       因此只有<b>已通过认证</b>的请求才能生效，匿名请求拿不到这个能力。</li>
 * </ol>
 *
 * <p><b>它没有绕开任何业务钩子</b>：本类只回答"现在几点"，
 * 库存余额、预占、应收、应付、凭证、成本层依旧由各自的业务服务在事务里生成。
 * 这正是它与"SQL 直插"的分野 —— 直插能造出有行无账的假数据，本类造的是真账。
 */
public final class DemoClockOverride {

    /**
     * 当前请求线程被指定的业务时间；{@code null} 表示"没有覆盖，用真实时间"。
     *
     * <p>用 {@link InheritableThreadLocal} 的理由：{@code @Transactional} 与审计 AOP
     * 都在同一线程内同步执行，不需要跨线程传递；故意<b>不</b>用可继承版本，
     * 避免时间覆盖顺着线程池泄漏到别的请求上。
     */
    private static final ThreadLocal<LocalDateTime> PINNED = new ThreadLocal<>();

    private DemoClockOverride() {
    }

    /** 当前线程被覆盖的业务时间，未覆盖时返回 {@code null}。 */
    public static LocalDateTime pinned() {
        return PINNED.get();
    }

    /** 仅由 {@code DemoClockFilter} 调用；传 {@code null} 等价于 {@link #clear()}。 */
    public static void set(LocalDateTime at) {
        if (at == null) {
            PINNED.remove();
        } else {
            PINNED.set(at);
        }
    }

    public static void clear() {
        PINNED.remove();
    }
}
