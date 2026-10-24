package com.erp.rpt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.pur.domain.PurchaseOrder;
import com.erp.rpt.domain.OperatingReportRow;
import com.erp.rpt.domain.ReportPeriodDim;
import com.erp.sal.application.SalesOrderApplicationService;
import com.erp.sal.domain.SalesOrder;
import com.erp.shared.document.DocumentStatus;
import com.erp.shared.error.BusinessException;
import com.erp.support.CaseId;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowCallbackHandler;

/**
 * 经营日报 / 月报（FR-RPT-05，TC-RPT-014~020）。
 *
 * <p>核心断言：每行的流水四数满足 INV-6；流水口径与单据口径各自独立，
 * 跨天执行的单据只落在流水那一侧，不允许被拿来"配平"。
 */
@DisplayName("报表 · 经营日报/月报（INV-6、双口径、空日、自然月）")
class ReportOperatingIT extends ReportV28TestSupport {

    @Test
    @CaseId("TC-RPT-014")
    @DisplayName("★ 逐日出行（含空日）：每行 期初+入−出=期末，首行期初来自期初流水，末日等于当前余额")
    void dailyRows_satisfyInv6_andCoverEveryDay() {
        seedFebruaryOpening();
        at(LocalDateTime.of(2026, 3, 2, 9, 0));
        purchase("po-op-1", supplier1, wh1, LocalDate.of(2026, 3, 2), sku1, 5, 1000);
        at(LocalDateTime.of(2026, 3, 3, 9, 0));
        ship("so-op-1", customer1, wh1, LocalDate.of(2026, 3, 3), sku1, 3, 1000, 10000);
        at(LocalDateTime.of(2026, 3, 10, 9, 0));
        purchase("po-op-2", supplier2, wh1, LocalDate.of(2026, 3, 10), sku2, 4, 2000);

        List<OperatingReportRow> rows = reports.operatingReport(
                RANGE_FROM, RANGE_TO, ReportPeriodDim.DAY, null);

        assertThat(rows).as("3 月每一天都出行，没有流水的日子也要有 0 行")
                .hasSize(31);
        assertThat(rows.get(0).period()).isEqualTo("2026-03-01");
        assertThat(rows.get(30).period()).isEqualTo("2026-03-31");

        long runningOpening = rows.get(0).openingQty();
        long in = 0;
        long out = 0;
        for (OperatingReportRow r : rows) {
            assertThat(r.openingQty() + r.inboundQty() - r.outboundQty())
                    .as("%s 必须满足 INV-6", r.period())
                    .isEqualTo(r.closingQty());
            in += r.inboundQty();
            out += r.outboundQty();
        }
        assertThat(runningOpening + in - out)
                .as("首日 opening + 全期入 − 全期出 == 末日 closing")
                .isEqualTo(rows.get(30).closingQty());

        // 2 月期初 10−2 = 8；3/2 +5；3/3 −3；3/10 +4（另一个 SKU）
        assertThat(rows.get(0).openingQty()).as("3/1 期初 = 2 月流水净和").isEqualTo(8);
        assertThat(rows.get(1).inboundQty()).isEqualTo(5);
        assertThat(rows.get(1).purchaseQty()).isEqualTo(5);
        assertThat(rows.get(1).purchaseAmount()).isEqualTo(5000);
        assertThat(rows.get(2).outboundQty()).isEqualTo(3);
        assertThat(rows.get(2).salesQty()).isEqualTo(3);
        assertThat(rows.get(2).salesAmount()).isEqualTo(3000);
        assertThat(rows.get(4).inboundQty()).as("3/5 没有流水").isZero();
        assertThat(rows.get(4).closingQty()).as("空日期末不跳变").isEqualTo(rows.get(4).openingQty());

        assertThat(rows.get(30).closingQty())
                .as("末日期末 = 全部流水净和 = 库存余额合计")
                .isEqualTo(14);
    }

