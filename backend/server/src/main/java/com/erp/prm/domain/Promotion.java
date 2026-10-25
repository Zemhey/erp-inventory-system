package com.erp.prm.domain;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 促销活动（FR-V3-21）：纯 POJO。
 *
 * <p>简化口径（作业够用）：{@code conditionJson}/{@code benefitJson} 原样存 JSON 字符串，
 * 服务端不解析、不参与出货算价链路 —— 本期促销是"台账 + 展示"，
 * 接入折扣链路与否在 .plan 报告里如实说明。
 */
public record Promotion(
        Long id,
        String code,
        String name,
        PromoType promoType,
        LocalDateTime startTime,
        LocalDateTime endTime,
        int priority,
        String status,
        String remark,
        List<Rule> rules) {

    public enum PromoType {
        TIME_DISCOUNT, FULL_REDUCE, QTY_DISCOUNT
    }

    /** 状态机：DRAFT → ACTIVE ⇄ STOPPED；非法转移由应用服务用 BusinessException 拒绝。 */
    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_STOPPED = "STOPPED";

    public record Rule(Long id, String conditionJson, String benefitJson) {
    }
}
