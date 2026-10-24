package com.erp.tenant;

import com.erp.shared.tenant.TenantContext;
import com.erp.support.TestDataFactory;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 一个租户的**完整一套读资源**（主数据 / 单据 / 库存 / 审计 / 账期）。
 *
 * <p><b>为什么两个租户刻意用同一批编码</b>：{@code TNT-WH}、{@code PO-TNT-0001}
 * 在两个租户各存在一行 —— 这正是 V16 把唯一约束改成 {@code (tenant_id, …)}
 * 之后要证明的事：同编码可共存，且互相不可见。若两个租户的编码不同，
 * "查不到"可能只是因为"根本没有这个编码"，断言会变成恒真。
 *
 * <p><b>每个 id 都是"真实存在但属于另一个租户"</b>：跨租户矩阵的全部请求
 * 都用这里的 id，而不是随手编一个不存在的 id —— 后者只能证明"接口会 404"。
 */
final class TenantFixture {

    final long tenantId;
    final String poNo;
    final String soNo;
    final String roNo;
    final String period = "2026-03";
    final String traceId;

    long warehouse;
    long product;
    long sku;
    long supplier;
    long customer;
    long purchaseOrder;
    long purchaseOrderLine;
    long salesOrder;
    long salesOrderLine;
    long returnOrder;
    long auditId;

    private TenantFixture(long tenantId) {
        this.tenantId = tenantId;
        this.poNo = "PO-TNT-0001";
        this.soNo = "SO-TNT-0001";
        this.roNo = "RO-TNT-0001";
        this.traceId = "trace-tnt-" + tenantId;
    }

