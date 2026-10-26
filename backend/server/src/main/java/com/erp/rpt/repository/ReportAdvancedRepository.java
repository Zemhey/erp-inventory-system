package com.erp.rpt.repository;

import com.erp.rpt.domain.AgingGroupBy;
import com.erp.rpt.domain.AgingRow;
import com.erp.rpt.domain.AgingType;
import com.erp.rpt.domain.CustomerContributionRow;
import com.erp.rpt.domain.InventoryTurnoverRow;
import com.erp.rpt.domain.OperatingReportRow;
import com.erp.rpt.domain.ReportPeriodDim;
import com.erp.shared.scope.DataScope;
import com.erp.shared.scope.ScopeContext;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * V2-8 报表的聚合查询（<b>只读</b>，一条报表一条 SQL）。
 *
 * <p><b>★ 口径分界线</b>：
 * <ul>
 *   <li>经营报表的期初/入/出/期末、周转与呆滞：库存流水 {@code occurred_at}（事实源，ADR-0007）；</li>
 *   <li>经营报表的进货/销售、客户贡献：单据 {@code biz_date}，状态集合与进货/出货统计逐字相同；</li>
 *   <li>账龄：AR 以到期日起算、AP 以应付单业务日起算。</li>
 * </ul>
 * 两种口径可以出现在同一页面，但绝不互相推算。
 *
 * <p><b>★ 注入而非裸 JDBC</b>：全部语句走注入的 {@link JdbcTemplate}，
 * V2-7 合并后租户过滤在这个入口自动生效。所有用户输入走 {@code ?}，
 * SQL 片段只来自本类的常量。
 *
 * <p>包内可见的 {@code *Sql} 方法供执行计划/参数顺序用例直接复用同一份 SQL，
 * 避免测试手抄副本产生漂移。
 */
@Repository
public class ReportAdvancedRepository {

    private static final String COUNTED_STATUSES = "('PARTIAL','COMPLETED')";

    private static final RowMapper<OperatingReportRow> OPERATING_ROW = (rs, n) ->
            new OperatingReportRow(
                    rs.getString("period"),
                    rs.getLong("opening_qty"),
                    rs.getLong("inbound_qty"),
                    rs.getLong("outbound_qty"),
                    rs.getLong("closing_qty"),
                    rs.getLong("purchase_qty"),
                    rs.getLong("purchase_amount"),
                    rs.getLong("sales_qty"),
                    rs.getLong("sales_amount"));

    private static final RowMapper<InventoryTurnoverRow> TURNOVER_ROW = (rs, n) ->
            new InventoryTurnoverRow(
                    rs.getLong("sku_id"),
                    rs.getString("sku_code"),
                    rs.getString("sku_name"),
                    rs.getLong("warehouse_id"),
                    rs.getString("warehouse_name"),
                    rs.getLong("opening_qty"),
                    rs.getLong("closing_qty"),
                    rs.getLong("outbound_qty"),
                    rs.getBigDecimal("turnover_days"),
                    rs.getLong("idle_days"),
                    rs.getBoolean("stale"));

    private static final RowMapper<CustomerContributionRow> CONTRIBUTION_ROW = (rs, n) ->
            new CustomerContributionRow(
                    rs.getLong("customer_id"),
                    rs.getString("customer_code"),
                    rs.getString("customer_name"),
                    rs.getLong("order_count"),
                    rs.getLong("qty"),
                    rs.getLong("amount"),
                    rs.getBoolean("repurchase"),
                    rs.getBoolean("new_customer"));

    private static final RowMapper<AgingRow> AGING_ROW = (rs, n) ->
            new AgingRow(
                    rs.getLong("partner_id"),
                    rs.getString("partner_code"),
                    rs.getString("partner_name"),
                    rs.getLong("balance_amount"),
                    rs.getLong("bucket0"),
                    rs.getLong("bucket1"),
                    rs.getLong("bucket2"),
                    rs.getLong("bucket3"),
                    rs.getString("doc_no"),
                    rs.getString("doc_date"),
                    rs.getString("due_date"));

