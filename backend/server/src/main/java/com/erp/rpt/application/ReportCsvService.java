package com.erp.rpt.application;

import com.erp.rpt.domain.AgingResult;
import com.erp.rpt.domain.AgingRow;
import com.erp.rpt.domain.AgingSummary;
import com.erp.rpt.domain.AgingType;
import com.erp.rpt.domain.CustomerContributionResult;
import com.erp.rpt.domain.CustomerContributionRow;
import com.erp.rpt.domain.CustomerContributionSummary;
import com.erp.rpt.domain.InventorySummaryExportRow;
import com.erp.rpt.domain.InventoryTurnoverRow;
import com.erp.rpt.domain.OperatingReportRow;
import com.erp.rpt.domain.ReportGroupBy;
import com.erp.rpt.domain.ReportPeriodDim;
import com.erp.rpt.domain.ReportRow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 报表 CSV 导出（FR-RPT-09）。
 *
 * <p><b>★ 导出不是第二套报表</b>：每个方法都调用与页面完全相同的
 * {@link ReportApplicationService} 查询，再按与页面相同的格式规则落格。
 * 因此不存在"页面一个数、Excel 另一个数"的可能 —— 测试用同一批服务结果逐格比对。
 *
 * <p>格式契约（页面与 CSV 共用）：
 * <ul>
 *   <li>金额：整数分 → 元，两位小数（对齐前端 {@code fenToYuan}）；</li>
 *   <li>周转天数：一位小数，无出库为 {@code -}；</li>
 *   <li>复购率：万分比 → 两位小数百分比；</li>
 *   <li>布尔：{@code 是} / {@code 否}；空值：{@code -}。</li>
 * </ul>
 */
@Service
public class ReportCsvService {

    private static final String CRLF = "\r\n";

    private final ReportApplicationService reports;

    public ReportCsvService(ReportApplicationService reports) {
        this.reports = reports;
    }

    public CsvDocument purchaseCsv(LocalDate from, LocalDate to,
                                   ReportGroupBy groupBy, Long warehouseId) {
        List<ReportRow> rows = reports.purchaseReport(from, to, groupBy, warehouseId);
        Csv csv = new Csv().header("分组键", "名称", "数量", "金额（元）", "单据数");
        for (ReportRow r : rows) {
            csv.row(r.key(), r.label(), r.qty(), money(r.amount()), r.orderCount());
        }
        return new CsvDocument("purchase-%s_%s.csv".formatted(from, to), csv.build());
    }

    public CsvDocument salesCsv(LocalDate from, LocalDate to,
                                ReportGroupBy groupBy, Long warehouseId) {
        List<ReportRow> rows = reports.salesReport(from, to, groupBy, warehouseId);
        Csv csv = new Csv().header("分组键", "名称", "数量", "金额（元）", "单据数");
        for (ReportRow r : rows) {
            csv.row(r.key(), r.label(), r.qty(), money(r.amount()), r.orderCount());
        }
        return new CsvDocument("sales-%s_%s.csv".formatted(from, to), csv.build());
    }

    public CsvDocument inventorySummaryCsv(YearMonth period, Long warehouseId) {
        List<InventorySummaryExportRow> rows = reports.inventorySummaryExport(period, warehouseId);
        Csv csv = new Csv().header("SKU", "仓库", "期初", "本期入库", "本期出库", "期末");
        for (InventorySummaryExportRow r : rows) {
            csv.row(r.skuName(), r.warehouseName(), r.openingQty(), r.inboundQty(),
                    r.outboundQty(), r.closingQty());
        }
        return new CsvDocument("inventory-summary-%s.csv".formatted(period), csv.build());
    }

    public CsvDocument operatingCsv(LocalDate from, LocalDate to,
                                    ReportPeriodDim dim, Long warehouseId) {
        List<OperatingReportRow> rows = reports.operatingReport(from, to, dim, warehouseId);
        Csv csv = new Csv().header("周期", "期初", "本期入库", "本期出库", "期末",
                "进货数量", "进货金额（元）", "出货数量", "出货金额（元）");
        for (OperatingReportRow r : rows) {
            csv.row(r.period(), r.openingQty(), r.inboundQty(), r.outboundQty(), r.closingQty(),
                    r.purchaseQty(), money(r.purchaseAmount()),
                    r.salesQty(), money(r.salesAmount()));
        }
        return new CsvDocument("operating-%s_%s-%s.csv".formatted(from, to,
                dim.name().toLowerCase(java.util.Locale.ROOT)), csv.build());
    }

