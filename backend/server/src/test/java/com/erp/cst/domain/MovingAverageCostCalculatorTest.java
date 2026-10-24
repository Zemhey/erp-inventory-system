package com.erp.cst.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.support.CaseId;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 移动加权成本计算器 · 纯单元测试（不依赖数据库）。 */
@DisplayName("成本 · 移动加权计算器")
class MovingAverageCostCalculatorTest {

    private static final OffsetDateTime BASE =
            OffsetDateTime.of(2026, 10, 1, 9, 0, 0, 0, ZoneOffset.ofHours(8));
    private static final long SKU = 1L;
    private static final long WH = 10L;
    private static final long WH2 = 20L;

    // ─────────────────────────── 基础均价 ───────────────────────────

    @Test
    @CaseId("TC-CST-006")
    @DisplayName("空流水 → 全零且守恒")
    void emptyEvents() {
        CostSolution solution = MovingAverageCostCalculator.calculate(List.of());
        assertThat(solution.layers()).isEmpty();
        assertThat(solution.inboundCostAmount()).isZero();
        assertThat(solution.outboundCostAmount()).isZero();
        assertThat(solution.endingCostAmount()).isZero();
        assertThat(solution.balanced()).isTrue();
    }

    @Test
    @CaseId("TC-CST-007")
    @DisplayName("单笔入库：成本层单位成本 = 进货单价，池状态正确")
    void singlePurchase() {
        CostSolution solution = calc(purchase(1, 10, 100));
        assertThat(solution.layers()).hasSize(1);
        CostLayer layer = solution.layers().get(0);
        assertThat(layer.direction()).isEqualTo(CostDirection.IN);
        assertThat(layer.unitCostAmount()).isEqualTo(100);
        assertThat(layer.totalCostAmount()).isEqualTo(1000);
        assertThat(layer.poolQtyAfter()).isEqualTo(10);
        assertThat(layer.poolCostAfter()).isEqualTo(1000);
        assertThat(solution.balanced()).isTrue();
    }

    @Test
    @CaseId("TC-CST-008")
    @DisplayName("两笔入库加权平均：10@100 + 10@200 → 均价 150")
    void weightedAverage() {
        CostSolution solution = calc(purchase(1, 10, 100), purchase(2, 10, 200));
        CostLayer second = solution.layers().get(1);
        assertThat(second.poolQtyAfter()).isEqualTo(20);
        assertThat(second.poolCostAfter()).isEqualTo(3000);
        assertThat(second.unitCostAmount()).isEqualTo(200);

        CostSolution withOut = calc(purchase(1, 10, 100), purchase(2, 10, 200),
                sale(3, 5, "SO-1"));
        CostLayer out = withOut.layers().get(2);
        assertThat(out.unitCostAmount()).isEqualTo(150);
        assertThat(out.totalCostAmount()).isEqualTo(750);
        assertThat(withOut.endingPools().get(new CostPoolKey(SKU, WH)))
                .isEqualTo(new CostPoolState(15, 2250));
    }

    @Test
    @CaseId("TC-CST-009")
    @DisplayName("部分出库后均价保持不变")
    void partialOutKeepsAverage() {
        CostSolution solution = calc(purchase(1, 10, 100), purchase(2, 10, 200),
                sale(3, 5, "SO-1"), sale(4, 3, "SO-2"));
        CostLayer secondOut = solution.layers().get(3);
        assertThat(secondOut.unitCostAmount()).isEqualTo(150);
        assertThat(secondOut.totalCostAmount()).isEqualTo(450);
        assertThat(solution.endingPools().get(new CostPoolKey(SKU, WH)))
                .isEqualTo(new CostPoolState(12, 1800));
    }

    @Test
    @CaseId("TC-CST-010")
    @DisplayName("一次出清：结转全部池成本，期末数量与成本都为 0")
    void sellAllClearsPool() {
        CostSolution solution = calc(purchase(1, 10, 100), purchase(2, 10, 200),
                sale(3, 20, "SO-1"));
        CostLayer out = solution.layers().get(2);
        assertThat(out.totalCostAmount()).isEqualTo(3000);
        assertThat(out.poolQtyAfter()).isZero();
        assertThat(out.poolCostAfter()).isZero();
        assertThat(solution.endingCostAmount()).isZero();
        assertThat(solution.balanced()).isTrue();
    }

