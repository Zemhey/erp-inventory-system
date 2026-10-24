package com.erp.shared.tenant;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把租户谓词 / 租户列**注入到 SQL 文本**里（FR-TNT-03）。
 *
 * <p><b>★ 为什么是这一层，而不是 366 个调用点逐个改</b>
 * 本项目用 {@code JdbcTemplate} 直写 SQL（无 JPA/Hibernate），没有实体映射层
 * 可以挂 {@code @TenantId}。要在"查询层强制过滤"，只有三个位置可选：
 * <ol>
 *   <li><b>改每一条 SQL</b> —— 366 个调用点、254 条 SELECT，以及<b>将来</b>新增的每一条。
 *       漏一条就是跨租户泄露，而漏掉的那条不会有任何编译错误或测试失败提示；</li>
 *   <li><b>数据库 RLS</b> —— 语义上最干净，但本项目实测走不通：compose /
 *       Testcontainers 用 {@code POSTGRES_USER=erp} 起的库，{@code erp} 是超级用户，
 *       而 <b>超级用户永远绕过 RLS，连 FORCE ROW LEVEL SECURITY 也拦不住</b>。
 *       装了策略、用例全绿、隔离零生效 —— 这正是本泳道要防的假绿
 *       （实测过程见 {@code V16__tenant_isolation.sql} §⑪ 与报告 §漂移注入 ①）；</li>
 *   <li><b>改写 SQL 文本</b>（本类）—— 唯一出口在 {@link TenantDataSource}（JDBC 层），
 *       于是"新写的 SQL 自动带租户谓词"，而不是"靠人记得加"。</li>
 * </ol>
 *
 * <p><b>★ 为什么租户值写成字面量而不是 {@code ?} 占位符</b>
 * 这是本类唯一一处"看起来违反参数绑定纪律"的地方，理由必须写清楚：
 * <ul>
 *   <li>租户 id 是 {@code long}，来自服务端 {@link TenantContext} 绑定，
 *       不经任何请求参数；{@link TenantId#isKnown} 已拒绝非正数 ——
 *       <b>不存在注入面</b>；</li>
 *   <li>用占位符会引入"参数顺序"问题：多行 {@code VALUES} 时追加的占位符在文本里
 *       是<b>交错</b>分布的（行1参数、租户、行2参数、租户），而
 *       {@code JdbcTemplate} 按 varargs 顺序绑定 —— 把租户参数统一追加到末尾会绑定
 *       错位，且错位后的 SQL <b>照样能跑</b>（只是把租户号当成业务参数），
 *       那是最难查的一类缺陷；</li>
 *   <li>字面量让每条语句的参数列表<b>完全保持原样</b>。这条性质让
 *       {@link TenantPreparedStatements} 能把 19 处"手工 {@code ps.setXxx(1..n)}"
 *       的调用点<b>只改文本、不改绑定下标</b>就纳入过滤。</li>
 * </ul>
 * 代价是不同租户产生不同 SQL 文本（PG 各自缓存执行计划）。对一个租户数以十计、
 * 查询数以百计的 ERP，这个代价远小于"参数错位"的风险。
 *
 * <p><b>★ 能力与边界（务必读完再改）</b>
 * 它<b>不是</b> SQL 解析器，而是**只认本项目 SQL 写法**的改写器。
 * 认得：{@code SELECT … FROM/JOIN … WHERE …}、{@code WITH … SELECT …}
 * （含 {@code DISTINCT ON} / 窗口函数）、{@code INSERT … VALUES}、
 * {@code INSERT … SELECT}、{@code UPDATE}、{@code DELETE}，以及它们的
 * {@code ON CONFLICT} / {@code RETURNING} / {@code FOR UPDATE} 尾巴。
 * <b>拒绝改写并大声失败</b>的写法（宁可执行期炸掉，也不能静默放过一条没有租户
 * 谓词的查询）：{@code INSERT INTO <表>} 缺少列清单、括号不闭合、{@code VALUES}
 * 之后没有括号组。每一类都有用例（{@code TenantSqlTest}）钉住"它会抛"。
 *
 * <p><b>改写规则</b>：
 * <ol>
 *   <li>{@code SELECT}：在 {@code WHERE} 段之后（无则新建 {@code WHERE 1 = 1}）
 *       为<b>每一个</b>业务表追加 {@code AND <限定名>.tenant_id = <n>}；</li>
 *   <li>{@code INSERT … VALUES}：列清单尾部追加 {@code tenant_id}，每个
 *       {@code VALUES} 行尾部追加字面量；{@code ON CONFLICT (列…)} 的目标列表
 *       前缀 {@code tenant_id}（复合唯一改了，目标必须跟着改，否则 PG 报
 *       "no unique or exclusion constraint matching the ON CONFLICT specification"）；</li>
 *   <li>{@code INSERT … SELECT}：<b>不</b>动列清单（让 V16 的 BEFORE INSERT 触发器
 *       按会话租户填充），只把内部的 {@code SELECT} 按规则 1 过滤 ——
 *       于是源表被隔离、目标行的租户由触发器兜底；</li>
 *   <li>{@code UPDATE / DELETE}：同规则 1。</li>
 * </ol>
 * <b>幂等</b>：若语句里已有的 {@code tenant_id} 出现次数已覆盖全部表站点，
 * 判定"已改写"并原样返回 —— 这样重复经过本类（或有人手写了谓词）不会重复追加。
 *
 * <p><b>★ 一个刻意的取舍：{@code JOIN} 的 {@code ON} 不追加租户条件</b>
 * 联表两侧各自带谓词后，{@code JOIN} 结果必然是"两侧同租户"的子集，语义已正确。
 * 把条件写进 {@code ON} 会改变 {@code LEFT JOIN} 语义（内表不匹配仍保留外表行），
 * 而本项目实测 0 处 {@code LEFT JOIN}（成本模块的两处由
 * {@code CostSourceRepository} 的注释说明为"只在 RETURN_IN 分支生效"，
 * 且该查询已按 {@code l} 过滤）—— 因此不为它承担"改写错语义"的风险。
 */
public final class TenantSql {

    private static final Set<String> TABLES = TenantTables.ALL;

    /** 语句里所有"表出现点"。{@code start} 指向表名首字符。 */
    private record TableSite(String table, int start, int end, String alias) {
    }

    private static final Pattern TABLE_TOKEN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /** 关键字集合：切断"别名"的误识别（{@code FROM t WHERE} 里的 WHERE 不是别名）。 */
    private static final Set<String> KEYWORDS = Set.of(
            "where", "group", "order", "limit", "offset", "for", "returning", "on",
            "join", "left", "right", "inner", "outer", "full", "cross", "using",
            "union", "intersect", "except", "having", "window", "fetch", "values",
            "select", "from", "as", "set", "and", "or", "not", "do", "conflict",
            "update", "delete", "insert", "into", "with", "distinct", "lateral",
            "when", "then", "else", "end", "case", "asc", "desc", "nulls");

    private TenantSql() {
    }

    /**
     * 改写结果。
     *
     * @param sql     改写后的 SQL（参数列表与原文完全相同 —— 租户以字面量注入）
     * @param changed 是否真的改写过（供守卫测试与诊断）
     */
    public record Rewritten(String sql, boolean changed) {
    }

    /** 按当前 {@link TenantContext} 改写一条 SQL。 */
    public static Rewritten rewrite(String sql) {
        return rewrite(sql, TenantContext.require());
    }

    /** 按指定租户改写一条 SQL（供迁移脚本、单元测试与守卫测试）。 */
    public static Rewritten rewrite(String sql, long tenantId) {
        if (!TenantId.isKnown(tenantId)) {
            throw new IllegalArgumentException("非法租户 id：" + tenantId);
        }
        if (sql == null || sql.isBlank()) {
            return new Rewritten(sql, false);
        }
        String trimmed = stripTrailingSemicolon(sql);
        return switch (firstKeyword(trimmed)) {
            case "select", "with" -> rewriteSelect(trimmed, tenantId);
            case "insert" -> rewriteInsert(trimmed, tenantId);
            case "update" -> rewriteUpdate(trimmed, tenantId);
            case "delete" -> rewriteDelete(trimmed, tenantId);
            default -> new Rewritten(trimmed, false);
        };
    }

    /**
     * 系统旁路：<b>不改写</b>，原样返回。
     *
     * <p>只给两类路径用：登录时按用户名找账号（此刻还不知道租户）、运维/迁移脚本。
     * 它不是"关掉租户过滤"的开关 —— 那个开关只存在于
     * {@code erp.tenant.enforce=false}（全局降级，启动打 WARN）。
     */
    public static Rewritten passthrough(String sql) {
        return new Rewritten(sql, false);
    }

    // ─────────────────────────── SELECT ───────────────────────────

    private static Rewritten rewriteSelect(String sql, long tenantId) {
        if (collectSites(sql).isEmpty()) {
            return new Rewritten(sql, false);
        }
        String out = rewriteScope(sql, tenantId);
        return new Rewritten(out, !out.equals(sql));
    }

    /**
     * 改写**一个作用域**（一条 SELECT / 一个带 FROM 的括号子查询）。
     *
     * <p><b>★ 为什么必须逐层处理而不是一条 WHERE 打天下</b>：SQL 的
     * {@code FROM} 只对**本层**可见。把派生表内层的表名写到外层 WHERE 上，
     * PostgreSQL 会直接报 {@code missing FROM-clause entry for table}；
     * 而把 {@code EXISTS (…)} 这种"表达式子查询"当成有 WHERE 的子句，
     * 会拼出 {@code …) WHERE 1 = 1 …} 这种非法语法。两者都实测踩到过。
     *
     * <p>处理顺序：① 先递归改括号内的子语句（它们各自成域）；
     * ② 再为本层直接出现的表追加谓词。
     * 先递归是必要的 —— 递归会改变文本长度，后做的外层插入用重新计算的偏移。
     */
    private static String rewriteScope(String sql, long tenantId) {
        // ① 自底向上：把每个括号里的"看起来是完整语句"的片段先改写掉
        StringBuilder rewritten = new StringBuilder(sql);
        List<int[]> regions = parenthesizedRegions(rewritten.toString());
        // 从后往前替换，避免前面的替换影响后面的下标
        for (int i = regions.size() - 1; i >= 0; i--) {
            int open = regions.get(i)[0];
            int close = regions.get(i)[1];
            String inner = rewritten.substring(open + 1, close);
            String head = firstKeyword(inner.strip());
            if ("select".equals(head) || "with".equals(head)) {
                String innerNew = rewriteScope(inner, tenantId);
                rewritten.replace(open + 1, close, innerNew);
            }
        }
        String sql2 = rewritten.toString();

        // ② 本层：只认深度 0 的 FROM / JOIN 目标
        List<TableSite> sites = collectSitesAtDepthZero(sql2);
        if (sites.isEmpty()) {
            return sql2;
        }
        if (alreadyScopedAtDepthZero(sql2, sites)) {
            return sql2;
        }
        StringBuilder predicates = new StringBuilder();
        for (TableSite site : sites) {
            predicates.append(" AND ").append(qualified(site))
                    .append(".tenant_id = ").append(tenantId);
        }
        int whereIdx = indexOfTopLevel(sql2, "where", 0);
        int guardEnd = minPositive(
                indexOfTopLevel(sql2, "group", 0),
                indexOfTopLevel(sql2, "order", 0),
                indexOfTopLevel(sql2, "limit", 0),
                indexOfTopLevel(sql2, "offset", 0),
                indexOfTopLevel(sql2, "for update", 0));
        if (whereIdx >= 0) {
            int insertAt = guardEnd > whereIdx ? guardEnd : sql2.length();
            return insert(sql2, insertAt, predicates.toString().strip());
        }
        int insertAt = guardEnd >= 0 ? guardEnd : sql2.length();
        return insert(sql2, insertAt, "WHERE 1 = 1" + predicates);
    }

    /** 所有括号区间（含嵌套），按左括号位置升序。 */
    private static List<int[]> parenthesizedRegions(String sql) {
        List<int[]> out = new ArrayList<>();
        boolean inSingle = false;
        int depth = 0;
        int openAt = -1;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'') {
                inSingle = !inSingle;
                continue;
            }
            if (inSingle) {
                continue;
            }
            if (c == '(') {
                if (depth == 0) {
                    openAt = i;
                }
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0 && openAt >= 0) {
                    out.add(new int[]{openAt, i});
                    openAt = -1;
                }
            }
        }
        return out;
    }

    /**
     * 收集**深度 0** 的 FROM / JOIN 目标（不看括号里的）。
     *
     * <p>与 {@link #collectSites} 的区别：后者会把子查询里的表也算进来，
     * 那些表对外层 WHERE 不可见，写上去就是 {@code missing FROM-clause entry}。
     */
    static List<TableSite> collectSitesAtDepthZero(String sql) {
        List<TableSite> sites = new ArrayList<>();
        boolean inSingle = false;
        int depth = 0;
        Matcher kw = Pattern.compile("(?i)\\b(from|join)\\b").matcher(sql);
        while (kw.find()) {
            // 计算该关键字处（含）之前的括号深度
            depth = 0;
            inSingle = false;
            for (int i = 0; i < kw.start(); i++) {
                char c = sql.charAt(i);
                if (c == '\'') {
                    inSingle = !inSingle;
                } else if (!inSingle && c == '(') {
                    depth++;
                } else if (!inSingle && c == ')') {
                    depth--;
                }
            }
            if (depth != 0) {
                continue;
            }
            if (precededBy(sql, kw.start(), "do") || precededBy(sql, kw.start(), "conflict")
                    || precededBy(sql, kw.start(), "for")) {
                continue;
            }
            int after = kw.end();
            while (after < sql.length() && Character.isWhitespace(sql.charAt(after))) {
                after++;
            }
            if (after < sql.length() && sql.charAt(after) == '(') {
                continue;
            }
            Matcher tok = TABLE_TOKEN.matcher(sql);
            tok.region(after, sql.length());
            if (!tok.lookingAt()) {
                continue;
            }
            String word = tok.group().toLowerCase(Locale.ROOT);
            if (KEYWORDS.contains(word) || !TABLES.contains(word)) {
                continue;
            }
            sites.add(new TableSite(word, tok.start(), tok.end(), aliasAfter(sql, tok.end())));
        }
        return sites;
    }

    /** 本层的表是否都已有谓词。 */
    private static boolean alreadyScopedAtDepthZero(String sql, List<TableSite> sites) {
        int whereIdx = indexOfTopLevel(sql, "where", 0);
        if (whereIdx < 0) {
            return false;
        }
        int guardEnd = minPositive(
                indexOfTopLevel(sql, "group", 0),
                indexOfTopLevel(sql, "order", 0),
                indexOfTopLevel(sql, "limit", 0),
                indexOfTopLevel(sql, "offset", 0),
                indexOfTopLevel(sql, "for update", 0));
        String whereClause = sql.substring(whereIdx,
                guardEnd > whereIdx ? guardEnd : sql.length());
        for (TableSite site : sites) {
            if (!whereClause.contains(qualified(site) + ".tenant_id")) {
                return false;
            }
        }
        return true;
    }

    // ─────────────────────────── INSERT ───────────────────────────

    private static final Pattern INSERT_TARGET = Pattern.compile(
            "^(?is)\\s*insert\\s+into\\s+([A-Za-z_][A-Za-z0-9_]*)");

    private static Rewritten rewriteInsert(String sql, long tenantId) {
        Matcher m = INSERT_TARGET.matcher(sql);
        if (!m.find()) {
            return new Rewritten(sql, false);
        }
        String table = m.group(1).toLowerCase(Locale.ROOT);
        if (!TABLES.contains(table)) {
            return new Rewritten(sql, false);
        }
        if (alreadyScoped(sql)) {
            return new Rewritten(sql, false);
        }
        int open = sql.indexOf('(', m.end());
        if (open < 0) {
            throw unsupported(sql, "INSERT INTO " + table + " 缺少列清单");
        }
        int close = matchingParen(sql, open);
        if (close < 0) {
            throw unsupported(sql, "INSERT INTO " + table + " 列清单括号不闭合");
        }
        int valuesIdx = indexOfTopLevel(sql, "values", close + 1);
        int selectIdx = indexOfTopLevel(sql, "select", close + 1);

        if (valuesIdx < 0 && selectIdx < 0) {
            throw unsupported(sql, "INSERT INTO " + table + " 既没有 VALUES 也没有 SELECT");
        }

        if (valuesIdx < 0) {
            // ★ INSERT … SELECT：列清单尾部加 tenant_id，SELECT 列表尾部加字面量，
            //   并把内部 SELECT 按作用域规则过滤（避免源表跨租户）。
            //
            //   为什么不能只靠 BEFORE INSERT 触发器：触发器能在 tenant_id 为 NULL 时
            //   填充值，但**列清单里必须有 tenant_id 这一列**，那列才会被写入。
            //   列清单不含它时，触发器填的值不会落库 —— 于是行会落进默认租户。
            int selectStop = minPositive(
                    indexOfTopLevel(sql, "on conflict", close + 1),
                    indexOfTopLevel(sql, "returning", close + 1));
            if (selectStop < 0) {
                selectStop = sql.length();
            }
            String selected = sql.substring(selectIdx, selectStop);
            Rewritten filtered = rewriteSelect(selected, tenantId);

            String head = sql.substring(0, selectIdx);
            String tail = sql.substring(selectStop);
            String withCol = head.substring(0, close) + ", tenant_id"
                    + head.substring(close);
            return new Rewritten(rewriteOnConflictTarget(
                    withCol + filtered.sql() + ", " + tenantId + tail, table), true);
        }
        // ★ 只认 VALUES 之后、且在 ON CONFLICT / RETURNING 之前的那几组括号。
        //   不设上界会把 ON CONFLICT (request_id) 的目标列表也当成"一行 VALUES"，
        //   于是往里塞租户**值**，产生 (tenant_id, request_id, 7) 这种非法目标 ——
        //   实测踩到过：它在 PG 里是语法错误，但在我们自己的改写里"看起来成功了"。
        int valuesEnd = minPositive(indexOfTopLevel(sql, "on conflict", valuesIdx),
                indexOfTopLevel(sql, "returning", valuesIdx));
        if (valuesEnd < 0) {
            valuesEnd = sql.length();
        }
        List<Integer> rowCloses = new ArrayList<>();
        int cursor = valuesIdx + "values".length();
        while (true) {
            int vOpen = sql.indexOf('(', cursor);
            if (vOpen < 0 || vOpen >= valuesEnd) {
                break;
            }
            int vClose = matchingParen(sql, vOpen);
            if (vClose < 0) {
                throw unsupported(sql, "INSERT INTO " + table + " 的 VALUES 括号不闭合");
            }
            rowCloses.add(vClose);
            cursor = vClose + 1;
        }
        if (rowCloses.isEmpty()) {
            throw unsupported(sql, "INSERT INTO " + table + " 的 VALUES 之后没有括号组");
        }

        StringBuilder out = new StringBuilder(sql);
        for (int i = rowCloses.size() - 1; i >= 0; i--) {
            out.insert(rowCloses.get(i), ", " + tenantId);
        }
        out.insert(close, ", tenant_id");
        // 先取 ON CONFLICT 目标的**位置**（此时还没插值），再做值插入，
        // 否则先插值会让目标括号的偏移错开。
        String withColumn = out.toString();
        return new Rewritten(rewriteOnConflictTarget(withColumn, table), true);
    }

    /**
     * {@code ON CONFLICT (列…)} 的目标列表前缀 {@code tenant_id}。
     *
     * <p>V16 把幂等键的唯一约束从 {@code (request_id)} 改成了
     * {@code (tenant_id, request_id)}。若 {@code ON CONFLICT} 的目标仍写
     * {@code (request_id)}，PostgreSQL 会直接报
     * {@code there is no unique or exclusion constraint matching the ON CONFLICT
     * specification} —— <b>这是好事</b>（响亮失败），但会让每一处
     * {@code ON CONFLICT (request_id)} 的并发幂等失效。所以这里跟着改。
     *
     * <p>{@code ON CONFLICT DO NOTHING}（无目标）不动：它对任何唯一冲突都成立。
     */
    /**
     * {@code ON CONFLICT (列…)} 的目标列表前缀 {@code tenant_id} —— **仅对 V16 改过
     * 复合唯一索引的表**。
     *
     * <p><b>★ 为什么要按表区分（实测踩出来的）</b>：{@code ON CONFLICT (目标)} 必须能在
     * 目标列上找到一个**存在的**唯一索引。V16 只把"去掉租户维就会跨租户撞号"的那些
     * 约束改成了 {@code (tenant_id, …)}；{@code customer_receivables.uq_receivable_order
     * (sales_order_id)} 这类含全局唯一代理 id 的约束**刻意没改**（天然不可能跨租户冲突）。
     * 对没改的表硬加 {@code tenant_id}，PG 会报
     * {@code there is no unique or exclusion constraint matching the ON CONFLICT
     * specification} —— 实测让 87 条应收用例一起变红。
     *
     * <p>因此这里维护一份白名单（与 V16 §⑤/§④ 逐条对应）；{@code TenantSchemaIT}
     * 断言"白名单里的表确实有以 tenant_id 开头的唯一索引"，白名单漂移也会被抓到。
     */
    private static final Set<String> ON_CONFLICT_SCOPED = Set.of(
            "inventory_ledger", "inventory_reservations", "purchase_orders", "sales_orders", "return_orders",
            "inventory_transfers", "stocktakes", "inventory_adjustments",
            "inventory_freezes", "payable_allocations", "sales_quotes", "so_shipments",
            "approval_flows", "approval_instances",
            "warehouses", "suppliers", "customers", "products", "skus",
            "document_sequences", "idempotency_keys", "settlement_periods");

    private static String rewriteOnConflictTarget(String sql, String table) {
        if (!ON_CONFLICT_SCOPED.contains(table)) {
            return sql;
        }
        int idx = indexOfTopLevel(sql, "on conflict", 0);
        if (idx < 0) {
            return sql;
        }
        int i = idx + "on conflict".length();
        while (i < sql.length() && Character.isWhitespace(sql.charAt(i))) {
            i++;
        }
        if (i >= sql.length() || sql.charAt(i) != '(') {
            return sql;   // ON CONFLICT DO NOTHING
        }
        int close = matchingParen(sql, i);
        if (close < 0) {
            return sql;
        }
        // ★ 目标列表里**只加列名，不能带值**（目标是"索引列清单"，7 不是列）；
        //   已带 tenant_id 的不重复加。
        String target = sql.substring(i + 1, close);
        boolean already = target.toLowerCase(Locale.ROOT).contains("tenant_id");
        StringBuilder out = new StringBuilder(sql);
        if (!already) {
            out.insert(i + 1, "tenant_id, ");
            close += "tenant_id, ".length();
        }
        // ★★ 关键：这些唯一索引都是**部分索引**（带 WHERE 谓词）。
        //   PostgreSQL 规定：ON CONFLICT 用"列清单"推断索引时，索引必须**无条件**
        //   （不能是部分索引）；要用部分索引就必须在 ON CONFLICT 后显式给出同样的
        //   WHERE 谓词。V16 把幂等键索引都建成了 `(tenant_id, request_id)
        //   WHERE request_id IS NOT NULL`，于是不带谓词的 ON CONFLICT 会报
        //   "there is no unique or exclusion constraint matching the ON CONFLICT
        //   specification"（实测：16+15+12+5 条用例因此变红）。
        //   目标列里已经有 request_id / create_request_id 这类列时，补上同一个谓词。
        // ★★ 关键：这些唯一索引都是**部分索引**（带 WHERE 谓词）。
        //   PostgreSQL 规定：ON CONFLICT 用"列清单"推断索引时，索引必须**无条件**
        //   （不能是部分索引）；要用部分索引就必须在 ON CONFLICT 后、**DO 之前**
        //   显式给出同样的 WHERE 谓词。位置写错（放到 DO NOTHING 之后）会变成语法错误。
        String targetCols = out.substring(i + 1, close);
        String predicate = partialIndexPredicate(targetCols);
        if (predicate != null) {
            // ON CONFLICT 的目标括号之后紧接的应当是 WHERE 谓词，然后才是 DO NOTHING /
            // DO UPDATE。写错顺序（放到 DO 之后）是语法错误（实测）。
            int afterParen = close + 1;
            String rest = out.substring(afterParen).stripLeading();
            String lower = rest.toLowerCase(Locale.ROOT);
            if (!lower.startsWith("where")) {
                out.insert(afterParen, " WHERE " + predicate);
            }

        }
        return out.toString();
    }

    /**
     * 部分唯一索引的谓词 —— 必须与 V16 里的定义**逐字一致**，否则 PG 仍认不出索引。
     *
     * <p>只覆盖本项目真实存在的几种形状；匹配不上就返回 {@code null}
     * （即不补谓词，保持原语义），因为"猜一个错的谓词"比"不补"更危险。
     */
    private static String partialIndexPredicate(String cols) {
        String c = cols.toLowerCase(Locale.ROOT);
        if (c.contains("request_id")) {
            return "request_id IS NOT NULL";
        }
        if (c.contains("void_request_id")) {
            return "void_request_id IS NOT NULL";
        }
        if (c.contains("receive_request_id")) {
            return "receive_request_id IS NOT NULL";
        }
        if (c.contains("post_request_id")) {
            return "post_request_id IS NOT NULL";
        }
        if (c.contains("confirm_request_id")) {
            return "confirm_request_id IS NOT NULL";
        }
        if (c.contains("create_request_id")) {
            return "create_request_id IS NOT NULL";
        }
        if (c.contains("disable_request_id")) {
            return "disable_request_id IS NOT NULL";
        }
        if (c.contains("conversion_request_id")) {
            return "conversion_request_id IS NOT NULL";
        }
        if (c.contains("approve_request_id")) {
            return "approve_request_id IS NOT NULL";
        }
        if (c.contains("release_request_id")) {
            return "release_request_id IS NOT NULL";
        }
        if (c.contains("convert_request_id")) {
            return "convert_request_id IS NOT NULL";
        }
        // idempotency_keys / document_sequences / settlement_periods 的主键是
        // **整表**唯一（非部分索引），不需要 WHERE
        return null;
    }

    // ─────────────────────────── UPDATE / DELETE ───────────────────────────

    private static final Pattern UPDATE_TARGET = Pattern.compile(
            "^(?is)\\s*update\\s+([A-Za-z_][A-Za-z0-9_]*)");

    private static Rewritten rewriteUpdate(String sql, long tenantId) {
        Matcher m = UPDATE_TARGET.matcher(sql);
        if (!m.find()) {
            return new Rewritten(sql, false);
        }
        String table = m.group(1).toLowerCase(Locale.ROOT);
        if (!TABLES.contains(table)) {
            return new Rewritten(sql, false);
        }
        String alias = aliasAfter(sql, m.end());
        if (alreadyScoped(sql)) {
            return new Rewritten(sql, false);
        }
        return new Rewritten(appendWhere(sql, " AND " + (alias == null ? table : alias)
                + ".tenant_id = " + tenantId), true);
    }

    private static final Pattern DELETE_TARGET = Pattern.compile(
            "^(?is)\\s*delete\\s+from\\s+([A-Za-z_][A-Za-z0-9_]*)");

    private static Rewritten rewriteDelete(String sql, long tenantId) {
        Matcher m = DELETE_TARGET.matcher(sql);
        if (!m.find()) {
            return new Rewritten(sql, false);
        }
        String table = m.group(1).toLowerCase(Locale.ROOT);
        if (!TABLES.contains(table)) {
            return new Rewritten(sql, false);
        }
        String alias = aliasAfter(sql, m.end());
        if (alreadyScoped(sql)) {
            return new Rewritten(sql, false);
        }
        return new Rewritten(appendWhere(sql, " AND " + (alias == null ? table : alias)
                + ".tenant_id = " + tenantId), true);
    }

    private static String appendWhere(String sql, String predicate) {
        int whereIdx = indexOfTopLevel(sql, "where", 0);
        int guardEnd = minPositive(
                indexOfTopLevel(sql, "returning", 0),
                indexOfTopLevel(sql, "order", 0),
                indexOfTopLevel(sql, "limit", 0));
        if (whereIdx >= 0) {
            int insertAt = guardEnd > whereIdx ? guardEnd : sql.length();
            return insert(sql, insertAt, predicate.strip());
        }
        int insertAt = guardEnd >= 0 ? guardEnd : sql.length();
        // predicate 自带前导 " AND "（注意**不能** stripLeading：那会把空格吃掉，
        // 拼出 "WHERE 1 = 1AND …" —— 实测踩到过，而且 SQL 语义直接变错）
        return insert(sql, insertAt, "WHERE 1 = 1" + predicate);
    }

    // ─────────────────────────── 站点收集 ───────────────────────────

    /**
     * 收集语句里所有业务表的出现点（含别名）。
     *
     * <p>按"关键字 → 紧邻的标识符"扫描，而不是一个大正则匹配整条语句：
     * 后者在 {@code ON CONFLICT … DO UPDATE}、{@code DISTINCT ON (…)} 上会误判
     * （{@code ON} / {@code UPDATE} 后面跟的并不是表）。
     */
    static List<TableSite> collectSites(String sql) {
        List<TableSite> sites = new ArrayList<>();
        Matcher kw = Pattern.compile("(?i)\\b(from|join|insert\\s+into|update|delete\\s+from)\\b")
                .matcher(sql);
        while (kw.find()) {
            if (precededBy(sql, kw.start(), "do") || precededBy(sql, kw.start(), "conflict")
                    || precededBy(sql, kw.start(), "for")) {
                continue;   // ON CONFLICT DO UPDATE / FOR UPDATE：不是表位置
            }
            int after = kw.end();
            while (after < sql.length() && Character.isWhitespace(sql.charAt(after))) {
                after++;
            }
            if (after < sql.length() && sql.charAt(after) == '(') {
                continue;   // 派生表：内部由本次扫描的其它命中覆盖
            }
            Matcher tok = TABLE_TOKEN.matcher(sql);
            tok.region(after, sql.length());
            if (!tok.lookingAt()) {
                continue;
            }
            String word = tok.group().toLowerCase(Locale.ROOT);
            if (KEYWORDS.contains(word) || !TABLES.contains(word)) {
                continue;
            }
            sites.add(new TableSite(word, tok.start(), tok.end(), aliasAfter(sql, tok.end())));
        }
        return sites;
    }

    private static boolean precededBy(String sql, int idx, String word) {
        int i = idx - 1;
        while (i >= 0 && Character.isWhitespace(sql.charAt(i))) {
            i--;
        }
        int end = i + 1;
        while (i >= 0 && Character.isLetter(sql.charAt(i))) {
            i--;
        }
        return sql.substring(i + 1, end).toLowerCase(Locale.ROOT).equals(word);
    }

    /** 表名之后的别名（也接受 {@code AS}）；拿不到就返回 {@code null}。 */
    static String aliasAfter(String sql, int end) {
        int i = end;
        while (i < sql.length() && Character.isWhitespace(sql.charAt(i))) {
            i++;
        }
        Matcher tok = TABLE_TOKEN.matcher(sql);
        tok.region(i, sql.length());
        if (tok.lookingAt()) {
            String word = tok.group();
            if (word.equalsIgnoreCase("AS")) {
                return aliasAfter(sql, tok.end());
            }
            if (!KEYWORDS.contains(word.toLowerCase(Locale.ROOT))) {
                return word;
            }
        }
        return null;
    }

    private static String qualified(TableSite site) {
        return site.alias() != null ? site.alias() : site.table();
    }

    // ─────────────────────────── 文本工具 ───────────────────────────

    /**
     * 在 {@code at} 处插入 {@code insertion}，并保证结果里不出现"两个空格"或"词粘连"。
     *
     * <p><b>为什么要处理空白</b>：改写点常常正好落在原有空白上（{@code FROM warehouses␣␣ORDER BY}
     * 中间有两个空格）。若只是 {@code head + insertion + tail}，会得到
     * {@code FROM warehouses␣␣␣WHERE 1 = 1 AND …} 这类多空白 —— SQL 合法，但
     * <b>断言就再也无法用等值比较</b>，而弱断言（只 contains）正是本泳道要避免的。
     * 因此这里统一：head 去掉尾部空白、insertion 自带一个前导空格、tail 去前导空白。
     */
    private static String insert(String sql, int at, String insertion) {
        String head = sql.substring(0, at).stripTrailing();
        String tail = sql.substring(at).stripLeading();
        String ins = insertion.strip();
        if (ins.isEmpty()) {
            return (head + (tail.isEmpty() ? "" : " " + tail)).stripTrailing();
        }
        // ★ 前导/尾随各**一个**空格，绝不拼接出 "1AND" 或 "␣␣GROUP"。
        String sep = tail.isEmpty() ? "" : " ";
        return (head + " " + ins + sep + tail).stripTrailing();
    }

    private static String stripTrailingSemicolon(String sql) {
        String s = sql.strip();
        while (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1).stripTrailing();
        }
        return s;
    }

    private static String firstKeyword(String sql) {
        Matcher m = Pattern.compile("(?is)^\\s*([a-z]+)").matcher(sql);
        return m.find() ? m.group(1).toLowerCase(Locale.ROOT) : "";
    }

    /** 括号配对（引号内的括号不算）。 */
    static int matchingParen(String sql, int open) {
        int depth = 0;
        boolean inSingle = false;
        for (int i = open; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'') {
                inSingle = !inSingle;
                continue;
            }
            if (inSingle) {
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** 找某关键字（或带空格的短语）在<b>顶层</b>（深度 0、不在引号里）的首次出现。 */
    static int indexOfTopLevel(String sql, String keyword, int from) {
        String needle = keyword.toLowerCase(Locale.ROOT);
        int depth = 0;
        boolean inSingle = false;
        for (int i = from; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'') {
                inSingle = !inSingle;
                continue;
            }
            if (inSingle) {
                continue;
            }
            if (c == '(') {
                depth++;
                continue;
            }
            if (c == ')') {
                depth--;
                continue;
            }
            if (depth != 0) {
                continue;
            }
            if (Character.toLowerCase(c) != needle.charAt(0)) {
                continue;
            }
            if (i > 0 && (Character.isLetterOrDigit(sql.charAt(i - 1)) || sql.charAt(i - 1) == '_')) {
                continue;
            }
            if (!matchesLoose(sql, i, needle)) {
                continue;
            }
            int after = i + needle.length();
            if (after < sql.length()
                    && (Character.isLetterOrDigit(sql.charAt(after)) || sql.charAt(after) == '_')) {
                continue;
            }
            return i;
        }
        return -1;
    }

    private static boolean matchesLoose(String sql, int start, String needle) {
        int i = start;
        int j = 0;
        while (j < needle.length()) {
            char n = needle.charAt(j);
            if (n == ' ') {
                if (i >= sql.length() || !Character.isWhitespace(sql.charAt(i))) {
                    return false;
                }
                while (i < sql.length() && Character.isWhitespace(sql.charAt(i))) {
                    i++;
                }
                j++;
                continue;
            }
            if (i >= sql.length() || Character.toLowerCase(sql.charAt(i)) != n) {
                return false;
            }
            i++;
            j++;
        }
        return true;
    }

    private static int minPositive(int... values) {
        return java.util.Arrays.stream(values).filter(v -> v > 0).min().orElse(-1);
    }

    private static com.erp.shared.error.BusinessException unsupported(String sql, String why) {
        return new com.erp.shared.error.BusinessException(
                com.erp.shared.error.ErrorCode.SYS_TENANT_CONTEXT_MISSING,
                ("租户过滤无法注入（%s）：这条 SQL 的写法不在 TenantSql 的能力范围内。"
                        + "请改写为标准形态，或显式走 TenantContext.runAsSystem(...) "
                        + "并在 .plan/V2-7-报告.md 登记豁免理由。SQL=%s")
                        .formatted(why, sql.strip()));
    }

    /** 供守卫测试判断某条 SQL 是否带了租户谓词。 */
    public static boolean mentionsTenant(String sql) {
        return sql != null && sql.toLowerCase(Locale.ROOT).contains("tenant_id");
    }

    /**
     * 这条语句是否**触及**业务表（因而需要租户上下文）。
     *
     * <p><b>为什么需要这个判断</b>：{@code TRUNCATE}（造数/清理）、
     * {@code CREATE/ALTER}（迁移）、以及只读 {@code tenants} /
     * {@code flyway_schema_history} 的语句<b>不需要</b>租户绑定 ——
     * 它们要么是 DDL 不受过滤，要么操作的是跨租户共享的元数据。
     * 若把它们也要求绑定租户，造数工具与启动期校验会因为"没有请求上下文"
     * 直接抛 {@code SYS_TENANT_CONTEXT_MISSING}，而那不是缺陷。
     *
     * <p>反过来，这个判断**不能**放宽到"看起来没有 FROM 就算安全"：
     * 它只回答"要不要绑定租户"，改写判断仍由 {@link #rewrite} 独立完成。
     */
    public static boolean touchesBusinessTables(String sql) {
        if (sql == null || sql.isBlank()) {
            return false;
        }
        // ★ 只对 DML（SELECT / WITH / INSERT / UPDATE / DELETE）判断。
        //   `TRUNCATE products, warehouses …` 里也会被 collectSites 认出表名
        //   （"warehouses" 前面没有 FROM），但它是 **DDL** —— 造数工具与清理脚本
        //   必须能清空所有租户的数据，不能被租户过滤拦住。
        //   实测踩到过：不排除 DDL 时，TestDataFactory.truncateAll() 在每条用例的
        //   @BeforeEach 就抛 SYS_TENANT_CONTEXT_MISSING，整套测试全红。
        String head = firstKeyword(stripTrailingSemicolon(sql));
        if (!"select".equals(head) && !"with".equals(head) && !"insert".equals(head)
                && !"update".equals(head) && !"delete".equals(head)) {
            return false;
        }
        return !collectSites(stripTrailingSemicolon(sql)).isEmpty();
    }

    /** {@code tenant_id} 的出现次数（幂等判定用）。 */
    static int tenantMentions(String sql) {
        if (sql == null) {
            return 0;
        }
        String lower = sql.toLowerCase(Locale.ROOT);
        int n = 0;
        int i = 0;
        while ((i = lower.indexOf("tenant_id", i)) >= 0) {
            n++;
            i += "tenant_id".length();
        }
        return n;
    }

    /**
     * 这条语句是否<b>已经</b>被租户化（幂等判定）。
     *
     * <p>判据：{@code tenant_id} 的出现次数 ≥ 业务表站点数 —— 每个站点至少要有一个
     * 谓词（或 INSERT 的列）。这样重复经过改写器不会重复追加，而"只给两张表里
     * 一张加了谓词"的语句仍会被补上（而不是被误判成已完成）。
     */
    static boolean alreadyScoped(String sql) {
        int sites = collectSites(stripTrailingSemicolon(sql)).size();
        return sites > 0 && tenantMentions(sql) >= sites;
    }

    /** 供诊断：语句里识别到的业务表（按出现顺序）。 */
    public static List<String> tablesOf(String sql) {
        return collectSites(stripTrailingSemicolon(sql)).stream().map(TableSite::table).toList();
    }

    /** 供诊断：把识别结果拼成可读串（失败信息里用得上）。 */
    public static Optional<String> describeSites(String sql) {
        List<TableSite> sites = collectSites(stripTrailingSemicolon(sql));
        if (sites.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(sites.stream()
                .map(s -> s.table() + (s.alias() == null ? "" : " " + s.alias()))
                .collect(java.util.stream.Collectors.joining(", ")));
    }
}