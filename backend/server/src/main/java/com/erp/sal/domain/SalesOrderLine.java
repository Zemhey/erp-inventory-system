package com.erp.sal.domain;

/**
 * 出货明细行（FR-SAL-01/02）。
 *
 * @param id              主键
 * @param lineNo          行号，单据内从 1 开始
 * @param skuId           SKU
 * @param qty             出货数量
 * @param shippedQty      累计已出库数量
 * @param returnedQty     累计已退货数量（FR-RMA-01，上限是 shippedQty）
 * @param unitPriceAmount 单价（分）
 * @param discountRate    折扣率，整数万分比（9000 = 9 折，10000 = 不打折）
 * @param amount          行金额 = round(qty × unitPrice × discountRate / 10000)（分）
 * @param reservations    本行对应的预占分段（FEFO 拆批次时可能有多段）
 */
public record SalesOrderLine(long id, int lineNo, long skuId, int qty, int shippedQty,
                             int returnedQty, long unitPriceAmount, int discountRate, long amount,
                             java.util.List<LineReservation> reservations,
                             Long bundleId, Integer bundleGroupNo, Integer bundleRatio) {

    public SalesOrderLine {
        reservations = reservations == null ? java.util.List.of() : java.util.List.copyOf(reservations);
    }

    /** 兼容 M1~M2.5 的构造（普通 SKU 行）。 */
    public SalesOrderLine(long id, int lineNo, long skuId, int qty, int shippedQty,
                          int returnedQty, long unitPriceAmount, int discountRate, long amount,
                          java.util.List<LineReservation> reservations) {
        this(id, lineNo, skuId, qty, shippedQty, returnedQty, unitPriceAmount, discountRate,
                amount, reservations, null, null, null);
    }

    public int openQty() {
        return qty - shippedQty;
    }

    /** 剩余可退数量 —— 退货建单的预检与入库时的原子守卫都用这一个口径。 */
    public int returnableQty() {
        return shippedQty - returnedQty;
    }

    /** 本行全部预占记录的 id（出货后需逐段消费，作废后需逐段释放）。 */
    public java.util.List<Long> reservationIds() {
        return reservations.stream().map(LineReservation::reservationId).toList();
    }

    public long totalReservedQty() {
        return reservations.stream().mapToLong(LineReservation::reservedQty).sum();
    }

    /** 套装组件行的套装数量 = 组件数量 ÷ 配比；普通行为 null。 */
    public Integer bundleQty() {
        return bundleRatio == null ? null : qty / bundleRatio;
    }
}
