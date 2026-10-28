package com.erp.shared.document;

import java.time.LocalDate;

/**
 * 往来单位的交易画像（供应商 / 客户通用）。
 *
 * <p><b>为什么需要这个东西</b>：原来"查供应商"返回的是数据库一行 ——
 * 编码、名称、联系人、地址、是否启用。这些字段用户看一眼就没了，
 * 真正会问"这个供应商怎么样"时想知道的是：跟我做过多少生意、累计多少钱、多久没来了。
 * 这些信息不在 {@code suppliers} 表里，必须从单据聚合出来。
 *
 * <p><b>为什么放在 shared 而不是 pur/sal</b>：供应商的画像来自采购单、客户的来自出货单，
 * 两个模块都要产出同一个形状的东西；放在 shared 才不会出现"两边各定义一份、
 * 字段名还不一样"的情况。
 *
 * <p><b>金额是「分」</b>：与全系统一致，展示层负责换算成元。
 *
 * @param orderCount   有效单据数（已排除作废）
 * @param totalAmount  累计金额（分）
 * @param lastBizDate  最近一次交易的业务日期；从未交易为 null
 * @param pendingCount 待审单据数（说明"有单子压着"）
 */
public record PartyDealSummary(
        int orderCount,
        long totalAmount,
        LocalDate lastBizDate,
        int pendingCount) {

    /** 从未交易：不是"查不到"，而是"查到了、但没做过生意"，这两者在业务上完全不同。 */
    public static PartyDealSummary none() {
        return new PartyDealSummary(0, 0L, null, 0);
    }

    public boolean empty() {
        return orderCount == 0;
    }
}
