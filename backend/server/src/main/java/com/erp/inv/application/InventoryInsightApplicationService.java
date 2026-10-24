package com.erp.inv.application;

import com.erp.inv.domain.AlertType;
import com.erp.inv.domain.InventoryAlert;
import com.erp.inv.domain.Ownership;
import com.erp.inv.domain.ReplenishmentSuggestion;
import com.erp.inv.domain.StockStatus;
import com.erp.inv.repository.InventoryInsightRepository;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.time.ClockProvider;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 库存洞察（V2-1）：五维结存 / 预警 / 补货建议。
 *
 * <p><b>全只读</b>：本类不修改任何数量与单据，也不落"建议表"。
 * 补货建议由查询实时算出，因此连续两次调用结果必然一致（天然幂等）；
 * 为了这份幂等，代价是查询略重 —— 在单店规模下这个取舍是划算的。
 *
 * <p>所有"今天"都来自 {@link ClockProvider}：临期边界与滞销天数
 * 必须能在测试里用可控时钟精确摆布。
 */
@Service
public class InventoryInsightApplicationService {

    private static final int DEFAULT_EXPIRING_DAYS = 30;
    private static final int DEFAULT_SLOW_MOVING_DAYS = 90;

    private final InventoryInsightRepository insights;
    private final ClockProvider clock;

    public InventoryInsightApplicationService(InventoryInsightRepository insights,
                                              ClockProvider clock) {
        this.insights = insights;
        this.clock = clock;
    }

    /** 五维结存的一行：仓 × SKU × 批次 × 状态 × 权属。 */
    public record BalanceDimensionRow(long warehouseId, long skuId, String batchNo,
                                      StockStatus stockStatus, Ownership ownership, int qty) {
    }

