package com.erp.cst.application;

import com.erp.cst.domain.MarginAggregateRow;
import com.erp.cst.domain.MarginGroupBy;
import com.erp.cst.domain.MarginRecord;
import com.erp.cst.domain.MarginSummaryRow;
import com.erp.cst.repository.CostSourceRepository;
import com.erp.cst.repository.MarginRecordRepository;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.money.MoneyMath;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 毛利快照与毛利分析（FR-CST-03）。
 *
 * <p><b>净额口径</b>：一条出货单 × SKU 的毛利 =
 * （出货行金额 − 退货行金额）−（SALES_OUT 成本 − RETURN_IN 成本）。
 * 退货成本按原出货成本回补，所以「出货 + 退货」在成本侧净额为 0；
 * 完全退货的记录不落表，避免用 qty=0 的行污染维度聚合。
 *
 * <p><b>为什么重建而不是累加</b>：退货可以在出货之后的任意账期发生。
 * 每次月结对该账期重新计算，才能让"商品/客户/期间"三个维度都反映到截至重建时点的净额；
 * 累加式更新会在退货发生时把差额记到错误的期间。
 */
@Service
public class MarginApplicationService {

    private static final long RATE_SCALE = 10_000L;

    private final CostSourceRepository source;
    private final MarginRecordRepository margins;

    public MarginApplicationService(CostSourceRepository source, MarginRecordRepository margins) {
        this.source = source;
        this.margins = margins;
    }

    public record RebuildResult(long recordCount, int changes) {
    }

    /**
     * 重建某账期的毛利快照。由成本月结在同一事务内调用。
     */
    @Transactional
    public RebuildResult rebuildPeriod(YearMonth period) {
        LocalDate from = period.atDay(1);
        LocalDate to = period.atEndOfMonth();

        Map<OrderSkuKey, Accumulator> computed = new LinkedHashMap<>();
        for (CostSourceRepository.SalesLineSource line : source.shippedSalesLines(from, to)) {
            Accumulator acc = computed.computeIfAbsent(
                    new OrderSkuKey(line.orderId(), line.skuId()),
                    key -> new Accumulator(line.orderId(), line.orderNo(), line.customerId(),
                            line.skuId(), line.warehouseId(), line.bizDate()));
            acc.shippedQty += line.shippedQty();
            acc.revenue += MoneyMath.lineAmount(
                    line.shippedQty(), line.unitPriceAmount(), line.discountRate());
        }
        for (CostSourceRepository.AmountSource returned : source.returnAmounts(from, to)) {
            Accumulator acc = computed.get(new OrderSkuKey(returned.orderId(), returned.skuId()));
            if (acc != null) {
                acc.returnedQty += returned.qty();
                acc.revenue -= returned.amount();
            }
        }
        for (CostSourceRepository.CostAmountSource cost : source.salesOutCosts(from, to)) {
            Accumulator acc = computed.get(new OrderSkuKey(cost.orderId(), cost.skuId()));
            if (acc != null) {
                acc.outboundCost += cost.costAmount();
            }
        }
        for (CostSourceRepository.CostAmountSource cost : source.returnCosts(from, to)) {
            Accumulator acc = computed.get(new OrderSkuKey(cost.orderId(), cost.skuId()));
            if (acc != null) {
                acc.returnCost += cost.costAmount();
            }
        }

        Map<OrderSkuKey, MarginRecord> next = new LinkedHashMap<>();
        for (Map.Entry<OrderSkuKey, Accumulator> entry : computed.entrySet()) {
            MarginRecord record = entry.getValue().toRecord();
            if (record != null) {
                next.put(entry.getKey(), record);
            }
        }

        Map<OrderSkuKey, MarginRecord> existing = new LinkedHashMap<>();
        for (MarginRecord record : margins.findByPeriod(period.toString())) {
            existing.put(new OrderSkuKey(record.salesOrderId(), record.skuId()), record);
        }

        int changes = 0;
        for (Map.Entry<OrderSkuKey, MarginRecord> entry : next.entrySet()) {
            MarginRecord fresh = entry.getValue();
            MarginRecord old = existing.get(entry.getKey());
            if (old == null) {
                margins.insert(fresh);
                changes++;
            } else if (!fresh.sameBusinessValues(old)) {
                margins.update(withId(fresh, old.id()));
                changes++;
            }
        }
        for (Map.Entry<OrderSkuKey, MarginRecord> entry : existing.entrySet()) {
            if (!next.containsKey(entry.getKey())) {
                margins.delete(entry.getValue().id());
                changes++;
            }
        }
        return new RebuildResult(next.size(), changes);
    }