    @Test
    @CaseId("TC-RPT-015")
    @DisplayName("★ 与原始流水独立复算一致；进货/销售金额与单据对象独立复算一致（两条路径）")
    void rows_matchIndependentRecompute_fromLedgerAndDocuments() {
        seedFebruaryOpening();
        at(LocalDateTime.of(2026, 3, 2, 9, 0));
        purchase("po-op-r1", supplier1, wh1, LocalDate.of(2026, 3, 2), sku1, 5, 1000);
        at(LocalDateTime.of(2026, 3, 7, 9, 0));
        ship("so-op-r1", customer1, wh1, LocalDate.of(2026, 3, 7), sku1, 3, 12900, 9950);
        at(LocalDateTime.of(2026, 3, 20, 9, 0));
        purchase("po-op-r2", supplier2, wh2, LocalDate.of(2026, 3, 20), sku2, 7, 500);

        List<OperatingReportRow> rows = reports.operatingReport(
                RANGE_FROM, RANGE_TO, ReportPeriodDim.DAY, null);
        Map<LocalDate, long[]> ledger = recomputeLedger(RANGE_FROM, RANGE_TO);
        Map<LocalDate, long[]> documents = recomputeDocuments(RANGE_FROM, RANGE_TO);

        for (OperatingReportRow r : rows) {
            LocalDate day = LocalDate.parse(r.period());
            long[] flow = ledger.getOrDefault(day, new long[4]);
            assertThat(new long[]{r.openingQty(), r.inboundQty(), r.outboundQty(),
                    r.closingQty()})
                    .as("%s 的流水四数必须与 Java 分桶重算一致", day)
                    .containsExactly(flow[0], flow[1], flow[2], flow[3]);
            long[] docs = documents.getOrDefault(day, new long[4]);
            assertThat(new long[]{r.purchaseQty(), r.purchaseAmount(),
                    r.salesQty(), r.salesAmount()})
                    .as("%s 的单据四数必须与单据对象重算一致", day)
                    .containsExactly(docs[0], docs[1], docs[2], docs[3]);
        }
    }

    @Test
    @CaseId("TC-RPT-016")
    @DisplayName("★ 月报按完整自然月：跨月区间首尾月恒等式成立；日合计 == 月合计")
    void monthlyRows_useWholeCalendarMonths_andIdentitiesHold() {
        seedFebruaryOpening();
        at(LocalDateTime.of(2026, 3, 2, 9, 0));
        purchase("po-op-m1", supplier1, wh1, LocalDate.of(2026, 3, 2), sku1, 5, 1000);
        at(LocalDateTime.of(2026, 3, 3, 9, 0));
        ship("so-op-m1", customer1, wh1, LocalDate.of(2026, 3, 3), sku1, 3, 1000, 10000);

        var march = reports.operatingReport(RANGE_FROM, RANGE_TO, ReportPeriodDim.MONTH, null);
        assertThat(march).as("3 月只有一行").hasSize(1);
        OperatingReportRow m = march.get(0);
        assertThat(m.period()).isEqualTo("2026-03");
        assertThat(new long[]{m.openingQty(), m.inboundQty(), m.outboundQty(), m.closingQty()})
                .containsExactly(8, 5, 3, 10);
        assertThat(m.openingQty() + m.inboundQty() - m.outboundQty()).isEqualTo(m.closingQty());

        // from=2/15、to=3/10：按完整自然月应出 2 月 + 3 月两行，而不是半个月
        var span = reports.operatingReport(LocalDate.of(2026, 2, 15), LocalDate.of(2026, 3, 10),
                ReportPeriodDim.MONTH, null);
        assertThat(span).extracting(OperatingReportRow::period)
                .containsExactly("2026-02", "2026-03");
        assertThat(new long[]{span.get(0).openingQty(), span.get(0).inboundQty(),
                span.get(0).outboundQty(), span.get(0).closingQty()})
                .as("2 月整月：0 + 10 − 2 = 8")
                .containsExactly(0, 10, 2, 8);
        assertThat(span.get(1).openingQty()).as("3 月期初接上 2 月期末").isEqualTo(8);

        var daily = reports.operatingReport(RANGE_FROM, RANGE_TO, ReportPeriodDim.DAY, null);
        assertThat(daily.stream().mapToLong(OperatingReportRow::inboundQty).sum())
                .isEqualTo(m.inboundQty());
        assertThat(daily.stream().mapToLong(OperatingReportRow::outboundQty).sum())
                .isEqualTo(m.outboundQty());
        assertThat(daily.stream().mapToLong(OperatingReportRow::purchaseAmount).sum())
                .isEqualTo(m.purchaseAmount());
        assertThat(daily.stream().mapToLong(OperatingReportRow::salesAmount).sum())
                .isEqualTo(m.salesAmount());
    }

