package com.erp.rpt.repository;

import com.erp.rpt.domain.DocumentDetailRow;
import com.erp.rpt.domain.InventorySummaryExportRow;
import com.erp.rpt.domain.InventorySummaryRow;
import com.erp.rpt.domain.ReportGroupBy;
import com.erp.rpt.domain.ReportOrderBy;
import com.erp.rpt.domain.ReportRow;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 报表聚合查询仓储（<b>只读</b>：本类没有 update / insert / delete，也不会有）。
 *
 * <p><b>★ 一条报表 = 一条聚合 SQL</b>（计划书 §6 坑 8）：分组、求和、计数全部交给
 * 数据库的 {@code GROUP BY}，Java 侧不在循环里查询。TC-RPT-011 用计数化的
 * JdbcTemplate 证明"分组数从 1 涨到 8，语句数恒为 1"，
 * {@code ReportQueryPlanIT} 再用执行计划证明事实表在计划里只出现一次。
 *
 * <p><b>★ 日期口径（写死，勿改）</b>（§3.5 / §6 坑 4）：
 * <ul>
 *   <li>进货统计、出货统计按<b>单据 {@code biz_date}</b> —— 回答"业务单据口径"，
 *       与 §3.4 的日清同源，因此两边能对得上（TC-RPT-010）；</li>
 *   <li>进销存汇总按<b>流水 {@code occurred_at}</b> —— 流水是事实源（ADR-0007），
 *       回答"货什么时候真的动了"。</li>
 * </ul>
 * 两种口径刻意不同，<b>不允许</b>混在同一张报表的合计里。
 *
 * <p><b>为什么 SQL 里只有常量片段</b>：分组维度先转成枚举（{@link ReportGroupBy}），
 * 片段由本类的常量表按枚举取值给出，用户输入一律走 {@code ?} 绑定 ——
 * 拼接式 SQL 的注入面就此封死。
 */
@Repository
public class ReportRepository {

    /**
     * 计入统计的单据状态。
     *
     * <p>与 §3.4 日清的口径<b>逐字相同</b>（{@code PARTIAL / COMPLETED}）：
     * 部分入库/部分出货的单子，其已执行的数量必须进报表，否则报表与日清对不上。
     * DRAFT/SUBMITTED/APPROVED 尚未产生入库/出货，REJECTED/VOIDED 已作废，都不算。
     */
    private static final String COUNTED_STATUSES = "('PARTIAL','COMPLETED')";

    private static final RowMapper<ReportRow> REPORT_ROW = (rs, n) -> new ReportRow(
            rs.getString("key"),
            rs.getString("label"),
            rs.getLong("qty"),
            rs.getLong("amount"),
            rs.getLong("order_count"));

    private static final RowMapper<InventorySummaryRow> SUMMARY_ROW = (rs, n) ->
            new InventorySummaryRow(
                    rs.getLong("sku_id"),
                    rs.getLong("warehouse_id"),
                    rs.getLong("opening_qty"),
                    rs.getLong("inbound_qty"),
                    rs.getLong("outbound_qty"),
                    rs.getLong("closing_qty"),
                    rs.getString("sku_code"),
                    rs.getString("sku_name"),
                    rs.getString("warehouse_name"),
                    nullableLong(rs, "opening_cost_amount"),
                    nullableLong(rs, "inbound_cost_amount"),
                    nullableLong(rs, "outbound_cost_amount"),
                    nullableLong(rs, "closing_cost_amount"));

    /**
     * 导出行读取与 JSON 完全相同的列（同一条 SQL、同一批行、同一组舍入前的整数），
     * 所以 CSV 与页面逐格相同。V4-4 之后 CSV 也带上名称与四个金额列。
     */
    private static final RowMapper<InventorySummaryExportRow> SUMMARY_EXPORT_ROW = (rs, n) ->
            new InventorySummaryExportRow(
                    rs.getLong("sku_id"),
                    rs.getString("sku_name"),
                    rs.getLong("warehouse_id"),
                    rs.getString("warehouse_name"),
                    rs.getLong("opening_qty"),
                    rs.getLong("inbound_qty"),
                    rs.getLong("outbound_qty"),
                    rs.getLong("closing_qty"),
                    rs.getString("sku_code"),
                    nullableLong(rs, "opening_cost_amount"),
                    nullableLong(rs, "inbound_cost_amount"),
                    nullableLong(rs, "outbound_cost_amount"),
                    nullableLong(rs, "closing_cost_amount"));

