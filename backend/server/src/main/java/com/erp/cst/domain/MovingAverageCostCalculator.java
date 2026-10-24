package com.erp.cst.domain;

import com.erp.shared.money.MoneyMath;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 移动加权平均成本计算器（FR-CST-01）。
 *
 * <p><b>输入只有库存流水</b>：这是 ADR-0007「流水为事实源」的直接兑现。
 * 成本层是派生结果，任何时刻都能按 {@code occurred_at, id} 顺序全量重算；
 * 因此不存在"余额和流水对不上时以谁为准"的歧义 —— 永远以流水为准。
 *
 * <p><b>红冲/退货为什么不能简单按当前均价</b>：
 * <ul>
 *   <li>进货红冲流水自带原进价，按原进价出库才能让「进货 + 红冲」净额为 0；</li>
 *   <li>出货红冲流水没有成本，必须配对原 {@code SALES_OUT} 的成本层；</li>
 *   <li>退货回补同理，按原出货成本回补，否则「出货 + 退货」会在成本池里留下差额；</li>
 *   <li>仓间调拨（TRANSFER_OUT/TRANSFER_IN）是两段式的仓间移动：调入必须配对同一
 *       调拨单号 + SKU + 批次的调出成本，不能按目的仓均价重新估值，否则两仓合计成本不守恒。</li>
 * </ul>
 * 配对信息只来自流水本身（单号、SKU、仓库、批次、数量）与退货单的原出货单号，
 * 不依赖内存里"上一次算到哪"的状态，所以重算与增量结果必然一致。
 *
 * <p><b>舍入</b>：唯一除法入口是 {@link MoneyMath}，显式 HALF_UP。
 * 出库数量恰好等于池数量时直接结转全部池成本，避免"数量清零但成本残留几分钱"。
 */
public final class MovingAverageCostCalculator {

    private MovingAverageCostCalculator() {
    }

    public static CostSolution calculate(List<CostEvent> events) {
        List<CostEvent> ordered = new ArrayList<>(events);
        ordered.sort(Comparator.comparing(CostEvent::occurredAt).thenComparingLong(CostEvent::ledgerId));

        Map<CostPoolKey, long[]> pools = new HashMap<>();
        Map<OutboundKey, Deque<OutboundCost>> salesOutQueue = new HashMap<>();
        Map<OriginKey, long[]> originOutstanding = new HashMap<>();
        Map<TransferKey, Deque<OutboundCost>> transferOutQueue = new HashMap<>();
        List<CostLayer> layers = new ArrayList<>(ordered.size());
        long inboundCost = 0;
        long outboundCost = 0;

        for (CostEvent event : ordered) {
            if (event.changeQty() == 0) {
                throw new IllegalArgumentException(
                        "流水数量不能为 0：ledgerId=%d".formatted(event.ledgerId()));
            }
            if (event.occurredAt() == null) {
                throw new IllegalArgumentException(
                        "流水业务时间不能为空：ledgerId=%d".formatted(event.ledgerId()));
            }

            CostPoolKey poolKey = new CostPoolKey(event.skuId(), event.warehouseId());
            long[] pool = pools.computeIfAbsent(poolKey, key -> new long[2]);
            int qty = event.qty();

            if (event.inbound()) {
                InboundCost inbound = resolveInbound(
                        event, qty, pool, salesOutQueue, originOutstanding, transferOutQueue);
                pool[0] += qty;
                pool[1] += inbound.totalCost();
                inboundCost += inbound.totalCost();
                layers.add(new CostLayer(event.ledgerId(), event.skuId(), event.warehouseId(),
                        event.batchNo(), event.bizType(), event.bizRefNo(), CostDirection.IN,
                        qty, inbound.unitCost(), inbound.totalCost(), pool[0], pool[1],
                        event.occurredAt()));
            } else {
                if (pool[0] < qty) {
                    throw new IllegalArgumentException(
                            "成本池数量不足，无法出库：ledgerId=%d, skuId=%d, warehouseId=%d, "
                                    .formatted(event.ledgerId(), event.skuId(), event.warehouseId())
                                    + "poolQty=%d, outQty=%d".formatted(pool[0], qty));
                }
                long total = resolveOutboundTotal(event, qty, pool);
                long unit = unitOf(total, qty);
                pool[0] -= qty;
                pool[1] -= total;
                outboundCost += total;
                layers.add(new CostLayer(event.ledgerId(), event.skuId(), event.warehouseId(),
                        event.batchNo(), event.bizType(), event.bizRefNo(), CostDirection.OUT,
                        qty, unit, total, pool[0], pool[1], event.occurredAt()));

                if ("SALES_OUT".equals(event.bizType())) {
                    rememberSalesOut(event, qty, total, salesOutQueue, originOutstanding);
                }
                if ("TRANSFER_OUT".equals(event.bizType())) {
                    rememberTransferOut(event, qty, total, transferOutQueue);
                }
            }
        }

        Map<CostPoolKey, CostPoolState> ending = new HashMap<>();
        long endingCost = 0;
        for (Map.Entry<CostPoolKey, long[]> entry : pools.entrySet()) {
            ending.put(entry.getKey(),
                    new CostPoolState(entry.getValue()[0], entry.getValue()[1]));
            endingCost += entry.getValue()[1];
        }
        return new CostSolution(layers, ending, inboundCost, outboundCost, endingCost);
    }

