package com.erp.shared.tenant;

import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

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
 * <p><b>★ 为什么 {@code @Primary} 是必须的</b>：Spring Boot 的
 * {@code JdbcTemplateAutoConfiguration}/{@code DataSourceAutoConfiguration}
 * 会自动注册朴素实现。若这两个 Bean 不标 {@code @Primary}，注入点会因"多个候选"
 * 启动失败；而更糟的"修法"是删掉它们 —— 那样租户过滤会悄悄消失，
 * 而大多数用例只跑默认租户，照样绿。{@code TenantWiringIT} 用反射断言
 * "容器里的 JdbcTemplate / DataSource 实际类型就是这两个"，把这条前提变成门禁。
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
     * 第二道闸门：JDBC 层。
     *
     * <p>用 {@code DataSource} 包一层而不是改连接串：连接串里的
     * {@code currentSchema} 之类参数无法承载"每次请求不同租户"这件事。
     */
    @Bean
    @Primary
    public DataSource dataSource(DataSource original,
                                 TenantEnforcement enforcement) {
        return new TenantDataSource(original, enforcement.enabled());
    }

    /** 第一道闸门：{@code JdbcTemplate} 层。 */
    @Bean
    @Primary
    public org.springframework.jdbc.core.JdbcTemplate jdbcTemplate(
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