    private static final RowMapper<DocumentDetailRow> DETAIL_ROW = (rs, n) -> new DocumentDetailRow(
            rs.getString("order_no"),
            rs.getObject("biz_date", java.time.LocalDate.class).toString(),
            rs.getString("status"),
            rs.getString("partner_code"),
            rs.getString("partner_name"),
            rs.getString("warehouse_name"),
            rs.getString("operator"),
            rs.getInt("line_no"),
            rs.getString("sku_code"),
            rs.getString("sku_name"),
            rs.getLong("ordered_qty"),
            rs.getLong("executed_qty"),
            rs.getLong("unit_price_amount"),
            nullableLong(rs, "discount_rate"),
            rs.getLong("amount"));

    /**
     * 可空的 bigint 列。
     *
     * <p>{@code rs.getLong} 对 NULL 返回 0，会把"没算出成本"读成"成本为 0"；
     * 报表里这是两种完全不同的事实，必须分开。
     */
    private static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private final JdbcTemplate jdbc;

    /**
     * 数据权限（FR-IAM-04）。
     *
     * <p><b>★ "统计聚合"越权方式的防线</b>：这是五种越权方式里最隐蔽的一种 ——
     * 攻击者不查明细、只查"本月出货总额"，而汇总里混进了别仓的数字。
     * 页面看不出异常（数字就是个数字），但数据已经泄露。
     * 因此仓库收窄必须同时进入<b>三条</b>聚合 SQL（进货/出货/进销存）。
     *
     * <p>报表是只读的，所以这里只需要仓库维度（没有"谁建的"这回事：
     * 统计的对象是单据事实，不是操作行为）。
     */
    private final com.erp.shared.scope.ScopeContext scope;

    public ReportRepository(JdbcTemplate jdbc, com.erp.shared.scope.ScopeContext scope) {
        this.jdbc = jdbc;
        this.scope = scope;
    }

    /**
     * 报表 SQL 与其绑定参数。包内可见是为了让执行计划用例
     * （{@code ReportQueryPlanIT}）能 EXPLAIN <b>同一条</b> SQL，而不是手写一份副本 ——
     * 副本会和真实查询漂移，那样的"计划证据"等于没有证据。
     */
    record Sql(String text, List<Object> args) {
    }

    // ─────────────────────────── 进货 / 出货统计 ───────────────────────────

    /**
     * 进货统计：期间内<b>已入库</b>数量与金额。
     *
     * <p>金额 = {@code Σ received_qty × unit_price_amount}（进货行没有折扣列，
     * §3.5 明确"金额按单价 × 入库量"）。
     */
    public List<ReportRow> purchaseReport(LocalDate from, LocalDate to,
                                          ReportGroupBy groupBy, Long warehouseId) {
        Sql sql = purchaseSql(from, to, groupBy, warehouseId);
        return jdbc.query(sql.text(), REPORT_ROW, sql.args().toArray());
    }

    /**
     * 进货统计（V4-4 扩展版）。
     *
     * <p>{@code orderBy} / {@code limit} 都是可选的：
     * {@code KEY} + 无 limit 与 {@link #purchaseReport(LocalDate, LocalDate, ReportGroupBy, Long)}
     * 逐字相同（同一份 SQL 文本、同一个顺序），老调用方零行为变化。
     */
    public List<ReportRow> purchaseReport(LocalDate from, LocalDate to, ReportGroupBy groupBy,
                                          Long warehouseId, ReportOrderBy orderBy, Integer limit) {
        Sql sql = purchaseSql(from, to, groupBy, warehouseId, orderBy, limit);
        return jdbc.query(sql.text(), REPORT_ROW, sql.args().toArray());
    }

