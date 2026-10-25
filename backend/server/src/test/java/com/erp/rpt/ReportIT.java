package com.erp.rpt;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.pur.application.PurchaseOrderApplicationService;
import com.erp.pur.domain.PurchaseOrder;
import com.erp.rpt.application.ReportApplicationService;
import com.erp.rpt.domain.InventorySummaryRow;
import com.erp.rpt.domain.ReportGroupBy;
import com.erp.rpt.domain.ReportRow;
import com.erp.sal.application.SalesOrderApplicationService;
import com.erp.sal.domain.SalesOrder;
import com.erp.shared.document.DocumentStatus;
import com.erp.support.CaseId;
import com.erp.support.PostgresTestBase;
import com.erp.support.TestClockConfig.ControllableClock;
import com.erp.support.TestDataFactory;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.context.ActiveProfiles;

/**
 * 三类报表的集成测试（FR-RPT-01/02/03，TC-RPT-001~008 + 010~012）。
 *
 * <p><b>断言的主张不是"SQL 返回了什么"，而是"报表与事实对不对得上"</b>：
 * 每张报表都有一条<b>互相独立</b>的复算路径 ——
 * <ul>
 *   <li>进货/出货统计：用单据聚合对象（{@code PurchaseOrder.lines} 的
 *       {@code receivedQty}、{@code SalesOrder} 的 {@code shippedQty}）在 Java 里重算；</li>
 *   <li>进销存汇总：把流水原始行捞出来，在 Java 里按 {@code occurred_at} 分桶重算。</li>
 * </ul>
 * 两条路径一个是 SQL 聚合、一个是 Java 累加，实现不同但答案必须相同 ——
 * 这才是"分组结果与已入库/已出货明细一致"（§4.4）的证明方式。
 * 只拿 SQL 去验 SQL，报的错会被复制成"两边都错得一致"。
 *
 * <p><b>时间全部注入</b>（{@code @ActiveProfiles("clock")}）：单据 {@code biz_date} 由命令传入，
 * 流水 {@code occurred_at} 来自 {@link ControllableClock}，两者可以刻意错开 ——
 * TC-RPT-012 就是靠这个错开来钉死"两种口径不许混"（§6 坑 4）。
 */
@SpringBootTest
@ActiveProfiles("clock")
@DisplayName("报表 · 进货 / 出货 / 进销存（口径、恒等式、无 N+1）")
class ReportIT extends PostgresTestBase {

    private static final LocalDate MARCH_FROM = LocalDate.of(2026, 3, 1);
    private static final LocalDate MARCH_TO = LocalDate.of(2026, 3, 31);
    private static final YearMonth MARCH = YearMonth.of(2026, 3);

    @Autowired
    private ReportApplicationService reports;
    @Autowired
    private PurchaseOrderApplicationService purchaseOrders;
    @Autowired
    private SalesOrderApplicationService salesOrders;
    @Autowired
    private ControllableClock clock;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private StatementCounter counter;

    private TestDataFactory data;

    /** 本用例造过的单据：Java 侧复算的输入，刻意不走 SQL。 */
    private final List<PurchaseOrder> purchases = new ArrayList<>();
    private final List<SalesOrder> shipments = new ArrayList<>();

    /** 分组键/展示名的期望映射（与 SQL 里 to_char / 拼接的结果同一套规则）。 */
    private final Map<Long, String> supplierKey = new HashMap<>();
    private final Map<Long, String> supplierLabel = new HashMap<>();
    private final Map<Long, String> customerKey = new HashMap<>();
    private final Map<Long, String> customerLabel = new HashMap<>();
    private final Map<Long, String> skuKey = new HashMap<>();
    private final Map<Long, String> skuLabel = new HashMap<>();
    private final Map<Long, String> productKeyOfSku = new HashMap<>();
    private final Map<Long, String> productLabelOfSku = new HashMap<>();

    private long wh1;
    private long wh2;
    private long supA;
    private long supB;
    private long cusA;
    private long cusB;
    private long skuA1;
    private long skuA2;
    private long skuB1;

