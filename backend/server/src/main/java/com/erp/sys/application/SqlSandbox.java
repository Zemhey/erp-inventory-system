package com.erp.sys.application;

import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.tenant.TenantContext;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 自然语言「自定义查询」的只读沙箱（FR-SYS-04 · text-to-SQL）。
 *
 * <p><b>为什么要有它</b>：固定工具只能覆盖事先想到的问法。用户问"最近一个月出货单的
 * 客户集中度"这种组合问题，没有任何一个工具能答。让模型自己写 SELECT 是唯一出路 ——
 * 但把数据库直接交给模型，等于把整个库的读取权限交出去。
 *
 * <p><b>四道闸，缺一不可</b>：
 * <ol>
 *   <li><b>只读</b>：{@code @Transactional(readOnly = true)} + 语句必须以 SELECT/WITH 开头，
 *       禁写关键字。前端可绕过，数据库这一层不可绕过。</li>
 *   <li><b>表白名单 + 视图隔离</b>：执行时 {@code SET LOCAL search_path = ai}，
 *       模型写的 {@code sales_orders} 命中的是 V27 里那个**已按租户过滤的视图**。
 *       于是 sys_config（API Key 密文）、app_users（口令哈希）根本不在可见范围内。</li>
 *   <li><b>租户</b>：{@code SET LOCAL app.tenant_id}，由视图施加条件。
 *       忘了设 → 视图返回空，而不是返回全部租户的数据（失败朝安全一侧倒）。</li>
 *   <li><b>成本</b>：8 秒超时 + 外层包 200 行上限。防止一条笛卡尔积把库拖死。</li>
 * </ol>
 *
 * <p><b>为什么不复用 {@code TenantAwareJdbcTemplate}</b>：它的租户注入是"改写 SQL 文本"，
 * 面对任意用户 SQL（CTE、子查询、UNION）改写结果不可预期；这里要的是**确定性**：
 * 用普通 JdbcTemplate + 视图，条件由数据库保证，而不是由字符串改写保证。
 */
@Component
public class SqlSandbox {

    /** 沙箱可见的视图名（与 V27 一一对应）。 */
    private static final Set<String> ALLOWED_TABLES = Set.of(
            "sales_orders", "sales_order_lines",
            "purchase_orders", "purchase_order_lines",
            "return_orders", "return_order_lines",
            "inventory_balances",
            "products", "skus", "suppliers", "customers", "warehouses");

    /** 出现即拒绝的关键字：即使语句以 select 开头，也不允许夹带。 */
    private static final Set<String> FORBIDDEN = Set.of(
            "insert", "update", "delete", "drop", "alter", "create", "grant", "revoke",
            "truncate", "copy", "call", "vacuum", "analyze", "reindex",
            "begin", "commit", "rollback", "savepoint", "listen", "notify", "lock");

    private static final Pattern TABLE_REF =
            Pattern.compile("\\b(?:from|join)\\s+([a-z_][a-z0-9_]*)");
    private static final Pattern CTE_NAME =
            Pattern.compile("\\b([a-z_][a-z0-9_]*)\\s+as\\s*\\(");
    private static final Pattern LITERAL = Pattern.compile("'(?:[^']|'')*'");

    private static final int MAX_ROWS = 200;
    private static final int TIMEOUT_SECONDS = 8;

    private final JdbcTemplate jdbc;

    public SqlSandbox(DataSource dataSource) {
        // 刻意 new 一个普通 JdbcTemplate：不走租户改写那一层（理由见类注释）。
        this.jdbc = new JdbcTemplate(dataSource);
        this.jdbc.setQueryTimeout(TIMEOUT_SECONDS);
        this.jdbc.setMaxRows(MAX_ROWS + 1);
    }

    /**
     * @param columns 结果列名（保持 SQL 里的顺序与别名）
     * @param rows    数据行；值已做 JSON 友好化
     * @param truncated 是否因超过 200 行被截断
     */
    public record Result(List<String> columns, List<Map<String, Object>> rows, boolean truncated) {
        public boolean empty() {
            return rows.isEmpty();
        }
    }

