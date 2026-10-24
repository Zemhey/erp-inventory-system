package com.erp.rpt;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.rpt.domain.AgingResult;
import com.erp.rpt.domain.AgingRow;
import com.erp.rpt.domain.AgingSummary;
import com.erp.rpt.domain.AgingType;
import com.erp.support.CaseId;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 应收 / 应付账龄（FR-RPT-08 / FR-SET-06，TC-RPT-035~041）。
 *
 * <p>硬断言只有一条但贯穿每张表：<b>四桶合计 == 余额</b>。
 * 边界日 30/31、60/61、90/91 各有一个单独用例；AR 与 AP 的起算日不同
 * （到期日 vs 应付单业务日），在数据构造里也分开写。
 */
@DisplayName("报表 · 应收/应付账龄（分桶守恒、边界、AR/AP）")
class ReportAgingIT extends ReportV28TestSupport {

    private static final LocalDate AS_OF = LocalDate.of(2026, 3, 31);
    private static final AtomicInteger SEQ = new AtomicInteger(1);

    @Test
    @CaseId("TC-RPT-035")
    @DisplayName("★ 应收四桶：行级与汇总级 桶合计 == 余额；桶金额精确")
    void arBuckets_conserveBalance() {
        seedAr();

        AgingResult result = reports.aging(AgingType.AR, AS_OF);
        AgingRow c1 = byCode(result.items(), "CUS-V28-1");

        assertThat(new long[]{c1.bucket0(), c1.bucket1(), c1.bucket2(), c1.bucket3()})
                .as("0-30: 1000+7000+8000；31-60: 2000+3000；61-90: 4000+5000；90+: 6000")
                .containsExactly(16000, 5000, 9000, 6000);
        assertThat(c1.balanceAmount()).isEqualTo(36000);
        for (AgingRow row : result.items()) {
            assertThat(row.bucket0() + row.bucket1() + row.bucket2() + row.bucket3())
                    .as("%s 桶合计必须等于余额", row.partnerCode())
                    .isEqualTo(row.balanceAmount());
        }
        AgingSummary s = result.summary();
        assertThat(s.totalBalance()).isEqualTo(41000);
        assertThat(new long[]{s.bucket0(), s.bucket1(), s.bucket2(), s.bucket3()})
                .containsExactly(16000, 5000, 9000, 11000);
        assertThat(s.bucket0() + s.bucket1() + s.bucket2() + s.bucket3())
                .isEqualTo(s.totalBalance());
    }

    @Test
    @CaseId("TC-RPT-036")
    @DisplayName("★ 账龄边界：30/31、60/61、90/91 各自落桶；未到期与无到期日归 0-30")
    void agingBoundaries_landInExpectedBuckets() {
        at(LocalDateTime.of(2026, 1, 5, 9, 0));
        purchase("po-age-bound", supplier1, wh1, LocalDate.of(2026, 1, 5), sku1, 100, 1000);

        long c30 = data.customer("CUS-AGE-30", 10000);
        long c31 = data.customer("CUS-AGE-31", 10000);
        long c60 = data.customer("CUS-AGE-60", 10000);
        long c61 = data.customer("CUS-AGE-61", 10000);
        long c90 = data.customer("CUS-AGE-90", 10000);
        long c91 = data.customer("CUS-AGE-91", 10000);
        long cNull = data.customer("CUS-AGE-NULL", 10000);
        long cFuture = data.customer("CUS-AGE-FUTURE", 10000);

        receivable(c30, 1000, AS_OF.minusDays(30));
        receivable(c31, 2000, AS_OF.minusDays(31));
        receivable(c60, 3000, AS_OF.minusDays(60));
        receivable(c61, 4000, AS_OF.minusDays(61));
        receivable(c90, 5000, AS_OF.minusDays(90));
        receivable(c91, 6000, AS_OF.minusDays(91));
        receivable(cNull, 7000, null);
        receivable(cFuture, 8000, AS_OF.plusDays(30));

        assertThat(byCode(reports.aging(AgingType.AR, AS_OF).items(), "CUS-AGE-30").bucket0())
                .isEqualTo(1000);
        assertThat(byCode(reports.aging(AgingType.AR, AS_OF).items(), "CUS-AGE-31").bucket1())
                .isEqualTo(2000);
        assertThat(byCode(reports.aging(AgingType.AR, AS_OF).items(), "CUS-AGE-60").bucket1())
                .isEqualTo(3000);
        assertThat(byCode(reports.aging(AgingType.AR, AS_OF).items(), "CUS-AGE-61").bucket2())
                .isEqualTo(4000);
        assertThat(byCode(reports.aging(AgingType.AR, AS_OF).items(), "CUS-AGE-90").bucket2())
                .isEqualTo(5000);
        assertThat(byCode(reports.aging(AgingType.AR, AS_OF).items(), "CUS-AGE-91").bucket3())
                .isEqualTo(6000);
        assertThat(byCode(reports.aging(AgingType.AR, AS_OF).items(), "CUS-AGE-NULL").bucket0())
                .as("无到期日按未逾期处理").isEqualTo(7000);
        assertThat(byCode(reports.aging(AgingType.AR, AS_OF).items(), "CUS-AGE-FUTURE").bucket0())
                .as("未到期按 0-30 处理").isEqualTo(8000);
    }