    @BeforeEach
    void setUp() {
        data = new TestDataFactory(jdbc);
        data.truncateAll();
        purchases.clear();
        shipments.clear();
        // 流水时间固定在月中：期间边界（月初 / 下月初）留足余量，口径判断不受时钟漂移影响
        clock.set(LocalDateTime.of(2026, 3, 5, 10, 0, 0));

        wh1 = data.warehouse("WH-RPT-1");
        wh2 = data.warehouse("WH-RPT-2");
        supA = rememberSupplier(data.supplier("SUP-RPT-A"), "SUP-RPT-A");
        supB = rememberSupplier(data.supplier("SUP-RPT-B"), "SUP-RPT-B");
        cusA = rememberCustomer(data.customer("CUS-RPT-A", 10000), "CUS-RPT-A");
        cusB = rememberCustomer(data.customer("CUS-RPT-B", 9000), "CUS-RPT-B");
        long productA = data.product("P-RPT-A", "报表商品A");
        long productB = data.product("P-RPT-B", "报表商品B");
        skuA1 = rememberSku("P-RPT-A", "报表商品A", data.sku(productA, "白色", "L"), "白色", "L");
        skuA2 = rememberSku("P-RPT-A", "报表商品A", data.sku(productA, "黑色", "M"), "黑色", "M");
        skuB1 = rememberSku("P-RPT-B", "报表商品B", data.sku(productB, "白色", "L"), "白色", "L");

        // ① 进货：A 供应商 30 件入 WH1；B 供应商两张行共 17 件入 WH1；A 供应商 8 件入 WH2
        purchase("po-rpt-1", supA, wh1, LocalDate.of(2026, 3, 2), new Line(skuA1, 30, 1000));
        purchase("po-rpt-2", supB, wh1, LocalDate.of(2026, 3, 6),
                new Line(skuA2, 12, 2000), new Line(skuB1, 5, 500));
        purchase("po-rpt-3", supA, wh2, LocalDate.of(2026, 3, 10), new Line(skuB1, 8, 1500));

        // ② 出货：合计 17 件 / 55707 分（其中 so-rpt-3 特意造出一个 .5 的四舍五入尾差）
        ship("so-rpt-1", cusA, wh1, LocalDate.of(2026, 3, 7), skuA1, 10, 1000, 10000);
        ship("so-rpt-2", cusB, wh1, LocalDate.of(2026, 3, 15), skuA2, 4, 2000, 9000);
        ship("so-rpt-3", cusB, wh1, LocalDate.of(2026, 3, 20), skuB1, 3, 12900, 9950);
    }

    // ─────────────────────────── 进货统计 ───────────────────────────

    @Test
    @CaseId("TC-RPT-001")
    @DisplayName("进货统计按供应商分组：数量/金额与已入库明细一致；未入库与已作废不进统计")
    void purchaseReport_bySupplier_matchesReceivedDetail() {
        var rows = reports.purchaseReport(MARCH_FROM, MARCH_TO, ReportGroupBy.SUPPLIER, null);

        // po-rpt-1（WH1 30 件）+ po-rpt-3（WH2 8 件）都挂在 A 供应商身上：不过滤仓库时必须两仓合计
        assertThat(cells(rows)).containsExactlyInAnyOrder(
                "SUP-RPT-A|测试供应商-SUP-RPT-A|38|42000|2",
                "SUP-RPT-B|测试供应商-SUP-RPT-B|17|26500|1");
        assertMatchesRecomputed(rows, expectedPurchase(ReportGroupBy.SUPPLIER,
                MARCH_FROM, MARCH_TO, null));

        // ★ 口径：只统计 received_qty > 0 的单据 —— 审核完但没入库、以及草稿作废的单都不算
        var before = List.copyOf(rows);
        purchaseApprovedOnly("po-rpt-pending", supA, wh1, LocalDate.of(2026, 3, 4),
                new Line(skuA1, 7, 1000));
        purchaseVoidedDraft("po-rpt-voided", supB, wh1, LocalDate.of(2026, 3, 5),
                new Line(skuA1, 9, 1000));
        assertThat(reports.purchaseReport(MARCH_FROM, MARCH_TO, ReportGroupBy.SUPPLIER, null))
                .as("已审核未入库 / 已作废都不得进入进货统计")
                .isEqualTo(before);
    }

    @Test
    @CaseId("TC-RPT-002")
    @DisplayName("进货统计按 product / sku / day / month 分组：行数与合计全部对得上")
    void purchaseReport_otherDimensions_keepSameTotals() {
        long totalQty = 0;
        long totalAmount = 0;
        for (ReportGroupBy dim : List.of(ReportGroupBy.SUPPLIER, ReportGroupBy.PRODUCT,
                ReportGroupBy.SKU, ReportGroupBy.DAY, ReportGroupBy.MONTH)) {
            var rows = reports.purchaseReport(MARCH_FROM, MARCH_TO, dim, null);
            assertMatchesRecomputed(rows, expectedPurchase(dim, MARCH_FROM, MARCH_TO, null));
            totalQty = rows.stream().mapToLong(ReportRow::qty).sum();
            totalAmount = rows.stream().mapToLong(ReportRow::amount).sum();
            assertThat(totalQty).as("%s 分组的数量合计必须相同", dim).isEqualTo(55);
            assertThat(totalAmount).as("%s 分组的金额合计必须相同", dim).isEqualTo(68500);
        }

        assertThat(cells(reports.purchaseReport(MARCH_FROM, MARCH_TO, ReportGroupBy.DAY, null)))
                .as("按日分组：一天一行，日期就是键")
                .containsExactlyInAnyOrder(
                        "2026-03-02|2026-03-02|30|30000|1",
                        "2026-03-06|2026-03-06|17|26500|1",
                        "2026-03-10|2026-03-10|8|12000|1");
        assertThat(cells(reports.purchaseReport(MARCH_FROM, MARCH_TO, ReportGroupBy.MONTH, null)))
                .as("按月分组：账期就是键，单据数按 COUNT(DISTINCT) 计")
                .containsExactly("2026-03|2026-03|55|68500|3");
    }

    // ─────────────────────────── 出货统计 ───────────────────────────

