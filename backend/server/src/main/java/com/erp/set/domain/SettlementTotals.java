package com.erp.set.domain;

/**
 * 结算汇总的六量一净（契约 {@code SettlementTotals}）。
 *
 * <p><b>为什么 {@code netAmount} 是派生的而不是第七个字段</b>：
 * 计划书 §3.4 定义 {@code netAmount = salesAmount − returnAmount}。一旦把它存成
 * 可独立赋值的字段，就存在"三个数和 net 对不上"的状态 —— 而对账正是靠这个等式
 * 才有意义。派生之后，不一致在类型层面不可表达（数据库层另有 CHECK 兜底）。
 *
 * <p>金额一律是"分"（ADR-0005），累加用 {@link Math#addExact}：
 * 日清/月结是长期累加的账目，静默溢出比报错危险得多。
 */
public record SettlementTotals(long purchaseQty, long purchaseAmount,
                               long salesQty, long salesAmount,
                               long returnQty, long returnAmount) {

    public static SettlementTotals zero() {
        return new SettlementTotals(0, 0, 0, 0, 0, 0);
    }

    /** 期间净额。可以为负：退货单的业务日期常常晚于原出货日，跨日净额天然是负数。 */
    public long netAmount() {
        return Math.subtractExact(salesAmount, returnAmount);
    }

    public SettlementTotals withPurchase(long qty, long amount) {
        return new SettlementTotals(Math.addExact(purchaseQty, qty),
                Math.addExact(purchaseAmount, amount), salesQty, salesAmount, returnQty, returnAmount);
    }

    public SettlementTotals withSales(long qty, long amount) {
        return new SettlementTotals(purchaseQty, purchaseAmount,
                Math.addExact(salesQty, qty), Math.addExact(salesAmount, amount),
                returnQty, returnAmount);
    }

    public SettlementTotals withReturn(long qty, long amount) {
        return new SettlementTotals(purchaseQty, purchaseAmount, salesQty, salesAmount,
                Math.addExact(returnQty, qty), Math.addExact(returnAmount, amount));
    }

    /** 把另一份汇总并入（月结把逐日汇总累加成期间汇总）。 */
    public SettlementTotals plus(SettlementTotals other) {
        return new SettlementTotals(
                Math.addExact(purchaseQty, other.purchaseQty),
                Math.addExact(purchaseAmount, other.purchaseAmount),
                Math.addExact(salesQty, other.salesQty),
                Math.addExact(salesAmount, other.salesAmount),
                Math.addExact(returnQty, other.returnQty),
                Math.addExact(returnAmount, other.returnAmount));
    }

    /**
     * 六量是否完全相等。
     *
     * <p>record 自带的 equals 已经够用，这里单独命名是为了让调用点把
     * "账目是否漂移"这个业务意图写出来，而不是留下一个语义不明的 {@code equals}。
     */
    public boolean sameAccounts(SettlementTotals other) {
        return other != null
                && purchaseQty == other.purchaseQty
                && purchaseAmount == other.purchaseAmount
                && salesQty == other.salesQty
                && salesAmount == other.salesAmount
                && returnQty == other.returnQty
                && returnAmount == other.returnAmount;
    }

    /** 用于异常 details 与日志的可读形式。 */
    public String describe() {
        return "入 %d 件 / %d 分，出 %d 件 / %d 分，退 %d 件 / %d 分，净额 %d 分".formatted(
                purchaseQty, purchaseAmount, salesQty, salesAmount,
                returnQty, returnAmount, netAmount());
    }
}