    public CsvDocument inventoryTurnoverCsv(LocalDate from, LocalDate to,
                                            int deadDays, Long warehouseId) {
        List<InventoryTurnoverRow> rows = reports.inventoryTurnover(from, to, deadDays, warehouseId);
        Csv csv = new Csv().header("SKU编码", "SKU名称", "仓库", "期初", "期末",
                "区间出库", "周转天数", "呆滞天数", "是否呆滞");
        for (InventoryTurnoverRow r : rows) {
            csv.row(r.skuCode(), r.skuName(), r.warehouseName(), r.openingQty(), r.closingQty(),
                    r.outboundQty(), turnoverDays(r.turnoverDays()), r.idleDays(),
                    yesNo(r.stale()));
        }
        return new CsvDocument("inventory-turnover-%s_%s.csv".formatted(from, to), csv.build());
    }

    public CsvDocument customerContributionCsv(LocalDate from, LocalDate to,
                                               Long warehouseId, int limit) {
        CustomerContributionResult result = reports.customerContribution(
                from, to, warehouseId, limit);
        Csv csv = new Csv().header("客户编码", "客户名称", "单据数", "销量",
                "销售额（元）", "是否复购", "客户类型");
        for (CustomerContributionRow r : result.items()) {
            csv.row(r.customerCode(), r.customerName(), r.orderCount(), r.qty(),
                    money(r.amount()), yesNo(r.repurchase()),
                    r.newCustomer() ? "新客" : "回头客");
        }
        CustomerContributionSummary s = result.summary();
        csv.blank();
        csv.header("汇总", "客户数", "复购客户数", "复购率", "新客数", "回头客数",
                "总销量", "总销售额（元）", "单据数");
        csv.row("汇总", s.customerCount(), s.repurchaseCount(), rate(s.repurchaseRateBp()),
                s.newCustomerCount(), s.returningCustomerCount(), s.totalQty(),
                money(s.totalAmount()), s.orderCount());
        return new CsvDocument("customer-contribution-%s_%s.csv".formatted(from, to),
                csv.build());
    }

    public CsvDocument agingCsv(AgingType type, LocalDate asOf) {
        AgingResult result = reports.aging(type, asOf);
        String partner = type == AgingType.AR ? "客户" : "供应商";
        Csv csv = new Csv().header(partner + "编码", partner + "名称", "余额（元）",
                "0-30", "31-60", "61-90", "90+");
        for (AgingRow r : result.items()) {
            csv.row(r.partnerCode(), r.partnerName(), money(r.balanceAmount()),
                    money(r.bucket0()), money(r.bucket1()), money(r.bucket2()),
                    money(r.bucket3()));
        }
        AgingSummary s = result.summary();
        csv.blank();
        csv.header("汇总", "往来单位数", "余额合计（元）", "0-30", "31-60", "61-90", "90+");
        csv.row("汇总", s.partnerCount(), money(s.totalBalance()), money(s.bucket0()),
                money(s.bucket1()), money(s.bucket2()), money(s.bucket3()));
        return new CsvDocument("aging-%s-%s.csv".formatted(type, asOf), csv.build());
    }

    // ─────────────────────────── 与页面共用的格式规则 ───────────────────────────

    /** 整数分 → 元，两位小数；与前端 {@code fenToYuan} 同一结果。 */
    static String money(long fen) {
        return BigDecimal.valueOf(fen, 2).toPlainString();
    }

    /** 周转天数一位小数；无出库（null）显示 {@code -}。 */
    static String turnoverDays(BigDecimal days) {
        return days == null ? "-" : days.setScale(1, RoundingMode.HALF_UP).toPlainString();
    }

    /** 万分比 → 两位小数百分比（6667 → 66.67%）。 */
    static String rate(long basisPoints) {
        return BigDecimal.valueOf(basisPoints, 2).toPlainString() + "%";
    }

    static String yesNo(boolean value) {
        return value ? "是" : "否";
    }

    /**
     * 最小 CSV 组装器：UTF-8 BOM + CRLF；含逗号、引号、换行的字段加引号并双写引号。
     * Excel 直接打开即可，不需要额外编码设置。
     */
    static final class Csv {

        private final StringBuilder out = new StringBuilder("\uFEFF");

        Csv header(Object... cells) {
            return row(cells);
        }

        Csv row(Object... cells) {
            for (int i = 0; i < cells.length; i++) {
                if (i > 0) {
                    out.append(',');
                }
                out.append(escape(cells[i] == null ? "" : String.valueOf(cells[i])));
            }
            out.append(CRLF);
            return this;
        }

        Csv blank() {
            out.append(CRLF);
            return this;
        }

        String build() {
            return out.toString();
        }

        private static String escape(String raw) {
            if (raw.indexOf(',') < 0 && raw.indexOf('"') < 0
                    && raw.indexOf('\n') < 0 && raw.indexOf('\r') < 0) {
                return raw;
            }
            return '"' + raw.replace("\"", "\"\"") + '"';
        }
    }
}
