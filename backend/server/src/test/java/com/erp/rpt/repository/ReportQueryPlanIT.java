package com.erp.rpt.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.rpt.domain.ReportGroupBy;
import com.erp.support.CaseId;
import com.erp.support.PostgresTestBase;
import com.erp.support.TestDataFactory;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 报表执行计划用例（TC-RPT-013）。
 *
 * <p><b>它证明两件"看起来对、跑起来崩"的事</b>：
 * <ol>
 *   <li><b>分组真的发生在数据库里</b>：计划里有聚合节点，且每张事实表在整个计划中
 *       <b>只出现一次</b>。若有人把聚合改成"逐组回查明细"，同一张表会被反复扫描，
 *       本用例连同 {@code ReportIT} 的语句计数器一起变红。</li>
 *   <li><b>V6 建的索引真的服务这条查询</b>：关掉顺序扫描后计划必须走 {@code idx_rpt_*}，
 *       否则那个索引就是白建 —— 写入付成本、读取无收益。</li>
 * </ol>
 *
 * <p><b>为什么必须人造几十万行</b>：规划器按代价选计划，几百行时它永远选顺序扫描，
 * 那时候"索引被使用"只能靠嘴说。这里把量级做足以触发真实选择，并用
 * {@code SET LOCAL enable_seqscan = off}（在同一事务里，连接确定）做<b>可复现</b>验证：
 * 断言的是"这条索引能服务这些谓词"，而不是"今天优化器心情如何"。
 * 生产环境是否真的选它仍取决于统计信息与数据量 —— 这一点写在这里，不假装测试已经证明。
 *
 * <p>样本只落在 Testcontainers 的临时库里（先 {@code truncateAll()} 再灌），
 * 不碰共享开发库（127.0.0.1:15432），也不留长期数据。
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("报表 · 执行计划（单条聚合、事实表单扫、V6 索引可用）")
class ReportQueryPlanIT extends PostgresTestBase {

    private static final LocalDate FROM = LocalDate.of(2026, 3, 1);
    private static final LocalDate TO = LocalDate.of(2026, 3, 31);
    private static final LocalDateTime FLOW_FROM = LocalDateTime.of(2026, 3, 1, 0, 0);
    private static final LocalDateTime FLOW_TO = LocalDateTime.of(2026, 4, 1, 0, 0);

    @Autowired
    private ReportRepository reports;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TransactionTemplate tx;

    @BeforeAll
    void seedBulkSample() {
        new TestDataFactory(jdbc).truncateAll();

        jdbc.execute("""
                INSERT INTO warehouses (code, name)
                SELECT 'WH-PLAN-' || g, '计划仓' || g FROM generate_series(1, 4) g
                """);
        jdbc.execute("""
                INSERT INTO suppliers (code, name)
                SELECT 'SUP-PLAN-' || g, '计划供应商' || g FROM generate_series(1, 100) g
                """);
        jdbc.execute("""
                INSERT INTO customers (code, name, discount_rate)
                SELECT 'CUS-PLAN-' || g, '计划客户' || g, 9000 FROM generate_series(1, 100) g
                """);
        jdbc.execute("""
                INSERT INTO products (code, name)
                SELECT 'P-PLAN-' || g, '计划商品' || g FROM generate_series(1, 40) g
                """);
        jdbc.execute("""
                INSERT INTO skus (product_id, color, size, retail_price_amount)
                SELECT p.id, '白色', 'L', 12900 FROM products p
                """);

        // 2 万张进货单 / 2 万张出货单，biz_date 横跨一年；报表区间只取其中的 2026-03
        jdbc.execute("""
                INSERT INTO purchase_orders
                    (order_no, status, supplier_id, warehouse_id, biz_date,
                     total_amount, total_qty, period)
                SELECT 'PO-PLAN-' || lpad(g::text, 6, '0'), 'COMPLETED',
                       1 + (g % 100), 1 + (g % 4), DATE '2025-07-01' + (g % 365),
                       10000, 10, to_char(DATE '2025-07-01' + (g % 365), 'YYYY-MM')
                  FROM generate_series(1, 20000) g
                """);
        jdbc.execute("""
                INSERT INTO purchase_order_lines
                    (order_id, line_no, sku_id, qty, received_qty, unit_price_amount, amount)
                SELECT g, 1, 1 + (g % 40), 10, 10, 1000, 10000
                  FROM generate_series(1, 20000) g
                """);
        jdbc.execute("""
                INSERT INTO sales_orders
                    (order_no, status, customer_id, warehouse_id, biz_date,
                     total_amount, total_qty, period)
                SELECT 'SO-PLAN-' || lpad(g::text, 6, '0'), 'COMPLETED',
                       1 + (g % 100), 1 + (g % 4), DATE '2025-07-01' + (g % 365),
                       9000, 5, to_char(DATE '2025-07-01' + (g % 365), 'YYYY-MM')
                  FROM generate_series(1, 20000) g
                """);
        jdbc.execute("""
                INSERT INTO sales_order_lines
                    (order_id, line_no, sku_id, qty, shipped_qty,
                     unit_price_amount, discount_rate, amount)
                SELECT g, 1, 1 + (g % 40), 5, 5, 2000, 9000, 9000
                  FROM generate_series(1, 20000) g
                """);
        // 40 万条流水：进销存汇总要扫的就是它
        jdbc.execute("""
                INSERT INTO inventory_ledger
                    (sku_id, warehouse_id, batch_no, change_qty, biz_type,
                     biz_ref_no, occurred_at)
                SELECT 1 + (g % 40), 1 + (g % 4), '',
                       CASE WHEN g % 2 = 0 THEN 5 ELSE -5 END,
                       CASE WHEN g % 2 = 0 THEN 'PURCHASE_IN' ELSE 'SALES_OUT' END,
                       'LED-PLAN-' || g,
                       TIMESTAMP '2025-07-01 08:00' + (g % 365) * INTERVAL '1 day'
                  FROM generate_series(1, 400000) g
                """);

        for (String table : List.of("warehouses", "suppliers", "customers", "products", "skus",
                "purchase_orders", "purchase_order_lines", "sales_orders", "sales_order_lines",
                "inventory_ledger")) {
            jdbc.execute("ANALYZE " + table);
        }
    }

