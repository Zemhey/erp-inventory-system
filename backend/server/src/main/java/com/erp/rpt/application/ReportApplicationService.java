package com.erp.rpt.application;

import com.erp.rpt.domain.AgingResult;
import com.erp.rpt.domain.AgingRow;
import com.erp.rpt.domain.AgingSummary;
import com.erp.rpt.domain.AgingType;
import com.erp.rpt.domain.CustomerContributionResult;
import com.erp.rpt.domain.CustomerContributionRow;
import com.erp.rpt.domain.CustomerContributionSummary;
import com.erp.rpt.domain.InventorySummaryExportRow;
import com.erp.rpt.domain.InventorySummaryRow;
import com.erp.rpt.domain.InventoryTurnoverRow;
import com.erp.rpt.domain.OperatingReportRow;
import com.erp.rpt.domain.ReportGroupBy;
import com.erp.rpt.domain.ReportPeriodDim;
import com.erp.rpt.domain.ReportRow;
import com.erp.rpt.repository.ReportAdvancedRepository;
import com.erp.rpt.repository.ReportRepository;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 报表查询服务（<b>只读</b>，计划书 §3.1：RPT 只做跨域只读查询，不写任何表）。
 *
 * <p>这里没有任何"业务规则"要写 —— 报表的规则就是口径，而口径写死在
 * {@link ReportRepository} 的 SQL 片段里。本类只负责两件事：
 * <ol>
 *   <li><b>区间合法性</b>：{@code from > to} 是客户端错误，必须在进 SQL 之前拒绝，
 *       并给成 400 + 稳定错误码（放在应用服务而不是 Controller，
 *       是为了让 M4 小程序 / M5 Android 走同一套判断，不复制规则）；</li>
 *   <li><b>账期换算</b>：把契约的 {@code yyyy-MM} 变成流水时间轴上的
 *       <code>[月初, 下月初)</code> 左闭右开区间。用"下月初"而不是"月末 23:59:59"，
 *       是为了不把 {@code occurred_at} 的亚秒精度排除在外。</li>
 * </ol>
 */
@Service
public class ReportApplicationService {

    private final ReportRepository reports;
    private final ReportAdvancedRepository advanced;

    public ReportApplicationService(ReportRepository reports, ReportAdvancedRepository advanced) {
        this.reports = reports;
        this.advanced = advanced;
    }

    /** 进货统计（§3.5：只统计已入库的进货单，按单据 {@code biz_date} 归属）。 */
    public List<ReportRow> purchaseReport(LocalDate from, LocalDate to,
                                          ReportGroupBy groupBy, Long warehouseId) {
        requireRange(from, to);
        return reports.purchaseReport(from, to, groupBy, warehouseId);
    }

    /** 出货统计（§3.5：只统计已出货的出货单，按单据 {@code biz_date} 归属，不含退货）。 */
    public List<ReportRow> salesReport(LocalDate from, LocalDate to,
                                       ReportGroupBy groupBy, Long warehouseId) {
        requireRange(from, to);
        return reports.salesReport(from, to, groupBy, warehouseId);
    }

    /**
     * 进销存汇总（§3.5：按流水 {@code occurred_at} 归属；
     * 期初 + 入 − 出 = 期末，且期末等于截至期末的流水净和）。
     */
    public List<InventorySummaryRow> inventorySummaryReport(YearMonth period, Long warehouseId) {
        return reports.inventorySummary(
                period.atDay(1).atStartOfDay(),
                period.plusMonths(1).atDay(1).atStartOfDay(),
                warehouseId);
    }

    /**
     * 进销存导出：与页面 {@link #inventorySummaryReport} 同一条 SQL，只多读名称列。
     * 导出不重新计算、不重新舍入。
     */
    public List<InventorySummaryExportRow> inventorySummaryExport(YearMonth period, Long warehouseId) {
        return reports.inventorySummaryExport(
                period.atDay(1).atStartOfDay(),
                period.plusMonths(1).atDay(1).atStartOfDay(),
                warehouseId);
    }

    // ─────────────────────────── V2-8 报表 ───────────────────────────

