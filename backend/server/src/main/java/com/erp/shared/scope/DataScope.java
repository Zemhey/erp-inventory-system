package com.erp.shared.scope;

import java.util.List;
import java.util.Optional;

/**
 * 数据权限的**判定结果**（FR-IAM-04）。
 *
 * <p>这是"某个请求方能看到哪些行"的唯一表达，由 {@link DataScopeProvider} 在
 * 每次请求时从数据库解析出来，再注入到各模块的查询里。
 *
 * <p><b>★ 为什么过滤必须注入 SQL，而不是在 Controller 里过滤结果</b>：
 * <ul>
 *   <li>分页接口先查全量再在内存里筛，页面上会出现"第 1 页 3 条、第 2 页 0 条"
 *       这种由权限造成的假象，且 {@code total} 与实际可见条数不符；</li>
 *   <li>导出/统计/关联查询是另外几条代码路径，漏改一处就是漏洞 ——
 *       而攻击者恰好会挑这三条路径（蓝图 §7.1 的 L5 越权矩阵正是覆盖这 5 种方式）；</li>
 *   <li>前端过滤是体验，不是安全边界。任何人拿 token 直连 API 都能绕开它。</li>
 * </ul>
 *
 * <p><b>三种范围</b>：
 * <ul>
 *   <li>{@code ALL} —— 全量。未配置任何数据权限行时的默认值，也是显式放开的写法；</li>
 *   <li>{@code WAREHOUSE} —— 限定到若干仓库。多个仓库是"或"关系；</li>
 *   <li>{@code SELF} —— 只看本人创建的行（按 {@code created_by} 匹配）。</li>
 * </ul>
 *
 * <p><b>WAREHOUSE 与 SELF 同时配置时取交集</b>：既是这几个仓、又是我本人建的。
 * 交集语义是安全侧的保守选择 —— 两种约束各自代表一条独立授权，都不该被另一条放大。
 *
 * @param type           范围类型
 * @param warehouseIds   允许的仓库 id（{@code type=WAREHOUSE} 时有值，否则为空）
 * @param actorId        当前登录用户 id（{@code type=SELF} 时用于匹配 {@code created_by}）
 */
public record DataScope(ScopeType type, List<Long> warehouseIds, Long actorId) {

    /** 数据权限的三种范围。 */
    public enum ScopeType {
        /** 全量可见。 */
        ALL,
        /** 限定仓库。 */
        WAREHOUSE,
        /** 只看本人创建。 */
        SELF
    }

    public DataScope {
        warehouseIds = warehouseIds == null ? List.of() : List.copyOf(warehouseIds);
    }

    /** 无条件全量（未配置数据权限、或调用方是系统任务时使用）。 */
    public static DataScope all() {
        return new DataScope(ScopeType.ALL, List.of(), null);
    }

    /** 只看本人。 */
    public static DataScope self(long actorId) {
        return new DataScope(ScopeType.SELF, List.of(), actorId);
    }

    /** 限定仓库集合。 */
    public static DataScope warehouses(List<Long> warehouseIds) {
        return new DataScope(ScopeType.WAREHOUSE, warehouseIds, null);
    }

    /** 是否不做任何收窄 —— 调用方可以据此直接跳过 SQL 片段。 */
    public boolean isAll() {
        return type == ScopeType.ALL;
    }

    /**
     * 是否需要按 {@code created_by} 收窄。
     *
     * <p>刻意不写成 {@code type == SELF}：会签/转办会让"按本人"与"按仓库"同时成立，
     * 那时候也仍然要带上 {@code created_by} 条件。
     */
    public boolean restrictsByCreator() {
        return type == ScopeType.SELF;
    }

    /**
     * 把仓库维度收窄的条件应用到给定的 {@code warehouseColumn} 上。
     *
     * <p>把 SQL 片段生成放在这里（而不是每个仓储各写一遍）是为了：
     * ① 条件写法只有一份，"忘了加括号"这类错误不会在 8 个资源上各犯一次；
     * ② 越权矩阵用例可以对着这一份实现断言，而不是对着 8 份。
     *
     * @param warehouseColumn 该表的仓库列名（如 {@code o.warehouse_id}），
     *                        <b>调用方拼入的必须是本模块自己的常量</b>，绝不能来自请求参数
     * @param args            参数收集器，按 SQL 出现顺序追加
     * @return 以 {@code AND} 开头的 SQL 片段；无收窄时返回空串
     */
    public String warehousePredicate(String warehouseColumn, List<Object> args) {
        if (type == ScopeType.WAREHOUSE) {
            if (warehouseIds.isEmpty()) {
                // 配了 WAREHOUSE 却没有任何仓库 = 什么都不该看见。
                // 写成 1=0 而不是"回退到全量"：配置残缺时保守收紧，绝不放大权限。
                return " AND 1 = 0";
            }
            // ★ 用 IN (?, ?, ...) 而不是 = ANY (?)：
            //   JdbcTemplate 把 Java 的 List 当成**一个参数**传给 PreparedStatement，
            //   而 PG 驱动只有在参数本身是 java.sql.Array 时才能映射成 BIGINT[]。
            //   写成 ANY(?) 的后果是运行期报
            //   "cannot cast type bigint to bigint[]"（实测为 500，不是编译错误）。
            //   IN 列表是按集合长度动态生成占位符，驱动无需理解数组类型。
            args.addAll(warehouseIds);
            String placeholders = String.join(", ", java.util.Collections.nCopies(
                    warehouseIds.size(), "?"));
            return " AND " + warehouseColumn + " IN (" + placeholders + ")";
        }
        return "";
    }

    /**
     * 按 {@code created_by} 收窄。
     *
     * <p>与 {@link #warehousePredicate} 同样只产出常量片段，用户输入一律走绑定参数。
     */
    public String creatorPredicate(String creatorColumn, List<Object> args) {
        if (restrictsByCreator()) {
            if (actorId == null) {
                return " AND 1 = 0";
            }
            args.add(actorId);
            return " AND " + creatorColumn + " = ?";
        }
        return "";
    }

    /** 便于日志与错误消息的可读形式（不含敏感信息，只有 id 集合）。 */
    public Optional<String> describe() {
        if (isAll()) {
            return Optional.empty();
        }
        return Optional.of(type == ScopeType.WAREHOUSE
                ? "WAREHOUSE" + warehouseIds
                : "SELF(" + actorId + ")");
    }
}
