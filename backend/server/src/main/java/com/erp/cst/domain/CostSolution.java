package com.erp.cst.domain;

import java.util.List;
import java.util.Map;

/**
 * 一次成本回放的完整结果。
 *
 * <p>{@link #balanced()} 就是不变量 INV-11：
 * {@code Σ入库成本 == Σ出库成本 + 期末库存成本}。
 * 因为每条成本层的金额都已确定，这个恒等式在结构上成立；属性测试
 * 仍会随机生成上千组流水独立验证它，防止后续重构破坏算法。
 */
public record CostSolution(
        List<CostLayer> layers,
        Map<CostPoolKey, CostPoolState> endingPools,
        long inboundCostAmount,
        long outboundCostAmount,
        long endingCostAmount) {

    public CostSolution {
        layers = List.copyOf(layers);
        endingPools = Map.copyOf(endingPools);
    }

    public boolean balanced() {
        return inboundCostAmount == outboundCostAmount + endingCostAmount;
    }
}
