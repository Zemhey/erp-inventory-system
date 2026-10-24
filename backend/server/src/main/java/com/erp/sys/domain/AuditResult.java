package com.erp.sys.domain;

/** 审计结果（FR-SYS-06）。失败的写操作同样必须留痕 —— 那是最需要审计的部分。 */
public enum AuditResult {
    SUCCESS,
    FAIL
}