    /**
     * 呆滞行复用 {@link InventoryTurnoverRow} 的形状：字段名与 {@code /reports/inventory-turnover}
     * 逐字相同（前端两张表本来就是同一份列定义），因此把呆滞分析切到服务端过滤时
     * 页面不用改一列。
     */
    private static final RowMapper<InventoryTurnoverRow> SLOW_MOVING_ROW = TURNOVER_ROW;

    private final JdbcTemplate jdbc;
    private final ScopeContext scope;

    public ReportAdvancedRepository(JdbcTemplate jdbc, ScopeContext scope) {
        this.jdbc = jdbc;
        this.scope = scope;
    }

    record Sql(String text, List<Object> args) {
    }

    // ─────────────────────────── 经营日报 / 月报 ───────────────────────────

    public List<OperatingReportRow> operatingReport(LocalDate from, LocalDate to,
                                                    ReportPeriodDim dim, Long warehouseId) {
        Sql sql = operatingSql(from, to, dim, warehouseId);
        return jdbc.query(sql.text(), OPERATING_ROW, sql.args().toArray());
    }

    /**
     * 占位符顺序（自上而下）：分桶起止 ×2 → 流水联接过滤 → 期初截止 → 进货区间 ×2 →
     * 出货区间 ×2。分桶粒度以常量写入 SQL（{@code 1 day} / {@code 1 month}），
     * 月份模式把区间扩到完整自然月。
     */
    Sql operatingSql(LocalDate from, LocalDate to, ReportPeriodDim dim, Long warehouseId) {
        boolean monthly = dim == ReportPeriodDim.MONTH;
        LocalDate firstBucket = monthly ? from.withDayOfMonth(1) : from;
        LocalDate lastBucket = monthly ? to.withDayOfMonth(1) : to;
        LocalDate endExclusive = monthly ? lastBucket.plusMonths(1) : to.plusDays(1);
        String interval = monthly ? "1 month" : "1 day";
        String periodFormat = monthly ? "YYYY-MM" : "YYYY-MM-DD";
        String docBucket = monthly ? "date_trunc('month', o.biz_date)::date" : "o.biz_date";
        DataScope dataScope = scope.current();
        List<Object> args = new ArrayList<>(List.of(firstBucket, lastBucket));

        String flowFilter = warehouseAndScope("l.warehouse_id", warehouseId, dataScope, args);
        args.add(firstBucket.atStartOfDay());
        String baseFilter = warehouseAndScope("l.warehouse_id", warehouseId, dataScope, args);
        args.add(firstBucket);
        args.add(endExclusive);
        String purchaseFilter = warehouseAndScope("o.warehouse_id", warehouseId, dataScope, args);
        args.add(firstBucket);
        args.add(endExclusive);
        String salesFilter = warehouseAndScope("o.warehouse_id", warehouseId, dataScope, args);

        String text = """
                WITH buckets AS (
                    SELECT gs::date AS bucket_start
                      FROM generate_series(?::date, ?::date, INTERVAL '%1$s') gs
                ),
                flow AS (
                    SELECT b.bucket_start,
                           COALESCE(SUM(l.change_qty) FILTER (WHERE l.change_qty > 0), 0)
                               AS inbound_qty,
                           COALESCE(-SUM(l.change_qty) FILTER (WHERE l.change_qty < 0), 0)
                               AS outbound_qty,
                           COALESCE(SUM(l.change_qty), 0) AS net_qty
                      FROM buckets b
                      LEFT JOIN inventory_ledger l
                        ON l.occurred_at >= b.bucket_start::timestamp
                       AND l.occurred_at < (b.bucket_start + INTERVAL '%1$s')::timestamp
                       %2$s
                     GROUP BY b.bucket_start
                ),
                base AS (
                    SELECT COALESCE(SUM(l.change_qty), 0) AS qty
                      FROM inventory_ledger l
                     WHERE l.occurred_at < ?
                       %3$s
                ),
                cum AS (
                    SELECT f.bucket_start, f.inbound_qty, f.outbound_qty, f.net_qty,
                           b.qty AS base_qty,
                           SUM(f.net_qty) OVER (ORDER BY f.bucket_start) AS running_qty
                      FROM flow f CROSS JOIN base b
                ),
                purchase AS (
                    SELECT %4$s AS d,
                           COALESCE(SUM(l.received_qty), 0) AS qty,
                           COALESCE(SUM(l.received_qty::bigint * l.unit_price_amount), 0)
                               AS amount
                      FROM purchase_orders o
                      JOIN purchase_order_lines l ON l.order_id = o.id
                     WHERE o.biz_date >= ? AND o.biz_date < ?
                       AND o.status IN %5$s
                       AND l.received_qty > 0
                       %6$s
                     GROUP BY %4$s
                ),
                sales AS (
                    SELECT %4$s AS d,
                           COALESCE(SUM(l.shipped_qty), 0) AS qty,
                           COALESCE(SUM(ROUND((l.unit_price_amount::numeric * l.shipped_qty
                                               * l.discount_rate) / 10000)), 0)::bigint
                               AS amount
                      FROM sales_orders o
                      JOIN sales_order_lines l ON l.order_id = o.id
                     WHERE o.biz_date >= ? AND o.biz_date < ?
                       AND o.status IN %5$s
                       AND l.shipped_qty > 0
                       %7$s
                     GROUP BY %4$s
                )
                SELECT to_char(c.bucket_start, '%8$s') AS period,
                       c.base_qty + COALESCE(c.running_qty - c.net_qty, 0) AS opening_qty,
                       c.inbound_qty,
                       c.outbound_qty,
                       c.base_qty + c.running_qty AS closing_qty,
                       COALESCE(p.qty, 0) AS purchase_qty,
                       COALESCE(p.amount, 0) AS purchase_amount,
                       COALESCE(s.qty, 0) AS sales_qty,
                       COALESCE(s.amount, 0) AS sales_amount
                  FROM cum c
                  LEFT JOIN purchase p ON p.d = c.bucket_start
                  LEFT JOIN sales s ON s.d = c.bucket_start
                 ORDER BY c.bucket_start
                """.formatted(interval, flowFilter, baseFilter, docBucket,
                COUNTED_STATUSES, purchaseFilter, salesFilter, periodFormat);
        return new Sql(text, args);
    }

