package com.erp.rpt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.rpt.domain.InventorySummaryRow;
import com.erp.rpt.domain.InventoryTurnoverRow;
import com.erp.shared.error.BusinessException;
import com.erp.support.CaseId;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 库存周转与呆滞（FR-RPT-06，TC-RPT-021~027）。
 *
 * <p>周转与呆滞全部走库存流水，和进销存汇总同源；测试同时把两边的
 * 期末/出库逐行对齐，防止报表之间各算一套。
 */
@DisplayName("报表 · 库存周转与呆滞（公式、呆滞、与进销存一致）")
class ReportTurnoverIT extends ReportV28TestSupport {

    @Test
    @CaseId("TC-RPT-021")
    @DisplayName("★ 期初/期末/出库与流水独立复算一致；周转天数 = (期初+期末)/2 × 天数 / 出库")
    void turnover_matchesLedgerRecompute_andFormula() {
        seedStockAndOutbound();

        List<InventoryTurnoverRow> rows = reports.inventoryTurnover(
                RANGE_FROM, RANGE_TO, 30, null);
        InventoryTurnoverRow a = bySku(rows, sku1);

        assertThat(a.skuCode()).isEqualTo("P-V28-1-白色-L");
        assertThat(a.skuName()).isEqualTo("V2-8 商品A 白色/L");
        assertThat(a.warehouseName()).isEqualTo("测试仓-WH-V28-1");
        assertThat(new long[]{a.openingQty(), a.closingQty(), a.outboundQty()})
                .as("2 月入 100、3 月出 30 → 期初 100 / 期末 70 / 出库 30")
                .containsExactly(100, 70, 30);
        // (100+70)/2 × 31 / 30 = 87.833… → 87.8
        assertThat(a.turnoverDays()).isEqualByComparingTo(new BigDecimal("87.8"));

        // Java 侧从原始流水独立复算
        Map<String, Long> opening = jdbc.query("""
                SELECT sku_id, SUM(change_qty) AS qty FROM inventory_ledger
                 WHERE occurred_at < ? GROUP BY sku_id
                """, (rs, n) -> Map.entry("k" + rs.getLong("sku_id"), rs.getLong("qty")),
                RANGE_FROM.atStartOfDay()).stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        long javaOpening = opening.getOrDefault("k" + sku1, 0L);
        long javaClosing = jdbc.queryForObject("""
                SELECT COALESCE(SUM(change_qty), 0) FROM inventory_ledger
                 WHERE sku_id = ? AND occurred_at < ?
                """, Long.class, sku1, RANGE_TO.plusDays(1).atStartOfDay());
        long javaOut = jdbc.queryForObject("""
                SELECT COALESCE(-SUM(change_qty), 0) FROM inventory_ledger
                 WHERE sku_id = ? AND change_qty < 0
                   AND occurred_at >= ? AND occurred_at < ?
                """, Long.class, sku1, RANGE_FROM.atStartOfDay(),
                RANGE_TO.plusDays(1).atStartOfDay());
        assertThat(new long[]{a.openingQty(), a.closingQty(), a.outboundQty()})
                .containsExactly(javaOpening, javaClosing, javaOut);
        BigDecimal expected = BigDecimal.valueOf(javaOpening + javaClosing)
                .divide(BigDecimal.valueOf(2))
                .multiply(BigDecimal.valueOf(31))
                .divide(BigDecimal.valueOf(javaOut), 1, java.math.RoundingMode.HALF_UP);
        assertThat(a.turnoverDays()).isEqualByComparingTo(expected);
    }

