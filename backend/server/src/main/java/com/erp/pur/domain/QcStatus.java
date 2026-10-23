package com.erp.pur.domain;

/**
 * 明细行累计质检状态（FR-PUR-05）。
 *
 * <p>由"累计合格量 vs 累计到货量"推导，不是人工录入：
 * 分批到货时每批质检结果都会刷新它，避免"第一批全检合格、第二批全检不合格
 * 但仍显示 PASSED"这种账实不符。
 */
public enum QcStatus {

    /** 尚未到货。 */
    NONE,

    /** 累计合格量 == 累计到货量。 */
    PASSED,

    /** 0 < 累计合格量 < 累计到货量。 */
    PARTIAL,

    /** 已到货但累计合格量为 0。 */
    FAILED;

    /** 按累计数量推导状态。 */
    public static QcStatus of(int receivedQty, int qcQty) {
        if (receivedQty <= 0) {
            return NONE;
        }
        if (qcQty <= 0) {
            return FAILED;
        }
        return qcQty >= receivedQty ? PASSED : PARTIAL;
    }
}
