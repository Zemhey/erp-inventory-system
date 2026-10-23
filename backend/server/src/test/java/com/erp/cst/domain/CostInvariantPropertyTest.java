package com.erp.cst.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.support.CaseId;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * INV-11 成本守恒属性测试（硬验收 1）。
 *
 * <p>随机生成至少 1000 组合法流水序列（出货不超过当前池数量），每组断言：
 * <ol>
 *   <li>全局守恒：Σ入 = Σ出 + 期末；</li>
 *   <li>逐池守恒：每个 (SKU, 仓库) 自身也满足同一恒等式；</li>
 *   <li>出货红冲配对后成本净额为 0；</li>
 *   <li>退货配对后成本净额为 0；</li>
 *   <li>打乱输入顺序后重算结果完全一致（重算确定性）。</li>
 * </ol>
 *
 * <p><b>为什么不用 {@code assumeThat} 过滤</b>：那会把"生成器造不出合法序列"
 * 伪装成"属性成立"。这里在构造阶段就按当前池数量裁剪出货量，
 * 保证每一条生成的序列都真实可执行。
 */
class CostInvariantPropertyTest {

    private static final OffsetDateTime BASE =
            OffsetDateTime.of(2026, 10, 1, 0, 0, 0, 0, ZoneOffset.ofHours(8));
    private static final long SKU = 7L;
    private static final long WH = 70L;

    // ─────────────────────────── 属性 ───────────────────────────

    @Property(tries = 1000)
    @CaseId("TC-CST-023")
    void 全局守恒_入等于出加期末(@ForAll("operations") List<Operation> operations) {
        Scenario scenario = build(operations);
        CostSolution solution = MovingAverageCostCalculator.calculate(scenario.events());

        assertThat(solution.balanced()).isTrue();
        assertThat(solution.inboundCostAmount())
                .isEqualTo(solution.outboundCostAmount() + solution.endingCostAmount());
        assertThat(solution.endingCostAmount()).isGreaterThanOrEqualTo(0);
        assertThat(solution.layers()).hasSize(scenario.events().size());
    }

    @Property(tries = 1000)
    @CaseId("TC-CST-024")
    void 逐池守恒_数量与金额各自成立(@ForAll("operations") List<Operation> operations) {
        CostSolution solution = MovingAverageCostCalculator.calculate(build(operations).events());

        Map<CostPoolKey, long[]> expected = new HashMap<>();
        for (CostLayer layer : solution.layers()) {
            long[] pool = expected.computeIfAbsent(layer.poolKey(), key -> new long[2]);
            if (layer.direction() == CostDirection.IN) {
                pool[0] += layer.qty();
                pool[1] += layer.totalCostAmount();
            } else {
                pool[0] -= layer.qty();
                pool[1] -= layer.totalCostAmount();
            }
        }
        for (Map.Entry<CostPoolKey, CostPoolState> entry : solution.endingPools().entrySet()) {
            long[] pool = expected.getOrDefault(entry.getKey(), new long[2]);
            assertThat(entry.getValue().qty()).isEqualTo(pool[0]);
            assertThat(entry.getValue().costAmount()).isEqualTo(pool[1]);
        }
    }

    @Property(tries = 1000)
    @CaseId("TC-CST-025")
    void 出货红冲配对后成本净额为零(@ForAll("operations") List<Operation> operations) {
        Scenario scenario = build(operations);
        CostSolution solution = MovingAverageCostCalculator.calculate(scenario.events());
        Map<Long, CostLayer> byLedger = byLedger(solution);

        for (Pair pair : scenario.pairs()) {
            if (pair.returnPair()) {
                continue;
            }
            CostLayer out = byLedger.get(pair.outLedgerId());
            CostLayer reversal = byLedger.get(pair.reversalLedgerId());
            assertThat(out.direction()).isEqualTo(CostDirection.OUT);
            assertThat(reversal.direction()).isEqualTo(CostDirection.IN);
            assertThat(reversal.totalCostAmount()).isEqualTo(out.totalCostAmount());
        }
    }

    @Property(tries = 1000)
    @CaseId("TC-CST-026")
    void 退货配对后成本净额为零(@ForAll("operations") List<Operation> operations) {
        Scenario scenario = build(operations);
        CostSolution solution = MovingAverageCostCalculator.calculate(scenario.events());
        Map<Long, CostLayer> byLedger = byLedger(solution);

        for (Pair pair : scenario.pairs()) {
            if (!pair.returnPair()) {
                continue;
            }
            CostLayer out = byLedger.get(pair.outLedgerId());
            CostLayer returned = byLedger.get(pair.reversalLedgerId());
            assertThat(returned.direction()).isEqualTo(CostDirection.IN);
            assertThat(returned.totalCostAmount()).isEqualTo(out.totalCostAmount());
        }
    }