    /**
     * 经营日报 / 月报。期初/入/出/期末来自库存流水，进货/销售来自单据，
     * 两组口径分别列示、互不推算；INV-6 由流水三数独立求和保证。
     */
    public List<OperatingReportRow> operatingReport(LocalDate from, LocalDate to,
                                                    ReportPeriodDim dim, Long warehouseId) {
        requireRange(from, to);
        return advanced.operatingReport(from, to, dim, warehouseId);
    }

    /**
     * 库存周转与呆滞。周转天数按 {@code (期初+期末)/2 × 天数 / 出库} 一位小数；
     * 无出库返回 null（页面/CSV 显示 {@code -}）。
     */
    public List<InventoryTurnoverRow> inventoryTurnover(LocalDate from, LocalDate to,
                                                        int deadDays, Long warehouseId) {
        requireRange(from, to);
        if (deadDays < 1 || deadDays > 3650) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "deadDays 必须在 1~3650 之间：%d".formatted(deadDays));
        }
        return advanced.inventoryTurnover(from, to, deadDays, warehouseId);
    }

    /**
     * 客户贡献与复购。Top N 只截断展示，汇总始终覆盖全部客户；
     * 复购率以万分比整数返回，避免浮点误差。
     */
    public CustomerContributionResult customerContribution(LocalDate from, LocalDate to,
                                                            Long warehouseId, int limit) {
        requireRange(from, to);
        if (limit < 1 || limit > 200) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "limit 必须在 1~200 之间：%d".formatted(limit));
        }
        List<CustomerContributionRow> all = advanced.customerContribution(from, to, warehouseId);
        List<CustomerContributionRow> items = all.stream().limit(limit).toList();
        return new CustomerContributionResult(items, summarizeContribution(all));
    }

    /** 应收 / 应付账龄，桶合计守恒由 SQL 保证、汇总再校验一次。 */
    public AgingResult aging(AgingType type, LocalDate asOf) {
        List<AgingRow> rows = advanced.aging(type, asOf);
        long total = rows.stream().mapToLong(AgingRow::balanceAmount).sum();
        long b0 = rows.stream().mapToLong(AgingRow::bucket0).sum();
        long b1 = rows.stream().mapToLong(AgingRow::bucket1).sum();
        long b2 = rows.stream().mapToLong(AgingRow::bucket2).sum();
        long b3 = rows.stream().mapToLong(AgingRow::bucket3).sum();
        if (b0 + b1 + b2 + b3 != total) {
            throw new BusinessException(ErrorCode.INV_BALANCE_MISMATCH,
                    "账龄分桶不守恒：桶合计 %d != 余额 %d（type=%s, asOf=%s）"
                            .formatted(b0 + b1 + b2 + b3, total, type, asOf));
        }
        return new AgingResult(rows, new AgingSummary(rows.size(), total, b0, b1, b2, b3));
    }

    private static CustomerContributionSummary summarizeContribution(
            List<CustomerContributionRow> rows) {
        int customerCount = rows.size();
        int repurchaseCount = (int) rows.stream().filter(CustomerContributionRow::repurchase).count();
        int newCount = (int) rows.stream().filter(CustomerContributionRow::newCustomer).count();
        long totalQty = rows.stream().mapToLong(CustomerContributionRow::qty).sum();
        long totalAmount = rows.stream().mapToLong(CustomerContributionRow::amount).sum();
        long orderCount = rows.stream().mapToLong(CustomerContributionRow::orderCount).sum();
        long rateBp = customerCount == 0
                ? 0
                : (repurchaseCount * 10_000L + customerCount / 2L) / customerCount;
        return new CustomerContributionSummary(customerCount, repurchaseCount, rateBp,
                newCount, customerCount - newCount, totalQty, totalAmount, orderCount);
    }

    /** 日期区间为闭区间 [from, to]；{@code from > to} 返回 400（TC-RPT-009）。 */
    private static void requireRange(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "日期区间非法：起始日 %s 晚于结束日 %s（闭区间要求 from <= to）"
                            .formatted(from, to));
        }
    }
}