    /**
     * 出货统计：期间内<b>已出货</b>数量与金额，<b>不含退货</b>（§3.5 + §7 决策 5：
     * 报表展示毛出货，净额看结算的 {@code netAmount}）。
     *
     * <p>金额按行折扣公式 {@code Σ round(unit_price_amount × shipped_qty × discount_rate / 10000)}，
     * 与 {@code SalesOrderApplicationService.lineAmount} 同一个四舍五入口径：
     * 除数 10000 是 10 的幂，PostgreSQL 的 {@code numeric} 除法对此<b>精确终止</b>，
     * 因此 {@code ROUND} 落在 .5 时"远离零"进位，与非负金额上的 {@code BigDecimal HALF_UP}
     * 完全一致（TC-RPT-004 用 Java 侧重算逐行金额来钉死这条等价关系）。
     */
    public List<ReportRow> salesReport(LocalDate from, LocalDate to,
                                       ReportGroupBy groupBy, Long warehouseId) {
        Sql sql = salesSql(from, to, groupBy, warehouseId);
        return jdbc.query(sql.text(), REPORT_ROW, sql.args().toArray());
    }

    /** 出货统计（V4-4 扩展版），语义同 {@link #purchaseReport} 的扩展版。 */
    public List<ReportRow> salesReport(LocalDate from, LocalDate to, ReportGroupBy groupBy,
                                       Long warehouseId, ReportOrderBy orderBy, Integer limit) {
        Sql sql = salesSql(from, to, groupBy, warehouseId, orderBy, limit);
        return jdbc.query(sql.text(), REPORT_ROW, sql.args().toArray());
    }

    Sql purchaseSql(LocalDate from, LocalDate to, ReportGroupBy groupBy, Long warehouseId) {
        return documentSql(Source.PURCHASE, from, to, groupBy, warehouseId,
                scope.current());
    }

    Sql salesSql(LocalDate from, LocalDate to, ReportGroupBy groupBy, Long warehouseId) {
        return documentSql(Source.SALES, from, to, groupBy, warehouseId,
                scope.current());
    }

    Sql purchaseSql(LocalDate from, LocalDate to, ReportGroupBy groupBy, Long warehouseId,
                    ReportOrderBy orderBy, Integer limit) {
        return documentSql(Source.PURCHASE, from, to, groupBy, warehouseId, scope.current(),
                orderBy, limit);
    }

    Sql salesSql(LocalDate from, LocalDate to, ReportGroupBy groupBy, Long warehouseId,
                 ReportOrderBy orderBy, Integer limit) {
        return documentSql(Source.SALES, from, to, groupBy, warehouseId, scope.current(),
                orderBy, limit);
    }

    /**
     * @param dataScope 数据权限。<b>由调用方传入而不是在这里取</b>：
     *                  本方法是静态的（要能被执行计划用例直接调用），
     *                  在静态方法里读实例字段等于绕过构造注入。
     */
    private static Sql documentSql(Source src, LocalDate from, LocalDate to,
                                   ReportGroupBy groupBy, Long warehouseId,
                                   com.erp.shared.scope.DataScope dataScope) {
        return documentSql(src, from, to, groupBy, warehouseId, dataScope,
                ReportOrderBy.KEY, null);
    }

    private static Sql documentSql(Source src, LocalDate from, LocalDate to,
                                   ReportGroupBy groupBy, Long warehouseId,
                                   com.erp.shared.scope.DataScope dataScope,
                                   ReportOrderBy orderBy, Integer limit) {
        Group g = group(src, groupBy);
        List<Object> args = new ArrayList<>(List.of(from, to));

        StringBuilder sql = new StringBuilder("""
                SELECT %s AS key,
                       %s AS label,
                       COALESCE(SUM(l.%s), 0) AS qty,
                       %s AS amount,
                       COUNT(DISTINCT o.id) AS order_count
                  FROM %s o
                  JOIN %s l ON l.order_id = o.id
                  %s
                """.formatted(g.keyExpr(), g.labelExpr(), src.qtyColumn,
                src.amountExpr, src.orderTable, src.lineTable, g.join()));
        sql.append(" WHERE o.biz_date >= ? AND o.biz_date <= ?\n");
        sql.append("   AND o.status IN ").append(COUNTED_STATUSES).append('\n');
        sql.append("   AND l.").append(src.qtyColumn).append(" > 0\n");
        if (warehouseId != null) {
            sql.append("   AND o.warehouse_id = ?\n");
            args.add(warehouseId);
        }
        // ★ 数据权限：与用户给的 warehouseId 是"与"关系，不会互相覆盖 ——
        //   传一个别仓的 warehouseId 只会得到空结果，而不是别人的数据。
        sql.append(dataScope.warehousePredicate("o.warehouse_id", args)).append('\n');
        sql.append(" GROUP BY ").append(g.groupByExpr()).append('\n');
        if (orderBy == ReportOrderBy.AMOUNT) {
            // TOP N 只在金额降序上有意义；同额按分组键升序，顺序仍然确定可复现。
            sql.append(" ORDER BY amount DESC, 1");
        } else {
            // ORDER BY 1 = 分组键：编码类维度按字典序、day/month 按时间序，结果顺序确定可复现
            sql.append(" ORDER BY 1");
        }
        if (limit != null) {
            // limit 由应用服务校验过范围（1~200），并且是 int 绑定，不是拼进 SQL 的字面量。
            sql.append("\n LIMIT ?");
            args.add(limit);
        }
        return new Sql(sql.toString(), args);
    }

