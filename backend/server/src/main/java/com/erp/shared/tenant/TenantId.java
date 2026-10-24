package com.erp.shared.tenant;

/**
 * 租户 id 的常量与校验（FR-TNT-01/02）。
 *
 * <p><b>为什么用 {@code long} 而不是一个 {@code Tenant} 值对象</b>：
 * 租户 id 会出现在<b>每一个</b> SQL 的参数位上（55 张表 × 各条语句）。
 * 引入值对象意味着 366 个调用点要么解包、要么改签名 —— 前者是噪声，
 * 后者会让"这个参数到底是租户还是业务 id"变成一个需要看类型别名才能回答的问题。
 * 而裸 {@code long} 的风险（传错）由一处集中解决：{@link TenantSql} 是唯一
 * 能往 SQL 里塞租户值的地方，业务代码永远拿不到"把租户当参数传"的机会。
 *
 * @see TenantContext
 * @see TenantSql
 */
public final class TenantId {

    /**
     * 默认租户：**存量单租户历史数据的归属**。
     *
     * <p>取值 1 与 {@code V16__tenant_isolation.sql} 的种子一致。
     * 它不是一个"随便挑的默认值"：V13 当年给 {@code audit_logs.tenant_id}
     * 写的默认值也是 1，两处必须同一个数，否则审计行会落在别的租户下。
     */
    public static final long DEFAULT = 1L;

    /**
     * 演示租户：隔离验收用的<b>第二套</b>数据。
     *
     * <p><b>为什么必须有第二套而不是"造一条临时数据"</b>：
     * "租户 B 看不到 A 的数据"这句话，在 B 一行数据都没有时是<b>恒真</b>的
     * —— 接口写错了（比如根本没加过滤）它也会过。只有当两个租户
     * <b>各有自己的、且编码/单号刻意相同</b>的数据时，"看不到"才是一个有含量的断言。
     */
    public static final long DEMO = 2L;

    private TenantId() {
    }

    /** 是否为已知的租户取值（越界即视为编程错误，早失败）。 */
    public static boolean isKnown(long tenantId) {
        return tenantId > 0;
    }
}