    @Test
    @CaseId("TC-RPT-022")
    @DisplayName("周转天数保留一位小数（10.333… → 10.3）；无出库为 null，不抛除零")
    void turnoverRounding_andNullWhenNoOutbound() {
        at(LocalDateTime.of(2026, 3, 1, 9, 0));
        purchase("po-turn-r1", supplier1, wh1, LocalDate.of(2026, 3, 1), sku1, 5, 1000);
        purchase("po-turn-r2", supplier2, wh2, LocalDate.of(2026, 3, 1), sku2, 2, 1000);
        at(LocalDateTime.of(2026, 3, 2, 9, 0));
        ship("so-turn-r1", customer1, wh1, LocalDate.of(2026, 3, 2), sku1, 3, 1000, 10000);

        var rows = reports.inventoryTurnover(RANGE_FROM, RANGE_TO, 30, null);
        InventoryTurnoverRow rounding = bySku(rows, sku1);
        InventoryTurnoverRow noOut = bySku(rows, sku2);

        // 期初 0、期末 2、出库 3 → (0+2)/2 × 31 / 3 = 10.333… → 10.3
        assertThat(new long[]{rounding.openingQty(), rounding.closingQty(),
                rounding.outboundQty()}).containsExactly(0, 2, 3);
        assertThat(rounding.turnoverDays()).isEqualByComparingTo(new BigDecimal("10.3"));
        assertThat(rounding.turnoverDays().scale()).isEqualTo(1);

        assertThat(noOut.outboundQty()).isZero();
        assertThat(noOut.turnoverDays()).as("无出库时周转天数无定义").isNull();
    }

    @Test
    @CaseId("TC-RPT-023")
    @DisplayName("呆滞：N 天无出库；从未出库从首次流水起算；年轻库存不算呆滞")
    void staleFlag_usesIdleDaysAndFirstLedgerDate() {
        seedStockAndOutbound();
        // WH2 的 SKU2 在 3/26 才入库，距观察日 5 天：年轻库存，不应呆滞
        at(LocalDateTime.of(2026, 3, 26, 9, 0));
        purchase("po-turn-fresh", supplier2, wh2, LocalDate.of(2026, 3, 26), sku2, 20, 1000);

        var rows = reports.inventoryTurnover(RANGE_FROM, RANGE_TO, 30, null);
        InventoryTurnoverRow a = bySku(rows, sku1);
        InventoryTurnoverRow b = bySku(rows, sku2);

        assertThat(a.idleDays()).as("SKU1 最近出库 3/10，距 3/31 共 21 天").isEqualTo(21);
        assertThat(a.stale()).as("21 < 30").isFalse();
        assertThat(b.idleDays()).as("SKU2 从未出库，从 3/26 首次流水起算 5 天").isEqualTo(5);
        assertThat(b.stale()).isFalse();
        assertThat(b.turnoverDays()).as("从未出库 → 周转天数无定义").isNull();

        var strict = reports.inventoryTurnover(RANGE_FROM, RANGE_TO, 5, null);
        assertThat(bySku(strict, sku2).stale())
                .as("deadDays=5 时 5 天正好达到阈值")
                .isTrue();
        assertThat(bySku(strict, sku1).stale())
                .as("deadDays 收紧到 5 后，21 天前的出库也变成呆滞")
                .isTrue();
    }

    @Test
    @CaseId("TC-RPT-024")
    @DisplayName("呆滞阈值可调：40 天前出库在 30 天口径下呆滞、60 天口径下不呆滞")
    void deadDaysParameter_changesClassification() {
        at(LocalDateTime.of(2026, 2, 1, 9, 0));
        purchase("po-stale-old", supplier1, wh1, LocalDate.of(2026, 2, 1), sku1, 100, 1000);
        at(LocalDateTime.of(2026, 2, 20, 9, 0));
        ship("so-stale-old", customer1, wh1, LocalDate.of(2026, 2, 20), sku1, 10, 1000, 10000);

        var by30 = bySku(reports.inventoryTurnover(
                LocalDate.of(2026, 2, 1), RANGE_TO, 30, null), sku1);
        var by60 = bySku(reports.inventoryTurnover(
                LocalDate.of(2026, 2, 1), RANGE_TO, 60, null), sku1);
        // 2/20 → 3/31 = 39 天
        assertThat(by30.idleDays()).isEqualTo(39);
        assertThat(by30.stale()).isTrue();
        assertThat(by60.stale()).isFalse();
    }

    @Test
    @CaseId("TC-RPT-025")
    @DisplayName("★ 与进销存汇总同口径：同月同 SKU 的期初/期末/出库逐格一致")
    void turnover_reconcilesWithInventorySummary() {
        seedStockAndOutbound();

        List<InventorySummaryRow> summary = reports.inventorySummaryReport(
                java.time.YearMonth.of(2026, 3), null);
        List<InventoryTurnoverRow> turnover = reports.inventoryTurnover(
                RANGE_FROM, RANGE_TO, 30, null);

        for (InventorySummaryRow s : summary) {
            InventoryTurnoverRow t = bySku(turnover, s.skuId());
            assertThat(t.openingQty()).as("SKU %d 期初", s.skuId()).isEqualTo(s.openingQty());
            assertThat(t.closingQty()).as("SKU %d 期末", s.skuId()).isEqualTo(s.closingQty());
            assertThat(t.outboundQty()).as("SKU %d 出库", s.skuId()).isEqualTo(s.outboundQty());
        }
    }

