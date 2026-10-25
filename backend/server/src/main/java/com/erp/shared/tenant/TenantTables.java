package com.erp.shared.tenant;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 带 {@code tenant_id} 的业务表清单（FR-TNT-02）—— <b>单一事实源</b>。
 *
 * <p>这份清单有三个使用者，三处必须逐字一致，否则会出现"某张表被漏掉"的静默缺陷：
 * <ol>
 *   <li>{@code V16__tenant_isolation.sql} 的加列 / 回填 / 索引（由
 *       {@code .plan/_gen_v16.py} 生成，同一份清单）；</li>
 *   <li>{@link TenantSql} 的改写范围（不在清单里的标识符一律不改写）；</li>
 *   <li>{@code TenantSchemaIT}：拿 {@code information_schema} 反查
 *       "库里带 tenant_id 的表"与这份清单<b>双向相等</b> —— 少一张会漏过滤，
 *       多一张会让改写器往不存在的列上写谓词（表现为 SQL 报错，反而是好事，
 *       但根本原因是清单漂移）。</li>
 * </ol>
 *
 * <p><b>刻意不在清单里的表</b>：
 * <ul>
 *   <li>{@code tenants} —— 它就是租户的定义本身，没有"属于哪个租户"这回事；</li>
 *   <li>{@code flyway_schema_history} —— Flyway 自己的账本，跨租户共享；</li>
 *   <li>{@code pg_*} / {@code information_schema.*} —— 系统目录。</li>
 * </ul>
 *
 * <p><b>{@code audit_logs} 为什么在清单里但性质不同</b>：V13 就给它建了
 * {@code tenant_id BIGINT NOT NULL DEFAULT 1}（当时是"预留列"）。它带
 * append-only 触发器，任何 UPDATE 都会被拒绝，因此 <b>V16 对它的列一个字都不改</b>
 * （不回填、不改默认值、不动触发器与既有索引），只追加租户索引并把它纳入
 * 查询改写范围。它的历史行全部是 1 —— 那本来就是单租户历史数据的正确归属。
 */
public final class TenantTables {

    /**
     * 全部带 {@code tenant_id} 的业务表（64 张 = V16 的 55 张 + V17 的成员关系表 1 张
     * + V18 的主数据扩展 3 张 + V19 的骨架系统表 5 张）。
     */
    public static final Set<String> ALL = Set.copyOf(List.of(
            // 主数据
            "warehouses", "products", "skus", "suppliers", "customers",
            // 主数据扩展（V18 · B2）：客户地址 / 仓库库位 / 商品规格属性
            "customer_addresses", "warehouse_locations", "product_attribute_values",
            // 库存
            "inventory_balances", "inventory_ledger", "inventory_reservations",
            "inventory_transfers", "transfer_lines", "stocktakes", "stocktake_lines",
            "inventory_adjustments", "inventory_freezes",
            // 幂等与单号
            "idempotency_keys", "document_sequences",
            // 进货闭环
            "purchase_orders", "purchase_order_lines",
            "po_receipts", "po_receipt_lines",
            "supplier_payables", "payable_allocations", "purchase_returns",
            // 出货闭环
            "sales_orders", "sales_order_lines", "sales_order_line_reservations",
            "product_bundles", "bundle_items",
            "sales_quotes", "sales_quote_lines",
            "so_shipment_plans", "so_shipments", "shipment_lines",
            "customer_receivables", "customer_receipts", "receivable_allocations",
            "customer_credit_snapshots",
            // 退货
            "return_orders", "return_order_lines",
            // 结算
            "settlement_periods", "daily_settlements",
            // 成本与毛利
            "cost_layers", "cost_monthly", "margin_records",
            // 系统：字典 / 数据权限 / 账号角色
            "dict_types", "dict_items", "data_scopes", "app_users", "app_roles",
            "app_user_roles", "app_role_permissions",
            // B1：用户×租户成员关系（主租户也有一行；跨租户枚举必须走系统旁路）
            "app_user_tenants",
            // 审批
            "approval_flows", "approval_instances", "approval_steps",
            // 审计（V13 已有列；V16 只追加索引，不改列）
            "audit_logs",
            // V19 · 骨架组：组织架构 / 系统参数 / 单号规则 / 登录日志
            "sys_department", "sys_position", "sys_config",
            "sys_number_rule", "sys_login_log"));

    /**
     * 在查询里**可能出现但不需要改写**的表。
     *
     * <p>用于 {@code TenantSqlGuardTest} 的反向断言：一条 SQL 命中了这些表名
     * 而不带租户谓词时，应当被<b>豁免</b>而不是被误报。
     */
    public static final Set<String> EXEMPT = Set.of(
            "tenants", "flyway_schema_history");

    private TenantTables() {
    }

    /** 大小写不敏感地判断是否业务表（SQL 里既有大写也有小写）。 */
    public static boolean isBusinessTable(String identifier) {
        return identifier != null && ALL.contains(identifier.toLowerCase(java.util.Locale.ROOT));
    }

    /** 诊断：清单的稳定快照（测试里用来对比库里的真实列）。 */
    public static Set<String> snapshot() {
        return new LinkedHashSet<>(ALL);
    }
}
