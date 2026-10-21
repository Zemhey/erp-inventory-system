package com.erp.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 关联测试用例编号（docs/06-测试策略.md §8）。
 *
 * <p>编号格式 {@code TC-<模块>-<三位序号>}，如 {@code TC-INV-001}。
 * 这个编号是"需求↔测试追踪矩阵"（docs/00-索引与追踪矩阵.md §3）的连接键，
 * 让 {@code index/test-map.md} 能自动把用例挂到 FR 上。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface CaseId {
    String value();
}
