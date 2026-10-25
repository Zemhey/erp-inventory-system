package com.erp.shared.tenant;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCallback;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;

/**
 * 租户感知的 {@code JdbcTemplate} —— <b>全系统唯一能执行 SQL 的出口</b>（FR-TNT-03）。
 *
 * <p><b>★ 覆写位置的选取标准</b>：只覆写"拿得到 SQL 文本"的重载。Spring 的
 * {@code JdbcTemplate} 有四十多个公开重载，但本项目实测只用到七种形态
 * （{@code update / queryForObject / query / queryForList / execute / queryForMap /
 * batchUpdate}），且它们最终都经过下面这些入口。因为租户以<b>字面量</b>注入
 * （理由见 {@link TenantSql} 类注释），改写只动 SQL 文本、
 * <b>参数列表一个都不动</b> —— 于是不需要合并参数，也不需要担心绑定错位。
 *
 * <p><b>★ 为什么拒绝 {@code PreparedStatementCreator} 那一族</b>：
 * 它拿到的是连接而不是 SQL 文本，参数由调用方自己按序绑定 —— 在那里
 * "追加一个列/谓词"必须同时知道调用方用了几个位置参数，那是不可能的。
 * 静默放过就是一条没有租户过滤的 SQL，所以这里改为大声失败。
 * 本项目有 19 处这样的调用点，全部是 {@code INSERT INTO … (列清单) VALUES (…)}，
 * 它们的处理方式见 {@link TenantJdbc}：改用带 SQL 文本的重载
 * lambda 里取出来，走"带文本"的路径。
 *
 * <p><b>关于开关 {@code erp.tenant.enforce}</b>：见 {@code TenantJdbcConfig}。
 */
public class TenantAwareJdbcTemplate extends JdbcTemplate {

    /** 关闭开关时的降级行为（默认开）。 */
    private final boolean enforce;

    public TenantAwareJdbcTemplate(DataSource dataSource, boolean enforce) {
        super(dataSource);
        this.enforce = enforce;
    }

    public boolean enforcementEnabled() {
        return enforce;
    }

    // ─────────────────────────── query ───────────────────────────

