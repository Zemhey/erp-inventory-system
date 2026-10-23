package com.erp.rpt.application;

import com.erp.rpt.domain.InventorySummaryRow;
import com.erp.rpt.domain.ReportGroupBy;
import com.erp.rpt.domain.ReportRow;
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

    public ReportApplicationService(ReportRepository reports) {
        this.reports = reports;
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

    /** 日期区间为闭区间 [from, to]；{@code from > to} 返回 400（TC-RPT-009）。 */
    private static void requireRange(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "日期区间非法：起始日 %s 晚于结束日 %s（闭区间要求 from <= to）"
                            .formatted(from, to));
        }
    }
}