    @Test
    @CaseId("TC-RPT-037")
    @DisplayName("★ 应付四桶：以应付单业务日起算，行级/汇总级同样守恒")
    void apBuckets_conserveBalance() {
        seedAp();

        AgingResult result = reports.aging(AgingType.AP, AS_OF);
        AgingRow s1 = byCode(result.items(), "SUP-V28-1");
        assertThat(new long[]{s1.bucket0(), s1.bucket1(), s1.bucket2(), s1.bucket3()})
                .containsExactly(1000, 5000, 9000, 6000);
        assertThat(s1.balanceAmount()).isEqualTo(21000);
        for (AgingRow row : result.items()) {
            assertThat(row.bucket0() + row.bucket1() + row.bucket2() + row.bucket3())
                    .isEqualTo(row.balanceAmount());
        }
        assertThat(result.summary().totalBalance()).isEqualTo(26000);
        assertThat(result.summary().bucket3()).as("SUP-2 的 5000 全在 90+").isEqualTo(11000);
    }

    @Test
    @CaseId("TC-RPT-038")
    @DisplayName("作废与余额为 0 的应收不进账龄；正常行照常")
    void voidedAndZeroBalance_areExcluded() {
        at(LocalDateTime.of(2026, 1, 5, 9, 0));
        purchase("po-age-ex", supplier1, wh1, LocalDate.of(2026, 1, 5), sku1, 100, 1000);
        long customer = data.customer("CUS-AGE-EX", 10000);
        var voided = receivable(customer, 9000, AS_OF.minusDays(10));
        var settled = receivable(customer, 9000, AS_OF.minusDays(10));
        jdbc.update("UPDATE customer_receivables SET status = 'VOIDED' WHERE sales_order_id = ?",
                voided);
        jdbc.update("""
                UPDATE customer_receivables
                   SET status = 'SETTLED', received_amount = shipped_amount, balance = 0
                 WHERE sales_order_id = ?
                """, settled);

        AgingResult result = reports.aging(AgingType.AR, AS_OF);
        assertThat(result.items()).extracting(AgingRow::partnerCode)
                .doesNotContain("CUS-AGE-EX");
        assertThat(result.summary().totalBalance()).isZero();
    }

    @Test
    @CaseId("TC-RPT-039")
    @DisplayName("汇总 == 各行相加；伙伴数、余额、四桶全部对齐")
    void summaryEqualsRows() {
        seedAr();
        AgingResult result = reports.aging(AgingType.AR, AS_OF);
        AgingSummary s = result.summary();

        assertThat(s.partnerCount()).isEqualTo(result.items().size()).isEqualTo(2);
        assertThat(s.totalBalance())
                .isEqualTo(result.items().stream().mapToLong(AgingRow::balanceAmount).sum());
        assertThat(s.bucket0())
                .isEqualTo(result.items().stream().mapToLong(AgingRow::bucket0).sum());
        assertThat(s.bucket1())
                .isEqualTo(result.items().stream().mapToLong(AgingRow::bucket1).sum());
        assertThat(s.bucket2())
                .isEqualTo(result.items().stream().mapToLong(AgingRow::bucket2).sum());
        assertThat(s.bucket3())
                .isEqualTo(result.items().stream().mapToLong(AgingRow::bucket3).sum());
    }