    // ─────────────────────────── 单据行级明细（V4-4） ───────────────────────────

    /** 进货明细（按单据展开）。金额算式与进货统计逐字相同，因此两者可逐期核对。 */
    public List<DocumentDetailRow> purchaseDetail(LocalDate from, LocalDate to,
                                                  Long warehouseId, Long partnerId,
                                                  int offset, int size) {
        Sql sql = detailSql(Source.PURCHASE, from, to, warehouseId, partnerId, offset, size);
        return jdbc.query(sql.text(), DETAIL_ROW, sql.args().toArray());
    }

    /** 销售明细（按单据展开）。 */
    public List<DocumentDetailRow> salesDetail(LocalDate from, LocalDate to,
                                               Long warehouseId, Long partnerId,
                                               int offset, int size) {
        Sql sql = detailSql(Source.SALES, from, to, warehouseId, partnerId, offset, size);
        return jdbc.query(sql.text(), DETAIL_ROW, sql.args().toArray());
    }

    /** 明细总行数（与 {@link #detailSql} 同一组过滤条件，只把投影换成 COUNT）。 */
    public long purchaseDetailCount(LocalDate from, LocalDate to, Long warehouseId, Long partnerId) {
        return detailCount(Source.PURCHASE, from, to, warehouseId, partnerId);
    }

    /** 明细总行数（销售）。 */
    public long salesDetailCount(LocalDate from, LocalDate to, Long warehouseId, Long partnerId) {
        return detailCount(Source.SALES, from, to, warehouseId, partnerId);
    }

    /**
     * 明细全量（导出用）。
     *
     * <p>导出<b>不接受</b> page/size：CSV 必须是"这个查询条件下的全部行"，
     * 分页导出会产出十份互不相干的表，既不可核对也违背"导出 == 页面"的初衷。
     * 金额合计与页面各页之和一致，由同一份 SQL 保证。
     */
    public List<DocumentDetailRow> purchaseDetailAll(LocalDate from, LocalDate to,
                                                     Long warehouseId, Long partnerId) {
        Sql sql = detailSql(Source.PURCHASE, from, to, warehouseId, partnerId, 0, 0);
        // size = 0 表示不带 LIMIT（见 detailSql）
        return jdbc.query(sql.text(), DETAIL_ROW, sql.args().toArray());
    }

    /** 明细全量（销售，导出用）。 */
    public List<DocumentDetailRow> salesDetailAll(LocalDate from, LocalDate to,
                                                  Long warehouseId, Long partnerId) {
        Sql sql = detailSql(Source.SALES, from, to, warehouseId, partnerId, 0, 0);
        return jdbc.query(sql.text(), DETAIL_ROW, sql.args().toArray());
    }

