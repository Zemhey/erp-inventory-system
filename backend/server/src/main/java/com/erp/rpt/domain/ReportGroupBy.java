package com.erp.rpt.domain;

/**
 * 报表分组维度（契约 {@code groupBy}：supplier / customer / product / sku / day / month）。
 *
 * <p>刻意做成枚举而不是字符串：分组表达式要拼进 SQL，字符串入参会把
 * "用户能控制 SQL 片段"这条路留下来。枚举之后，SQL 里的分组片段只可能来自
 * 本模块写死的常量表，取值范围由类型系统保证。
 *
 * <p>哪个接口允许哪些维度由契约决定（进货没有 customer、出货没有 supplier），
 * 在 api 层校验，见 {@code ReportController}。
 */
public enum ReportGroupBy {

    /** 按供应商（仅进货统计）。 */
    SUPPLIER,
    /** 按客户（仅出货统计）。 */
    CUSTOMER,
    /** 按商品（款式）。 */
    PRODUCT,
    /** 按 SKU。 */
    SKU,
    /** 按业务日期逐日。 */
    DAY,
    /** 按账期（yyyy-MM）逐月。 */
    MONTH
}
