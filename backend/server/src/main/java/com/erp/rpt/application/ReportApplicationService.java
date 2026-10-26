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
import com.erp.rpt.domain.InventorySummaryRow;
import com.erp.rpt.domain.InventoryTurnoverRow;
import com.erp.rpt.domain.InventoryValueDim;
import com.erp.rpt.domain.InventoryValueResult;
import com.erp.rpt.domain.InventoryValueRow;
import com.erp.rpt.domain.OperatingReportRow;
import com.erp.rpt.domain.ReportGroupBy;
import com.erp.rpt.domain.ReportOrderBy;
import com.erp.rpt.domain.ReportPeriodDim;
import com.erp.rpt.domain.ReportRow;
import com.erp.rpt.repository.ReportAdvancedRepository;
import com.erp.rpt.repository.ReportRepository;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.web.PageResult;
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

    /**
     * 进货统计（V4-4 扩展：{@code orderBy} + {@code limit}）。
     *
     * <p>与 {@link #customerContribution} 的约定一致：{@code limit} 只截断<b>展示行</b>。
     * 这两个端点没有 summary 信封，所以截断就是截断，页面若要合计得另外取全量
     * （不传 limit）—— 这也是 {@code orderBy=amount} 必须与 {@code limit} 配对的原因：
     * 按分组键排序再截断切掉的是字典序靠后的行，不是"最少的 N 个"。
     */
    public List<ReportRow> purchaseReport(LocalDate from, LocalDate to, ReportGroupBy groupBy,
                                          Long warehouseId, ReportOrderBy orderBy, Integer limit) {
        requireRange(from, to);
        requireLimit(limit);
        return reports.purchaseReport(from, to, groupBy, warehouseId,
                orderBy == null ? ReportOrderBy.KEY : orderBy, limit);
    }

    /** 出货统计（§3.5：只统计已出货的出货单，按单据 {@code biz_date} 归属，不含退货）。 */
    public List<ReportRow> salesReport(LocalDate from, LocalDate to,
                                       ReportGroupBy groupBy, Long warehouseId) {
        requireRange(from, to);
        return reports.salesReport(from, to, groupBy, warehouseId);
    }

    /** 出货统计（V4-4 扩展），语义同 {@link #purchaseReport} 的扩展版。 */
    public List<ReportRow> salesReport(LocalDate from, LocalDate to, ReportGroupBy groupBy,
                                       Long warehouseId, ReportOrderBy orderBy, Integer limit) {
        requireRange(from, to);
        requireLimit(limit);
        return reports.salesReport(from, to, groupBy, warehouseId,
                orderBy == null ? ReportOrderBy.KEY : orderBy, limit);
    }

    // ─────────────────────────── 单据行级明细（V4-4） ───────────────────────────

    /**
     * 进货明细（按单据展开）。
     *
     * <p>{@code page}/{@code size} 沿用项目既有分页写法（从 1 起，默认 20），
     * 金额口径与进货统计逐字相同，所以"明细各行金额之和 == 同期同参的统计金额"可核对。
     */
    public PageResult<DocumentDetailRow> purchaseDetail(
            LocalDate from, LocalDate to, Long warehouseId, Long partnerId, int page, int size) {
        requireRange(from, to);
        requirePage(page, size);
        List<DocumentDetailRow> items =
                reports.purchaseDetail(from, to, warehouseId, partnerId, offset(page, size), size);
        return new PageResult<>(items,
                reports.purchaseDetailCount(from, to, warehouseId, partnerId), page, size);
    }

    /** 销售明细（按单据展开）。 */
    public PageResult<DocumentDetailRow> salesDetail(
            LocalDate from, LocalDate to, Long warehouseId, Long partnerId, int page, int size) {
        requireRange(from, to);
        requirePage(page, size);
        List<DocumentDetailRow> items =
                reports.salesDetail(from, to, warehouseId, partnerId, offset(page, size), size);
        return new PageResult<>(items,
                reports.salesDetailCount(from, to, warehouseId, partnerId), page, size);
    }

    /** 进货明细全量（导出用，不接受分页）。 */
    public List<DocumentDetailRow> purchaseDetailAll(LocalDate from, LocalDate to,
                                                     Long warehouseId, Long partnerId) {
        requireRange(from, to);
        return reports.purchaseDetailAll(from, to, warehouseId, partnerId);
    }

    /** 销售明细全量（导出用，不接受分页）。 */
    public List<DocumentDetailRow> salesDetailAll(LocalDate from, LocalDate to,
                                                  Long warehouseId, Long partnerId) {
        requireRange(from, to);
        return reports.salesDetailAll(from, to, warehouseId, partnerId);
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

    // ─────────────────────────── 库存金额 / 总值（V4-4） ───────────────────────────

    /**
     * 库存金额（库存总值）。
     *
     * <p><b>★ 为什么在 rpt 里自己开一个端点，而不是让前端去调 {@code GET /cost/monthly}</b>：
     * 那个端点属成本域（权限码 {@code cst:*}），报表页的账号只有 {@code rpt:read}。
     * 跨权限域取数要么让用户组多拿一个不该有的权限码，要么在前端拼两个域的字典 ——
     * 两条都把"报表只读"变成了"报表要写权限"。这里的做法是：rpt 用自己的只读 SQL
     * 读 {@code cost_monthly}（成本月结写下的结果表），权限仍是 {@code rpt:read}，
     * 成本域的计算与写入口一个字都不碰。<b>没有借权限码，也没有借应用服务</b>
     * （{@code ArchitectureTest} 规则 7 因此依然绿）。
     *
     * <p><b>单位分</b>（ADR-0005），全程成本口径，不出现售价。
     * 未做成本月结的账期／池返回 {@code null} 金额，并在 {@code summary} 里以
     * {@code valuedPoolCount} 与 {@code coverageRateBp} 如实报告覆盖率，
     * 而不是把"没数"混进合计冒充"值为 0"。
     *
     * <p>期末数量与期末金额都来自 {@link #inventorySummaryReport} 的同一条 SQL，
     * 所以这张表与进销存汇总页、与 {@code /reports/inventory-summary} 的
     * {@code closingQty} / {@code closingCostAmount} 两列必然一致。
     */
    public InventoryValueResult inventoryValue(YearMonth period, Long warehouseId,
                                               InventoryValueDim dim, Integer limit) {
        requireLimit(limit);
        List<InventorySummaryRow> pools = inventorySummaryReport(period, warehouseId);

        java.util.Map<String, Acc> grouped = new java.util.LinkedHashMap<>();
        long totalQty = 0;
        long valuedQty = 0;
        long totalCost = 0;
        int valuedPools = 0;
        for (InventorySummaryRow p : pools) {
            String key = dim == InventoryValueDim.WAREHOUSE
                    ? String.valueOf(p.warehouseId()) : String.valueOf(p.skuId());
            String label = dim == InventoryValueDim.WAREHOUSE
                    ? p.warehouseName() : p.skuName();
            grouped.computeIfAbsent(key, k -> new Acc(k, label)).add(p);
            totalQty += p.closingQty();
            if (p.closingCostAmount() != null) {
                valuedPools++;
                valuedQty += p.closingQty();
                totalCost += p.closingCostAmount();
            }
        }

        List<InventoryValueRow> all = grouped.values().stream()
                .sorted((a, b) -> {
                    int byCost = Long.compare(b.cost, a.cost);
                    if (byCost != 0) {
                        return byCost;
                    }
                    return String.CASE_INSENSITIVE_ORDER.compare(
                            a.label == null ? "" : a.label, b.label == null ? "" : b.label);
                })
                .map(Acc::toRow)
                .toList();
        List<InventoryValueRow> items = limit == null ? all : all.stream().limit(limit).toList();
        long coverageBp = pools.isEmpty()
                ? 0
                : (valuedPools * 10_000L + pools.size() / 2L) / pools.size();
        return new InventoryValueResult(items, new InventoryValueResult.Summary(
                pools.size(), valuedPools, totalQty, valuedQty, totalCost, coverageBp));
    }

    /**
     * 库存金额的可变累加器（一个维度值 = 一个累加槽）。
     *
     * <p>只在方法内部活，所以写成私有静态类；单位分。
     * {@code valuedPoolCount == 0} 时 {@code toRow()} 返回 {@code null} 金额，
     * 这就是"未月结的维度不显示 0 元"的落点。
     */
    private static final class Acc {
        private final String key;
        private final String label;
        private long qty;
        private long cost;
        private int poolCount;
        private int valuedPoolCount;

        private Acc(String key, String label) {
            this.key = key;
            this.label = label;
        }

        private void add(InventorySummaryRow p) {
            qty += p.closingQty();
            poolCount++;
            if (p.closingCostAmount() != null) {
                cost += p.closingCostAmount();
                valuedPoolCount++;
            }
        }

        private InventoryValueRow toRow() {
            Long unit = valuedPoolCount == 0 || qty <= 0
                    ? null
                    : Math.round((double) cost / (double) qty);
            return new InventoryValueRow(key, label, qty, unit,
                    valuedPoolCount == 0 ? null : cost, poolCount, valuedPoolCount);
        }
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
        requireDeadDays(deadDays);
        return advanced.inventoryTurnover(from, to, deadDays, warehouseId);
    }

    /**
     * 呆滞分析（V4-4 新增端点）。
     *
     * <p><b>阈值语义与周转表共用同一个 {@code deadDays} 口径</b>，不新造第二套定义：
     * 结果 = 周转查询里 {@code idle_days >= days} 的那些行（期末有库存由周转 SQL 的
     * {@code HAVING closing_qty > 0} 保证）。前端此前是把全量周转行拉回浏览器再过滤，
     * 既白跑传输也没有服务端 CSV；现在过滤进 SQL，排序仍是呆滞优先。
     *
     * <p>行形状与 {@code /reports/inventory-turnover} 逐字相同（同一份列定义），
     * 所以页面切端点不用改一列。
     */
    public List<InventoryTurnoverRow> slowMoving(LocalDate from, LocalDate to,
                                                 int days, Long warehouseId) {
        requireRange(from, to);
        requireDeadDays(days);
        return advanced.slowMoving(from, to, days, warehouseId);
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
        return aging(type, asOf, null, AgingGroupBy.PARTNER, null);
    }

    /**
     * 账龄（V4-4 扩展：{@code partnerId} / {@code groupBy} / {@code limit}）。
     *
     * <p>三条纪律：
     * <ul>
     *   <li>{@code summary} 始终覆盖<b>过滤后、截断前</b>的全部行 ——
     *       {@code limit=5} 看的是前 5 行，合计仍是这个单位/这批单的全额；</li>
     *   <li>{@code partnerId} 是"只看这一个往来单位"，桶算法与全表一致，
     *       不是另起一套口径（所以单位级金额与不传 partnerId 时该单位那行逐字相同）；</li>
     *   <li>{@code groupBy=document} 只是换分组键，四桶守恒同样要成立，
     *       守恒校验对两种粒度都跑。</li>
     * </ul>
     *
     * <p>金额单位分（ADR-0005）。
     */
    public AgingResult aging(AgingType type, LocalDate asOf, Long partnerId,
                             AgingGroupBy groupBy, Integer limit) {
        requireLimit(limit);
        List<AgingRow> rows = advanced.aging(type, asOf, partnerId,
                groupBy == null ? AgingGroupBy.PARTNER : groupBy);
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
        // partnerCount 按 partnerId 去重而不是取行数：groupBy=document 时一行是一张单据，
        // 行数已经不是"往来单位数"了。PARTNER 粒度下两者相等，既有响应因此不变。
        int partnerCount = (int) rows.stream().map(AgingRow::partnerId).distinct().count();
        List<AgingRow> items = limit == null ? rows : rows.stream().limit(limit).toList();
        return new AgingResult(items, new AgingSummary(partnerCount, total, b0, b1, b2, b3));
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

    /**
     * Top N 上限。范围与 {@code customer-contribution} 的 {@code limit} 逐字相同（1~200），
     * 不传（{@code null}）＝不截断，也就是既有行为。
     */
    private static void requireLimit(Integer limit) {
        if (limit != null && (limit < 1 || limit > 200)) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "limit 必须在 1~200 之间：%d".formatted(limit));
        }
    }

    /** 呆滞/周转的天数阈值（同一个 1~3650 约束，两处共用一条判断）。 */
    private static void requireDeadDays(int days) {
        if (days < 1 || days > 3650) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "days/deadDays 必须在 1~3650 之间：%d".formatted(days));
        }
    }

    /** 分页参数：与项目既有分页端点同样"从 1 起、每页最多 200 条"。 */
    private static void requirePage(int page, int size) {
        if (page < 1) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "page 从 1 起：%d".formatted(page));
        }
        if (size < 1 || size > 200) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "size 必须在 1~200 之间：%d".formatted(size));
        }
    }

    private static int offset(int page, int size) {
        return (page - 1) * size;
    }
}
