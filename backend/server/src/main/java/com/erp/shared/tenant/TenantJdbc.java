package com.erp.shared.tenant;

import java.util.List;
import java.util.function.Function;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * 给 {@code PreparedStatementCreator} 那族的<b>替代写法</b>（FR-TNT-03）。
 *
 * <p><b>★ 为什么不是"把 19 处逐个手改"</b>：那能过，但每一处的手改都是一次
 * 改坏业务参数顺序的机会，而且以后新写的 INSERT 又会漏。
 *
 * <p><b>★ 这里提供两类替换，按"是否需要自增主键"分</b>：
 * <ol>
 *   <li>{@link #insertReturningId} —— 需要主键。用 {@code INSERT … RETURNING id}
 *       直接取回（比 {@code KeyHolder} + {@code prepareStatement(sql, new String[]{"id"})}
 *       更直白，也不依赖驱动的 {@code getGeneratedKeys} 行为）；</li>
 *   <li>{@link #update} —— 不需要主键（改状态、批量插入）。</li>
 * </ol>
 * 两者的 SQL 文本都是**方法参数**，因此会经过 {@link TenantAwareJdbcTemplate}
 * 的改写：{@code tenant_id} 列与字面量被追加进列清单 / VALUES 行尾，
 * 而原有占位符的位置与个数<b>一个都不变</b> —— 这也是选择"字面量注入"
 * 而不是"占位符注入"的核心收益（见 {@link TenantSql} 类注释）。
 *
 * <p><b>★ 为什么必须换成 {@code ?} 参数而不是继续 {@code ps.setXxx(1..n)}</b>
 * 后者是"按位置硬编码"的绑定，一旦有人在列清单中间插一列，所有下标就整体错位，
 * 而错位后的 SQL 往往<b>照样能跑</b>（只是把 A 字段写进 B 列）。这与"租户"无关，
 * 是本项目本来就存在的一类隐患；本泳道顺手把它们收敛到命名/顺序稳定的写法上。
 */
public final class TenantJdbc {

    private TenantJdbc() {
    }

    /**
     * 执行一条 {@code INSERT … RETURNING id}，返回新行主键。
     *
     * @param jdbc 租户感知模板
     * @param sql  含 {@code RETURNING id} 的 INSERT（列清单里<b>不要</b>写 tenant_id）
     * @param args 与 SQL 占位符一一对应的参数
     * @return 新行 id
     */
    public static long insertReturningId(TenantAwareJdbcTemplate jdbc, String sql, Object... args) {
        Long id = jdbc.queryForObject(sql, Long.class, args);
        if (id == null) {
            throw new IllegalStateException("INSERT … RETURNING id 未返回主键：" + sql.strip());
        }
        return id;
    }

    /**
     * 执行一条写语句，返回影响行数。
     *
     * @param jdbc 租户感知模板
     * @param sql  写语句（不含 tenant_id）
     * @param args 与占位符一一对应的参数
     */
    public static int update(TenantAwareJdbcTemplate jdbc, String sql, Object... args) {
        return jdbc.update(sql, args);
    }

    /** 读一行（供替换"手工 prepareStatement 之后 executeQuery"的少数读路径）。 */
    public static <T> List<T> query(TenantAwareJdbcTemplate jdbc, String sql,
                                    RowMapper<T> mapper, Object... args) {
        return jdbc.query(sql, mapper, args);
    }

    /**
     * 命名参数版本的包装（供参数很多、位置绑定易错的 INSERT 使用）。
     *
     * <p>命名参数最终也会被展开成带 {@code ?} 的文本，因此同样经过租户改写。
     */
    public static NamedParameterJdbcTemplate named(TenantAwareJdbcTemplate jdbc) {
        return new NamedParameterJdbcTemplate(jdbc);
    }

    /** 便捷构造：一对"键 → 值"。 */
    public static MapSqlParameterSource params() {
        return new MapSqlParameterSource();
    }

    /** 诊断：把改写器作用在一条 SQL 上（测试与排查用）。 */
    public static <T> T rewriteWith(long tenantId, String sql, Function<String, T> probe) {
        return probe.apply(TenantSql.rewrite(sql, tenantId).sql());
    }
}