    @Test
    @CaseId("TC-RPT-017")
    @DisplayName("仓库过滤：分仓期末之和 == 全部，跨仓不串数；空仓日仍出行")
    void warehouseFilter_doesNotMixWarehouses() {
        seedFebruaryOpening();
        at(LocalDateTime.of(2026, 3, 4, 9, 0));
        purchase("po-op-w2", supplier2, wh2, LocalDate.of(2026, 3, 4), sku2, 6, 1000);

        List<OperatingReportRow> all = reports.operatingReport(
                RANGE_FROM, RANGE_TO, ReportPeriodDim.DAY, null);
        List<OperatingReportRow> one = reports.operatingReport(
                RANGE_FROM, RANGE_TO, ReportPeriodDim.DAY, wh1);
        List<OperatingReportRow> two = reports.operatingReport(
                RANGE_FROM, RANGE_TO, ReportPeriodDim.DAY, wh2);

        assertThat(one).hasSameSizeAs(all);
        assertThat(two).hasSameSizeAs(all);
        LocalDate end = RANGE_TO;
        assertThat(closingOf(one, end) + closingOf(two, end))
                .as("WH1 + WH2 期末必须等于全部仓库期末")
                .isEqualTo(closingOf(all, end));
        assertThat(closingOf(one, end)).isEqualTo(8);
        assertThat(closingOf(two, end)).isEqualTo(6);
        assertThat(one.get(3).inboundQty()).as("3/4 的入库只在 WH2")
                .isZero();
        assertThat(two.get(3).inboundQty()).isEqualTo(6);
    }

    @Test
    @CaseId("TC-RPT-018")
    @DisplayName("★ 口径不混：2/28 的单据在 3/20 才入库，单据侧归 2/28、流水侧归 3/20")
    void documentDateAndFlowDate_stayOnTheirOwnSides() {
        at(LocalDateTime.of(2026, 3, 20, 15, 0));
        purchase("po-op-cross", supplier1, wh1, LocalDate.of(2026, 2, 28), sku1, 5, 1000);

        var feb28 = reports.operatingReport(LocalDate.of(2026, 2, 28),
                LocalDate.of(2026, 2, 28), ReportPeriodDim.DAY, null).get(0);
        assertThat(feb28.purchaseQty()).as("单据按 biz_date 归 2/28").isEqualTo(5);
        assertThat(feb28.inboundQty()).as("流水发生在 3/20，2/28 不得有入库").isZero();
        assertThat(feb28.closingQty()).isZero();

        var mar20 = reports.operatingReport(LocalDate.of(2026, 3, 20),
                LocalDate.of(2026, 3, 20), ReportPeriodDim.DAY, null).get(0);
        assertThat(mar20.inboundQty()).as("流水按 occurred_at 归 3/20").isEqualTo(5);
        assertThat(mar20.purchaseQty()).as("3/20 没有 biz_date 当天的单据").isZero();
        assertThat(mar20.closingQty()).isEqualTo(5);
    }

    @Test
    @CaseId("TC-RPT-019")
    @DisplayName("空区间：日/月都按分桶返回 0 行，不是空数组也不是错误")
    void emptyPeriods_returnZeroRows() {
        var days = reports.operatingReport(LocalDate.of(2020, 1, 1),
                LocalDate.of(2020, 1, 3), ReportPeriodDim.DAY, null);
        assertThat(days).hasSize(3);
        assertThat(days).allSatisfy(r -> {
            assertThat(r.openingQty()).isZero();
            assertThat(r.inboundQty()).isZero();
            assertThat(r.outboundQty()).isZero();
            assertThat(r.closingQty()).isZero();
            assertThat(r.purchaseAmount()).isZero();
            assertThat(r.salesAmount()).isZero();
        });

        var months = reports.operatingReport(LocalDate.of(2020, 1, 1),
                LocalDate.of(2020, 1, 31), ReportPeriodDim.MONTH, null);
        assertThat(months).extracting(OperatingReportRow::period).containsExactly("2020-01");
        assertThat(months.get(0).closingQty()).isZero();
    }