    /** 按商品 / 客户 / 期间聚合毛利（只读）。 */
    @Transactional(readOnly = true)
    public List<MarginSummaryRow> analyze(String fromRaw, String toRaw, String groupByRaw) {
        YearMonth from = parsePeriod(fromRaw, "from");
        YearMonth to = parsePeriod(toRaw, "to");
        if (from.isAfter(to)) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "账期区间非法：from %s 晚于 to %s".formatted(from, to));
        }
        MarginGroupBy groupBy;
        try {
            groupBy = MarginGroupBy.parse(groupByRaw);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR, ex.getMessage());
        }
        List<MarginAggregateRow> rows = margins.aggregate(groupBy, from.toString(), to.toString());
        List<MarginSummaryRow> result = new ArrayList<>(rows.size());
        for (MarginAggregateRow row : rows) {
            long rate = row.revenueAmount() == 0
                    ? 0
                    : MoneyMath.mulDivHalfUp(row.grossProfitAmount(), RATE_SCALE,
                            row.revenueAmount());
            result.add(new MarginSummaryRow(row.key(), row.label(), row.qty(),
                    row.revenueAmount(), row.costAmount(), row.grossProfitAmount(),
                    rate, row.orderCount()));
        }
        return result;
    }

    private static MarginRecord withId(MarginRecord record, long id) {
        return new MarginRecord(id, record.period(), record.salesOrderId(), record.salesOrderNo(),
                record.customerId(), record.skuId(), record.warehouseId(), record.bizDate(),
                record.qty(), record.revenueAmount(), record.costAmount(),
                record.grossProfitAmount(), record.grossMarginRateBp());
    }

    private static YearMonth parsePeriod(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "%s 不能为空（要求 yyyy-MM）".formatted(field));
        }
        try {
            return YearMonth.parse(raw.trim());
        } catch (DateTimeParseException ex) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "%s 格式非法：%s（要求 yyyy-MM，例如 2026-10）".formatted(field, raw));
        }
    }

    private record OrderSkuKey(long orderId, long skuId) {
    }

    private static final class Accumulator {
        private final long orderId;
        private final String orderNo;
        private final long customerId;
        private final long skuId;
        private final long warehouseId;
        private final LocalDate bizDate;
        private long shippedQty;
        private long returnedQty;
        private long revenue;
        private long outboundCost;
        private long returnCost;

        private Accumulator(long orderId, String orderNo, long customerId,
                            long skuId, long warehouseId, LocalDate bizDate) {
            this.orderId = orderId;
            this.orderNo = orderNo;
            this.customerId = customerId;
            this.skuId = skuId;
            this.warehouseId = warehouseId;
            this.bizDate = bizDate;
        }

        private MarginRecord toRecord() {
            long qty = shippedQty - returnedQty;
            if (qty <= 0) {
                return null;
            }
            if (revenue < 0) {
                throw new BusinessException(ErrorCode.INV_BALANCE_MISMATCH,
                        "出货单 %s 的退货金额超过出货收入：revenue=%d"
                                .formatted(orderNo, revenue));
            }
            long cost = outboundCost - returnCost;
            if (cost < 0) {
                throw new BusinessException(ErrorCode.INV_BALANCE_MISMATCH,
                        "出货单 %s 的退货成本超过出库成本：cost=%d"
                                .formatted(orderNo, cost));
            }
            long rate = revenue == 0
                    ? 0
                    : MoneyMath.mulDivHalfUp(revenue - cost, RATE_SCALE, revenue);
            return MarginRecord.of(orderId, orderNo, customerId, skuId, warehouseId, bizDate,
                    (int) qty, revenue, cost, rate);
        }
    }
}