    // ─────────────────────────── 库存周转与呆滞 ───────────────────────────

    public List<InventoryTurnoverRow> inventoryTurnover(LocalDate from, LocalDate to,
                                                        int deadDays, Long warehouseId) {
        Sql sql = inventoryTurnoverSql(from, to, deadDays, warehouseId);
        return jdbc.query(sql.text(), TURNOVER_ROW, sql.args().toArray());
    }

    Sql inventoryTurnoverSql(LocalDate from, LocalDate to, int deadDays, Long warehouseId) {
        return turnoverSql(from, to, deadDays, warehouseId, null);
    }

    /**
     * 呆滞分析（V4-4 新增端点的仓储方法）。
     *
     * <p><b>刻意不另写一条 SQL</b>：呆滞就是"周转结果里 {@code idle_days >= days} 的那些行"，
     * 口径必须与周转表同源，否则两张表对同一个 SKU 会给出不同的空闲天数
     * （前端兜底版本正是因为共用周转端点才对得上）。这里只在周转查询外面加一层
     * {@code WHERE q.idle_days >= ?}，阈值同时作为 {@code stale} 的判定线。
     */
    public List<InventoryTurnoverRow> slowMoving(LocalDate from, LocalDate to,
                                                 int days, Long warehouseId) {
        Sql sql = slowMovingSql(from, to, days, warehouseId);
        return jdbc.query(sql.text(), SLOW_MOVING_ROW, sql.args().toArray());
    }

    Sql slowMovingSql(LocalDate from, LocalDate to, int days, Long warehouseId) {
        return turnoverSql(from, to, days, warehouseId, days);
    }

