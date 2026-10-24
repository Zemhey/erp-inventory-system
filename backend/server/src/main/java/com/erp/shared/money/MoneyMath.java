package com.erp.shared.money;

import java.math.BigInteger;

/**
 * 金额整数运算（ADR-0005）。
 *
 * <p><b>为什么必须集中在一处</b>：金额乘法 + 除法 + 舍入只要散落到第二个地方，
 * 两处对 HALF_UP 的理解迟早分叉，表现是"行金额之和比合计多一分钱"这类
 * 永远调不平的账。成本引擎、毛利、行金额全部调用本类。
 *
 * <p><b>为什么用 BigInteger 而不是 BigDecimal</b>：本项目的金额全部是整数分，
 * 中间乘积（数量 × 单价 × 折扣万分比）可能超出 long。BigInteger 只做整数
 * 乘除，不引入十进制小数语义；除法在这里一次性完成并显式按 HALF_UP 舍入。
 *
 * <p><b>HALF_UP 的确切含义</b>：远离零方向舍入（与 {@code RoundingMode.HALF_UP} 一致）。
 * 负数仅在买退交错等极端成本场景出现，这里也按同一规则处理，保证可复现。
 */
public final class MoneyMath {

    private MoneyMath() {
    }

    /**
     * {@code value × multiplier ÷ divisor}，结果按 HALF_UP 舍入到整数。
     * 例：{@code mulDivHalfUp(1000, 3, 7) = 429}。
     */
    public static long mulDivHalfUp(long value, long multiplier, long divisor) {
        if (divisor == 0) {
            throw new ArithmeticException("除数不能为 0");
        }
        BigInteger numerator = BigInteger.valueOf(value).multiply(BigInteger.valueOf(multiplier));
        return divideHalfUp(numerator, BigInteger.valueOf(divisor));
    }

    /**
     * {@code qty × unitPriceAmount × discountRate ÷ 10000}（行金额公式）。
     * 折扣率为整数万分比（9000 = 9 折）。
     */
    public static long lineAmount(int qty, long unitPriceAmount, int discountRate) {
        if (qty < 0) {
            throw new IllegalArgumentException("数量不能为负：qty=" + qty);
        }
        if (unitPriceAmount < 0) {
            throw new IllegalArgumentException("单价不能为负：unitPriceAmount=" + unitPriceAmount);
        }
        if (discountRate <= 0) {
            throw new IllegalArgumentException("折扣率必须为正：discountRate=" + discountRate);
        }
        BigInteger numerator = BigInteger.valueOf(unitPriceAmount)
                .multiply(BigInteger.valueOf(qty))
                .multiply(BigInteger.valueOf(discountRate));
        return divideHalfUp(numerator, BigInteger.valueOf(10_000));
    }

    /** 精确整数除法 + HALF_UP（远离零）。 */
    public static long divideHalfUp(BigInteger numerator, BigInteger divisor) {
        if (divisor.signum() == 0) {
            throw new ArithmeticException("除数不能为 0");
        }
        BigInteger[] quotientAndRemainder = numerator.divideAndRemainder(divisor);
        BigInteger quotient = quotientAndRemainder[0];
        BigInteger remainder = quotientAndRemainder[1];
        if (remainder.signum() == 0) {
            return quotient.longValueExact();
        }
        // |余数| × 2 >= |除数| 时向远离零方向进一
        if (remainder.abs().shiftLeft(1).compareTo(divisor.abs()) >= 0) {
            int sign = numerator.signum() * divisor.signum();
            quotient = quotient.add(BigInteger.valueOf(sign));
        }
        return quotient.longValueExact();
    }
}
