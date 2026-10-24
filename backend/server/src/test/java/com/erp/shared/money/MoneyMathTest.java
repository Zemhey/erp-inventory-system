package com.erp.shared.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.support.CaseId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 金额整数运算与 HALF_UP 的唯一入口（ADR-0005）。 */
@DisplayName("金额 · MoneyMath")
class MoneyMathTest {

    @Test
    @CaseId("TC-CST-001")
    @DisplayName("行金额 = 数量 × 单价 × 折扣万分比 ÷ 10000")
    void lineAmount_basic() {
        assertThat(MoneyMath.lineAmount(3, 1000, 10000)).isEqualTo(3000);
        assertThat(MoneyMath.lineAmount(1, 12345, 9000)).isEqualTo(11111);
    }

    @Test
    @CaseId("TC-CST-002")
    @DisplayName("行金额除法按 HALF_UP 落到分")
    void lineAmount_roundsHalfUp() {
        // 3 × 333 × 9000 = 8,991,000；÷10000 = 899.1 → 899
        assertThat(MoneyMath.lineAmount(3, 333, 9000)).isEqualTo(899);
        // 1 × 5 × 5000 = 25,000；÷10000 = 2.5 → 3
        assertThat(MoneyMath.lineAmount(1, 5, 5000)).isEqualTo(3);
    }

    @Test
    @CaseId("TC-CST-003")
    @DisplayName("mulDivHalfUp：正数按 HALF_UP 舍入")
    void mulDivHalfUp_positive() {
        assertThat(MoneyMath.mulDivHalfUp(10, 1, 3)).isEqualTo(3);
        assertThat(MoneyMath.mulDivHalfUp(10, 2, 3)).isEqualTo(7);
        assertThat(MoneyMath.mulDivHalfUp(1, 1, 2)).isEqualTo(1);
    }

    @Test
    @CaseId("TC-CST-004")
    @DisplayName("mulDivHalfUp：负数按远离零方向 HALF_UP")
    void mulDivHalfUp_negative() {
        assertThat(MoneyMath.mulDivHalfUp(-1, 1, 2)).isEqualTo(-1);
        assertThat(MoneyMath.mulDivHalfUp(-10, 1, 3)).isEqualTo(-3);
        assertThat(MoneyMath.mulDivHalfUp(-10, 2, 3)).isEqualTo(-7);
    }

    @Test
    @CaseId("TC-CST-005")
    @DisplayName("除数为 0 / 非法入参必须拒绝")
    void invalidInputs_rejected() {
        assertThatThrownBy(() -> MoneyMath.mulDivHalfUp(1, 1, 0))
                .isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> MoneyMath.lineAmount(-1, 100, 10000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MoneyMath.lineAmount(1, -100, 10000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MoneyMath.lineAmount(1, 100, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