    /**
     * 占位符顺序（自上而下）：{@code from}、{@code to}、可选仓库、可选伙伴、
     * 数据权限、{@code size}、{@code offset}。列名全部实测于
     * {@code information_schema}（{@code purchase_order_lines.order_id} /
     * {@code received_qty} / {@code unit_price_amount}、
     * {@code sales_order_lines.discount_rate} 等），不是照抄别处的写法。
     */
    Sql detailSql(Source src, LocalDate from, LocalDate to, Long warehouseId, Long partnerId,
                  int offset, int size) {
        List<Object> args = new ArrayList<>(List.of(from, to));
        String executed = src.qtyColumn;   // received_qty / shipped_qty（实测列名）
        StringBuilder sql = new StringBuilder("""
                SELECT o.order_no,
                       o.biz_date,
                       o.status,
                       g.code AS partner_code,
                       g.name AS partner_name,
                       COALESCE(CASE WHEN w.enabled THEN w.name
                                     ELSE w.name || '（已停用）' END,
                                '仓库#' || o.warehouse_id) AS warehouse_name,
                       u.display_name AS operator,
                       l.line_no,
                       COALESCE(pr.code || '-' || k.color || '-' || k.size,
                                'SKU#' || l.sku_id) AS sku_code,
                       COALESCE(pr.name || ' ' || k.color || '/' || k.size,
                                'SKU#' || l.sku_id) AS sku_name,
                       l.qty AS ordered_qty,
                       l.%1$s AS executed_qty,
                       l.unit_price_amount,
                       %2$s AS discount_rate,
                       %3$s AS amount
                  FROM %4$s o
                  JOIN %5$s l ON l.order_id = o.id
                  %6$s
                  LEFT JOIN warehouses w ON w.id = o.warehouse_id
                  LEFT JOIN app_users u ON u.id = o.created_by
                  LEFT JOIN skus k ON k.id = l.sku_id
                  LEFT JOIN products pr ON pr.id = k.product_id
                 WHERE o.biz_date >= ? AND o.biz_date <= ?
                   AND o.status IN %7$s
                   AND l.%1$s > 0
                """.formatted(executed, src.discountExpr, src.lineAmountExpr,
                src.orderTable, src.lineTable, src.partnerJoin, COUNTED_STATUSES));
        if (warehouseId != null) {
            sql.append("   AND o.warehouse_id = ?\n");
            args.add(warehouseId);
        }
        if (partnerId != null) {
            sql.append("   AND o.").append(src.partnerColumn).append(" = ?\n");
            args.add(partnerId);
        }
        sql.append(scope.current().warehousePredicate("o.warehouse_id", args)).append('\n');
        sql.append(" ORDER BY o.biz_date DESC, o.order_no, l.line_no");
        if (size > 0) {
            sql.append("\n LIMIT ?\n OFFSET ?");
            args.add(size);
            args.add(offset);
        }
        return new Sql(sql.toString(), args);
    }

    private long detailCount(Source src, LocalDate from, LocalDate to,
                             Long warehouseId, Long partnerId) {
        List<Object> args = new ArrayList<>(List.of(from, to));
        StringBuilder sql = new StringBuilder("""
                SELECT COUNT(*)
                  FROM %1$s o
                  JOIN %2$s l ON l.order_id = o.id
                 WHERE o.biz_date >= ? AND o.biz_date <= ?
                   AND o.status IN %3$s
                   AND l.%4$s > 0
                """.formatted(src.orderTable, src.lineTable, COUNTED_STATUSES, src.qtyColumn));
        if (warehouseId != null) {
            sql.append("   AND o.warehouse_id = ?\n");
            args.add(warehouseId);
        }
        if (partnerId != null) {
            sql.append("   AND o.").append(src.partnerColumn).append(" = ?\n");
            args.add(partnerId);
        }
        sql.append(scope.current().warehousePredicate("o.warehouse_id", args)).append('\n');
        Long count = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0 : count;
    }

    // ─────────────────────────── 进销存汇总 ───────────────────────────

    /**
     * 进销存汇总：按 (SKU, 仓库) 给出期初 / 本期入 / 本期出 / 期末。
     *
     * <p>四个数<b>分别独立求和</b>：期末用"截至期末的全部流水净和"，
     * 不是 {@code 期初 + 入 - 出} 推出来的。这样 TC-RPT-006 断言的恒等式
     * 是"两条不同算式的结果相同"，而不是"自己等于自己"。
     *
     * @param periodFrom 账期首日 00:00（含）
     * @param periodTo   下月首日 00:00（不含）—— 用它表达"整个月"的闭区间语义
     */
    public List<InventorySummaryRow> inventorySummary(LocalDateTime periodFrom,
                                                      LocalDateTime periodTo,
                                                      Long warehouseId) {
        Sql sql = inventorySummarySql(periodFrom, periodTo, warehouseId);
        return jdbc.query(sql.text(), SUMMARY_ROW, sql.args().toArray());
    }