    @Test
    @CaseId("TC-RPT-003")
    @DisplayName("出货统计按客户分组：金额按行折扣公式；.5 尾差与 Java HALF_UP 完全一致")
    void salesReport_byCustomer_usesDiscountFormula() {
        // 15 件 × 不打折 = 10000 + 7200；3 件 × 9950 折 = 38506.5 → HALF_UP → 38507
        assertThat(SalesOrderApplicationService.lineAmount(3, 12900, 9950))
                .as("先固定 Java 侧口径，再要求 SQL 与它相等")
                .isEqualTo(38507);
        assertThat(cells(reports.salesReport(MARCH_FROM, MARCH_TO, ReportGroupBy.CUSTOMER, null)))
                .containsExactlyInAnyOrder(
                        "CUS-RPT-A|测试客户-CUS-RPT-A|10|10000|1",
                        "CUS-RPT-B|测试客户-CUS-RPT-B|7|45707|2");
        assertMatchesRecomputed(reports.salesReport(MARCH_FROM, MARCH_TO,
                ReportGroupBy.CUSTOMER, null),
                expectedSales(ReportGroupBy.CUSTOMER, MARCH_FROM, MARCH_TO, null));
    }

    @Test
    @CaseId("TC-RPT-004")
    @DisplayName("出货统计按 product / sku / day / month 分组：合计一致，逐行金额等于 SAL 的行金额公式")
    void salesReport_otherDimensions_keepSameTotals() {
        for (ReportGroupBy dim : List.of(ReportGroupBy.CUSTOMER, ReportGroupBy.PRODUCT,
                ReportGroupBy.SKU, ReportGroupBy.DAY, ReportGroupBy.MONTH)) {
            var rows = reports.salesReport(MARCH_FROM, MARCH_TO, dim, null);
            assertMatchesRecomputed(rows, expectedSales(dim, MARCH_FROM, MARCH_TO, null));
            assertThat(rows.stream().mapToLong(ReportRow::qty).sum())
                    .as("%s 分组的数量合计", dim).isEqualTo(17);
            assertThat(rows.stream().mapToLong(ReportRow::amount).sum())
                    .as("%s 分组的金额合计", dim).isEqualTo(55707);
        }

        // 逐行公式复算（独立于 SQL 的 numeric ROUND）：SQL 必须等于 Σ lineAmount(shipped, unit, rate)
        long javaSide = shipments.stream()
                .flatMap(o -> o.lines().stream())
                .filter(l -> l.shippedQty() > 0)
                .mapToLong(l -> SalesOrderApplicationService.lineAmount(
                        l.shippedQty(), l.unitPriceAmount(), l.discountRate()))
                .sum();
        assertThat(reports.salesReport(MARCH_FROM, MARCH_TO, ReportGroupBy.MONTH, null)
                .get(0).amount()).isEqualTo(javaSide);
    }

    @Test
    @CaseId("TC-RPT-005")
    @DisplayName("★ 日期闭区间 [from, to]：首末日都包含，越界一天也不含")
    void dateRange_isInclusiveOnBothEnds() {
        purchase("po-rpt-first", supA, wh1, LocalDate.of(2026, 2, 28), new Line(skuA1, 4, 1000));
        purchase("po-rpt-last", supA, wh1, LocalDate.of(2026, 3, 31), new Line(skuA1, 6, 1000));
        purchase("po-rpt-next", supA, wh1, LocalDate.of(2026, 4, 1), new Line(skuA1, 8, 1000));

        assertThat(dayKeys(reports.purchaseReport(MARCH_FROM, MARCH_TO, ReportGroupBy.DAY, null)))
                .as("首日 03-01 与末日 03-31 都算，2/28 与 4/1 都不算")
                .containsExactlyInAnyOrder("2026-03-02", "2026-03-06", "2026-03-10", "2026-03-31");

        assertThat(dayKeys(reports.purchaseReport(MARCH_FROM, LocalDate.of(2026, 3, 30),
                ReportGroupBy.DAY, null)))
                .as("to 往前挪一天，3-31 那单就必须消失")
                .doesNotContain("2026-03-31");

        assertThat(dayKeys(reports.purchaseReport(LocalDate.of(2026, 3, 3), MARCH_TO,
                ReportGroupBy.DAY, null)))
                .as("from 往后挪一天，3-02 那单就必须消失")
                .doesNotContain("2026-03-02");
    }

    // ─────────────────────────── 进销存汇总 ───────────────────────────