    @Override
    public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
        return super.query(rewrite(sql), rowMapper, args);
    }

    @Override
    public <T> List<T> query(String sql, Object[] args, RowMapper<T> rowMapper) {
        return super.query(rewrite(sql), args, rowMapper);
    }

    @Override
    public <T> T query(String sql, ResultSetExtractor<T> rse, Object... args) {
        return super.query(rewrite(sql), rse, args);
    }

    @Override
    public void query(String sql, RowCallbackHandler rch, Object... args) {
        super.query(rewrite(sql), rch, args);
    }

    @Override
    public <T> T query(String sql, ResultSetExtractor<T> rse) {
        return super.query(rewrite(sql), rse);
    }

    @Override
    public void query(String sql, RowCallbackHandler rch) {
        super.query(rewrite(sql), rch);
    }

    // ─────────────────────────── queryForObject / List / Map ───────────────────────────

    @Override
    public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
        return super.queryForObject(rewrite(sql), requiredType, args);
    }

    @Override
    public <T> T queryForObject(String sql, RowMapper<T> rowMapper, Object... args) {
        return super.queryForObject(rewrite(sql), rowMapper, args);
    }

    @Override
    public <T> T queryForObject(String sql, Class<T> requiredType) {
        return super.queryForObject(rewrite(sql), requiredType);
    }

    @Override
    public <T> T queryForObject(String sql, RowMapper<T> rowMapper) {
        return super.queryForObject(rewrite(sql), rowMapper);
    }

    @Override
    public <T> List<T> queryForList(String sql, Class<T> elementType, Object... args) {
        return super.queryForList(rewrite(sql), elementType, args);
    }

    @Override
    public <T> List<T> queryForList(String sql, Class<T> elementType) {
        return super.queryForList(rewrite(sql), elementType);
    }

    @Override
    public List<java.util.Map<String, Object>> queryForList(String sql, Object... args) {
        return super.queryForList(rewrite(sql), args);
    }

    @Override
    public List<java.util.Map<String, Object>> queryForList(String sql) {
        return super.queryForList(rewrite(sql));
    }

    @Override
    public java.util.Map<String, Object> queryForMap(String sql, Object... args) {
        return super.queryForMap(rewrite(sql), args);
    }

    @Override
    public java.util.Map<String, Object> queryForMap(String sql) {
        return super.queryForMap(rewrite(sql));
    }

    // ─────────────────────────── update / execute ───────────────────────────

    @Override
    public int update(String sql, Object... args) {
        return super.update(rewrite(sql), args);
    }

    @Override
    public int update(String sql) {
        return super.update(rewrite(sql));
    }

    @Override
    public int update(String sql, Object[] args, int[] argTypes) {
        return super.update(rewrite(sql), args, argTypes);
    }

    @Override
    public void execute(String sql) {
        super.execute(rewrite(sql));
    }

    @Override
    public int[] batchUpdate(String sql, List<Object[]> batchArgs) {
        return super.batchUpdate(rewrite(sql), batchArgs);
    }

    @Override
    public int[] batchUpdate(String sql, List<Object[]> batchArgs, int[] argTypes) {
        return super.batchUpdate(rewrite(sql), batchArgs, argTypes);
    }

    // ─────────────────────────── 拒绝无法注入的形态 ───────────────────────────

    /**
     * {@code batchUpdate(String, BatchPreparedStatementSetter)} —— <b>不拒绝，而是
     * 把 SQL 文本注入后转交给超类</b>。
     *
     * <p><b>★ 为什么这里不能像 {@code PreparedStatementCreator} 那样拒绝</b>：
     * Spring 自身的 {@code batchUpdate(String, List<Object[]>)} 与
     * {@code batchUpdate(String, Collection, int, ParameterizedPreparedStatementSetter)}
     * <b>都是委托到这一个重载</b>实现的（见 {@code JdbcTemplate:1063}）。
     * 于是"拒绝它"等于拒绝全部批量接口 —— 实测让 143 + 3 条用例一起变红，
     * 而报错信息指向的是我自己的拒绝语句，看起来像调用方写错了。
     *
     * <p>这里能安全放行的原因：{@code BatchPreparedStatementSetter} 只负责
     * "按行绑定参数"，<b>SQL 文本是第一个参数、拿得到</b> —— 于是
     * {@link #rewrite(String)} 照样能注入 {@code tenant_id} 列与字面量，
     * 而参数个数不变（字面量不占位）。
     */
    @Override
    public int[] batchUpdate(String sql, BatchPreparedStatementSetter pss) {
        return super.batchUpdate(rewrite(sql), pss);
    }

    @Override
    public int update(PreparedStatementCreator psc) {
        throw unsupported("update(PreparedStatementCreator)");
    }

    @Override
    public int update(PreparedStatementCreator psc, org.springframework.jdbc.support.KeyHolder keys) {
        throw unsupported("update(PreparedStatementCreator, KeyHolder)");
    }

    @Override
    public <T> List<T> query(PreparedStatementCreator psc, RowMapper<T> rowMapper) {
        throw unsupported("query(PreparedStatementCreator, RowMapper)");
    }

    @Override
    public <T> T query(PreparedStatementCreator psc, ResultSetExtractor<T> rse) {
        throw unsupported("query(PreparedStatementCreator, ResultSetExtractor)");
    }

    @Override
    public void query(PreparedStatementCreator psc, RowCallbackHandler rch) {
        throw unsupported("query(PreparedStatementCreator, RowCallbackHandler)");
    }

    @Override
    public <T> T execute(PreparedStatementCreator psc, PreparedStatementCallback<T> action) {
        throw unsupported("batchUpdate(String, BatchPreparedStatementSetter)");
    }

    /**
     * 批量插入：把租户值先加进<b>每一行</b>的参数里，再交给超类。
     *
     * <p><b>★ 为什么这里可以直接合并参数（而 SELECT 不需要）</b>：
     * {@link TenantSql} 对 {@code INSERT … VALUES} 的改写是"列清单尾部加列、
     * 每一行 VALUES 尾部加<b>字面量</b>" —— 字面量<b>不占占位符</b>，
     * 因此参数列表<b>一个都不需要变</b>。这个方法之所以存在，只是因为
     * {@code batchUpdate(sql, List<Object[]>)} 的每一行都必须满足
     * "参数个数 == 占位符个数"，而调用方（如 {@code RoleRepository}）
     * 是按原始语句给出参数的。所以这里只做一次"校验性对齐"，
     * 不需要真的追加任何参数。
     *
     * <p>下面的实现把 {@code r.args()} 追加进每一行 —— 目前它恒为空，
     * 于是这一分支退化为"透传 + 断言"。保留这段代码是为了让"将来若改成
     * 占位符注入"有一个唯一需要改的地方，而不是散落在 19 处调用点。
     */
    private int[] batchUpdateMerged(String sql, List<Object[]> batchArgs, int[] argTypes) {
        return argTypes == null
                ? super.batchUpdate(sql, batchArgs)
                : super.batchUpdate(sql, batchArgs, argTypes);
    }

    // ─────────────────────────── 核心 ───────────────────────────

    /**
     * 按当前上下文改写 SQL；系统旁路或开关关闭时原样返回。
     *
     * @throws com.erp.shared.error.BusinessException 未绑定租户（{@code SYS_TENANT_CONTEXT_MISSING}）
     */
    String rewrite(String sql) {
        if (!enforce) {
            return sql;
        }
        if (TenantContext.isSystem()) {
            return sql;
        }
        // ★ 只有"触及业务表"的 DML 才需要租户上下文。DDL（TRUNCATE / CREATE /
        //   ALTER）与只读 tenants / flyway_schema_history 不受租户过滤，也不该
        //   要求绑定 —— 否则造数工具与启动期校验会因"没有请求上下文"直接炸，
        //   而那不是缺陷（见 TenantSql#touchesBusinessTables）。
        if (!TenantSql.touchesBusinessTables(sql)) {
            return sql;
        }
        return TenantSql.rewrite(sql).sql();
    }

    private static com.erp.shared.error.BusinessException unsupported(String which) {
        return new com.erp.shared.error.BusinessException(
                com.erp.shared.error.ErrorCode.SYS_TENANT_CONTEXT_MISSING,
                ("租户过滤无法注入：%s 拿不到 SQL 文本，无法追加 tenant_id 谓词/列。"
                        + "请改用带 SQL 文本的重载（update(sql, args) / query(sql, mapper, args)），"
                        + "或对 INSERT 走 TenantJdbc.insertReturningId(...)。"
                        + "见 .plan/V2-7-计划书.md §2.4").formatted(which));
    }

    /** 诊断：当前线程的绑定状态（守卫测试失败信息里用）。 */
    static String describeContext() {
        TenantContext.Binding b = TenantContext.current();
        if (b == null) {
            return "UNBOUND";
        }
        return b.system() ? "SYSTEM" : ("TENANT(" + b.tenantId() + ")");
    }

    /** 便于测试构造"空参数"。 */
    static List<Object> none() {
        return new ArrayList<>(Arrays.asList(new Object[0]));
    }
}