package com.erp.cst.application;

import com.erp.cst.domain.MarginSummaryRow;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 毛利分析的服务端 CSV 导出（FR-RPT-09 / FR-CST-03 的导出半边）。
 *
 * <p><b>为什么写在 {@code cst} 而不是 {@code rpt}</b>：毛利查询本来就是
 * {@code com.erp.cst.application.MarginApplicationService} 提供的（路径挂在
 * {@code /api/v1/reports/profit} 只是为了前端菜单与权限归口）。
 * {@code ArchitectureTest} 规则 7 明确禁止 {@code com.erp.rpt..} 依赖其它模块的
 * {@code ..application..}（报表不许拿到别人的写入口），所以导出只能落在数据的归属模块里，
 * 与查询端点同模块、同一个 service、同一组参数 —— 这才还是"导出 == 页面"。
 *
 * <p>格式规则与报表中心其余 7 个导出<b>逐字相同</b>（UTF-8 BOM + CRLF、
 * 整数分→元两位小数、万分比→百分比、空值 {@code -}）。这里没有复用
 * {@code com.erp.rpt.application.ReportCsvService} 的内部 Csv 组装器，
 * 是因为那会让 {@code cst.application} 反向依赖 {@code rpt.application}；
 * 宁可二十行格式代码各写一份，也不给模块之间添一条新的依赖边。
 *
 * <p><b>★ 单位</b>：源数据是<b>分</b>（ADR-0005），CSV 表头一律标（元），
 * 毛利率是万分比（表头标 %）。
 */
@Service
public class MarginCsvService {

    private static final String CRLF = "\r\n";

    private final MarginApplicationService margins;

    public MarginCsvService(MarginApplicationService margins) {
        this.margins = margins;
    }

    /**
     * 与 {@code GET /reports/profit} 同一组参数、同一次 {@code analyze} 调用。
     *
     * <p>{@code from} / {@code to} 是<b>账期</b>（{@code yyyy-MM}），不是 ISO 日期 ——
     * 这一点与报表中心其它表不同，照日期传会 400，页面与导出两边口径必须一致。
     */
    public Result csv(String from, String to, String groupBy) {
        List<MarginSummaryRow> rows = margins.analyze(from, to, groupBy);
        StringBuilder out = new StringBuilder("\uFEFF");
        row(out, "分组键", "名称", "销量", "销售额（元）", "成本（元）",
                "毛利额（元）", "毛利率", "单据数");
        for (MarginSummaryRow r : rows) {
            row(out, r.key(), r.label(), r.qty(), money(r.revenueAmount()),
                    money(r.costAmount()), money(r.grossProfitAmount()),
                    rate(r.grossMarginRateBp()), r.orderCount());
        }
        String filename = "profit-%s_%s.csv".formatted(
                YearMonth.parse(from.trim()), YearMonth.parse(to.trim()));
        return new Result(filename, out.toString());
    }

    /** 导出结果（文件名 + 完整 CSV 文本）。与 rpt 侧的 {@code CsvDocument} 同形、各自独立。 */
    public record Result(String filename, String content) {
    }

    private static void row(StringBuilder out, Object... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(escape(cells[i] == null ? "" : String.valueOf(cells[i])));
        }
        out.append(CRLF);
    }

    /** 整数分 → 元，两位小数（与前端 fenToYuan、rpt 侧 money 同一结果）。 */
    private static String money(long fen) {
        return BigDecimal.valueOf(fen, 2).toPlainString();
    }

    /** 万分比 → 两位小数百分比（6667 → 66.67%）。 */
    private static String rate(long basisPoints) {
        return BigDecimal.valueOf(basisPoints, 2).toPlainString() + "%";
    }

    private static String escape(String raw) {
        if (raw.indexOf(',') < 0 && raw.indexOf('"') < 0
                && raw.indexOf('\n') < 0 && raw.indexOf('\r') < 0) {
            return raw;
        }
        return '"' + raw.replace("\"", "\"\"") + '"';
    }
}