    @Test
    @CaseId("TC-RPT-026")
    @DisplayName("仓库过滤：只返回该仓的 SKU，分仓之和 == 全部")
    void warehouseFilter() {
        seedStockAndOutbound();
        at(LocalDateTime.of(2026, 3, 26, 9, 0));
        purchase("po-turn-w2", supplier2, wh2, LocalDate.of(2026, 3, 26), sku2, 20, 1000);

        var all = reports.inventoryTurnover(RANGE_FROM, RANGE_TO, 30, null);
        var wh1Rows = reports.inventoryTurnover(RANGE_FROM, RANGE_TO, 30, wh1);
        var wh2Rows = reports.inventoryTurnover(RANGE_FROM, RANGE_TO, 30, wh2);

        assertThat(wh1Rows).allSatisfy(r -> assertThat(r.warehouseId()).isEqualTo(wh1));
        assertThat(wh2Rows).allSatisfy(r -> assertThat(r.warehouseId()).isEqualTo(wh2));
        assertThat(wh1Rows).extracting(InventoryTurnoverRow::skuId).containsExactly(sku1);
        assertThat(wh2Rows).extracting(InventoryTurnoverRow::skuId).containsExactly(sku2);
        long allClosing = all.stream().mapToLong(InventoryTurnoverRow::closingQty).sum();
        long split = wh1Rows.stream().mapToLong(InventoryTurnoverRow::closingQty).sum()
                + wh2Rows.stream().mapToLong(InventoryTurnoverRow::closingQty).sum();
        assertThat(split).isEqualTo(allClosing);
    }

    @Test
    @CaseId("TC-RPT-027")
    @DisplayName("只返回期末有库存的 SKU：清空的 SKU 不出行；deadDays 越界与 from>to 均 400")
    void onlyStockedSkus_andValidation() {
        long sku3 = data.sku(data.product("P-V28-3", "V2-8 商品C"), "红色", "S");
        at(LocalDateTime.of(2026, 3, 1, 9, 0));
        purchase("po-clear", supplier1, wh1, LocalDate.of(2026, 3, 1), sku3, 10, 1000);
        at(LocalDateTime.of(2026, 3, 2, 9, 0));
        ship("so-clear", customer1, wh1, LocalDate.of(2026, 3, 2), sku3, 10, 1000, 10000);

        var rows = reports.inventoryTurnover(RANGE_FROM, RANGE_TO, 30, null);
        assertThat(rows).extracting(InventoryTurnoverRow::skuId).doesNotContain(sku3);
        assertThat(rows).isEmpty();

        assertThatThrownBy(() -> reports.inventoryTurnover(RANGE_FROM, RANGE_TO, 0, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("deadDays");
        assertThatThrownBy(() -> reports.inventoryTurnover(RANGE_FROM, RANGE_TO, 3651, null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> reports.inventoryTurnover(RANGE_TO, RANGE_FROM, 30, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("from");
    }

    // ─────────────────────────── 造数与辅助 ───────────────────────────

    /** SKU1：2 月入 100，3/10 出 30 → 期初 100、期末 70、出库 30。 */
    private void seedStockAndOutbound() {
        at(LocalDateTime.of(2026, 2, 1, 9, 0));
        purchase("po-turn-a", supplier1, wh1, LocalDate.of(2026, 2, 1), sku1, 100, 1000);
        at(LocalDateTime.of(2026, 3, 10, 9, 0));
        ship("so-turn-a", customer1, wh1, LocalDate.of(2026, 3, 10), sku1, 30, 1000, 10000);
        at(DEFAULT_NOW);
    }

    private static InventoryTurnoverRow bySku(List<InventoryTurnoverRow> rows, long skuId) {
        return rows.stream().filter(r -> r.skuId() == skuId).findFirst()
                .orElseThrow(() -> new AssertionError("缺少 SKU " + skuId));
    }
}