    // ─────────────────────────── 入库成本 ───────────────────────────

    private static InboundCost resolveInbound(CostEvent event, int qty, long[] pool,
                                              Map<OutboundKey, Deque<OutboundCost>> salesOutQueue,
                                              Map<OriginKey, long[]> originOutstanding,
                                              Map<TransferKey, Deque<OutboundCost>> transferOutQueue) {
        if ("SALES_REVERSAL".equals(event.bizType())) {
            OutboundKey key = new OutboundKey(event.bizRefNo(), event.skuId(),
                    event.warehouseId(), event.batchNo());
            OutboundCost matched = takeMatching(salesOutQueue.get(key), qty);
            if (matched != null) {
                return fromTotal(matched.totalCost(), qty);
            }
            if (event.explicitUnitCostAmount() != null) {
                return fromUnit(event.explicitUnitCostAmount(), qty);
            }
            return fromUnit(currentUnit(pool), qty);
        }

        if ("RETURN_IN".equals(event.bizType()) && event.originSalesOrderNo() != null) {
            OriginKey key = new OriginKey(event.originSalesOrderNo(), event.skuId(),
                    event.warehouseId(), event.batchNo());
            long[] outstanding = originOutstanding.get(key);
            if (outstanding != null && outstanding[0] > 0) {
                int take = (int) Math.min(qty, outstanding[0]);
                long takeCost = take == outstanding[0]
                        ? outstanding[1]
                        : MoneyMath.mulDivHalfUp(outstanding[1], take, outstanding[0]);
                outstanding[0] -= take;
                outstanding[1] -= takeCost;
                long total = takeCost;
                if (qty > take) {
                    // 数据异常（退得比原出货多）时，超出部分退回当前均价，保证守恒。
                    total += MoneyMath.mulDivHalfUp(currentUnit(pool), qty - take, 1);
                }
                return fromTotal(total, qty);
            }
            if (event.explicitUnitCostAmount() != null) {
                return fromUnit(event.explicitUnitCostAmount(), qty);
            }
            return fromUnit(currentUnit(pool), qty);
        }

        if ("TRANSFER_IN".equals(event.bizType())) {
            InboundCost paired = resolveTransferIn(event, qty, pool, transferOutQueue);
            if (paired != null) {
                return paired;
            }
        }

        if (event.explicitUnitCostAmount() != null) {
            return fromUnit(event.explicitUnitCostAmount(), qty);
        }
        return fromUnit(currentUnit(pool), qty);
    }

    /**
     * 调入成本：优先配对同一调拨单（biz_ref_no + sku + 批次）的 {@code TRANSFER_OUT}。
     *
     * <p>调拨是仓间移动，不是真正的成本退出：源仓按成本出、目的仓必须按同一成本入，
     * 否则两仓合计成本会凭空增加/减少。调拨行允许 {@code unitCostAmount} 为空，
     * 所以不能依赖调用方传成本；配对是主路径，显式成本只在配不上时兜底。
     */
    private static InboundCost resolveTransferIn(CostEvent event, int qty, long[] pool,
                                                 Map<TransferKey, Deque<OutboundCost>> transferOutQueue) {
        Deque<OutboundCost> queue = transferOutQueue.get(
                new TransferKey(event.bizRefNo(), event.skuId(), event.batchNo()));
        if (queue == null || queue.isEmpty()) {
            return null;
        }
        long total = 0;
        int remaining = qty;
        while (remaining > 0 && !queue.isEmpty()) {
            OutboundCost head = queue.peekFirst();
            int take = Math.min(remaining, head.qty());
            long takeCost = take == head.qty()
                    ? head.totalCost()
                    : MoneyMath.mulDivHalfUp(head.totalCost(), take, head.qty());
            if (take == head.qty()) {
                queue.removeFirst();
            } else {
                queue.addFirst(new OutboundCost(head.qty() - take, head.totalCost() - takeCost));
            }
            total += takeCost;
            remaining -= take;
        }
        if (remaining > 0) {
            // 调拨单数据不完整（调入多于调出）时，超出部分按显式成本/目的仓均价兜底。
            long unit = event.explicitUnitCostAmount() != null
                    ? event.explicitUnitCostAmount()
                    : currentUnit(pool);
            total += MoneyMath.mulDivHalfUp(unit, remaining, 1);
        }
        return fromTotal(total, qty);
    }