    @Transactional(readOnly = true)
    public Result run(String rawSql) {
        String sql = validate(rawSql);
        long tenantId = TenantContext.require();

        // SET LOCAL 只在当前事务内生效；两个参数都是数字/固定串，不存在注入。
        jdbc.execute("SET LOCAL app.tenant_id = '" + tenantId + "'");
        jdbc.execute("SET LOCAL search_path = ai");

        String wrapped = "SELECT * FROM (" + sql + ") sandbox_result LIMIT " + (MAX_ROWS + 1);
        try {
            return jdbc.query(wrapped, (ResultSetExtractor<Result>) rs -> {
                ResultSetMetaData md = rs.getMetaData();
                int count = md.getColumnCount();
                List<String> columns = new ArrayList<>(count);
                for (int i = 1; i <= count; i++) {
                    columns.add(md.getColumnLabel(i));
                }
                List<Map<String, Object>> rows = new ArrayList<>();
                while (rs.next() && rows.size() <= MAX_ROWS) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= count; i++) {
                        row.put(columns.get(i - 1), friendly(rs.getObject(i)));
                    }
                    rows.add(row);
                }
                boolean truncated = rows.size() > MAX_ROWS;
                if (truncated) {
                    rows.remove(rows.size() - 1);
                }
                return new Result(columns, rows, truncated);
            });
        } catch (BusinessException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            // 把数据库的原话给出去：模型/用户据此能改 SQL。吞成"查询失败"就没人能修。
            Throwable root = ex;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            String message = root.getMessage() == null ? ex.getMessage() : root.getMessage();
            throw new BusinessException(ErrorCode.NL_SQL_INVALID,
                    "查询语句执行失败：" + (message == null ? ex.getClass().getSimpleName() : message));
        }
    }

    /**
     * 语句校验。**故意逐条给具体原因**："不允许"三个字对模型和用户都没有信息量，
     * 而"不允许查询表 sys_config"能立刻让人知道边界在哪。
     */
    String validate(String rawSql) {
        if (rawSql == null || rawSql.isBlank()) {
            throw new BusinessException(ErrorCode.NL_SQL_INVALID, "SQL 为空");
        }
        String sql = rawSql.trim();
        if (sql.endsWith(";")) {
            sql = sql.substring(0, sql.length() - 1).trim();
        }
        if (sql.contains(";")) {
            throw new BusinessException(ErrorCode.NL_SQL_INVALID, "只允许一条语句");
        }
        String lowered = sql.toLowerCase(Locale.ROOT);
        if (!lowered.startsWith("select") && !lowered.startsWith("with")) {
            throw new BusinessException(ErrorCode.NL_SQL_INVALID, "只允许 SELECT 查询");
        }
        if (lowered.contains("--") || lowered.contains("/*")) {
            throw new BusinessException(ErrorCode.NL_SQL_INVALID, "不允许使用注释");
        }

        String stripped = LITERAL.matcher(lowered).replaceAll("''");
        for (String keyword : FORBIDDEN) {
            if (Pattern.compile("\\b" + keyword + "\\b").matcher(stripped).find()) {
                throw new BusinessException(ErrorCode.NL_SQL_INVALID,
                        "不允许出现 " + keyword.toUpperCase(Locale.ROOT));
            }
        }

        Set<String> cteNames = new LinkedHashSet<>();
        Matcher cte = CTE_NAME.matcher(stripped);
        while (cte.find()) {
            cteNames.add(cte.group(1));
        }
        Matcher ref = TABLE_REF.matcher(stripped);
        while (ref.find()) {
            String table = ref.group(1);
            if (!ALLOWED_TABLES.contains(table) && !cteNames.contains(table)) {
                throw new BusinessException(ErrorCode.NL_SQL_INVALID,
                        "不允许查询表：" + table + "（可用：" + String.join("、", ALLOWED_TABLES) + "）");
            }
        }
        return sql;
    }

    /**
     * 让值能安全地进 JSON：金额是 {@code numeric}，直接序列化会出现
     * {@code 1068.0000} 这种尾巴；时间类型转字符串，避免前端还要按对象处理。
     */
    private static Object friendly(Object value) {
        if (value instanceof java.math.BigDecimal bd) {
            return bd.stripTrailingZeros().toPlainString();
        }
        if (value instanceof java.time.temporal.Temporal
                || value instanceof java.sql.Timestamp
                || value instanceof java.sql.Date) {
            String text = value.toString();
            return text.length() > 19 ? text.substring(0, 19) : text;
        }
        return value;
    }
}