    @Test
    @CaseId("TC-RPT-013")
    @DisplayName("★ 三条报表计划：事实表各只扫一次、分组在数据库完成、V6 索引可用")
    void reportPlans_areSingleAggregateAndUseV6Indexes() {
        // ① 进货统计（按供应商）：默认计划形态 —— 聚合 + 每张表只出现一次
        var purchase = explain(reports.purchaseSql(FROM, TO, ReportGroupBy.SUPPLIER, null), false);
        assertThat(purchase.normalized())
                .as("计划必须包含聚合节点：分组发生在数据库里，不是 Java 里")
                .contains("aggregate");
        assertThat(purchase.count("purchase_orders")).as("进货单主表只能被扫描一次").isEqualTo(1);
        assertThat(purchase.count("purchase_order_lines")).as("进货明细只能被扫描一次").isEqualTo(1);
        assertThat(purchase.count("suppliers")).as("供应商维表只能被扫描一次").isEqualTo(1);
        assertThat(reports.purchaseReport(FROM, TO, ReportGroupBy.SUPPLIER, null))
                .as("样本里 100 个供应商在 2026-03 都有入库，必须一行不少")
                .hasSize(100);

        // ② 出货统计：V6 为它建的索引必须能被使用
        var sales = explainForced(reports.salesSql(FROM, TO, ReportGroupBy.CUSTOMER, null));
        assertThat(sales.normalized())
                .as("GET /reports/sales 的 (biz_date, status) 谓词必须能用上 V6 索引")
                .contains("idx_rpt_so_bizdate_status");
        assertThat(sales.count("sales_orders")).isEqualTo(1);
        assertThat(sales.count("sales_order_lines")).isEqualTo(1);
        assertThat(sales.count("customers")).isEqualTo(1);

        // ③ 进货统计带仓库过滤 + SKU 分组：仍然是单条聚合、单扫
        var purchaseFiltered = explainForced(reports.purchaseSql(FROM, TO, ReportGroupBy.SKU, 2L));
        assertThat(purchaseFiltered.normalized()).contains("idx_rpt_po_bizdate_status");
        assertThat(purchaseFiltered.count("purchase_orders")).isEqualTo(1);
        assertThat(purchaseFiltered.count("skus")).as("SKU 维表只扫一次，不是每组扫一次")
                .isEqualTo(1);
        assertThat(reports.purchaseReport(FROM, TO, ReportGroupBy.SKU, 2L))
                .as("WH-PLAN-2 的分组结果必须非空")
                .isNotEmpty();

        // ④ 进销存汇总：不带仓库时全量聚合；带仓库时 (warehouse_id, occurred_at) 必须可用
        var summary = explain(reports.inventorySummarySql(FLOW_FROM, FLOW_TO, null), false);
        assertThat(summary.normalized()).contains("aggregate");
        assertThat(summary.count("inventory_ledger")).as("40 万行流水也只扫一次").isEqualTo(1);

        var summaryWh = explain(reports.inventorySummarySql(FLOW_FROM, FLOW_TO, 3L), true);
        assertThat(summaryWh.normalized())
                .as("按仓过滤必须能用上 V6 的 idx_rpt_ledger_wh_occurred")
                .contains("idx_rpt_ledger_wh_occurred");
        assertThat(summaryWh.count("inventory_ledger")).isEqualTo(1);
        assertThat(reports.inventorySummary(FLOW_FROM, FLOW_TO, 3L))
                .as("第 3 仓的样本覆盖 10 个 SKU，必须正好 10 行（串数就会多行或少行）")
                .hasSize(10);

        // 执行计划原文太长且随版本变化，这里输出"节点链摘要"作为可复核的证据：
        // 它落在 Gradle 测试报告的 system-out 里，而不是靠事后一句"我跑过了"。
        System.out.println("[TC-RPT-013] purchase/supplier       → " + purchase.digest());
        System.out.println("[TC-RPT-013] sales/customer forced   → " + sales.digest());
        System.out.println("[TC-RPT-013] purchase/sku+wh forced  → " + purchaseFiltered.digest());
        System.out.println("[TC-RPT-013] summary/all             → " + summary.digest());
        System.out.println("[TC-RPT-013] summary/wh3 forced      → " + summaryWh.digest());
    }