    /**
     * 与 {@link #inventorySummary} <b>同一条 SQL</b>，只是额外读取名称列供 CSV 导出。
     * 不另发查询、不重算：导出与页面看到的是同一批行、同一组舍入前的整数。
     */
    public List<InventorySummaryExportRow> inventorySummaryExport(LocalDateTime periodFrom,
                                                                  LocalDateTime periodTo,
                                                                  Long warehouseId) {
        Sql sql = inventorySummarySql(periodFrom, periodTo, warehouseId);
        return jdbc.query(sql.text(), SUMMARY_EXPORT_ROW, sql.args().toArray());
    }

    /**
     * 占位符顺序（与 SQL 文本自上而下一致）：期间起点 ×3（三个 FILTER）、
     * 期间终点 ×1（WHERE）、可选仓库 ×1。改动 SQL 时必须同步改动参数顺序，
     * 这条约束由 {@code ReportQueryPlanIT} 直接跑同一条语句来兜住。
     */
    Sql inventorySummarySql(LocalDateTime periodFrom, LocalDateTime periodTo, Long warehouseId) {
        List<Object> args = new ArrayList<>(List.of(periodFrom, periodFrom, periodFrom));
        StringBuilder sql = new StringBuilder("""
                SELECT l.sku_id,
                       l.warehouse_id,
                       COALESCE(pr.code || '-' || k.color || '-' || k.size,
                                'SKU#' || l.sku_id) AS sku_code,
                       COALESCE(pr.name || ' · ' || k.color || '-' || k.size,
                                'SKU#' || l.sku_id) AS sku_name,
                       COALESCE(CASE WHEN w.enabled THEN w.name
                                     ELSE w.name || '（已停用）' END,
                                '仓库#' || l.warehouse_id) AS warehouse_name,
                       COALESCE(SUM(l.change_qty) FILTER (WHERE l.occurred_at < ?), 0) AS opening_qty,
                       COALESCE(SUM(l.change_qty) FILTER (WHERE l.occurred_at >= ?
                                                            AND l.change_qty > 0), 0) AS inbound_qty,
                       COALESCE(-SUM(l.change_qty) FILTER (WHERE l.occurred_at >= ?
                                                             AND l.change_qty < 0), 0) AS outbound_qty,
                       COALESCE(SUM(l.change_qty), 0) AS closing_qty,
                       cm.opening_cost_amount,
                       cm.inbound_cost_amount,
                       cm.outbound_cost_amount,
                       cm.closing_cost_amount
                  FROM inventory_ledger l
                  LEFT JOIN skus k ON k.id = l.sku_id
                  LEFT JOIN products pr ON pr.id = k.product_id
                  LEFT JOIN warehouses w ON w.id = l.warehouse_id
                  LEFT JOIN cost_monthly cm
                         ON cm.sku_id = l.sku_id AND cm.warehouse_id = l.warehouse_id
                        AND cm.period = ?
                 WHERE l.occurred_at < ?
                """);
        // cost_monthly 的唯一键是 (period, sku_id, warehouse_id)（实测 uq_cost_monthly），
        // 所以这条 LEFT JOIN 每个池最多命中一行，四个金额列可以直接进 GROUP BY，
        // 不需要 MAX() 之类的聚合伪装。账期串由流水时间轴的起点反推，不新增入参。
        args.add(periodFrom.toLocalDate().toString().substring(0, 7));
        args.add(periodTo);
        if (warehouseId != null) {
            sql.append("   AND l.warehouse_id = ?\n");
            args.add(warehouseId);
        }
        // ★ 数据权限：进销存汇总同样要收窄，否则"合计"会把别仓的进出算进来
        sql.append(scope.current().warehousePredicate("l.warehouse_id", args)).append('\n');
        // cm 的四个金额列是"每池最多一行"的直取值（uq_cost_monthly 保证），
        // 列进 GROUP BY 只是为了满足 SQL 语法，不改变分组基数；主键 k.id/pr.id/w.id
        // 让名称列走 PostgreSQL 的函数依赖，与改动前同一套写法。
        sql.append(" GROUP BY l.sku_id, l.warehouse_id, k.id, pr.id, w.id,"
                + " cm.opening_cost_amount, cm.inbound_cost_amount,"
                + " cm.outbound_cost_amount, cm.closing_cost_amount\n");
        sql.append(" ORDER BY l.sku_id, l.warehouse_id");
        return new Sql(sql.toString(), args);
    }

