package com.erp.inv.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 补货建议公式单测（FR-INV-13，纯逻辑：不碰库、不起服务）。
 *
 * <p>这是答辩会被追问的定义性规则，四道门禁之外唯一保留的测试。
 */
class ReplenishmentFormulaTest {

    @Test
    @DisplayName("低于 min_level：补到 max_level")
    void 低于下限时建议量为上限减可用() {
        assertThat(ReplenishmentFormula.suggest(10, 20, 100, 0)).isEqualTo(90);
    }

    @Test
    @DisplayName("在途量 > 0：不重复建议（防二次采购）")
    void 存在在途采购时不出建议() {
        assertThat(ReplenishmentFormula.suggest(10, 20, 100, 5)).isNull();
    }

    @Test
    @DisplayName("可用量不低于下限：不建议")
    void 库存达标时不出建议() {
        assertThat(ReplenishmentFormula.suggest(20, 20, 100, 0)).isNull();
        assertThat(ReplenishmentFormula.suggest(80, 20, 100, 0)).isNull();
    }

    @Test
    @DisplayName("建议量非正（max_level 未设置等）：不出 0 或负数建议")
    void 建议量非正时不出建议() {
        assertThat(ReplenishmentFormula.suggest(10, 20, 10, 0)).isNull();
        assertThat(ReplenishmentFormula.suggest(10, 20, 5, 0)).isNull();
    }

    @Test
    @DisplayName("幂等：同一输入连续调用结果一致")
    void 重复调用结果一致() {
        Integer first = ReplenishmentFormula.suggest(3, 30, 120, 0);
        Integer second = ReplenishmentFormula.suggest(3, 30, 120, 0);
        assertThat(first).isEqualTo(second).isEqualTo(117);
    }
}