    @Test
    @CaseId("TC-RPT-006")
    @DisplayName("★ 进销存恒等式：期初+入−出=期末，且期末等于截至期末的流水净和与当前余额")
    void inventorySummary_identityAndLedgerNet() {
        // 先补一段"期间之前"的活动：没有它，期初恒为 0，恒等式就只验到三条腿（假绿）
        clock.set(LocalDateTime.of(2026, 2, 10, 9, 0, 0));
        purchase("po-rpt-feb", supA, wh1, LocalDate.of(2026, 2, 10), new Line(skuA1, 7, 1000));
        ship("so-rpt-feb", cusA, wh1, LocalDate.of(2026, 2, 11), skuA1, 2, 1000, 10000);
        clock.set(LocalDateTime.of(2026, 3, 5, 10, 0, 0));

        var rows = reports.inventorySummaryReport(MARCH, null);
        assertThat(rows).isNotEmpty();
        assertThat(rows).anySatisfy(r -> {
            assertThat(r.skuId()).isEqualTo(skuA1);
            assertThat(r.warehouseId()).isEqualTo(wh1);
            assertThat(r.openingQty()).as("期初必须来自 2 月的流水净和（7 − 2），不能是 0").isEqualTo(5);
        });

        // ① 恒等式：对每一行都成立
        for (InventorySummaryRow r : rows) {
            assertThat(r.openingQty() + r.inboundQty() - r.outboundQty())
                    .as("(sku=%d, wh=%d) 必须满足 opening + in - out = closing",
                            r.skuId(), r.warehouseId())
                    .isEqualTo(r.closingQty());
        }

        // ② 期末 == 截至期末的流水净和：把原始流水捞出来在 Java 里分桶，独立复算四个数
        Map<String, long[]> net = recomputeFromRawLedger(MARCH);
        assertThat(rows).extracting(r -> r.skuId() + ":" + r.warehouseId())
                .as("汇总行必须与流水里出现过的 (SKU, 仓库) 组合一一对应")
                .containsExactlyInAnyOrderElementsOf(net.keySet());
        for (InventorySummaryRow r : rows) {
            long[] v = net.get(r.skuId() + ":" + r.warehouseId());
            assertThat(new long[]{r.openingQty(), r.inboundQty(), r.outboundQty(), r.closingQty()})
                    .as("(sku=%d, wh=%d) 期初/入/出/期末必须与 Java 重算一致", r.skuId(), r.warehouseId())
                    .containsExactly(v[0], v[1], v[2], v[3]);
        }

        // ③ 本期就是最后一个有流水的期间 → 期末必须等于 inventory_balances 的实存
        Map<String, Long> onHand = new HashMap<>();
        jdbc.query("""
                SELECT sku_id, warehouse_id, SUM(on_hand_qty) AS on_hand
                  FROM inventory_balances
                 GROUP BY sku_id, warehouse_id
                """, (RowCallbackHandler) rs ->
            onHand.put(rs.getLong("sku_id") + ":" + rs.getLong("warehouse_id"),
                    rs.getLong("on_hand")));
        for (InventorySummaryRow r : rows) {
            assertThat(r.closingQty())
                    .as("(sku=%d, wh=%d) 期末必须等于余额表的实存净和（不变量 INV-2 的另一面）",
                            r.skuId(), r.warehouseId())
                    .isEqualTo(onHand.getOrDefault(r.skuId() + ":" + r.warehouseId(), 0L));
        }
        assertThat(onHand).as("余额表不应出现流水里没有的组合").hasSize(rows.size());
    }

    @Test
    @CaseId("TC-RPT-007")
    @DisplayName("仓库过滤：只统计指定仓库，跨仓不串数；两仓之和等于不过滤")
    void warehouseFilter_doesNotMixWarehouses() {
        assertThat(cells(reports.purchaseReport(MARCH_FROM, MARCH_TO, ReportGroupBy.SUPPLIER, wh1)))
                .as("WH1 只有 po-rpt-1 / po-rpt-2")
                .containsExactlyInAnyOrder(
                        "SUP-RPT-A|测试供应商-SUP-RPT-A|30|30000|1",
                        "SUP-RPT-B|测试供应商-SUP-RPT-B|17|26500|1");
        assertThat(cells(reports.purchaseReport(MARCH_FROM, MARCH_TO, ReportGroupBy.SUPPLIER, wh2)))
                .as("WH2 只有 po-rpt-3，数量金额都不能和 WH1 混")
                .containsExactly("SUP-RPT-A|测试供应商-SUP-RPT-A|8|12000|1");

        long wh1Qty = sum(reports.purchaseReport(MARCH_FROM, MARCH_TO, ReportGroupBy.SUPPLIER, wh1));
        long wh2Qty = sum(reports.purchaseReport(MARCH_FROM, MARCH_TO, ReportGroupBy.SUPPLIER, wh2));
        long allQty = sum(reports.purchaseReport(MARCH_FROM, MARCH_TO, ReportGroupBy.SUPPLIER, null));
        assertThat(wh1Qty + wh2Qty).as("分仓之和必须等于全部，缺一件都算串数").isEqualTo(allQty);

        assertThat(cells(reports.salesReport(MARCH_FROM, MARCH_TO, ReportGroupBy.CUSTOMER, wh2)))
                .as("WH2 没有出货")
                .isEmpty();

        var summaryWh2 = reports.inventorySummaryReport(MARCH, wh2);
        assertThat(summaryWh2).allSatisfy(r ->
                assertThat(r.warehouseId()).isEqualTo(wh2));
        assertThat(summaryWh2).as("WH2 只有 skuB1 进过 8 件").hasSize(1);
        assertThat(cells2(summaryWh2)).containsExactly(
                skuB1 + ":" + wh2 + "|0|8|0|8");
    }

    @Test
    @CaseId("TC-RPT-008")
    @DisplayName("空区间返回空 items，不是错误")
    void emptyRange_returnsEmptyItemsNotError() {
        var purchase = reports.purchaseReport(LocalDate.of(2020, 1, 1),
                LocalDate.of(2020, 1, 31), ReportGroupBy.SUPPLIER, null);
        var sales = reports.salesReport(LocalDate.of(2020, 1, 1),
                LocalDate.of(2020, 1, 31), ReportGroupBy.CUSTOMER, null);
        var summary = reports.inventorySummaryReport(YearMonth.of(2020, 1), null);

        assertThat(purchase).as("空区间是合法结果，必须返回空数组而不是异常").isEmpty();
        assertThat(sales).isEmpty();
        assertThat(summary).isEmpty();
    }