    /**
     * 五维结存查询（FR-INV-11）。
     *
     * <p>把每条余额按"状态"拆成至多三行：可用 / 预占 / 冻结。
     * 因此对任一 (仓, SKU, 批次, 权属)，<b>Σ各状态 == 实存总量</b>；
     * 数量为 0 的状态不出行（避免满屏 0 行）。
     */
    @Transactional(readOnly = true)
    public List<BalanceDimensionRow> balanceDimensions(Long skuId, Long warehouseId, String batchNo,
                                                       StockStatus status, Ownership ownership) {
        Ownership effectiveOwnership = ownership == null ? Ownership.SELF : ownership;
        if (effectiveOwnership != Ownership.SELF) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "暂不支持该权属类型：%s（当前仅 SELF）".formatted(effectiveOwnership));
        }
        List<BalanceDimensionRow> rows = new ArrayList<>();
        for (var slice : insights.balanceSlices(skuId, warehouseId, batchNo)) {
            int available = slice.onHandQty() - slice.reservedQty() - slice.frozenQty();
            addIfVisible(rows, slice, StockStatus.AVAILABLE, available, status);
            addIfVisible(rows, slice, StockStatus.RESERVED, slice.reservedQty(), status);
            addIfVisible(rows, slice, StockStatus.FROZEN, slice.frozenQty(), status);
        }
        return rows;
    }

    private static void addIfVisible(List<BalanceDimensionRow> rows,
                                     InventoryInsightRepository.BalanceSlice slice,
                                     StockStatus stockStatus, int qty, StockStatus filter) {
        if (qty <= 0) {
            return;
        }
        if (filter != null && filter != stockStatus) {
            return;
        }
        rows.add(new BalanceDimensionRow(slice.warehouseId(), slice.skuId(), slice.batchNo(),
                stockStatus, Ownership.SELF, qty));
    }

    /**
     * 库存预警（FR-INV-12）。
     *
     * @param type           只看某一类；null = 三类都返回
     * @param expiringDays   效期阈值（天），null 用默认 30
     * @param slowMovingDays 滞销阈值（天），null 用默认 90
     */
    @Transactional(readOnly = true)
    public List<InventoryAlert> alerts(Long warehouseId, AlertType type,
                                       Integer expiringDays, Integer slowMovingDays) {
        int expireDays = expiringDays == null ? DEFAULT_EXPIRING_DAYS : expiringDays;
        int slowDays = slowMovingDays == null ? DEFAULT_SLOW_MOVING_DAYS : slowMovingDays;
        if (expireDays < 0 || slowDays < 0) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "预警天数不能为负：expiringDays=%d, slowMovingDays=%d"
                            .formatted(expireDays, slowDays));
        }

        List<InventoryAlert> result = new ArrayList<>();
        if (type == null || type == AlertType.SAFETY) {
            for (var row : insights.safetyAlerts(warehouseId)) {
                result.add(new InventoryAlert(AlertType.SAFETY, row.skuId(), row.warehouseId(),
                        null, row.onHandQty(), row.availableQty(), row.minLevel(),
                        null, null, row.warehousePriority()));
            }
        }
        if (type == null || type == AlertType.EXPIRING) {
            // ★ cutoff 由注入时钟计算，不用 LocalDate.now()
            var cutoff = clock.today().plusDays(expireDays);
            for (var row : insights.expiringAlerts(warehouseId, cutoff)) {
                result.add(new InventoryAlert(AlertType.EXPIRING, row.skuId(), row.warehouseId(),
                        row.batchNo(), row.onHandQty(), row.availableQty(), expireDays,
                        row.expiryDate(), null, row.warehousePriority()));
            }
        }
        if (type == null || type == AlertType.SLOW_MOVING) {
            LocalDateTime cutoff = clock.now().minusDays(slowDays);
            for (var row : insights.slowMovingCandidates(warehouseId)) {
                OffsetDateTime lastMovement = row.lastOutboundAt() != null
                        ? row.lastOutboundAt() : row.firstMovementAt();
                if (lastMovement == null) {
                    continue;
                }
                if (lastMovement.toLocalDateTime().isBefore(cutoff)) {
                    result.add(new InventoryAlert(AlertType.SLOW_MOVING, row.skuId(),
                            row.warehouseId(), null, row.onHandQty(), row.availableQty(),
                            slowDays, null, lastMovement, row.warehousePriority()));
                }
            }
        }

        result.sort(Comparator
                .comparingInt(InventoryAlert::warehousePriority).reversed()
                .thenComparing(InventoryAlert::type)
                .thenComparingLong(InventoryAlert::skuId)
                .thenComparing(a -> a.batchNo() == null ? "" : a.batchNo()));
        return result;
    }

    /**
     * 补货建议（FR-INV-13）：可用量低于 {@code min_level} 时补到 {@code max_level}。
     *
     * <p><b>已下单未到货不重复建议</b>：同仓同 SKU 存在 APPROVED/PARTIAL
     * 且未收满的进货行时，直接跳过 —— 否则运营会对着建议再下一单，形成重复采购。
     *
     * <p>不落表，连续两次查询结果完全一致。
     */
    @Transactional(readOnly = true)
    public List<ReplenishmentSuggestion> replenishment(Long warehouseId) {
        List<ReplenishmentSuggestion> result = new ArrayList<>();
        for (var row : insights.replenishmentCandidates(warehouseId)) {
            if (row.pendingPurchaseQty() > 0) {
                continue;
            }
            int suggested = row.maxLevel() - row.availableQty();
            if (suggested <= 0) {
                continue;
            }
            result.add(new ReplenishmentSuggestion(row.skuId(), row.warehouseId(),
                    row.availableQty(), row.minLevel(), row.maxLevel(), suggested,
                    row.warehousePriority()));
        }
        result.sort(Comparator
                .comparingInt(ReplenishmentSuggestion::warehousePriority).reversed()
                .thenComparing(Comparator.comparingInt(ReplenishmentSuggestion::suggestedQty).reversed())
                .thenComparingLong(ReplenishmentSuggestion::skuId));
        return result;
    }
}