    // ─────────────────────────── EXPLAIN 辅助 ───────────────────────────

    /**
     * @param forceIndex true = 在同一事务里 {@code SET LOCAL enable_seqscan = off}，
     *                   用来确定性地验证"这条索引能服务这些谓词"
     */
    private Plan explain(ReportRepository.Sql sql, boolean forceIndex) {
        String statement = "EXPLAIN (ANALYZE, TIMING OFF, FORMAT JSON) " + sql.text();
        String plan = forceIndex
                ? tx.execute(status -> {
                    jdbc.execute("SET LOCAL enable_seqscan = off");
                    return joinRows(jdbc.queryForList(statement, sql.args().toArray()));
                })
                : joinRows(jdbc.queryForList(statement, sql.args().toArray()));
        assertThat(plan).as("EXPLAIN 必须返回计划").isNotBlank();
        return new Plan(plan);
    }

    private Plan explainForced(ReportRepository.Sql sql) {
        return explain(sql, true);
    }

    /** {@code FORMAT JSON} 是一行一条文本，拼起来再断言。 */
    private static String joinRows(List<Map<String, Object>> rows) {
        StringBuilder sb = new StringBuilder();
        rows.forEach(r -> sb.append(r.values().iterator().next()).append('\n'));
        return sb.toString();
    }

    /** 计划文本：提供"去空格小写"、"某张表被扫描几次"与"节点摘要"三个断言/取证入口。 */
    private record Plan(String raw) {

        String normalized() {
            return raw.replace(" ", "").replace("\n", "").toLowerCase(Locale.ROOT);
        }

        /**
         * {@code "Relation Name": "<table>"} 的出现次数。
         *
         * <p>同一个关系出现两次，就意味着计划里对同一张表做了两遍扫描 ——
         * 那正是"把聚合拆成逐组回查"在计划上的形状。
         */
        int count(String table) {
            String needle = "\"relationname\":\"" + table + "\"";
            String haystack = normalized();
            int n = 0;
            for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
                n++;
            }
            return n;
        }

        /**
         * 节点链摘要，用于把执行计划当成<b>可复核的证据</b>输出（进 Gradle 测试报告的
         * {@code system-out}，而不是只留一句"我跑过了"）。
         */
        String digest() {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"(Node Type|Relation Name|Index Name)\": \"([^\"]+)\"")
                    .matcher(raw);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                sb.append(m.group(1)).append('=').append(m.group(2)).append(" > ");
            }
            return sb.toString();
        }
    }
}
