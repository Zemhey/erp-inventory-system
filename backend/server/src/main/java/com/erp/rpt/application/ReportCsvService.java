package com.erp.rpt.application;

import com.erp.rpt.domain.AgingGroupBy;
import com.erp.rpt.domain.AgingResult;
import com.erp.rpt.domain.AgingRow;
import com.erp.rpt.domain.AgingSummary;
import com.erp.rpt.domain.AgingType;
import com.erp.rpt.domain.CustomerContributionResult;
import com.erp.rpt.domain.CustomerContributionRow;
import com.erp.rpt.domain.CustomerContributionSummary;
import com.erp.rpt.domain.DocumentDetailRow;
import com.erp.rpt.domain.InventorySummaryExportRow;
import com.erp.rpt.domain.InventoryTurnoverRow;
import com.erp.rpt.domain.InventoryValueDim;
import com.erp.rpt.domain.InventoryValueResult;
import com.erp.rpt.domain.InventoryValueRow;
import com.erp.rpt.domain.OperatingReportRow;
import com.erp.rpt.domain.ReportGroupBy;
import com.erp.rpt.domain.ReportOrderBy;
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
        return purchaseCsv(from, to, groupBy, warehouseId, ReportOrderBy.KEY, null);
    }

    /** 进货统计导出（V4-4 扩展）：与 JSON 端点同一组参数、同一条 SQL。 */
    public CsvDocument purchaseCsv(LocalDate from, LocalDate to, ReportGroupBy groupBy,
                                   Long warehouseId, ReportOrderBy orderBy, Integer limit) {
        List<ReportRow> rows = reports.purchaseReport(from, to, groupBy, warehouseId, orderBy, limit);
        Csv csv = new Csv().header("分组键", "名称", "数量", "金额（元）", "单据数");
        for (ReportRow r : rows) {
            csv.row(r.key(), r.label(), r.qty(), money(r.amount()), r.orderCount());
        }
        return new CsvDocument("purchase-%s_%s.csv".formatted(from, to), csv.build());
    }

    public CsvDocument salesCsv(LocalDate from, LocalDate to,
                                ReportGroupBy groupBy, Long warehouseId) {
        return salesCsv(from, to, groupBy, warehouseId, ReportOrderBy.KEY, null);
    }

    /** 出货统计导出（V4-4 扩展）。 */
    public CsvDocument salesCsv(LocalDate from, LocalDate to, ReportGroupBy groupBy,
                                Long warehouseId, ReportOrderBy orderBy, Integer limit) {
        List<ReportRow> rows = reports.salesReport(from, to, groupBy, warehouseId, orderBy, limit);
        Csv csv = new Csv().header("分组键", "名称", "数量", "金额（元）", "单据数");
        for (ReportRow r : rows) {
            csv.row(r.key(), r.label(), r.qty(), money(r.amount()), r.orderCount());
        }
        return new CsvDocument("sales-%s_%s.csv".formatted(from, to), csv.build());
    }

    // ─────────────────────────── 单据行级明细（V4-4） ───────────────────────────

    /**
     * 进货明细导出（全量，不带分页）。
     *
     * <p>页面是分页看的，CSV 是这个查询条件下的<b>全部行</b>：
     * 分列导出会产出互不相干的若干张表，也就失去了"导出 == 页面"的意义。
     * 金额列与 {@code /reports/purchase/export} 同样按<b>元、两位小数</b>落格，
     * 单位分只存在于 JSON。
     */
    public CsvDocument purchaseDetailCsv(LocalDate from, LocalDate to,
                                         Long warehouseId, Long partnerId) {
        return detailCsv("进货明细", reports.purchaseDetailAll(from, to, warehouseId, partnerId),
                "purchase-detail");
    }

    /** 销售明细导出（全量，不带分页）。 */
    public CsvDocument salesDetailCsv(LocalDate from, LocalDate to,
                                      Long warehouseId, Long partnerId) {
        return detailCsv("销售明细", reports.salesDetailAll(from, to, warehouseId, partnerId),
                "sales-detail");
    }

    private static CsvDocument detailCsv(String title, List<DocumentDetailRow> rows, String stem) {
        Csv csv = new Csv().header("单号", "业务日期", "状态", "往来单位编码", "往来单位名称",
                "仓库", "经手人", "行号", "SKU编码", "SKU名称", "下单数量", "执行数量",
                "单价（元）", "折扣率（万分比）", "金额（元）");
        for (DocumentDetailRow r : rows) {
            csv.row(r.orderNo(), r.bizDate(), r.status(), r.partnerCode(), r.partnerName(),
                    r.warehouseName(), r.operator(), r.lineNo(), r.skuCode(), r.skuName(),
                    r.orderedQty(), r.executedQty(), money(r.unitPriceAmount()),
                    r.discountRate() == null ? "-" : r.discountRate(), money(r.amount()));
        }
        return new CsvDocument("%s-%s.csv".formatted(stem, java.time.LocalDate.now()), csv.build());
    }

    // ─────────────────────────── 呆滞 / 库存金额（V4-4） ───────────────────────────

    /**
     * 呆滞分析导出。列与 {@code /reports/inventory-turnover/export} 一致，
     * 因为两张表本来就是同一份行形状（阈值不同而已）。
     */
    public CsvDocument slowMovingCsv(LocalDate from, LocalDate to, int days, Long warehouseId) {
        List<InventoryTurnoverRow> rows = reports.slowMoving(from, to, days, warehouseId);
        Csv csv = new Csv().header("SKU编码", "SKU名称", "仓库", "期初", "期末",
                "区间出库", "周转天数", "呆滞天数", "是否呆滞");
        for (InventoryTurnoverRow r : rows) {
            csv.row(r.skuCode(), r.skuName(), r.warehouseName(), r.openingQty(), r.closingQty(),
                    r.outboundQty(), turnoverDays(r.turnoverDays()), r.idleDays(),
                    yesNo(r.stale()));
        }
        return new CsvDocument("slow-moving-%s_%s.csv".formatted(from, to), csv.build());
    }

    /**
     * 库存金额导出。金额列标<b>（元）</b>并按与页面相同的分→元换算落格，
     * 未月结的维度显示 {@code -}（不是 0），页面上是空白单元格 —— 两边都是"没数"。
     */
    public CsvDocument inventoryValueCsv(YearMonth period, Long warehouseId,
                                         InventoryValueDim dim, Integer limit) {
        InventoryValueResult result = reports.inventoryValue(period, warehouseId, dim, limit);
        Csv csv = new Csv().header("维度键", "名称", "期末数量", "加权平均单价（元）",
                "期末库存金额（元）", "库存池数", "已估值池数");
        for (InventoryValueRow r : result.items()) {
            csv.row(r.key(), r.label(), r.qty(),
                    r.unitCostAmount() == null ? "-" : money(r.unitCostAmount()),
                    r.costAmount() == null ? "-" : money(r.costAmount()),
                    r.poolCount(), r.valuedPoolCount());
        }
        InventoryValueResult.Summary s = result.summary();
        csv.blank();
        csv.header("汇总", "库存池数", "已估值池数", "估值覆盖率", "期末数量合计",
                "已估值数量合计", "期末库存金额合计（元）");
        csv.row("汇总", s.poolCount(), s.valuedPoolCount(), rate(s.coverageRateBp()),
                s.totalQty(), s.valuedQty(), money(s.totalCostAmount()));
        return new CsvDocument("inventory-value-%s.csv".formatted(period), csv.build());
    }

    public CsvDocument inventorySummaryCsv(YearMonth period, Long warehouseId) {
        List<InventorySummaryExportRow> rows = reports.inventorySummaryExport(period, warehouseId);
        // 前 6 列一个没动（老脚本按列位读仍然对）；V4-4 追加 SKU编码与四个金额列。
        Csv csv = new Csv().header("SKU", "仓库", "期初", "本期入库", "本期出库", "期末",
                "SKU编码", "期初金额（元）", "本期入库金额（元）", "本期出库金额（元）",
                "期末金额（元）");
        for (InventorySummaryExportRow r : rows) {
            csv.row(r.skuName(), r.warehouseName(), r.openingQty(), r.inboundQty(),
                    r.outboundQty(), r.closingQty(), r.skuCode(),
                    moneyOrDash(r.openingCostAmount()), moneyOrDash(r.inboundCostAmount()),
                    moneyOrDash(r.outboundCostAmount()), moneyOrDash(r.closingCostAmount()));
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
        return agingCsv(type, asOf, null, AgingGroupBy.PARTNER, null);
    }

    /** 账龄导出（V4-4 扩展）：可选单一往来单位、可选按单据展开、可选 Top N。 */
    public CsvDocument agingCsv(AgingType type, LocalDate asOf, Long partnerId,
                                AgingGroupBy groupBy, Integer limit) {
        AgingResult result = reports.aging(type, asOf, partnerId, groupBy, limit);
        String partner = type == AgingType.AR ? "客户" : "供应商";
        boolean byDoc = groupBy == AgingGroupBy.DOCUMENT;
        Csv csv = byDoc
                ? new Csv().header(partner + "编码", partner + "名称", "单号", "单据日期",
                    "到期日", "余额（元）", "0-30", "31-60", "61-90", "90+")
                : new Csv().header(partner + "编码", partner + "名称", "余额（元）",
                    "0-30", "31-60", "61-90", "90+");
        for (AgingRow r : result.items()) {
            if (byDoc) {
                csv.row(r.partnerCode(), r.partnerName(), dash(r.docNo()), dash(r.docDate()),
                        dash(r.dueDate()), money(r.balanceAmount()),
                        money(r.bucket0()), money(r.bucket1()), money(r.bucket2()),
                        money(r.bucket3()));
            } else {
                csv.row(r.partnerCode(), r.partnerName(), money(r.balanceAmount()),
                        money(r.bucket0()), money(r.bucket1()), money(r.bucket2()),
                        money(r.bucket3()));
            }
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

    /** 可空金额：null = 该账期未做成本月结（没数），落 {@code -} 而不是 0.00。 */
    static String moneyOrDash(Long fen) {
        return fen == null ? "-" : money(fen);
    }

    /** 可空文本列（按单据展开时的单号/日期）；AP 没有到期日，恒为 {@code -}。 */
    static String dash(String value) {
        return value == null ? "-" : value;
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