    // ─────────────────────────── 片段常量 ───────────────────────────

    /** 事实来源：进货单还是出货单（表名、数量列、金额算式三处不同）。 */
    private enum Source {
        PURCHASE("purchase_orders", "purchase_order_lines", "received_qty",
                "COALESCE(SUM(l.received_qty * l.unit_price_amount), 0)",
                "JOIN suppliers g ON g.id = o.supplier_id",
                "supplier_id",
                "NULL",
                "COALESCE(l.received_qty * l.unit_price_amount, 0)"),
        SALES("sales_orders", "sales_order_lines", "shipped_qty",
                "COALESCE(SUM(ROUND((l.unit_price_amount::numeric * l.shipped_qty"
                        + " * l.discount_rate) / 10000)), 0)::bigint",
                "JOIN customers g ON g.id = o.customer_id",
                "customer_id",
                "l.discount_rate",
                "COALESCE(ROUND((l.unit_price_amount::numeric * l.shipped_qty"
                        + " * l.discount_rate) / 10000), 0)::bigint");

        final String orderTable;
        final String lineTable;
        final String qtyColumn;
        final String amountExpr;
        /** 伙伴主数据（供应商/客户）的关联，仅 SUPPLIER / CUSTOMER 维度使用。 */
        final String partnerJoin;
        /** 单据上的伙伴外键列名（明细端点的 partnerId 过滤用，实测列名）。 */
        final String partnerColumn;
        /** 行级折扣率表达式；进货单没有折扣列，恒为 NULL。 */
        final String discountExpr;
        /** 行级金额表达式 —— 与聚合端点的 {@link #amountExpr} 同一个舍入口径。 */
        final String lineAmountExpr;

        Source(String orderTable, String lineTable, String qtyColumn,
               String amountExpr, String partnerJoin, String partnerColumn,
               String discountExpr, String lineAmountExpr) {
            this.orderTable = orderTable;
            this.lineTable = lineTable;
            this.qtyColumn = qtyColumn;
            this.amountExpr = amountExpr;
            this.partnerJoin = partnerJoin;
            this.partnerColumn = partnerColumn;
            this.discountExpr = discountExpr;
            this.lineAmountExpr = lineAmountExpr;
        }
    }

    /** 分组片段：关联表 + 分组键 + 展示名 + GROUP BY 列。 */
    private record Group(String join, String keyExpr, String labelExpr, String groupByExpr) {
    }

    private static final String SKU_JOIN =
            "JOIN skus k ON k.id = l.sku_id JOIN products pr ON pr.id = k.product_id";

    private static Group group(Source src, ReportGroupBy dim) {
        return switch (dim) {
            // SKU 没有独立编码列（V1：SKU = 商品 + 颜色 + 尺码），
            // 故分组键用 "商品编码-颜色-尺码"，与主数据页的展示口径一致。
            case SUPPLIER, CUSTOMER -> new Group(src.partnerJoin, "g.code", "g.name",
                    "g.id, g.code, g.name");
            case PRODUCT -> new Group(SKU_JOIN, "pr.code", "pr.name",
                    "pr.id, pr.code, pr.name");
            case SKU -> new Group(SKU_JOIN,
                    "pr.code || '-' || k.color || '-' || k.size",
                    "pr.name || ' ' || k.color || '/' || k.size",
                    "k.id, pr.code, pr.name, k.color, k.size");
            case DAY -> new Group("", "to_char(o.biz_date, 'YYYY-MM-DD')",
                    "to_char(o.biz_date, 'YYYY-MM-DD')", "o.biz_date");
            case MONTH -> new Group("", "to_char(o.biz_date, 'YYYY-MM')",
                    "to_char(o.biz_date, 'YYYY-MM')", "to_char(o.biz_date, 'YYYY-MM')");
        };
    }
}
