package com.erp.sal.domain;

/**
 * 出货明细的一段预占。
 *
 * <p>为什么要"分段"而不是"一行一个预占 id"：
 * 出货单建单时不知道批次（批次由 FEFO 决定），一笔跨批次的出货会被拆成多段预占。
 * 例如 12 件出货，若库存为 B1(10) + B2(10)，则分成"B1 预占 10 + B2 预占 2"两段。
 *
 * @param reservationId 预占记录 id（指向 inventory_reservations）
 * @param seq           段序号，从 1 开始
 * @param reservedQty   本段预占数量
 */
public record LineReservation(long reservationId, int seq, int reservedQty) {
}