    /**
     * 周转与呆滞共用的那条 SQL。
     *
     * @param minIdleDays 非空时在外层追加"空闲天数下限"过滤（呆滞用），
     *                    占位符排在所有既有参数<b>之后</b> —— 因为它在 SQL 文本里出现得最晚，
     *                    既有调用方（{@code minIdleDays = null}）的参数顺序一字未动
     */
    private Sql turnoverSql(LocalDate from, LocalDate to, int deadDays, Long warehouseId,
                            Integer minIdleDays) {
        LocalDateTime fromStart = from.atStartOfDay();
        LocalDateTime toEnd = to.plusDays(1).atStartOfDay();
        long periodDays = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
        DataScope dataScope = scope.current();
        List<Object> args = new ArrayList<>();
        args.add(fromStart);
        args.add(toEnd);
        String stockFilter = warehouseAndScope("l.warehouse_id", warehouseId, dataScope, args);
        args.add(fromStart);
        args.add(toEnd);
        String outboundFilter = warehouseAndScope("l.warehouse_id", warehouseId, dataScope, args);
        args.add(periodDays);
        args.add(to);
        args.add(to);
        args.add(deadDays);

        String text = """
                WITH stock AS (
                    SELECT l.sku_id, l.warehouse_id,
                           COALESCE(SUM(l.change_qty) FILTER (WHERE l.occurred_at < ?), 0)
                               AS opening_qty,
                           COALESCE(SUM(l.change_qty), 0) AS closing_qty,
                           MIN(l.occurred_at) AS first_at,
                           MAX(l.occurred_at) FILTER (WHERE l.change_qty < 0) AS last_out_at
                      FROM inventory_ledger l
                     WHERE l.occurred_at < ?
                       %1$s
                     GROUP BY l.sku_id, l.warehouse_id
                    HAVING COALESCE(SUM(l.change_qty), 0) > 0
                ),
                outbound AS (
                    SELECT l.sku_id, l.warehouse_id,
                           COALESCE(-SUM(l.change_qty) FILTER (WHERE l.change_qty < 0
                                       AND l.occurred_at >= ?), 0) AS qty
                      FROM inventory_ledger l
                     WHERE l.occurred_at < ?
                       %2$s
                     GROUP BY l.sku_id, l.warehouse_id
                )
                SELECT q.*
                  FROM (
                    SELECT s.sku_id,
                           COALESCE(pr.code || '-' || k.color || '-' || k.size,
                                    'SKU#' || s.sku_id) AS sku_code,
                           COALESCE(pr.name || ' ' || k.color || '/' || k.size,
                                    'SKU#' || s.sku_id) AS sku_name,
                           s.warehouse_id,
                           COALESCE(CASE WHEN w.enabled THEN w.name
                                         ELSE w.name || '（已停用）' END,
                                    '仓库#' || s.warehouse_id) AS warehouse_name,
                           s.opening_qty,
                           s.closing_qty,
                           o.qty AS outbound_qty,
                           CASE WHEN o.qty = 0 THEN NULL
                                ELSE ROUND((s.opening_qty + s.closing_qty) / 2.0
                                           * ? / o.qty, 1)
                           END AS turnover_days,
                           ?::date - COALESCE(s.last_out_at, s.first_at)::date AS idle_days,
                           (?::date - COALESCE(s.last_out_at, s.first_at)::date) >= ?
                               AS stale
                      FROM stock s
                      LEFT JOIN skus k ON k.id = s.sku_id
                      LEFT JOIN products pr ON pr.id = k.product_id
                      LEFT JOIN warehouses w ON w.id = s.warehouse_id
                      LEFT JOIN outbound o
                        ON o.sku_id = s.sku_id AND o.warehouse_id = s.warehouse_id
                  ) q
                 WHERE TRUE %3$s
                 ORDER BY q.stale DESC, q.closing_qty DESC, q.sku_id, q.warehouse_id
                """.formatted(stockFilter, outboundFilter,
                minIdleDays == null ? "" : "AND q.idle_days >= ?");
        if (minIdleDays != null) {
            args.add(minIdleDays);
        }
        return new Sql(text, args);
    }

    // ─────────────────────────── 客户贡献与复购 ───────────────────────────

