package com.erp.shared.tenant;

import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把租户过滤装到<b>两个</b>必经之路（FR-TNT-03）。
 *
 * <p><b>★ 两个 Bean 各自解决什么（缺一不可）</b>：
 * <table border="1">
 *   <caption>两道闸门</caption>
 *   <tr><th>Bean</th><th>拦住的写法</th><th>拦不住的</th></tr>
 *   <tr><td>{@link TenantAwareJdbcTemplate}</td>
 *       <td>所有"带 SQL 文本"的 {@code JdbcTemplate} 调用（本项目 366 处里的 347 处）</td>
 *       <td>{@code PreparedStatementCreator} 那一族（19 处 INSERT）</td></tr>
 *   <tr><td>{@link TenantDataSource}</td>
 *       <td><b>一切</b>最终到达 {@code Connection#prepareStatement} 的 SQL ——
 *           包括那 19 处、以及将来任何裸 JDBC 写法</td>
 *       <td>不需要再拦：它是最后一个出口</td></tr>
 * </table>
 * 两道闸门都用 {@link TenantSql}，而它是幂等的，所以同一条 SQL 经过两遍
 * 仍然只被改写一次（见 {@code TenantSql#alreadyScoped}）。
 *
 * <p><b>★ 为什么这个 Bean 刻意<b>不</b>标 {@code @Primary}</b>（V2-7 实测踩到）：
 * Spring Boot 的 {@code JdbcTemplateAutoConfiguration} 带
 * {@code @ConditionalOnMissingBean(JdbcTemplate.class)} —— 只要容器里已有一个
 * {@code JdbcTemplate} Bean（本 Bean 就是），自动配置会退让，正常启动时它是<b>唯一候选</b>。
 * 标上 {@code @Primary} 反而会与测试里的"计数包装"（{@code ReportIT.CountingConfig}
 * 也标了 {@code @Primary}）撞成"两个 primary"，整个 Web 上下文起不来
 * （实测 18 条报表/指标用例因此上下文失败）。去掉之后两种上下文都成立：
 * 正常启动用本 Bean；测试要换实现时由测试自己的 {@code @Primary} 胜出。
 *
 * <p>返回类型声明为 {@link TenantAwareJdbcTemplate}（而不是 {@code JdbcTemplate}）：
 * 容器里的 Bean 类型因此就是租户感知实现，守卫测试能直接断言
 * {@code enforcementEnabled()}；而仓储仍按接口 {@code JdbcTemplate} 注入，
 * 测试替换实现（计数/桩）不会被挡住。
 * {@code TenantWiringIT} 断言"容器里的 JdbcTemplate 实际类型是租户感知实现"，
 * 把这条前提变成门禁。
 *
 * <p><b>★ 开关 {@code erp.tenant.enforce}</b>（默认 {@code true}）：
 * 关闭时两者都退化为纯透传，用于线上事故紧急回退。关闭会打 {@code WARN}，
 * 且 {@code TenantEnforcementSwitchTest} 钉住"关掉之后跨租户确实可见" ——
 * 让这个开关本身被证明有效，而不是"写了没试过"。
 * 它<b>不</b>关闭数据库层的复合唯一约束与 INSERT 填充触发器（那些是 schema）。
 */
@Configuration
public class TenantJdbcConfig {

    private static final Logger log = LoggerFactory.getLogger(TenantJdbcConfig.class);

    /** 是否强制租户过滤。 */
    @Bean
    public TenantEnforcement tenantEnforcement(
            @Value("${erp.tenant.enforce:true}") boolean enforce) {
        if (!enforce) {
            log.warn("""
                    ════════════════════════════════════════════════════════════════
                    ★ erp.tenant.enforce=false：租户过滤已**关闭**
                      所有查询将返回全部租户的数据。此模式仅供事故回退，
                      绝不可用于生产，也不可用于任何隔离类用例。
                    ════════════════════════════════════════════════════════════════""");
        }
        return new TenantEnforcement(enforce);
    }

    /**
     * 第二道闸门：{@code JdbcTemplate} 层（{@link TenantAwareJdbcTemplate}）。
     *
     * <p><b>★ 这里必须先说清楚一个被放弃的方案，否则下一个读代码的人会以为漏了</b>：
     * 原本还设计了第三层 TenantDataSource（用动态代理包 JDBC
     * {@code Connection}，拦 {@code prepareStatement}）—— 理论上它能覆盖
     * "在 lambda 里自己 {@code prepareStatement}" 的那 19 处 INSERT。
     *
     * <p>放弃它的原因不是"没想到"，而是**Spring 的 bean 生命周期不允许**：
     * 把 {@code DataSource} 包一层并要求"原始 DataSource"作为参数，
     * 无论用 {@code @Qualifier} 还是 {@code ObjectProvider}，都会形成
     * {@code tenantDataSource → DataSource → tenantDataSource} 的自引用，
     * 实测报 {@code BeanCurrentlyInCreationException: unresolvable circular reference}，
     * 53 个测试类全红。而启动期就炸的隔离层等于没有隔离层。
     *
     * <p>于是那 19 处改为"把 SQL 文本提出来"（见 {@code TenantJdbc}）：这不是妥协，
     * 而是更好的解法 —— 文本可见之后，<b>断言</b>就能直接对着它做
     * （{@code TenantSqlGuardTest} 逐条检查"这条 INSERT 有没有 tenant_id 列"），
     * 而动态代理那种"运行时才生效"的拦截根本无法被断言。
     */
    @Bean
    public TenantAwareJdbcTemplate jdbcTemplate(
            DataSource dataSource, TenantEnforcement enforcement) {
        return new TenantAwareJdbcTemplate(dataSource, enforcement.enabled());
    }

    /**
     * 开关的载体。
     *
     * <p>做成 Bean 而不是把布尔值到处传：测试要能读到"当前是否强制"，
     * 而断言"强制开着"本身是一条验收（{@code TenantWiringIT}）。
     */
    public record TenantEnforcement(boolean enabled) {
    }
}
