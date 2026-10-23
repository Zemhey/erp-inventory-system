package com.erp.inv.domain;

/** 冻结记录状态（FR-INV-10）：人工解冻 RELEASED，超时解冻 EXPIRED。 */
public enum FreezeStatus {
    ACTIVE,
    RELEASED,
    EXPIRED
}
