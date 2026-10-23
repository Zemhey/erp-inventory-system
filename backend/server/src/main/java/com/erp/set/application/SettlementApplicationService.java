package com.erp.set.application;

import com.erp.sal.application.SalesOrderApplicationService;
import com.erp.set.domain.DailySettlement;
import com.erp.set.domain.SalesSourceLine;
import com.erp.set.domain.SettlementKey;
import com.erp.set.domain.SettlementPeriod;
import com.erp.set.domain.SettlementPeriodStatus;
import com.erp.set.domain.SettlementTotals;
import com.erp.set.domain.SourceAmountLine;
import com.erp.set.repository.DailySettlementRepository;
import com.erp.set.repository.SettlementPeriodRepository;
import com.erp.set.repository.SettlementSourceRepository;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.time.ClockProvider;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 结算应用服务（FR-SET-01 日清、FR-SET-02 月结锁账、FR-SET-03 对账）。
 *
 * <p><b>三个接口共用同一套"重算"实现</b>（{@link #recompute}）。这不是偷懒，
 * 而是结算能不能对上的关键：日清、月结、对账如果各写一份聚合 SQL，
 * 三处口径迟早分叉，然后"总账与明细差额 0"会变成永远调不平的黑洞。
 * 一份口径 + 一个 {@code net_amount} 恒等式，才让不变量 INV-5 可验证。
 *
 * <p><b>三条不能弯的规则</b>（计划书 §3.4 / §3.7）：
 * <ol>
 *   <li>日清幂等靠数据库唯一键 {@code (biz_date, warehouse_id)} 上的 upsert，
 *       写的是"本次重算值"，<b>不是累加</b>；</li>
 *   <li>月结<b>从不静默覆盖</b>已清的日清行：重算与已存不一致就是数据问题，
 *       必须报 {@code SET_ALREADY_SETTLED} 把它挖出来，而不是埋掉；</li>
 *   <li>金额舍入复用 {@link SalesOrderApplicationService#lineAmount}，
 *       不在 SET 里再抄一份 round。</li>
 * </ol>
 */
@Service
public class SettlementApplicationService {

    private final SettlementSourceRepository source;
    private final DailySettlementRepository daily;
    private final SettlementPeriodRepository periods;
    private final ClockProvider clock;

    public SettlementApplicationService(SettlementSourceRepository source,
                                        DailySettlementRepository daily,
                                        SettlementPeriodRepository periods,
                                        ClockProvider clock) {
        this.source = source;
        this.daily = daily;
        this.periods = periods;
        this.clock = clock;
    }

    // ─────────────────────────── 结果 ───────────────────────────

    /** 一个仓库的日清结果（契约 {@code DailySettlementResult.items[]}）。 */
    public record DailyItem(long warehouseId, SettlementTotals totals) {
    }

    public record DailyResult(LocalDate bizDate, List<DailyItem> items, SettlementTotals totals) {
    }

    /**
     * 月结结果。
     *
     * @param duplicate true 表示本次没有改动任何账目 —— 账期早已结清且重算一致（幂等命中）
     */
    public record MonthlyResult(String period, SettlementTotals totals, SettlementPeriodStatus status,
                                OffsetDateTime closedAt, boolean duplicate) {
    }

    public record ReconcileResult(String period, long generalTotal, long detailTotal,
                                  long diff, boolean balanced) {
    }

    // ─────────────────────────── 日清 ───────────────────────────

    /**
     * 日清：按单据 {@code bizDate} 重算当日各仓库的出入库与金额，并落到日清行。
     *
     * <p><b>幂等</b>：同一 (日期, 仓库) 重复执行只会 upsert 同一行，结果必然一致。
     * 并发执行由唯一索引串行化，因此"点两次日清"不会把当天的账算成两倍（TC-SET-003/013）。
     *
     * <p><b>账期已锁则拒绝</b>：锁账之后不允许任何账目再被改写，日清也不例外
     * （TC-SET-012）。这里直接判断账期状态而不走 {@code PeriodGuard}，
     * 因为 guard 的语义是"这笔单据业务能不能记账"，而日清本身是记账动作（计划书 §3.4）。
     *
     * @param warehouseId null 表示全部仓库
     */
    @Transactional
    public DailyResult settleDaily(LocalDate bizDate, Long warehouseId, String requestId) {
        requireOpenPeriod(bizDate);
        List<Long> targets = targetWarehouses(warehouseId);
        Map<SettlementKey, SettlementTotals> recomputed = recompute(bizDate, bizDate);
        OffsetDateTime settledAt = clockNow();

        for (Long target : targets) {
            SettlementKey key = new SettlementKey(bizDate, target);
            SettlementTotals totals = recomputed.getOrDefault(key, SettlementTotals.zero());
            daily.upsert(new DailySettlement(bizDate, target, totals), requestId, settledAt);
        }

        // 回读而不是直接返回内存里的值：响应必须证明"数据库里确实是这样"，
        // 否则幂等断言（TC-SET-003）测的只是 Java 对象相等。
        List<DailyItem> items = daily.findByDate(bizDate, warehouseId).stream()
                .map(row -> new DailyItem(row.warehouseId(), row.totals()))
                .toList();
        SettlementTotals totals = items.stream()
                .map(DailyItem::totals)
                .reduce(SettlementTotals.zero(), SettlementTotals::plus);
        return new DailyResult(bizDate, items, totals);
    }

    // ─────────────────────────── 月结 ───────────────────────────

    /**
     * 月结：补齐该账期缺失的日清行，把期间汇总写入 {@code settlement_periods} 并置 CLOSED。
     *
     * <p><b>为什么要补齐日清行</b>：漏点一天日清就永远关不了账，是运营必然踩的坑。
     *
     * <p><b>为什么不覆盖已存在的日清行</b>：已存的行代表"那一天已经清过账"这个事实。
     * 若重算与它不一致，说明锁账前后有数据被动过 —— 悄悄覆盖就等于把数据问题埋进账本，
     * 所以一律 {@code SET_ALREADY_SETTLED}（计划书 §3.4、§6 坑 6）。
     *
     * <p><b>幂等</b>：已 CLOSED 且重算一致 → 返回原结果并置 {@code duplicate=true}。
     * 并发月结由主键 {@code ON CONFLICT DO NOTHING} + {@code version} 乐观锁收敛，
     * 后到者读到已锁账的同一份汇总，同样返回 duplicate。
     */
    @Transactional
    public MonthlyResult settleMonthly(String periodRaw, String requestId) {
        YearMonth yearMonth = parsePeriod(periodRaw);
        String period = yearMonth.toString();
        LocalDate first = yearMonth.atDay(1);
        LocalDate last = yearMonth.atEndOfMonth();

        Map<SettlementKey, SettlementTotals> recomputed = recompute(first, last);
        SettlementTotals recalculated = fold(recomputed.values());

        requireNoDailyDrift(first, last, recomputed);

        Optional<SettlementPeriod> stored = periods.findByPeriod(period);
        if (stored.isPresent() && stored.get().locked()) {
            return closedIdempotently(stored.get(), recalculated);
        }

        OffsetDateTime closedAt = clockNow();
        for (Map.Entry<SettlementKey, SettlementTotals> entry : recomputed.entrySet()) {
            SettlementKey key = entry.getKey();
            daily.upsert(new DailySettlement(key.bizDate(), key.warehouseId(), entry.getValue()),
                    requestId, closedAt);
        }

        if (stored.isEmpty()) {
            if (periods.insertClosed(period, recalculated, closedAt)) {
                return new MonthlyResult(period, recalculated, SettlementPeriodStatus.CLOSED,
                        closedAt, false);
            }
            // 并发：另一个请求刚把这一行建出来。重新读，按幂等/漂移重新判定。
            return closedIdempotently(requirePeriodRow(period), recalculated);
        }

        SettlementPeriod open = stored.get();
        if (!periods.updateToClosed(period, recalculated, closedAt, open.version())) {
            return closedIdempotently(requirePeriodRow(period), recalculated);
        }
        SettlementPeriod closed = requirePeriodRow(period);
        return new MonthlyResult(period, closed.totals(), closed.status(), closed.closedAt(), false);
    }

    /**
     * 已锁账（或刚被别人锁掉）时的收敛：一致就幂等返回，不一致就是数据漂移。
     *
     * <p>{@code SET_ALREADY_SETTLED} 的语义正是"这个账期早就结完了，而且现在的数字和
     * 当初结出来的不一样" —— 它比"锁账了不能改"更严重，因为说明锁账没锁住东西。
     */
    private MonthlyResult closedIdempotently(SettlementPeriod closed, SettlementTotals recalculated) {
        if (!closed.totals().sameAccounts(recalculated)) {
            throw new BusinessException(ErrorCode.SET_ALREADY_SETTLED,
                    "账期 %s 已月结，但按单据重算的结果与已锁账汇总不一致，已拒绝覆盖：已存 %s；重算 %s"
                            .formatted(closed.period(), closed.totals().describe(),
                                    recalculated.describe()),
                    Map.of("period", closed.period(),
                            "generalTotal", closed.totals().netAmount(),
                            "detailTotal", recalculated.netAmount()));
        }
        return new MonthlyResult(closed.period(), closed.totals(), closed.status(),
                closed.closedAt(), true);
    }

    /** 日清行漂移检查：已存在的行必须与按单据重算的结果逐字段相等。 */
    private void requireNoDailyDrift(LocalDate first, LocalDate last,
                                     Map<SettlementKey, SettlementTotals> recomputed) {
        for (DailySettlement existing : daily.findInRange(first, last)) {
            SettlementTotals expected =
                    recomputed.getOrDefault(existing.key(), SettlementTotals.zero());
            if (!expected.sameAccounts(existing.totals())) {
                throw new BusinessException(ErrorCode.SET_ALREADY_SETTLED,
                        "账期 %s-%02d 的 %s / 仓库 %d 日清行与按单据重算的结果不一致，"
                                .formatted(first.getYear(), first.getMonthValue(),
                                        existing.bizDate(), existing.warehouseId())
                                + "已存 %s；重算 %s。请先重跑该日日清，或查明数据来源后再月结"
                                        .formatted(existing.totals().describe(),
                                                expected.describe()),
                        Map.of("bizDate", existing.bizDate().toString(),
                                "warehouseId", existing.warehouseId(),
                                "stored", existing.totals().describe(),
                                "recalculated", expected.describe()));
            }
        }
    }

    // ─────────────────────────── 对账 ───────────────────────────

    /**
     * 对账：总账（{@code settlement_periods} 已锁的净额）与明细（按单据实时重算的净额）比对。
     *
     * <p><b>比对口径是净额</b>（{@code salesAmount − returnAmount}）：
     * 契约的 {@code ReconcileResult} 只有一对 total，净额是唯一同时覆盖销售与退货、
     * 且与"这个期间到底成交了多少钱"直接对应的单一数字。
     * 采购与数量的六量一致性在月结时已由
     * {@link #requireNoDailyDrift} 逐字段守住。
     *
     * <p>账期未结算时给 {@code SET_PERIOD_NOT_CLOSED} 而不是返回全 0：
     * "还没结账"和"账是平的"是两件事，混成一件事会让财务把没核实的期间当成核实过。
     */
    @Transactional(readOnly = true)
    public ReconcileResult reconcile(String periodRaw) {
        YearMonth yearMonth = parsePeriod(periodRaw);
        String period = yearMonth.toString();
        SettlementPeriod closed = periods.findByPeriod(period)
                .filter(SettlementPeriod::locked)
                .orElseThrow(() -> new BusinessException(ErrorCode.SET_PERIOD_NOT_CLOSED,
                        "账期 %s 尚未月结，无法对账".formatted(period),
                        Map.of("period", period)));

        SettlementTotals detail = fold(recompute(yearMonth.atDay(1), yearMonth.atEndOfMonth())
                .values());
        long generalTotal = closed.totals().netAmount();
        long detailTotal = detail.netAmount();
        long diff = generalTotal - detailTotal;
        if (diff != 0) {
            throw new BusinessException(ErrorCode.SET_RECONCILE_MISMATCH,
                    "对账不平：账期 %s 总账 %d 分，明细合计 %d 分，差额 %d 分"
                            .formatted(period, generalTotal, detailTotal, diff),
                    Map.of("period", period, "generalTotal", generalTotal,
                            "detailTotal", detailTotal, "diff", diff));
        }
        return new ReconcileResult(period, generalTotal, detailTotal, 0L, true);
    }

    // ─────────────────────────── 重算（唯一口径） ───────────────────────────

    /**
     * 按单据 {@code biz_date} 重算 [from, to] 闭区间内每个 (日期, 仓库) 的六量。
     *
     * <p>三条来源各一条 SQL，<b>没有按天/按仓循环查询</b>：
     * 区间最大 31 天 × N 个仓库，如果逐格查就是上百次往返（计划书 §6 坑 8）。
     *
     * @return 只包含<b>有账务发生</b>的键；没出现的键即"当日该仓无出入库"
     */
    private Map<SettlementKey, SettlementTotals> recompute(LocalDate from, LocalDate to) {
        Map<SettlementKey, SettlementTotals> out = new LinkedHashMap<>();
        for (SourceAmountLine line : source.purchaseByDate(from, to)) {
            out.merge(line.key(),
                    SettlementTotals.zero().withPurchase(line.qty(), line.amount()),
                    SettlementTotals::plus);
        }
        for (SalesSourceLine line : source.salesLinesByDate(from, to)) {
            // ★ 出货行金额按"实际出货数量"重算，且必须走 SAL 的同一个 lineAmount：
            //   订购 10 件只出 6 件时，账上只能记 6 件的钱；
            //   而折扣舍入只要和 SAL 差一位，日清与出货单的金额就永远对不上（TC-SET-014）。
            long amount = SalesOrderApplicationService.lineAmount(
                    line.shippedQty(), line.unitPriceAmount(), line.discountRate());
            out.merge(line.key(),
                    SettlementTotals.zero().withSales(line.shippedQty(), amount),
                    SettlementTotals::plus);
        }
        for (SourceAmountLine line : source.returnsByDate(from, to)) {
            out.merge(line.key(),
                    SettlementTotals.zero().withReturn(line.qty(), line.amount()),
                    SettlementTotals::plus);
        }
        return out;
    }

    private static SettlementTotals fold(Iterable<SettlementTotals> rows) {
        SettlementTotals total = SettlementTotals.zero();
        for (SettlementTotals row : rows) {
            total = total.plus(row);
        }
        return total;
    }

    // ─────────────────────────── 校验 ───────────────────────────

    /** 日清/月结自己按账期状态判断（不经 PeriodGuard），已锁账一律拒绝。 */
    private void requireOpenPeriod(LocalDate bizDate) {
        String period = periodOf(bizDate);
        if (periods.isClosed(period)) {
            throw new BusinessException(ErrorCode.SET_PERIOD_CLOSED,
                    "账期 %s 已月结锁账，不能再执行 %s 的日清".formatted(period, bizDate),
                    Map.of("period", period, "bizDate", bizDate.toString()));
        }
    }

    /**
     * 目标仓库列表。省略 {@code warehouseId} = 全部仓库（含已停用仓，
     * 否则历史账目会因为仓库停用而从日清结果里凭空消失）。
     */
    private List<Long> targetWarehouses(Long warehouseId) {
        List<Long> all = source.allWarehouseIds();
        if (warehouseId == null) {
            return all;
        }
        if (!all.contains(warehouseId)) {
            throw new BusinessException(ErrorCode.MDM_WAREHOUSE_NOT_FOUND,
                    "仓库不存在：id=%d".formatted(warehouseId));
        }
        return List.of(warehouseId);
    }

    private SettlementPeriod requirePeriodRow(String period) {
        return periods.findByPeriod(period)
                .orElseThrow(() -> new BusinessException(ErrorCode.SYS_NOT_FOUND,
                        "账期行意外缺失：%s（并发月结后必须存在，请重试）".formatted(period)));
    }

    /**
     * 账期格式必须是 {@code yyyy-MM}，且能构成真实月份。
     *
     * <p>{@code 2026-13} 过不了 {@link YearMonth#parse}，必须在进 SQL 之前就变成 400，
     * 而不是让它变成一个查不到行的"合法"参数、最后以 SET_PERIOD_NOT_CLOSED 的面目出现。
     */
    private static YearMonth parsePeriod(String raw) {
        if (raw == null || !raw.matches("\\d{4}-\\d{2}")) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "账期格式非法：%s，必须是 yyyy-MM（如 2026-10）".formatted(raw));
        }
        try {
            return YearMonth.parse(raw);
        } catch (DateTimeParseException ex) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "账期不是真实月份：%s".formatted(raw));
        }
    }

    private static String periodOf(LocalDate bizDate) {
        return "%04d-%02d".formatted(bizDate.getYear(), bizDate.getMonthValue());
    }

    /** 业务时间只能来自注入时钟；TIMESTAMPTZ 需要一个带偏移的 instant，故在此落地系统时区。 */
    private OffsetDateTime clockNow() {
        return clock.now().atZone(ZoneId.systemDefault()).toOffsetDateTime();
    }
}
