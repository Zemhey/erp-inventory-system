package com.erp.cst.domain;

/** 成本层方向。数据库用 IN/OUT 字符串存储，领域层用枚举避免拼写漂移。 */
public enum CostDirection {
    IN,
    OUT
}