    @Property(tries = 1000)
    @CaseId("TC-CST-027")
    void 打乱输入顺序后重算结果一致(@ForAll("operations") List<Operation> operations) {
        Scenario scenario = build(operations);
        CostSolution first = MovingAverageCostCalculator.calculate(scenario.events());

        List<CostEvent> shuffled = new ArrayList<>(scenario.events());
        Collections.shuffle(shuffled, new Random(20261024L + operations.size()));
        CostSolution second = MovingAverageCostCalculator.calculate(shuffled);

        assertThat(second.layers()).isEqualTo(first.layers());
        assertThat(second.endingPools()).isEqualTo(first.endingPools());
        assertThat(second.inboundCostAmount()).isEqualTo(first.inboundCostAmount());
        assertThat(second.outboundCostAmount()).isEqualTo(first.outboundCostAmount());
    }

    // ─────────────────────────── 生成器 ───────────────────────────

    @Provide
    Arbitrary<List<Operation>> operations() {
        Arbitrary<Operation> one = Combinators.combine(
                        Arbitraries.integers().between(0, 3),
                        Arbitraries.integers().between(1, 50),
                        Arbitraries.integers().between(0, 100_000))
                .as((kind, qty, unitCost) -> new Operation(
                        Kind.values()[kind], qty, unitCost.longValue()));
        return one.list().ofMinSize(1).ofMaxSize(40);
    }

    private static Scenario build(List<Operation> operations) {
        long nextId = 1;
        long refSeq = 1;
        long poolQty = 0;
        List<CostEvent> events = new ArrayList<>();
        List<Pair> pairs = new ArrayList<>();

        for (Operation operation : operations) {
            switch (operation.kind()) {
                case PURCHASE -> {
                    events.add(event(nextId++, operation.qty(), "PURCHASE_IN",
                            "PO-" + refSeq++, operation.unitCost(), null));
                    poolQty += operation.qty();
                }
                case SALE -> {
                    if (poolQty == 0) {
                        continue;
                    }
                    int qty = (int) Math.min(operation.qty(), poolQty);
                    events.add(event(nextId++, -qty, "SALES_OUT",
                            "SO-" + refSeq++, null, null));
                    poolQty -= qty;
                }
                case SALE_REVERSAL_PAIR -> {
                    if (poolQty == 0) {
                        continue;
                    }
                    int qty = (int) Math.min(operation.qty(), poolQty);
                    String ref = "SO-" + refSeq++;
                    long outId = nextId++;
                    events.add(event(outId, -qty, "SALES_OUT", ref, null, null));
                    long reversalId = nextId++;
                    events.add(event(reversalId, qty, "SALES_REVERSAL", ref, null, null));
                    pairs.add(new Pair(outId, reversalId, false));
                }
                case RETURN_PAIR -> {
                    if (poolQty == 0) {
                        continue;
                    }
                    int qty = (int) Math.min(operation.qty(), poolQty);
                    String salesRef = "SO-" + refSeq++;
                    long outId = nextId++;
                    events.add(event(outId, -qty, "SALES_OUT", salesRef, null, null));
                    poolQty -= qty;
                    long returnId = nextId++;
                    events.add(event(returnId, qty, "RETURN_IN",
                            "RO-" + refSeq++, null, salesRef));
                    poolQty += qty;
                    pairs.add(new Pair(outId, returnId, true));
                }
                default -> throw new IllegalStateException("未知操作：" + operation.kind());
            }
        }
        return new Scenario(events, pairs);
    }

    private static CostEvent event(long id, int changeQty, String bizType, String ref,
                                   Long unitCost, String originSalesOrderNo) {
        return new CostEvent(id, SKU, WH, "", changeQty, bizType, ref, unitCost,
                originSalesOrderNo, BASE.plusSeconds(id));
    }

    private static Map<Long, CostLayer> byLedger(CostSolution solution) {
        Map<Long, CostLayer> map = new HashMap<>();
        for (CostLayer layer : solution.layers()) {
            map.put(layer.ledgerId(), layer);
        }
        return map;
    }

    // ─────────────────────────── 内部类型 ───────────────────────────

    private enum Kind {
        PURCHASE,
        SALE,
        SALE_REVERSAL_PAIR,
        RETURN_PAIR
    }

    private record Operation(Kind kind, int qty, long unitCost) {
    }

    private record Pair(long outLedgerId, long reversalLedgerId, boolean returnPair) {
    }

    private record Scenario(List<CostEvent> events, List<Pair> pairs) {
    }
}
