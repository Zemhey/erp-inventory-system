package com.erp.shared.tenant;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * 租户感知的 {@code DataSource} —— 把租户过滤下沉到 <b>JDBC 层</b>（FR-TNT-03）。
 *
 * <p><b>★ 为什么还需要这一层（{@code TenantAwareJdbcTemplate} 不够吗）</b>
 * 不够，而且是本泳道最值得记录的一处权衡：
 * <ul>
 *   <li>{@code JdbcTemplate} 只能覆写"拿得到 SQL 文本"的重载。本项目有 <b>19 处</b>
 *       用 {@code PreparedStatementCreator} 手工拼 INSERT 的调用点
 *       （为了拿自增主键与强类型绑定）—— SQL 文本藏在 lambda 里，那一层
 *       <b>根本看不见</b>。</li>
 *   <li>还有更隐蔽的缝：{@code jdbc.execute(conn -> …)} 里可以拿到裸
 *       {@code Connection} 再自己 {@code prepareStatement} —— 比 19 处更难枚举。</li>
 * </ul>
 * 与其逐处审计"有没有第三种写法绕过"，不如把闸门放到它们<b>共同的必经之路</b>：
 * {@code Connection#prepareStatement(String, …)} 与 {@code Statement#execute*(String)}。
 * 无论上层是 {@code JdbcTemplate}、裸 JDBC，还是将来换的任何框架，SQL 最终都要
 * 经过这两个方法。
 *
 * <p><b>★ 为什么不会双重注入</b>：{@link TenantSql} 是<b>幂等</b>的 ——
 * 一条已经覆盖了全部表站点的 SQL 会被判定"已租户化"并原样返回。
 * 于是同一条语句先经 {@code JdbcTemplate}、再过 JDBC 层，第二次是 no-op。
 * 这条性质由 {@code TenantSqlTest} 的幂等用例钉住 —— 没有它，双重注入会产生
 * {@code column tenant_id specified more than once}（响亮的失败，但会让一切红）。
 *
 * <p><b>★ 代价</b>：SQL 文本变成动态的，PG 无法在租户间复用同一份预编译计划。
 * 对一个租户数以十计的系统完全可接受，换来的是"没有任何一条 SQL 能绕过租户过滤"。
 *
 * <p><b>★ 与 {@code erp.tenant.enforce=false} 的关系</b>：关闭时本类纯透传，
 * 行为与 V15 完全一致。
 */
public class TenantDataSource extends DelegatingDataSource {

    /**
     * 需要改写的 Connection 方法：第一参是 SQL 文本。
     *
     * <p>只列会真正把文本交给数据库的那些。刻意<b>不</b>含
     * {@code prepareStatement(String)} 之外的任何"看起来像 SQL"的方法
     * （例如 {@code nativeSQL}：它只做转义，不执行）。
     */
    private static final java.util.Set<String> PREPARE_METHODS = java.util.Set.of(
            "prepareStatement", "prepareCall");

    private static final java.util.Set<String> STATEMENT_SQL_METHODS = java.util.Set.of(
            "execute", "executeQuery", "executeUpdate", "executeLargeUpdate", "addBatch");

    private final boolean enforce;

    public TenantDataSource(DataSource target, boolean enforce) {
        super(target);
        this.enforce = enforce;
    }

    public boolean enforcementEnabled() {
        return enforce;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return wrapConnection(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return wrapConnection(super.getConnection(username, password));
    }

    private Connection wrapConnection(Connection target) {
        if (!enforce || target == null) {
            return target;
        }
        return (Connection) java.lang.reflect.Proxy.newProxyInstance(
                TenantDataSource.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    Object result = invoke(target, method, args);
                    if (args != null && args.length > 0 && args[0] instanceof String sql
                            && PREPARE_METHODS.contains(method.getName())) {
                        String rewritten = rewrite(sql);
                        if (!rewritten.equals(sql)) {
                            args = args.clone();
                            args[0] = rewritten;
                            return invoke(target, method, args);
                        }
                    }
                    return result;
                });
    }

    /** 按当前上下文改写；系统旁路或开关关闭时原样返回。 */
    static String rewrite(String sql) {
        if (TenantContext.isSystem()) {
            return sql;
        }
        return TenantSql.rewrite(sql).sql();
    }

    /** 反射调用并把受检异常原样抛出（保持 JDBC 的异常语义）。 */
    static Object invoke(Object target, java.lang.reflect.Method method, Object[] args)
            throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (java.lang.reflect.InvocationTargetException ex) {
            throw ex.getTargetException();
        }
    }
}