    @Test
    @CaseId("TC-CST-011")
    @DisplayName("出库除法按 HALF_UP；最后一件取剩余全部成本")
    void outboundRounding() {
        // 池数量 3、金额 1 分：出 1 → 0（0.33 舍去）；再出 1 → 1（0.5 进位）；最后出 1 → 0（取剩余）
        CostSolution solution = calc(purchase(1, 1, 1), purchase(2, 2, 0),
                sale(3, 1, "SO-1"), sale(4, 1, "SO-2"), sale(5, 1, "SO-3"));
        assertThat(solution.layers().get(2).totalCostAmount()).isZero();
        assertThat(solution.layers().get(3).totalCostAmount()).isEqualTo(1);
        assertThat(solution.layers().get(4).totalCostAmount()).isZero();
        assertThat(solution.endingPools().get(new CostPoolKey(SKU, WH)))
                .isEqualTo(CostPoolState.empty());
    }

    // ─────────────────────────── 红冲 / 退货配对 ───────────────────────────

    @Test
    @CaseId("TC-CST-012")
    @DisplayName("进货红冲按原进价出库，池金额不被当前均价污染")
    void purchaseReversalUsesOriginalCost() {
        CostSolution solution = calc(
                purchase(1, 10, 100),
                purchase(2, 10, 200),
                outbound(3, 10, "PURCHASE_REVERSAL", "PO-1", 100L));
        CostLayer reversal = solution.layers().get(2);
        assertThat(reversal.direction()).isEqualTo(CostDirection.OUT);
        assertThat(reversal.totalCostAmount()).isEqualTo(1000);
        assertThat(solution.endingPools().get(new CostPoolKey(SKU, WH)))
                .isEqualTo(new CostPoolState(10, 2000));
        assertThat(solution.balanced()).isTrue();
    }

    @Test
    @CaseId("TC-CST-013")
    @DisplayName("出货红冲配对原 SALES_OUT：成本净额为 0")
    void salesReversalPairsOriginalCost() {
        CostSolution solution = calc(
                purchase(1, 10, 100),
                sale(2, 3, "SO-1"),
                inbound(3, 3, "SALES_REVERSAL", "SO-1", null));
        CostLayer out = solution.layers().get(1);
        CostLayer reversal = solution.layers().get(2);
        assertThat(out.totalCostAmount()).isEqualTo(300);
        assertThat(reversal.totalCostAmount()).isEqualTo(300);
        assertThat(reversal.unitCostAmount()).isEqualTo(100);
        assertThat(solution.endingPools().get(new CostPoolKey(SKU, WH)))
                .isEqualTo(new CostPoolState(10, 1000));
    }

    @Test
    @CaseId("TC-CST-014")
    @DisplayName("出货红冲无原始流水可配对时退回当前均价")
    void salesReversalWithoutPairFallsBackToAverage() {
        CostSolution solution = calc(
                purchase(1, 10, 100),
                purchase(2, 10, 200),
                inbound(3, 5, "SALES_REVERSAL", "SO-MISSING", null));
        assertThat(solution.layers().get(2).unitCostAmount()).isEqualTo(150);
        assertThat(solution.layers().get(2).totalCostAmount()).isEqualTo(750);
        assertThat(solution.endingPools().get(new CostPoolKey(SKU, WH)))
                .isEqualTo(new CostPoolState(25, 3750));
    }

    @Test
    @CaseId("TC-CST-015")
    @DisplayName("退货按原出货成本回补：出货 + 退货在成本侧净额为 0")
    void returnInPairsOriginalOutboundCost() {
        CostSolution solution = calc(
                purchase(1, 10, 100),
                sale(2, 4, "SO-1"),
                returnIn(3, 4, "RO-1", "SO-1"));
        assertThat(solution.layers().get(1).totalCostAmount()).isEqualTo(400);
        assertThat(solution.layers().get(2).totalCostAmount()).isEqualTo(400);
        assertThat(solution.layers().get(2).unitCostAmount()).isEqualTo(100);
        assertThat(solution.endingPools().get(new CostPoolKey(SKU, WH)))
                .isEqualTo(new CostPoolState(10, 1000));
    }

    @Test
    @CaseId("TC-CST-016")
    @DisplayName("退货找不到原出货单时退回当前均价")
    void returnInWithoutOriginFallsBackToAverage() {
        CostSolution solution = calc(
                purchase(1, 10, 100),
                purchase(2, 10, 200),
                returnIn(3, 4, "RO-1", null));
        assertThat(solution.layers().get(2).unitCostAmount()).isEqualTo(150);
        assertThat(solution.layers().get(2).totalCostAmount()).isEqualTo(600);
    }

