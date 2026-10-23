package com.erp.rpt.repository;

import com.erp.rpt.domain.InventorySummaryRow;
import com.erp.rpt.domain.ReportGroupBy;
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
                    rs.getLong("closing_qty"));

    private final JdbcTemplate jdbc;

    public ReportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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

    Sql purchaseSql(LocalDate from, LocalDate to, ReportGroupBy groupBy, Long warehouseId) {
        return documentSql(Source.PURCHASE, from, to, groupBy, warehouseId);
    }

    Sql salesSql(LocalDate from, LocalDate to, ReportGroupBy groupBy, Long warehouseId) {
        return documentSql(Source.SALES, from, to, groupBy, warehouseId);
    }

    private static Sql documentSql(Source src, LocalDate from, LocalDate to,
                                   ReportGroupBy groupBy, Long warehouseId) {
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
        sql.append(" GROUP BY ").append(g.groupByExpr()).append('\n');
        // ORDER BY 1 = 分组键：编码类维度按字典序、day/month 按时间序，结果顺序确定可复现
        sql.append(" ORDER BY 1");
        return new Sql(sql.toString(), args);
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
     * 占位符顺序（与 SQL 文本自上而下一致）：期间起点 ×3（三个 FILTER）、
     * 期间终点 ×1（WHERE）、可选仓库 ×1。改动 SQL 时必须同步改动参数顺序，
     * 这条约束由 {@code ReportQueryPlanIT} 直接跑同一条语句来兜住。
     */
    Sql inventorySummarySql(LocalDateTime periodFrom, LocalDateTime periodTo, Long warehouseId) {
        List<Object> args = new ArrayList<>(List.of(periodFrom, periodFrom, periodFrom));
        StringBuilder sql = new StringBuilder("""
                SELECT l.sku_id,
                       l.warehouse_id,
                       COALESCE(SUM(l.change_qty) FILTER (WHERE l.occurred_at < ?), 0) AS opening_qty,
                       COALESCE(SUM(l.change_qty) FILTER (WHERE l.occurred_at >= ?
                                                            AND l.change_qty > 0), 0) AS inbound_qty,
                       COALESCE(-SUM(l.change_qty) FILTER (WHERE l.occurred_at >= ?
                                                             AND l.change_qty < 0), 0) AS outbound_qty,
                       COALESCE(SUM(l.change_qty), 0) AS closing_qty
                  FROM inventory_ledger l
                 WHERE l.occurred_at < ?
                """);
        args.add(periodTo);
        if (warehouseId != null) {
            sql.append("   AND l.warehouse_id = ?\n");
            args.add(warehouseId);
        }
        sql.append(" GROUP BY l.sku_id, l.warehouse_id\n");
        sql.append(" ORDER BY l.sku_id, l.warehouse_id");
        return new Sql(sql.toString(), args);
    }

    // ─────────────────────────── 片段常量 ───────────────────────────

    /** 事实来源：进货单还是出货单（表名、数量列、金额算式三处不同）。 */
    private enum Source {
        PURCHASE("purchase_orders", "purchase_order_lines", "received_qty",
                "COALESCE(SUM(l.received_qty * l.unit_price_amount), 0)",
                "JOIN suppliers g ON g.id = o.supplier_id"),
        SALES("sales_orders", "sales_order_lines", "shipped_qty",
                "COALESCE(SUM(ROUND((l.unit_price_amount::numeric * l.shipped_qty"
                        + " * l.discount_rate) / 10000)), 0)::bigint",
                "JOIN customers g ON g.id = o.customer_id");

        final String orderTable;
        final String lineTable;
        final String qtyColumn;
        final String amountExpr;
        /** 伙伴主数据（供应商/客户）的关联，仅 SUPPLIER / CUSTOMER 维度使用。 */
        final String partnerJoin;

        Source(String orderTable, String lineTable, String qtyColumn,
               String amountExpr, String partnerJoin) {
            this.orderTable = orderTable;
            this.lineTable = lineTable;
            this.qtyColumn = qtyColumn;
            this.amountExpr = amountExpr;
            this.partnerJoin = partnerJoin;
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