    private static OutboundCost takeMatching(Deque<OutboundCost> queue, int qty) {
        if (queue == null || queue.isEmpty()) {
            return null;
        }
        Iterator<OutboundCost> it = queue.iterator();
        while (it.hasNext()) {
            OutboundCost candidate = it.next();
            if (candidate.qty() == qty) {
                it.remove();
                return candidate;
            }
        }
        return null;
    }

    // ─────────────────────────── 出库成本 ───────────────────────────

    private static long resolveOutboundTotal(CostEvent event, int qty, long[] pool) {
        if (event.explicitUnitCostAmount() != null) {
            return MoneyMath.mulDivHalfUp(event.explicitUnitCostAmount(), qty, 1);
        }
        if (qty == pool[0]) {
            return pool[1];
        }
        return MoneyMath.mulDivHalfUp(pool[1], qty, pool[0]);
    }

    private static void rememberSalesOut(CostEvent event, int qty, long total,
                                         Map<OutboundKey, Deque<OutboundCost>> salesOutQueue,
                                         Map<OriginKey, long[]> originOutstanding) {
        OutboundKey outboundKey = new OutboundKey(event.bizRefNo(), event.skuId(),
                event.warehouseId(), event.batchNo());
        salesOutQueue.computeIfAbsent(outboundKey, key -> new ArrayDeque<>())
                .addLast(new OutboundCost(qty, total));

        if (event.bizRefNo() != null && !event.bizRefNo().isBlank()) {
            OriginKey originKey = new OriginKey(event.bizRefNo(), event.skuId(),
                    event.warehouseId(), event.batchNo());
            long[] outstanding = originOutstanding.computeIfAbsent(originKey, key -> new long[2]);
            outstanding[0] += qty;
            outstanding[1] += total;
        }
    }

    /**
     * 记住调出成本，供稍后到货的 {@code TRANSFER_IN} 配对。
     *
     * <p>调拨是两段式的：建单即写出库流水，到货才写入库流水，两者可能相隔数天。
     * 因此队列必须跨事件保留，不能只在"同一事务"里找。
     * 键按调拨单号 + SKU + 批次：调拨单号唯一，批次保证多批次不会串成本。
     */
    private static void rememberTransferOut(CostEvent event, int qty, long total,
                                            Map<TransferKey, Deque<OutboundCost>> transferOutQueue) {
        transferOutQueue.computeIfAbsent(
                        new TransferKey(event.bizRefNo(), event.skuId(), event.batchNo()),
                        key -> new ArrayDeque<>())
                .addLast(new OutboundCost(qty, total));
    }

    // ─────────────────────────── 小工具 ───────────────────────────

    private static long currentUnit(long[] pool) {
        return pool[0] <= 0 ? 0 : MoneyMath.mulDivHalfUp(pool[1], 1, pool[0]);
    }

    private static long unitOf(long total, int qty) {
        return MoneyMath.divideHalfUp(BigInteger.valueOf(total), BigInteger.valueOf(qty));
    }

    private static InboundCost fromTotal(long total, int qty) {
        return new InboundCost(unitOf(total, qty), total);
    }

    private static InboundCost fromUnit(long unit, int qty) {
        return new InboundCost(unit, MoneyMath.mulDivHalfUp(unit, qty, 1));
    }

    private record InboundCost(long unitCost, long totalCost) {
    }

    private record OutboundCost(int qty, long totalCost) {
    }

    private record OutboundKey(String bizRefNo, long skuId, long warehouseId, String batchNo) {
    }

    private record OriginKey(String originSalesOrderNo, long skuId, long warehouseId,
                             String batchNo) {
    }

    /** 调拨配对键：单号 + SKU + 批次（单号已隐含源/目的仓）。 */
    private record TransferKey(String bizRefNo, long skuId, String batchNo) {
    }
}