    // ─────────────────────────── 其他类型 / 边界 ───────────────────────────

    @Test
    @CaseId("TC-CST-017")
    @DisplayName("无显式成本的调整入库按当前均价入池（调拨入库已改为优先配对）")
    void inboundWithoutExplicitCostUsesAverage() {
        CostSolution solution = calc(
                purchase(1, 10, 100),
                inbound(2, 10, "ADJUST_IN", "ADJ-1", null));
        assertThat(solution.layers().get(1).unitCostAmount()).isEqualTo(100);
        assertThat(solution.layers().get(1).totalCostAmount()).isEqualTo(1000);
        assertThat(solution.endingPools().get(new CostPoolKey(SKU, WH)))
                .isEqualTo(new CostPoolState(20, 2000));
    }

    @Test
    @CaseId("TC-CST-018")
    @DisplayName("不同 (SKU, 仓库) 成本池互不影响")
    void multiplePoolsAreIndependent() {
        CostSolution solution = MovingAverageCostCalculator.calculate(List.of(
                purchase(1, SKU, WH, 10, 100),
                purchase(2, SKU, 20L, 10, 200),
                sale(3, SKU, WH, 5, "SO-1"),
                sale(4, SKU, 20L, 5, "SO-2")));
        assertThat(solution.endingPools().get(new CostPoolKey(SKU, WH)))
                .isEqualTo(new CostPoolState(5, 500));
        assertThat(solution.endingPools().get(new CostPoolKey(SKU, 20L)))
                .isEqualTo(new CostPoolState(5, 1000));
        assertThat(solution.balanced()).isTrue();
    }

    @Test
    @CaseId("TC-CST-019")
    @DisplayName("混合序列（入/出/红冲/退货/调整）仍满足 INV-11")
    void mixedSequenceConservesCost() {
        CostSolution solution = calc(
                purchase(1, 10, 100),
                purchase(2, 10, 200),
                sale(3, 5, "SO-1"),
                inbound(4, 5, "SALES_REVERSAL", "SO-1", null),
                returnIn(5, 2, "RO-1", "SO-1"),
                outbound(6, 1, "ADJUST_OUT", "ADJ-1", null),
                outbound(7, 10, "PURCHASE_REVERSAL", "PO-2", 200L));
        assertThat(solution.balanced()).isTrue();
        assertThat(solution.inboundCostAmount())
                .isEqualTo(solution.outboundCostAmount() + solution.endingCostAmount());
        assertThat(solution.endingPools().get(new CostPoolKey(SKU, WH)).qty()).isEqualTo(11);
    }

    @Test
    @CaseId("TC-CST-020")
    @DisplayName("乱序输入按业务时间 + 流水 id 排序，结果确定")
    void deterministicOrdering() {
        List<CostEvent> events = new ArrayList<>(List.of(
                purchase(1, 10, 100),
                purchase(2, 10, 200),
                sale(3, 5, "SO-1"),
                sale(4, 3, "SO-2")));
        CostSolution ordered = MovingAverageCostCalculator.calculate(events);
        Collections.reverse(events);
        CostSolution shuffled = MovingAverageCostCalculator.calculate(events);
        assertThat(shuffled.layers()).isEqualTo(ordered.layers());
        assertThat(shuffled.endingPools()).isEqualTo(ordered.endingPools());
    }

    @Test
    @CaseId("TC-CST-021")
    @DisplayName("逐条增量计算与一次性回放结果一致")
    void incrementalMatchesBatch() {
        List<CostEvent> events = List.of(
                purchase(1, 10, 100),
                purchase(2, 10, 200),
                sale(3, 5, "SO-1"),
                inbound(4, 5, "SALES_REVERSAL", "SO-1", null));
        CostSolution batch = MovingAverageCostCalculator.calculate(events);
        for (int i = 1; i <= events.size(); i++) {
            CostSolution prefix = MovingAverageCostCalculator.calculate(events.subList(0, i));
            assertThat(prefix.layers().get(i - 1)).isEqualTo(batch.layers().get(i - 1));
        }
    }