    // ─────────────────────────── 口径对照与查询形态 ───────────────────────────

    @Test
    @CaseId("TC-RPT-010")
    @DisplayName("★ 与日清口径可对照：同日同仓的采购/销售数量金额，与 §3.4 日清公式重算一致")
    void dailySettlementParity_sameDateWarehouseTotals() {
        // §3.4 日清的口径：按单据 biz_date + status IN ('PARTIAL','COMPLETED')，
        // purchase = Σ received_qty / Σ unit_price × received_qty，
        // sales   = Σ shipped_qty / Σ round(unit_price × shipped_qty × discount / 10000)。
        // 报表按日分组 + 指定仓库，得到的就是同一格子的数 —— 两张表必须一模一样。
        for (LocalDate day : List.of(LocalDate.of(2026, 3, 2), LocalDate.of(2026, 3, 6),
                LocalDate.of(2026, 3, 10), LocalDate.of(2026, 3, 7),
                LocalDate.of(2026, 3, 15), LocalDate.of(2026, 3, 20))) {
            var reportDay = reports.purchaseReport(day, day, ReportGroupBy.DAY, null).stream()
                    .findFirst();
            long[] daily = dailyPurchase(day, null);
            if (daily[0] == 0) {
                assertThat(reportDay).isEmpty();
            } else {
                assertThat(reportDay.orElseThrow().qty() + "/" + reportDay.orElseThrow().amount())
                        .as("%s 的进货报表行必须等于日清的 purchaseQty / purchaseAmount", day)
                        .isEqualTo(daily[0] + "/" + daily[1]);
            }
            var salesDay = reports.salesReport(day, day, ReportGroupBy.DAY, null).stream()
                    .findFirst();
            long[] salesDaily = dailySales(day, null);
            if (salesDaily[0] == 0) {
                assertThat(salesDay).isEmpty();
            } else {
                assertThat(salesDay.orElseThrow().qty() + "/" + salesDay.orElseThrow().amount())
                        .as("%s 的出货报表行必须等于日清的 salesQty / salesAmount", day)
                        .isEqualTo(salesDaily[0] + "/" + salesDaily[1]);
            }
        }

        // 按仓库的日清格子（WH1，2026-03-06）：报表带 warehouseId 必须与带同一仓库的日清重算一致
        assertThat(reports.purchaseReport(LocalDate.of(2026, 3, 6), LocalDate.of(2026, 3, 6),
                ReportGroupBy.DAY, wh1).get(0).amount())
                .isEqualTo(dailyPurchase(LocalDate.of(2026, 3, 6), wh1)[1]);
        assertThat(reports.purchaseReport(LocalDate.of(2026, 3, 6), LocalDate.of(2026, 3, 6),
                ReportGroupBy.DAY, wh2))
                .as("这天 WH2 没有入库，日清不会有这一格，报表也不能凭空造数")
                .isEmpty();
    }

    @Test
    @CaseId("TC-RPT-011")
    @DisplayName("★ 无 N+1：分组数从 1 涨到 8，每张报表仍只发一条聚合 SQL")
    void reports_issueExactlyOneStatement_regardlessOfGroupCount() {
        counter.reset();
        assertThat(reports.purchaseReport(MARCH_FROM, MARCH_TO, ReportGroupBy.SUPPLIER, null))
                .as("3 个供应商分组")
                .hasSize(2);
        assertThat(counter.statements).as("一条聚合 SQL，不在循环里查").isOne();

        counter.reset();
        assertThat(reports.inventorySummaryReport(MARCH, null)).isNotEmpty();
        assertThat(counter.statements).isOne();

        // 把分组数撑到 8：如果实现是"逐组查明细"，语句数会跟着分组数一起涨
        for (int i = 1; i <= 6; i++) {
            long supplier = rememberSupplier(data.supplier("SUP-RPT-X" + i), "SUP-RPT-X" + i);
            purchase("po-rpt-x" + i, supplier, wh1, LocalDate.of(2026, 3, 11),
                    new Line(skuA1, 1, 100));
        }
        counter.reset();
        var rows = reports.purchaseReport(MARCH_FROM, MARCH_TO, ReportGroupBy.SUPPLIER, null);
        assertThat(rows).as("8 个供应商 = 8 行，但语句数不变").hasSizeGreaterThanOrEqualTo(8);
        assertThat(counter.statements)
                .as("★ 分组数翻 4 倍，SQL 条数仍然是 1 —— 这才叫没有 N+1")
                .isOne();

        counter.reset();
        reports.salesReport(MARCH_FROM, MARCH_TO, ReportGroupBy.PRODUCT, null);
        assertThat(counter.statements).isOne();
    }

    @Test
    @CaseId("TC-RPT-012")
    @DisplayName("★ 两种口径不许混：单据 biz_date vs 流水 occurred_at，跨天执行各归各的")
    void documentDateAndFlowDateAreDeliberatelyDifferent() {
        // 一张 biz_date 落在 2 月的进货单，实际在 3 月才执行入库（跨天补录）
        clock.set(LocalDateTime.of(2026, 3, 20, 15, 0, 0));
        purchase("po-rpt-cross", supA, wh1, LocalDate.of(2026, 2, 28), new Line(skuA1, 5, 1000));

        assertThat(cells(reports.purchaseReport(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28),
                ReportGroupBy.DAY, null)))
                .as("进货统计按单据日期：归到 2-28")
                .containsExactly("2026-02-28|2026-02-28|5|5000|1");