    @Test
    @CaseId("TC-RPT-040")
    @DisplayName("asOf 推移会把同一笔余额从 0-30 推到 31-60、61-90、90+")
    void asOfMovesBuckets() {
        at(LocalDateTime.of(2026, 1, 5, 9, 0));
        purchase("po-age-move", supplier1, wh1, LocalDate.of(2026, 1, 5), sku1, 10, 1000);
        long customer = data.customer("CUS-AGE-MOVE", 10000);
        receivable(customer, 1000, LocalDate.of(2026, 3, 1));

        assertThat(byCode(reports.aging(AgingType.AR, LocalDate.of(2026, 3, 31)).items(),
                "CUS-AGE-MOVE").bucket0()).as("逾期 30 天").isEqualTo(1000);
        assertThat(byCode(reports.aging(AgingType.AR, LocalDate.of(2026, 4, 1)).items(),
                "CUS-AGE-MOVE").bucket1()).as("逾期 31 天").isEqualTo(1000);
        assertThat(byCode(reports.aging(AgingType.AR, LocalDate.of(2026, 5, 1)).items(),
                "CUS-AGE-MOVE").bucket2()).as("逾期 61 天").isEqualTo(1000);
        assertThat(byCode(reports.aging(AgingType.AR, LocalDate.of(2026, 5, 31)).items(),
                "CUS-AGE-MOVE").bucket3()).as("逾期 91 天").isEqualTo(1000);
    }

    @Test
    @CaseId("TC-RPT-041")
    @DisplayName("空账龄：AR/AP 都返回空 items 与全零汇总")
    void emptyAging_returnsZeroSummary() {
        for (AgingType type : AgingType.values()) {
            AgingResult result = reports.aging(type, AS_OF);
            assertThat(result.items()).isEmpty();
            AgingSummary s = result.summary();
            assertThat(s.partnerCount()).isZero();
            assertThat(s.totalBalance()).isZero();
            assertThat(s.bucket0() + s.bucket1() + s.bucket2() + s.bucket3()).isZero();
        }
    }

    // ─────────────────────────── 造数 ───────────────────────────

    /**
     * CUS-1 八笔应收覆盖 30/31/60/61/90/91/无到期日/未到期；
     * CUS-2 一笔 90+。合计 41000。
     */
    private void seedAr() {
        at(LocalDateTime.of(2026, 1, 5, 9, 0));
        purchase("po-age-ar", supplier1, wh1, LocalDate.of(2026, 1, 5), sku1, 100, 1000);
        receivable(customer1, 1000, AS_OF.minusDays(30));
        receivable(customer1, 2000, AS_OF.minusDays(31));
        receivable(customer1, 3000, AS_OF.minusDays(60));
        receivable(customer1, 4000, AS_OF.minusDays(61));
        receivable(customer1, 5000, AS_OF.minusDays(90));
        receivable(customer1, 6000, AS_OF.minusDays(91));
        receivable(customer1, 7000, null);
        receivable(customer1, 8000, AS_OF.plusDays(30));
        receivable(customer2, 5000, AS_OF.minusDays(120));
    }

    /** SUP-1 六笔应付覆盖 30/31/60/61/90/91；SUP-2 一笔 90+。合计 26000。 */
    private void seedAp() {
        payable(supplier1, 1000, AS_OF.minusDays(30));
        payable(supplier1, 2000, AS_OF.minusDays(31));
        payable(supplier1, 3000, AS_OF.minusDays(60));
        payable(supplier1, 4000, AS_OF.minusDays(61));
        payable(supplier1, 5000, AS_OF.minusDays(90));
        payable(supplier1, 6000, AS_OF.minusDays(91));
        payable(supplier2, 5000, AS_OF.minusDays(120));
    }

    /** 造一张已出货单并设定应收到期日；返回销售单 id。 */
    private long receivable(long customerId, long amount, LocalDate dueDate) {
        at(LocalDateTime.of(2026, 3, 15, 9, 0));
        var order = ship("so-age-" + SEQ.getAndIncrement(), customerId, wh1,
                LocalDate.of(2026, 3, 15), sku1, 1, amount, 10000);
        setReceivableDueDate(order.id(), dueDate);
        return order.id();
    }

    /** 造一张已收货进货单并把应付起算日改成指定日期。 */
    private void payable(long supplierId, long amount, LocalDate bizDate) {
        at(LocalDateTime.of(2026, 1, 5, 9, 0));
        var order = purchase("po-age-" + SEQ.getAndIncrement(), supplierId, wh2,
                LocalDate.of(2026, 1, 5), sku2, 1, amount);
        setPayableBizDate(order.id(), bizDate);
    }

    private static AgingRow byCode(List<AgingRow> rows, String code) {
        return rows.stream().filter(r -> r.partnerCode().equals(code)).findFirst()
                .orElseThrow(() -> new AssertionError("缺少往来单位 " + code));
    }
}
