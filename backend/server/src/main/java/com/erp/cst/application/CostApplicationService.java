package com.erp.cst.application;

import com.erp.cst.domain.CostDirection;
import com.erp.cst.domain.CostMonthlyRow;
import com.erp.cst.domain.CostSolution;
import com.erp.cst.domain.MovingAverageCostCalculator;
import com.erp.cst.repository.CostLayerRepository;
import com.erp.cst.repository.CostMonthlyRepository;
import com.erp.cst.repository.CostSourceRepository;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.period.PeriodGuard;
import com.erp.shared.time.ClockProvider;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 成本应用服务：全量重算、单池重算、成本月结（FR-CST-01/02）。
 *
 * <p><b>重算永远是唯一事实路径</b>：成本层不维护"增量状态"，每次重算都从
 * {@code inventory_ledger} 回放。单池重算与全量重算共用同一个
 * {@link MovingAverageCostCalculator}，因此两者结果必然一致（硬验收 5）。
 *
 * <p><b>并发</b>：成本池没有独立状态行，无法 {@code SELECT ... FOR UPDATE}；
 * 使用 PostgreSQL 事务级 advisory lock 串行化同一 (SKU, 仓库) 的重算。
 * 后到事务在前一个事务提交后才读流水，所以两笔并发入库的均价不会「后写赢」。
 *
 * <p><b>月结</b>：先过 {@link PeriodGuard}（账期已锁则拒绝），再重建成本层、
 * 重算月结行、重建毛利快照。同账期无数据变化时重复执行不改任何行，
 * 返回 {@code duplicate=true}（硬验收 4）。
 */
@Service
public class CostApplicationService {

    private final CostSourceRepository source;
    private final CostLayerRepository layers;
    private final CostMonthlyRepository monthly;
    private final MarginApplicationService margins;
    private final PeriodGuard periodGuard;
    private final ClockProvider clock;

    public CostApplicationService(CostSourceRepository source,
                                  CostLayerRepository layers,
                                  CostMonthlyRepository monthly,
                                  MarginApplicationService margins,
                                  PeriodGuard periodGuard,
                                  ClockProvider clock) {
        this.source = source;
        this.layers = layers;
        this.monthly = monthly;
        this.margins = margins;
        this.periodGuard = periodGuard;
        this.clock = clock;
    }

    public record RecalculateResult(long layerCount, long inboundCostAmount,
                                    long outboundCostAmount, long endingCostAmount,
                                    boolean balanced) {
    }

    public record MonthlyResult(String period, boolean duplicate, List<CostMonthlyRow> items,
                                long marginRecordCount) {
    }

    // ─────────────────────────── 重算 ───────────────────────────

    @Transactional
    public RecalculateResult recalculate() {
        layers.lockGlobal();
        return recalculateLocked();
    }

    /**
     * 只重算一个成本池。并发入库测试与故障修复用；
     * 粒度小，不会因为一个 SKU 的异常把整库成本层锁住。
     */
    @Transactional
    public RecalculateResult rebuildPool(long skuId, long warehouseId) {
        // 共享全局锁：与全量重算（排他）互斥，但不同池的单池重算可以并行。
        layers.lockGlobalShared();
        layers.lockPool(skuId, warehouseId);
        CostSolution solution = MovingAverageCostCalculator.calculate(
                source.events(skuId, warehouseId));
        layers.deletePool(skuId, warehouseId);
        layers.insertAll(solution.layers());

        return new RecalculateResult(solution.layers().size(), solution.inboundCostAmount(),
                solution.outboundCostAmount(), solution.endingCostAmount(),
                solution.balanced());
    }

    private RecalculateResult recalculateLocked() {
        CostSolution solution = MovingAverageCostCalculator.calculate(source.events());
        layers.deleteAll();
        layers.insertAll(solution.layers());

        // 回读数据库而不是直接返回内存值：证明落表后的结果仍满足 INV-11。
        long inbound = layers.sumByDirection(CostDirection.IN);
        long outbound = layers.sumByDirection(CostDirection.OUT);
        long ending = layers.endingCost();
        return new RecalculateResult(layers.count(), inbound, outbound, ending,
                inbound == outbound + ending);
    }

    // ─────────────────────────── 月结 ───────────────────────────

    @Transactional
    public MonthlyResult closeMonthly(String periodRaw, String requestId) {
        YearMonth yearMonth = parsePeriod(periodRaw);
        periodGuard.requireOpen(yearMonth.atEndOfMonth());

        layers.lockGlobal();
        recalculateLocked();

        String period = yearMonth.toString();
        layers.lockPeriod(period);

        List<CostMonthlyRow> computed = monthly.computeRows(period,
                yearMonth.atDay(1).atStartOfDay(),
                yearMonth.plusMonths(1).atDay(1).atStartOfDay());
        Map<PoolKey, CostMonthlyRow> existing = new LinkedHashMap<>();
        for (CostMonthlyRow row : monthly.findByPeriod(period)) {
            existing.put(new PoolKey(row.skuId(), row.warehouseId()), row);
        }

        OffsetDateTime computedAt = now();
        int changes = 0;
        java.util.Set<PoolKey> computedKeys = new java.util.HashSet<>();
        for (CostMonthlyRow row : computed) {
            PoolKey key = new PoolKey(row.skuId(), row.warehouseId());
            computedKeys.add(key);
            CostMonthlyRow old = existing.get(key);
            if (old == null) {
                monthly.insert(row, requestId, computedAt);
                changes++;
            } else if (!row.sameBusinessValues(old)) {
                if (!monthly.update(row, requestId, computedAt, old.version())) {
                    throw new BusinessException(ErrorCode.SET_ALREADY_SETTLED,
                            "成本月结 %s 的 SKU %d / 仓库 %d 行已被并发修改，请重试"
                                    .formatted(period, row.skuId(), row.warehouseId()));
                }
                changes++;
            }
        }
        for (Map.Entry<PoolKey, CostMonthlyRow> entry : existing.entrySet()) {
            if (!computedKeys.contains(entry.getKey())) {
                monthly.delete(period, entry.getKey().skuId(), entry.getKey().warehouseId());
                changes++;
            }
        }

        MarginApplicationService.RebuildResult margin = margins.rebuildPeriod(yearMonth);
        List<CostMonthlyRow> rows = monthly.findByPeriod(period);
        return new MonthlyResult(period, changes == 0 && margin.changes() == 0,
                rows, margin.recordCount());
    }

    @Transactional(readOnly = true)
    public List<CostMonthlyRow> monthlyRows(String periodRaw) {
        return monthly.findByPeriod(parsePeriod(periodRaw).toString());
    }

    // ─────────────────────────── 小工具 ───────────────────────────

    private OffsetDateTime now() {
        return clock.now().atZone(ZoneId.systemDefault()).toOffsetDateTime();
    }

    private static YearMonth parsePeriod(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "period 不能为空（要求 yyyy-MM）");
        }
        try {
            return YearMonth.parse(raw.trim());
        } catch (DateTimeParseException ex) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "period 格式非法：%s（要求 yyyy-MM，例如 2026-10）".formatted(raw));
        }
    }

    private record PoolKey(long skuId, long warehouseId) {
    }
}