    static TenantFixture create(JdbcTemplate jdbc, TestDataFactory data, long tenantId,
                                String periodStatus) {
        return TenantContext.callIn(tenantId, () -> {
            TenantFixture f = new TenantFixture(tenantId);
            TestDataFactory d = data.inTenant(tenantId);

            f.warehouse = d.warehouse("TNT-WH");
            f.product = d.product("TNT-P", "跨租户矩阵商品");
            f.sku = d.sku(f.product, "黑色", "M");
            f.supplier = d.supplier("TNT-SUP");
            f.customer = d.customer("TNT-CUS");
            int qty = tenantId == 1L ? 11 : 22;
            long amount = tenantId == 1L ? 111_100L : 222_200L;
            int onHand = tenantId == 1L ? 100 : 40;
            d.stock(f.sku, f.warehouse, "TNT-B1", null, onHand);

            // 进销存报表按流水 occurred_at 归属：显式写 2026-03，不用 now()
            jdbc.update("""
                    INSERT INTO inventory_ledger
                        (sku_id, warehouse_id, batch_no, change_qty, biz_type,
                         biz_ref_no, occurred_at, tenant_id)
                    VALUES (?, ?, 'TNT-B1', ?, 'PURCHASE_IN', ?, ?, ?)
                    """, f.sku, f.warehouse, onHand, f.poNo,
                    OffsetDateTime.of(2026, 3, 10, 10, 0, 0, 0, ZoneOffset.ofHours(8)),
                    tenantId);

            jdbc.update("""
                    INSERT INTO purchase_orders
                        (order_no, status, supplier_id, warehouse_id, biz_date,
                         total_amount, total_qty, period, created_by, tenant_id)
                    VALUES (?, 'DRAFT', ?, ?, ?, ?, ?, ?, NULL, ?)
                    """, f.poNo, f.supplier, f.warehouse, LocalDate.of(2026, 3, 10),
                    amount, qty, f.period, tenantId);
            f.purchaseOrder = jdbc.queryForObject(
                    "SELECT id FROM purchase_orders WHERE order_no = ? AND tenant_id = ?",
                    Long.class, f.poNo, tenantId);
            jdbc.update("""
                    INSERT INTO purchase_order_lines
                        (order_id, line_no, sku_id, qty, received_qty,
                         unit_price_amount, amount, tenant_id)
                    VALUES (?, 1, ?, ?, 0, ?, ?, ?)
                    """, f.purchaseOrder, f.sku, qty, 10_100L, amount, tenantId);
            f.purchaseOrderLine = jdbc.queryForObject(
                    "SELECT id FROM purchase_order_lines WHERE order_id = ?",
                    Long.class, f.purchaseOrder);

            jdbc.update("""
                    INSERT INTO sales_orders
                        (order_no, status, customer_id, warehouse_id, biz_date,
                         total_amount, total_qty, period, created_by, tenant_id)
                    VALUES (?, 'DRAFT', ?, ?, ?, ?, ?, ?, NULL, ?)
                    """, f.soNo, f.customer, f.warehouse, LocalDate.of(2026, 3, 10),
                    amount, qty, f.period, tenantId);
            f.salesOrder = jdbc.queryForObject(
                    "SELECT id FROM sales_orders WHERE order_no = ? AND tenant_id = ?",
                    Long.class, f.soNo, tenantId);
            jdbc.update("""
                    INSERT INTO sales_order_lines
                        (order_id, line_no, sku_id, qty, shipped_qty, unit_price_amount,
                         discount_rate, amount, tenant_id)
                    VALUES (?, 1, ?, ?, 0, ?, 10000, ?, ?)
                    """, f.salesOrder, f.sku, qty, 10_100L, amount, tenantId);
            f.salesOrderLine = jdbc.queryForObject(
                    "SELECT id FROM sales_order_lines WHERE order_id = ?",
                    Long.class, f.salesOrder);

            jdbc.update("""
                    INSERT INTO return_orders
                        (order_no, status, origin_sales_order_id, customer_id, warehouse_id,
                         biz_date, total_amount, total_qty, period, created_by, tenant_id)
                    VALUES (?, 'DRAFT', ?, ?, ?, ?, ?, 1, ?, NULL, ?)
                    """, f.roNo, f.salesOrder, f.customer, f.warehouse,
                    LocalDate.of(2026, 3, 12), 10_100L, f.period, tenantId);
            f.returnOrder = jdbc.queryForObject(
                    "SELECT id FROM return_orders WHERE order_no = ? AND tenant_id = ?",
                    Long.class, f.roNo, tenantId);
            jdbc.update("""
                    INSERT INTO return_order_lines
                        (order_id, line_no, origin_line_id, sku_id, qty, unit_price_amount,
                         discount_rate, amount, tenant_id)
                    VALUES (?, 1, ?, ?, 1, ?, 10000, ?, ?)
                    """, f.returnOrder, f.salesOrderLine, f.sku, 10_100L, 10_100L, tenantId);

            // 账期：租户 1 = CLOSED，租户 2 = OPEN。跨租户查询用 status 做可区分的探针
            jdbc.update("""
                    INSERT INTO settlement_periods
                        (period, status, sales_amount, return_amount, net_amount, tenant_id)
                    VALUES (?, ?, ?, 0, ?, ?)
                    """, f.period, periodStatus, amount, amount, tenantId);

            // 审计：应用路径会由 TenantSql 补 tenant_id；这里显式写，等价于"真实产生的行"
            jdbc.update("""
                    INSERT INTO audit_logs
                        (actor_id, actor_name, action, resource_type, resource_id, biz_ref_no,
                         trace_id, occurred_at, result, tenant_id)
                    VALUES (NULL, ?, 'CREATE', 'PURCHASE_ORDER', ?, ?, ?, ?, 'SUCCESS', ?)
                    """, "审计-" + tenantId, String.valueOf(f.purchaseOrder), f.poNo,
                    f.traceId, OffsetDateTime.of(2026, 3, 10, 11, 0, 0, 0, ZoneOffset.ofHours(8)),
                    tenantId);
            f.auditId = jdbc.queryForObject(
                    "SELECT id FROM audit_logs WHERE trace_id = ? AND tenant_id = ?",
                    Long.class, f.traceId, tenantId);
            return f;
        });
    }
}
