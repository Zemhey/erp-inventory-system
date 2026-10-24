package com.erp.cst.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.support.CaseId;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * 仓间调拨成本守恒属性（V2-1 补丁 · TC-CST-061）。
 *
 * <p><b>要证明的命题</b>：调拨是仓间移动，不是成本退出。
 * 对任意合法序列「入库 / 调拨 / 出库」：
 *
 * <pre>
 * 两仓期末成本合计 + 外部出库成本 == 外部入库成本
 * </pre>
 *
 * 其中调拨的 OUT/IN 是内部移动，必须互相抵消，不得进入外部成本的加减。
 * 修复前调入按目标仓均价估值时，这个等式会随机失败；修复后它应恒成立。
 */
class CostTransferPropertyTest {

    private static final OffsetDateTime BASE =
            OffsetDateTime.of(2026, 10, 1, 0, 0, 0, 0, ZoneOffset.ofHours(8));
    private static final long SKU = 9L;
    private static final long WH_A = 90L;
    private static final long WH_B = 91L;

    @Property(tries = 1000)
    @CaseId("TC-CST-061")
    void 调拨不改变两仓合计成本(@ForAll("operations") List<Operation> operations) {
        Scenario scenario = build(operations);
        CostSolution solution = MovingAverageCostCalculator.calculate(scenario.events());

        long purchaseIn = sumLayers(solution, "PURCHASE_IN");
        long salesOut = sumLayers(solution, "SALES_OUT");
        long combinedEnding = solution.endingPools()
                .getOrDefault(new CostPoolKey(SKU, WH_A), CostPoolState.empty()).costAmount()
                + solution.endingPools()
                .getOrDefault(new CostPoolKey(SKU, WH_B), CostPoolState.empty()).costAmount();

        assertThat(combinedEnding + salesOut)
                .as("两仓期末 + 外部出库必须等于外部入库；调拨不得凭空增减成本")
                .isEqualTo(purchaseIn);
        assertThat(sumLayers(solution, "TRANSFER_IN"))
                .as("调拨调入成本合计必须等于调出成本合计")
                .isEqualTo(sumLayers(solution, "TRANSFER_OUT"));
        assertThat(solution.balanced()).isTrue();

        Map<Long, CostLayer> byLedger = new HashMap<>();
        solution.layers().forEach(layer -> byLedger.put(layer.ledgerId(), layer));
        for (TransferPair pair : scenario.transferPairs()) {
            assertThat(byLedger.get(pair.inLedgerId()).totalCostAmount())
                    .isEqualTo(byLedger.get(pair.outLedgerId()).totalCostAmount());
        }
    }

    private static long sumLayers(CostSolution solution, String bizType) {
        return solution.layers().stream()
                .filter(layer -> layer.bizType().equals(bizType))
                .mapToLong(CostLayer::totalCostAmount)
                .sum();
    }

    @Provide
    Arbitrary<List<Operation>> operations() {
        Arbitrary<Operation> one = Combinators.combine(
                        Arbitraries.integers().between(0, 4),
                        Arbitraries.integers().between(1, 20),
                        Arbitraries.integers().between(0, 10_000))
                .as((kind, qty, unitCost) -> new Operation(
                        Kind.values()[kind], qty, unitCost.longValue()));
        return one.list().ofMinSize(1).ofMaxSize(30);
    }

    private static Scenario build(List<Operation> operations) {
        long nextId = 1;
        long refSeq = 1;
        long qtyA = 0;
        long qtyB = 0;
        List<CostEvent> events = new ArrayList<>();
        List<TransferPair> transferPairs = new ArrayList<>();

        for (Operation operation : operations) {
            switch (operation.kind()) {
                case PURCHASE_A -> {
                    events.add(event(nextId++, WH_A, operation.qty(), "PURCHASE_IN",
                            "PO-" + refSeq++, operation.unitCost()));
                    qtyA += operation.qty();
                }
                case PURCHASE_B -> {
                    events.add(event(nextId++, WH_B, operation.qty(), "PURCHASE_IN",
                            "PO-" + refSeq++, operation.unitCost()));
                    qtyB += operation.qty();
                }
                case TRANSFER_A_TO_B -> {
                    if (qtyA == 0) {
                        continue;
                    }
                    int qty = (int) Math.min(operation.qty(), qtyA);
                    String ref = "TO-" + refSeq++;
                    long outId = nextId++;
                    events.add(event(outId, WH_A, -qty, "TRANSFER_OUT", ref, null));
                    long inId = nextId++;
                    events.add(event(inId, WH_B, qty, "TRANSFER_IN", ref, null));
                    qtyA -= qty;
                    qtyB += qty;
                    transferPairs.add(new TransferPair(outId, inId));
                }
                case SALE_A -> {
                    if (qtyA == 0) {
                        continue;
                    }
                    int qty = (int) Math.min(operation.qty(), qtyA);
                    events.add(event(nextId++, WH_A, -qty, "SALES_OUT",
                            "SO-" + refSeq++, null));
                    qtyA -= qty;
                }
                case SALE_B -> {
                    if (qtyB == 0) {
                        continue;
                    }
                    int qty = (int) Math.min(operation.qty(), qtyB);
                    events.add(event(nextId++, WH_B, -qty, "SALES_OUT",
                            "SO-" + refSeq++, null));
                    qtyB -= qty;
                }
                default -> throw new IllegalStateException("未知操作：" + operation.kind());
            }
        }
        return new Scenario(events, transferPairs);
    }

    private static CostEvent event(long id, long warehouseId, int changeQty, String bizType,
                                   String ref, Long unitCost) {
        return new CostEvent(id, SKU, warehouseId, "", changeQty, bizType, ref, unitCost,
                null, BASE.plusSeconds(id));
    }

    private enum Kind {
        PURCHASE_A,
        PURCHASE_B,
        TRANSFER_A_TO_B,
        SALE_A,
        SALE_B
    }

    private record Operation(Kind kind, int qty, long unitCost) {
    }

    private record TransferPair(long outLedgerId, long inLedgerId) {
    }

    private record Scenario(List<CostEvent> events, List<TransferPair> transferPairs) {
    }
}