    @Test
    @CaseId("TC-RPT-020")
    @DisplayName("单日闭区间包含首末日；from > to 必须 400")
    void singleDayAndInvalidRange() {
        seedFebruaryOpening();
        at(LocalDateTime.of(2026, 3, 2, 9, 0));
        purchase("po-op-day", supplier1, wh1, LocalDate.of(2026, 3, 2), sku1, 5, 1000);

        var only = reports.operatingReport(LocalDate.of(2026, 3, 2),
                LocalDate.of(2026, 3, 2), ReportPeriodDim.DAY, null);
        assertThat(only).extracting(OperatingReportRow::period).containsExactly("2026-03-02");
        assertThat(only.get(0).inboundQty()).isEqualTo(5);

        assertThatThrownBy(() -> reports.operatingReport(LocalDate.of(2026, 3, 5),
                LocalDate.of(2026, 3, 1), ReportPeriodDim.DAY, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("from");
    }

    // ─────────────────────────── 造数与复算 ───────────────────────────

    private void seedFebruaryOpening() {
        at(LocalDateTime.of(2026, 2, 10, 9, 0));
        purchase("po-op-feb", supplier1, wh1, LocalDate.of(2026, 2, 10), sku1, 10, 1000);
        at(LocalDateTime.of(2026, 2, 11, 9, 0));
        ship("so-op-feb", customer1, wh1, LocalDate.of(2026, 2, 11), sku1, 2, 1000, 10000);
        at(DEFAULT_NOW);
    }

    private static long closingOf(List<OperatingReportRow> rows, LocalDate day) {
        return rows.stream().filter(r -> r.period().equals(day.toString()))
                .findFirst().orElseThrow().closingQty();
    }

    /** 原始流水 → 每日 [期初, 入, 出, 期末]，与 SQL 路径完全独立。 */
    private Map<LocalDate, long[]> recomputeLedger(LocalDate from, LocalDate to) {
        Map<LocalDate, long[]> flow = new LinkedHashMap<>();
        long[] base = new long[1];
        jdbc.query("SELECT change_qty, occurred_at FROM inventory_ledger",
                (RowCallbackHandler) rs -> {
                    LocalDate day = rs.getObject("occurred_at", OffsetDateTime.class)
                            .toLocalDateTime().toLocalDate();
                    int change = rs.getInt("change_qty");
                    if (day.isBefore(from)) {
                        base[0] += change;
                    } else if (!day.isAfter(to)) {
                        long[] v = flow.computeIfAbsent(day, k -> new long[3]);
                        if (change > 0) {
                            v[0] += change;
                        } else {
                            v[1] -= change;
                        }
                        v[2] += change;
                    }
                });

        Map<LocalDate, long[]> out = new LinkedHashMap<>();
        long running = base[0];
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            long[] f = flow.getOrDefault(d, new long[3]);
            long opening = running;
            running += f[2];
            out.put(d, new long[]{opening, f[0], f[1], running});
        }
        return out;
    }

    /** 单据对象 → 每日 [进货数量, 进货金额, 出货数量, 出货金额]，与 SQL 路径完全独立。 */
    private Map<LocalDate, long[]> recomputeDocuments(LocalDate from, LocalDate to) {
        Map<LocalDate, long[]> out = new LinkedHashMap<>();
        for (PurchaseOrder o : purchases) {
            if (!counted(o.status()) || o.bizDate().isBefore(from) || o.bizDate().isAfter(to)) {
                continue;
            }
            long[] v = out.computeIfAbsent(o.bizDate(), k -> new long[4]);
            o.lines().stream().filter(l -> l.receivedQty() > 0).forEach(l -> {
                v[0] += l.receivedQty();
                v[1] += (long) l.receivedQty() * l.unitPriceAmount();
            });
        }
        for (SalesOrder o : shipments) {
            if (!counted(o.status()) || o.bizDate().isBefore(from) || o.bizDate().isAfter(to)) {
                continue;
            }
            long[] v = out.computeIfAbsent(o.bizDate(), k -> new long[4]);
            o.lines().stream().filter(l -> l.shippedQty() > 0).forEach(l -> {
                v[2] += l.shippedQty();
                v[3] += SalesOrderApplicationService.lineAmount(
                        l.shippedQty(), l.unitPriceAmount(), l.discountRate());
            });
        }
        return out;
    }

    private static boolean counted(DocumentStatus status) {
        return status == DocumentStatus.PARTIAL || status == DocumentStatus.COMPLETED;
    }
}
