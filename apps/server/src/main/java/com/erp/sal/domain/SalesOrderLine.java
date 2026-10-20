package com.erp.sal.domain;

/**
 * 出货明细行（FR-SAL-01/02）。
 *
 * @param id              主键
 * @param lineNo          行号，单据内从 1 开始
 * @param skuId           SKU
 * @param qty             出货数量
 * @param shippedQty      累计已出库数量
 * @param unitPriceAmount 单价（分）
 * @param discountRate    折扣率，整数万分比（9000 = 9 折，10000 = 不打折）
 * @param amount          行金额 = round(qty × unitPrice × discountRate / 10000)（分）
 * @param reservations    本行对应的预占分段（FEFO 拆批次时可能有多段）
 */
public record SalesOrderLine(long id, int lineNo, long skuId, int qty, int shippedQty,
                             long unitPriceAmount, int discountRate, long amount,
                             java.util.List<LineReservation> reservations) {

    public SalesOrderLine {
        reservations = reservations == null ? java.util.List.of() : java.util.List.copyOf(reservations);
    }

    public int openQty() {
        return qty - shippedQty;
    }

    /** 本行全部预占记录的 id（出货后需逐段消费，作废后需逐段释放）。 */
    public java.util.List<Long> reservationIds() {
        return reservations.stream().map(LineReservation::reservationId).toList();
    }

    public long totalReservedQty() {
        return reservations.stream().mapToLong(LineReservation::reservedQty).sum();
    }
}