    public List<CustomerContributionRow> customerContribution(LocalDate from, LocalDate to,
                                                              Long warehouseId) {
        Sql sql = customerContributionSql(from, to, warehouseId);
        return jdbc.query(sql.text(), CONTRIBUTION_ROW, sql.args().toArray());
    }

    Sql customerContributionSql(LocalDate from, LocalDate to, Long warehouseId) {
        DataScope dataScope = scope.current();
        List<Object> args = new ArrayList<>();
        args.add(from);
        args.add(to);
        String periodFilter = warehouseAndScope("o.warehouse_id", warehouseId, dataScope, args);
        args.add(from);
        String priorFilter = warehouseAndScope("o.warehouse_id", warehouseId, dataScope, args);

        String text = """
                WITH period AS (
                    SELECT o.customer_id,
                           COUNT(DISTINCT o.id) AS order_count,
                           COALESCE(SUM(l.shipped_qty), 0) AS qty,
                           COALESCE(SUM(ROUND((l.unit_price_amount::numeric * l.shipped_qty
                                               * l.discount_rate) / 10000)), 0)::bigint
                               AS amount
                      FROM sales_orders o
                      JOIN sales_order_lines l ON l.order_id = o.id
                     WHERE o.biz_date >= ? AND o.biz_date <= ?
                       AND o.status IN %1$s
                       AND l.shipped_qty > 0
                       %2$s
                     GROUP BY o.customer_id
                ),
                prior AS (
                    SELECT DISTINCT o.customer_id
                      FROM sales_orders o
                      JOIN sales_order_lines l ON l.order_id = o.id
                     WHERE o.biz_date < ?
                       AND o.status IN %1$s
                       AND l.shipped_qty > 0
                       %3$s
                )
                SELECT c.id AS customer_id,
                       c.code AS customer_code,
                       c.name AS customer_name,
                       p.order_count,
                       p.qty,
                       p.amount,
                       p.order_count >= 2 AS repurchase,
                       pr.customer_id IS NULL AS new_customer
                  FROM period p
                  JOIN customers c ON c.id = p.customer_id
                  LEFT JOIN prior pr ON pr.customer_id = p.customer_id
                 ORDER BY p.amount DESC, c.code
                """.formatted(COUNTED_STATUSES, periodFilter, priorFilter);
        return new Sql(text, args);
    }

    // ─────────────────────────── 应收 / 应付账龄 ───────────────────────────

    public List<AgingRow> aging(AgingType type, LocalDate asOf) {
        Sql sql = agingSql(type, asOf);
        return jdbc.query(sql.text(), AGING_ROW, sql.args().toArray());
    }

    /**
     * 账龄分桶：未到期 / 无到期日归 0–30；逾期天数 31–60、61–90、>90 依次进入后三桶。
     * 只统计未作废且余额 > 0 的行，因此「四桶合计 == 余额」在行级和汇总级都必须成立。
     */
    /** 账龄（V4-4 扩展版）：可按单一往来单位收窄、可按单据展开。 */
    public List<AgingRow> aging(AgingType type, LocalDate asOf, Long partnerId,
                                AgingGroupBy groupBy) {
        Sql sql = agingSql(type, asOf, partnerId, groupBy);
        return jdbc.query(sql.text(), AGING_ROW, sql.args().toArray());
    }

    /**
     * 占位符顺序：{@code asOf}（{@code p} CTE）→ 可选 {@code partnerId}。
     *
     * <p>{@code groupBy=document} 时分组键加上单据 id，四桶与余额的算法一个字没改 ——
     * 只是把同一个往来单位的若干张未结单据摊成若干行，
     * 所以"按单据各行合计 == 按单位那一行"仍然成立（应用服务的分桶守恒校验
     * 对两种粒度同样有效）。
     *
     * <p>列名全部实测：AR 侧 {@code customer_receivables.(customer_id, due_date, biz_date,
     * balance, status, sales_order_id)} 联 {@code sales_orders.order_no}；
     * AP 侧 {@code supplier_payables.(supplier_id, biz_date, balance_amount, payable_no, id)}
     * —— 应付表<b>没有</b> {@code due_date} 列，所以 AP 的到期日列恒为 NULL（不是 0）。
     */
    Sql agingSql(AgingType type, LocalDate asOf) {
        return agingSql(type, asOf, null, AgingGroupBy.PARTNER);
    }