        var feb = reports.inventorySummaryReport(YearMonth.of(2026, 2), null);
        var mar = reports.inventorySummaryReport(YearMonth.of(2026, 3), null);
        assertThat(feb).as("2 月汇总里没有这笔：流水发生在 3-20，事实源说了算").isEmpty();
        assertThat(mar).anySatisfy(r -> {
            assertThat(r.skuId()).isEqualTo(skuA1);
            assertThat(r.warehouseId()).isEqualTo(wh1);
            assertThat(r.inboundQty()).as("3 月入库含 30+5+4（30 与 5 都发生在 3 月，4 是出货回补的对冲）")
                    .isEqualTo(35);
        });
    }

    // ─────────────────────────── 造数 ───────────────────────────

    private record Line(long skuId, int qty, long unitPrice) {
    }

    /** 建单 → 提交 → 审核 → 全额入库（流水按当前注入时钟）。 */
    private PurchaseOrder purchase(String req, long supplierId, long warehouseId,
                                   LocalDate bizDate, Line... lines) {
        var created = createPurchase(req, supplierId, warehouseId, bizDate, lines);
        purchaseOrders.submit(created.id());
        purchaseOrders.approve(created.id());
        var received = purchaseOrders.receive(created.id(),
                new PurchaseOrderApplicationService.ReceiveCommand(
                        created.lines().stream()
                                .map(l -> new PurchaseOrderApplicationService.ReceiveLineCommand(
                                        l.id(), l.qty(), null, null, null))
                                .toList(),
                        req + "-recv"));
        purchases.add(received);
        return received;
    }

    /** 建单 → 提交 → 审核，但<b>不入库</b>：验证"审核完不等于进了报表"。 */
    private PurchaseOrder purchaseApprovedOnly(String req, long supplierId, long warehouseId,
                                               LocalDate bizDate, Line... lines) {
        var created = createPurchase(req, supplierId, warehouseId, bizDate, lines);
        purchaseOrders.submit(created.id());
        var approved = purchaseOrders.approve(created.id());
        purchases.add(approved);
        return approved;
    }

    /** 草稿直接作废：验证"作废单不得出现在任何报表里"。 */
    private PurchaseOrder purchaseVoidedDraft(String req, long supplierId, long warehouseId,
                                              LocalDate bizDate, Line... lines) {
        var created = createPurchase(req, supplierId, warehouseId, bizDate, lines);
        var voided = purchaseOrders.voidOrder(created.id(), req + "-void");
        purchases.add(voided);
        return voided;
    }

    private PurchaseOrder createPurchase(String req, long supplierId, long warehouseId,
                                         LocalDate bizDate, Line[] lines) {
        return purchaseOrders.create(new PurchaseOrderApplicationService.CreateCommand(
                supplierId, warehouseId, bizDate, "报表备货",
                List.of(lines).stream()
                        .map(l -> new PurchaseOrderApplicationService.CreateLineCommand(
                                l.skuId(), l.qty(), l.unitPrice()))
                        .toList(),
                req));
    }

    private SalesOrder ship(String req, long customerId, long warehouseId, LocalDate bizDate,
                            long skuId, int qty, long unitPrice, Integer discountRate) {
        var created = salesOrders.create(new SalesOrderApplicationService.CreateCommand(
                customerId, warehouseId, bizDate, "报表出货",
                List.of(new SalesOrderApplicationService.CreateLineCommand(
                        skuId, qty, unitPrice, discountRate)),
                req));
        salesOrders.submit(created.id());
        salesOrders.approve(created.id());
        var shipped = salesOrders.ship(created.id(), req + "-ship");
        shipments.add(shipped);
        return shipped;
    }

    // ─────────────────────────── Java 侧重算（与 SQL 互为独立路径） ───────────────────────────

    private Map<String, Agg> expectedPurchase(ReportGroupBy dim, LocalDate from, LocalDate to,
                                              Long warehouseId) {
        Map<String, Agg> out = new LinkedHashMap<>();
        for (PurchaseOrder o : purchases) {
            if (!isCounted(o.status()) || o.bizDate().isBefore(from) || o.bizDate().isAfter(to)) {
                continue;
            }
            if (warehouseId != null && o.warehouseId() != warehouseId) {
                continue;
            }
            for (var l : o.lines()) {
                if (l.receivedQty() <= 0) {
                    continue;
                }
                Agg agg = out.computeIfAbsent(purchaseKey(dim, o, l.skuId()),
                        k -> new Agg(purchaseLabel(dim, o, l.skuId())));
                agg.qty += l.receivedQty();
                agg.amount += (long) l.receivedQty() * l.unitPriceAmount();
                agg.orders.add(o.id());
            }
        }
        return out;
    }

    private Map<String, Agg> expectedSales(ReportGroupBy dim, LocalDate from, LocalDate to,
                                           Long warehouseId) {
        Map<String, Agg> out = new LinkedHashMap<>();
        for (SalesOrder o : shipments) {
            if (!isCounted(o.status()) || o.bizDate().isBefore(from) || o.bizDate().isAfter(to)) {
                continue;
            }
            if (warehouseId != null && o.warehouseId() != warehouseId) {
                continue;
            }
            for (var l : o.lines()) {
                if (l.shippedQty() <= 0) {
                    continue;
                }
                Agg agg = out.computeIfAbsent(salesKey(dim, o, l.skuId()),
                        k -> new Agg(salesLabel(dim, o, l.skuId())));
                agg.qty += l.shippedQty();
                // 与 SAL 同一个公式实现，不复制第二份
                agg.amount += SalesOrderApplicationService.lineAmount(
                        l.shippedQty(), l.unitPriceAmount(), l.discountRate());
                agg.orders.add(o.id());
            }
        }
        return out;
    }

    /** 日清口径重算（§3.4 表格里的 purchase 两列），用单据对象而不是 SQL。 */
    private long[] dailyPurchase(LocalDate day, Long warehouseId) {
        long qty = 0;
        long amount = 0;
        for (PurchaseOrder o : purchases) {
            if (!isCounted(o.status()) || !o.bizDate().equals(day)) {
                continue;
            }
            if (warehouseId != null && o.warehouseId() != warehouseId) {
                continue;
            }
            for (var l : o.lines()) {
                if (l.receivedQty() > 0) {
                    qty += l.receivedQty();
                    amount += (long) l.receivedQty() * l.unitPriceAmount();
                }
            }
        }
        return new long[]{qty, amount};
    }

    private long[] dailySales(LocalDate day, Long warehouseId) {
        long qty = 0;
        long amount = 0;
        for (SalesOrder o : shipments) {
            if (!isCounted(o.status()) || !o.bizDate().equals(day)) {
                continue;
            }
            if (warehouseId != null && o.warehouseId() != warehouseId) {
                continue;
            }
            for (var l : o.lines()) {
                if (l.shippedQty() > 0) {
                    qty += l.shippedQty();
                    amount += SalesOrderApplicationService.lineAmount(
                            l.shippedQty(), l.unitPriceAmount(), l.discountRate());
                }
            }
        }
        return new long[]{qty, amount};
    }

    /**
     * 把流水原始行捞出来，在 Java 里按 {@code occurred_at} 分桶重算四个数。
     *
     * <p>读 {@code OffsetDateTime} 再取 {@code toLocalDateTime()} 与写入时的
     * {@code LocalDateTime} 同用<b>数据库会话时区</b>，因此这里的时间比较
     * 与报表 SQL 的比较是同一套语义 —— 顺带把时区处理也钉住了。
     *
     * @return "skuId:warehouseId" → [期初, 入库, 出库, 期末]
     */
    private Map<String, long[]> recomputeFromRawLedger(YearMonth period) {
        LocalDateTime from = period.atDay(1).atStartOfDay();
        LocalDateTime to = period.plusMonths(1).atDay(1).atStartOfDay();
        Map<String, long[]> out = new LinkedHashMap<>();
        jdbc.query("""
                SELECT sku_id, warehouse_id, change_qty, occurred_at FROM inventory_ledger
                """, (RowCallbackHandler) rs -> {
            String key = rs.getLong("sku_id") + ":" + rs.getLong("warehouse_id");
            LocalDateTime at = rs.getObject("occurred_at", OffsetDateTime.class).toLocalDateTime();
            int change = rs.getInt("change_qty");
            if (at.isBefore(to)) {
                long[] v = out.computeIfAbsent(key, k -> new long[4]);
                v[3] += change;                       // 期末：截至期末的全部净和
                if (at.isBefore(from)) {
                    v[0] += change;                   // 期初：期间开始前的净和
                } else if (change > 0) {
                    v[1] += change;                   // 本期入库
                } else {
                    v[2] -= change;                   // 本期出库（取正数）
                }
            }
        });
        return out;
    }

    private static boolean isCounted(DocumentStatus status) {
        return status == DocumentStatus.PARTIAL || status == DocumentStatus.COMPLETED;
    }

    private String purchaseKey(ReportGroupBy dim, PurchaseOrder o, long skuId) {
        return switch (dim) {
            case SUPPLIER -> supplierKey.get(o.supplierId());
            case PRODUCT -> productKeyOfSku.get(skuId);
            case SKU -> skuKey.get(skuId);
            case DAY -> o.bizDate().toString();
            case MONTH -> monthOf(o.bizDate());
            default -> throw new IllegalStateException("进货统计不支持该分组：" + dim);
        };
    }

    private String salesKey(ReportGroupBy dim, SalesOrder o, long skuId) {
        return switch (dim) {
            case CUSTOMER -> customerKey.get(o.customerId());
            case PRODUCT -> productKeyOfSku.get(skuId);
            case SKU -> skuKey.get(skuId);
            case DAY -> o.bizDate().toString();
            case MONTH -> monthOf(o.bizDate());
            default -> throw new IllegalStateException("出货统计不支持该分组：" + dim);
        };
    }

    private String purchaseLabel(ReportGroupBy dim, PurchaseOrder o, long skuId) {
        return switch (dim) {
            case SUPPLIER -> supplierLabel.get(o.supplierId());
            case PRODUCT -> productLabelOfSku.get(skuId);
            case SKU -> skuLabel.get(skuId);
            case DAY, MONTH -> purchaseKey(dim, o, skuId);
            default -> throw new IllegalStateException();
        };
    }

    private String salesLabel(ReportGroupBy dim, SalesOrder o, long skuId) {
        return switch (dim) {
            case CUSTOMER -> customerLabel.get(o.customerId());
            case PRODUCT -> productLabelOfSku.get(skuId);
            case SKU -> skuLabel.get(skuId);
            case DAY, MONTH -> salesKey(dim, o, skuId);
            default -> throw new IllegalStateException();
        };
    }

    private static String monthOf(LocalDate date) {
        return "%04d-%02d".formatted(date.getYear(), date.getMonthValue());
    }

    private long rememberSupplier(long id, String code) {
        supplierKey.put(id, code);
        supplierLabel.put(id, "测试供应商-" + code);
        return id;
    }

    private long rememberCustomer(long id, String code) {
        customerKey.put(id, code);
        customerLabel.put(id, "测试客户-" + code);
        return id;
    }

    /** SQL 里 SKU 没有独立编码列，键 = 商品编码-颜色-尺码，名 = 商品名 颜色/尺码。 */
    private long rememberSku(String productCode, String productName,
                             long skuId, String color, String size) {
        skuKey.put(skuId, productCode + "-" + color + "-" + size);
        skuLabel.put(skuId, productName + " " + color + "/" + size);
        productKeyOfSku.put(skuId, productCode);
        productLabelOfSku.put(skuId, productName);
        return skuId;
    }

    // ─────────────────────────── 断言辅助 ───────────────────────────

    private static List<String> cells(List<ReportRow> rows) {
        return rows.stream()
                .map(r -> "%s|%s|%d|%d|%d".formatted(r.key(), r.label(), r.qty(),
                        r.amount(), r.orderCount()))
                .toList();
    }

    private static List<String> cells2(List<InventorySummaryRow> rows) {
        return rows.stream()
                .map(r -> "%d:%d|%d|%d|%d|%d".formatted(r.skuId(), r.warehouseId(),
                        r.openingQty(), r.inboundQty(), r.outboundQty(), r.closingQty()))
                .toList();
    }

    private static List<String> dayKeys(List<ReportRow> rows) {
        return rows.stream().map(ReportRow::key).toList();
    }

    private static long sum(List<ReportRow> rows) {
        return rows.stream().mapToLong(ReportRow::qty).sum();
    }

    private static void assertMatchesRecomputed(List<ReportRow> rows, Map<String, Agg> expected) {
        assertThat(rows).extracting(ReportRow::key)
                .as("分组键集合必须与 Java 复算一致（多了=串数，少了=漏数）")
                .containsExactlyInAnyOrderElementsOf(expected.keySet());
        for (ReportRow row : rows) {
            Agg agg = expected.get(row.key());
            assertThat(row.label()).as("%s 的展示名", row.key()).isEqualTo(agg.label);
            assertThat(row.qty()).as("%s 的数量", row.key()).isEqualTo(agg.qty);
            assertThat(row.amount()).as("%s 的金额", row.key()).isEqualTo(agg.amount);
            assertThat(row.orderCount()).as("%s 的单据数", row.key())
                    .isEqualTo(agg.orders.size());
        }
    }

    private static final class Agg {
        private final String label;
        private long qty;
        private long amount;
        private final Set<Long> orders = new HashSet<>();

        private Agg(String label) {
            this.label = label;
        }
    }

    // ─────────────────────────── 语句计数（证明没有 N+1） ───────────────────────────

    /**
     * 计数器：报表每发一条 SQL 就 +1。
     *
     * <p><b>为什么靠计数器而不是读代码</b>：N+1 是"看起来对、跑起来慢"的典型缺陷，
     * 评审能看出来但改动一多就会漏。把语句数变成断言，将来有人把聚合改成
     * "循环里逐组查明细"，本用例立刻变红。
     */
    static final class StatementCounter {
        private int statements;

        void reset() {
            statements = 0;
        }
    }

    @TestConfiguration
    static class CountingConfig {

        @Bean
        StatementCounter statementCounter() {
            return new StatementCounter();
        }

        @Bean
        @Primary
        JdbcTemplate countingJdbcTemplate(DataSource dataSource, StatementCounter counter,
                                          com.erp.shared.tenant.TenantJdbcConfig.TenantEnforcement enforcement) {
            return new CountingJdbcTemplate(dataSource, counter, enforcement.enabled());
        }
    }

    /**
     * 只统计查询条数；仓储用的就是 {@code query(sql, rowMapper, args)} 这一个入口。
     *
     * <p>★ 必须继承 {@code TenantAwareJdbcTemplate}：本类替换的是容器里的主
     * {@code JdbcTemplate}，若只包一个朴素实现，V2-7 的租户改写会被整体绕过 ——
     * 单号取值会撞复合唯一索引、报表查询也会跨租户。计数与租户过滤必须**同时**生效。
     */
    static final class CountingJdbcTemplate extends com.erp.shared.tenant.TenantAwareJdbcTemplate {

        private final StatementCounter counter;

        CountingJdbcTemplate(DataSource dataSource, StatementCounter counter, boolean enforce) {
            super(dataSource, enforce);
            this.counter = counter;
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            counter.statements++;
            return super.query(sql, rowMapper, args);
        }
    }
}