    @Test
    @CaseId("TC-CST-022")
    @DisplayName("出库超过池数量必须拒绝（程序性错误，不允许静默负库存）")
    void outboundBeyondPoolRejected() {
        assertThatThrownBy(() -> calc(purchase(1, 1, 100), sale(2, 2, "SO-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("成本池数量不足");
    }

    // ─────────────────────────── 仓间调拨配对（V2-1 补丁） ───────────────────────────

    @Test
    @CaseId("TC-CST-057")
    @DisplayName("调拨：目标仓为空时，调入按源仓出库成本配对，两仓合计守恒")
    void transferPairsCostWhenTargetEmpty() {
        CostSolution solution = calc(
                purchase(1, SKU, WH, 10, 100),
                transferOut(2, SKU, WH, "", 4, "TO-1"),
                transferIn(3, SKU, WH2, "", 4, "TO-1", null));

        assertThat(solution.endingPools().get(new CostPoolKey(SKU, WH)))
                .isEqualTo(new CostPoolState(6, 600));
        assertThat(solution.endingPools().get(new CostPoolKey(SKU, WH2)))
                .isEqualTo(new CostPoolState(4, 400));
        assertThat(layer(solution, "TRANSFER_OUT").totalCostAmount()).isEqualTo(400);
        assertThat(layer(solution, "TRANSFER_IN").totalCostAmount()).isEqualTo(400);
        assertThat(layer(solution, "TRANSFER_IN").unitCostAmount()).isEqualTo(100);
        assertThat(combinedEndingCost(solution)).isEqualTo(1000);
        assertThat(solution.balanced()).isTrue();
    }

    @Test
    @CaseId("TC-CST-058")
    @DisplayName("调拨：目标仓已有不同均价时，调入仍按源仓成本，不被目标均价重估")
    void transferUsesSourceCostWhenTargetHasDifferentAverage() {
        CostSolution solution = calc(
                purchase(1, SKU, WH, 10, 100),
                purchase(2, SKU, WH2, 10, 200),
                transferOut(3, SKU, WH, "", 4, "TO-2"),
                transferIn(4, SKU, WH2, "", 4, "TO-2", null));

        assertThat(layer(solution, "TRANSFER_OUT").unitCostAmount()).isEqualTo(100);
        assertThat(layer(solution, "TRANSFER_IN").unitCostAmount()).isEqualTo(100);
        assertThat(layer(solution, "TRANSFER_IN").totalCostAmount()).isEqualTo(400);
        assertThat(solution.endingPools().get(new CostPoolKey(SKU, WH)))
                .isEqualTo(new CostPoolState(6, 600));
        assertThat(solution.endingPools().get(new CostPoolKey(SKU, WH2)))
                .isEqualTo(new CostPoolState(14, 2400));
        assertThat(combinedEndingCost(solution))
                .as("两仓合计成本 = 初始 1000 + 2000，调拨不得凭空增减")
                .isEqualTo(3000);
    }

    @Test
    @CaseId("TC-CST-059")
    @DisplayName("多批次/部分调拨：每个批次各自配对，未带 unitCostAmount 仍守恒")
    void transferMultiBatchWithoutUnitCost() {
        CostSolution solution = calc(
                purchase(1, SKU, WH, "B1", 10, 100),
                purchase(2, SKU, WH, "B2", 10, 300),
                transferOut(3, SKU, WH, "B1", 4, "TO-3"),
                transferIn(4, SKU, WH2, "B1", 4, "TO-3", null),
                transferOut(5, SKU, WH, "B2", 3, "TO-3"),
                transferIn(6, SKU, WH2, "B2", 3, "TO-3", null));

        // 成本池是 SKU 级移动加权：两笔进货后均价 200，调出按 200 结转
        assertThat(sumLayers(solution, "TRANSFER_OUT")).isEqualTo(1400);
        assertThat(sumLayers(solution, "TRANSFER_IN")).isEqualTo(1400);
        assertThat(solution.endingPools().get(new CostPoolKey(SKU, WH)))
                .isEqualTo(new CostPoolState(13, 2600));
        assertThat(solution.endingPools().get(new CostPoolKey(SKU, WH2)))
                .isEqualTo(new CostPoolState(7, 1400));
        assertThat(combinedEndingCost(solution)).isEqualTo(4000);
    }

    @Test
    @CaseId("TC-CST-060")
    @DisplayName("调拨后再出库：目标仓出库成本沿用源仓成本，外部出库 + 两仓期末守恒")
    void saleAfterTransferUsesTransferredCost() {
        CostSolution solution = calc(
                purchase(1, SKU, WH, 10, 100),
                transferOut(2, SKU, WH, "", 4, "TO-4"),
                transferIn(3, SKU, WH2, "", 4, "TO-4", null),
                sale(4, SKU, WH2, 2, "SO-AFTER-TRANSFER"));

        assertThat(layer(solution, "SALES_OUT").unitCostAmount()).isEqualTo(100);
        assertThat(layer(solution, "SALES_OUT").totalCostAmount()).isEqualTo(200);
        assertThat(solution.endingPools().get(new CostPoolKey(SKU, WH)))
                .isEqualTo(new CostPoolState(6, 600));
        assertThat(solution.endingPools().get(new CostPoolKey(SKU, WH2)))
                .isEqualTo(new CostPoolState(2, 200));
        assertThat(combinedEndingCost(solution) + layer(solution, "SALES_OUT").totalCostAmount())
                .as("外部出库 200 + 两仓期末 800 = 初始入库 1000")
                .isEqualTo(1000);
    }

    // ─────────────────────────── 构造器 ───────────────────────────

    private static CostSolution calc(CostEvent... events) {
        return MovingAverageCostCalculator.calculate(List.of(events));
    }

    private static long combinedEndingCost(CostSolution solution) {
        return solution.endingPools().getOrDefault(new CostPoolKey(SKU, WH), CostPoolState.empty())
                .costAmount()
                + solution.endingPools().getOrDefault(new CostPoolKey(SKU, WH2),
                        CostPoolState.empty()).costAmount();
    }

    private static CostLayer layer(CostSolution solution, String bizType) {
        return solution.layers().stream()
                .filter(layer -> layer.bizType().equals(bizType))
                .findFirst().orElseThrow();
    }

    private static long sumLayers(CostSolution solution, String bizType) {
        return solution.layers().stream()
                .filter(layer -> layer.bizType().equals(bizType))
                .mapToLong(CostLayer::totalCostAmount)
                .sum();
    }

    private static CostEvent purchase(long id, int qty, long unitCost) {
        return purchase(id, SKU, WH, qty, unitCost);
    }

    private static CostEvent purchase(long id, long skuId, long warehouseId, int qty, long unitCost) {
        return inbound(id, skuId, warehouseId, qty, "PURCHASE_IN", "PO-" + id, unitCost);
    }

    private static CostEvent purchase(long id, long skuId, long warehouseId, String batch,
                                      int qty, long unitCost) {
        return new CostEvent(id, skuId, warehouseId, batch, qty, "PURCHASE_IN",
                "PO-" + id, unitCost, null, at(id));
    }

    private static CostEvent sale(long id, int qty, String orderNo) {
        return sale(id, SKU, WH, qty, orderNo);
    }

    private static CostEvent sale(long id, long skuId, long warehouseId, int qty, String orderNo) {
        return outbound(id, skuId, warehouseId, qty, "SALES_OUT", orderNo, null);
    }

    private static CostEvent sale(long id, long skuId, long warehouseId, String batch,
                                  int qty, String orderNo) {
        return new CostEvent(id, skuId, warehouseId, batch, -qty, "SALES_OUT", orderNo,
                null, null, at(id));
    }

    private static CostEvent transferOut(long id, long skuId, long warehouseId, String batch,
                                         int qty, String transferNo) {
        return new CostEvent(id, skuId, warehouseId, batch, -qty, "TRANSFER_OUT", transferNo,
                null, null, at(id));
    }

    private static CostEvent transferIn(long id, long skuId, long warehouseId, String batch,
                                        int qty, String transferNo, Long unitCost) {
        return new CostEvent(id, skuId, warehouseId, batch, qty, "TRANSFER_IN", transferNo,
                unitCost, null, at(id));
    }

    private static CostEvent returnIn(long id, int qty, String returnNo, String originOrderNo) {
        return new CostEvent(id, SKU, WH, "", qty, "RETURN_IN", returnNo, null,
                originOrderNo, at(id));
    }

    private static CostEvent inbound(long id, int qty, String bizType, String ref, Long unitCost) {
        return inbound(id, SKU, WH, qty, bizType, ref, unitCost);
    }

    private static CostEvent inbound(long id, long skuId, long warehouseId, int qty,
                                     String bizType, String ref, Long unitCost) {
        return new CostEvent(id, skuId, warehouseId, "", qty, bizType, ref, unitCost,
                null, at(id));
    }

    private static CostEvent outbound(long id, int qty, String bizType, String ref,
                                      Long unitCost) {
        return outbound(id, SKU, WH, qty, bizType, ref, unitCost);
    }

    private static CostEvent outbound(long id, long skuId, long warehouseId, int qty,
                                      String bizType, String ref, Long unitCost) {
        return new CostEvent(id, skuId, warehouseId, "", -qty, bizType, ref, unitCost,
                null, at(id));
    }

    private static OffsetDateTime at(long id) {
        return BASE.plusSeconds(id);
    }
}