    Sql agingSql(AgingType type, LocalDate asOf, Long partnerId, AgingGroupBy groupBy) {
        boolean byDoc = groupBy == AgingGroupBy.DOCUMENT;
        String source = type == AgingType.AR
                ? """
                  SELECT c.id AS partner_id, c.code AS partner_code, c.name AS partner_name,
                         r.balance AS balance,
                         p.as_of - r.due_date AS overdue_days,
                         r.id AS doc_key,
                         so.order_no AS doc_no,
                         r.biz_date AS doc_date,
                         r.due_date AS due_date
                    FROM customer_receivables r
                    CROSS JOIN p
                    JOIN customers c ON c.id = r.customer_id
                    LEFT JOIN sales_orders so ON so.id = r.sales_order_id
                   WHERE r.status <> 'VOIDED' AND r.balance > 0
                     AND r.biz_date <= p.as_of
                  """
                : """
                  SELECT s.id AS partner_id, s.code AS partner_code, s.name AS partner_name,
                         sp.balance_amount AS balance,
                         p.as_of - sp.biz_date AS overdue_days,
                         sp.id AS doc_key,
                         sp.payable_no AS doc_no,
                         sp.biz_date AS doc_date,
                         NULL AS due_date
                    FROM supplier_payables sp
                    CROSS JOIN p
                    JOIN suppliers s ON s.id = sp.supplier_id
                   WHERE sp.balance_amount > 0
                     AND sp.biz_date <= p.as_of
                  """;
        List<Object> args = new ArrayList<>(List.of(asOf));
        String partnerFilter = "";
        if (partnerId != null) {
            partnerFilter = type == AgingType.AR
                    ? "AND r.customer_id = ?" : "AND sp.supplier_id = ?";
            args.add(partnerId);
        }
        String docCols = byDoc
                ? "doc_no, doc_date::text AS doc_date, due_date::text AS due_date"
                : "NULL AS doc_no, NULL AS doc_date, NULL AS due_date";
        String docGroup = byDoc ? ", doc_key, doc_no, doc_date, due_date" : "";
        String docOrder = byDoc ? ", doc_no" : "";

        String text = """
                WITH p AS (SELECT ?::date AS as_of),
                rows AS (
                %1$s
                  %2$s
                )
                SELECT partner_id, partner_code, partner_name,
                       %3$s,
                       COALESCE(SUM(balance), 0) AS balance_amount,
                       COALESCE(SUM(balance) FILTER (
                           WHERE overdue_days IS NULL OR overdue_days <= 30), 0) AS bucket0,
                       COALESCE(SUM(balance) FILTER (
                           WHERE overdue_days BETWEEN 31 AND 60), 0) AS bucket1,
                       COALESCE(SUM(balance) FILTER (
                           WHERE overdue_days BETWEEN 61 AND 90), 0) AS bucket2,
                       COALESCE(SUM(balance) FILTER (WHERE overdue_days > 90), 0) AS bucket3
                  FROM rows
                 GROUP BY partner_id, partner_code, partner_name%4$s
                 ORDER BY balance_amount DESC, partner_code%5$s
                """.formatted(source, partnerFilter, docCols, docGroup, docOrder);
        return new Sql(text, args);
    }

    /**
     * 过滤片段：显式仓库（与用户选择的 warehouseId 取交集）+ 当前数据权限。
     * 调用方必须传本类的常量列名；用户输入只进 {@code ?}。
     */
    private static String warehouseAndScope(String warehouseColumn, Long warehouseId,
                                            DataScope dataScope, List<Object> args) {
        StringBuilder sql = new StringBuilder();
        if (warehouseId != null) {
            sql.append(" AND ").append(warehouseColumn).append(" = ?");
            args.add(warehouseId);
        }
        sql.append(dataScope.warehousePredicate(warehouseColumn, args));
        return sql.toString();
    }
}
