package com.erp.rma.repository;

import com.erp.rma.domain.ReturnOrder;
import com.erp.rma.domain.ReturnOrderLine;
import com.erp.shared.document.DocumentStatus;
import com.erp.shared.web.PageResult;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 退货单仓储（FR-RMA-01）。
 *
 * <p>与进货/出货仓储同构：乐观锁状态更新 + 部分唯一索引兜底幂等。
 * 差别在于"累计退货量"<b>不记在本域的行上</b>，而是记回出货明细行
 * （由 {@code SalesOrderReturnService} 负责），所以这里没有数量累计的原子方法。
 */
@Repository
public class ReturnOrderRepository {

    private static final String HEADER_COLUMNS =
            "id, order_no, status, origin_sales_order_id, customer_id, warehouse_id, biz_date, "
                    + "total_amount, total_qty, period, remark, version, receive_request_id";

    private static final RowMapper<ReturnOrder> ORDER_MAPPER = (rs, i) -> new ReturnOrder(
            rs.getLong("id"),
            rs.getString("order_no"),
            DocumentStatus.valueOf(rs.getString("status")),
            rs.getLong("origin_sales_order_id"),
            rs.getLong("customer_id"),
            rs.getLong("warehouse_id"),
            rs.getObject("biz_date", LocalDate.class),
            rs.getLong("total_amount"),
            rs.getInt("total_qty"),
            rs.getString("period"),
            rs.getString("remark"),
            rs.getInt("version"),
            rs.getString("receive_request_id"),
            List.of());   // 明细另行装载

    private static final RowMapper<ReturnOrderLine> LINE_MAPPER = (rs, i) -> new ReturnOrderLine(
            rs.getLong("id"),
            rs.getInt("line_no"),
            rs.getLong("origin_line_id"),
            rs.getLong("sku_id"),
            rs.getInt("qty"),
            rs.getLong("unit_price_amount"),
            rs.getInt("discount_rate"),
            rs.getLong("amount"),
            rs.getString("batch_no"),
            rs.getObject("production_date", LocalDate.class),
            rs.getObject("expiry_date", LocalDate.class));

    private final com.erp.shared.tenant.TenantAwareJdbcTemplate jdbc;

    /** 数据权限（FR-IAM-04）：仓库 + 本人；过滤在仓储层，出口唯一。 */
    private final com.erp.shared.scope.ScopeContext scope;

    public ReturnOrderRepository(com.erp.shared.tenant.TenantAwareJdbcTemplate jdbc, com.erp.shared.scope.ScopeContext scope) {
        this.jdbc = jdbc;
        this.scope = scope;
    }

    /** 插入主表。{@code createdBy} 供数据权限"按本人"判定，系统任务可为 null。 */
    public long insertHeader(String orderNo, long originSalesOrderId, long customerId,
                             long warehouseId, LocalDate bizDate, long totalAmount, int totalQty,
                             String period, String remark, String requestId, Long createdBy) {
        return com.erp.shared.tenant.TenantJdbc.insertReturningId(jdbc,
                """
                    INSERT INTO return_orders
                        (order_no, status, origin_sales_order_id, customer_id, warehouse_id,
                         biz_date, total_amount, total_qty, period, remark, request_id, created_by)
                    VALUES (?, 'DRAFT', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    RETURNING id
                    """,
                new Object[]{orderNo, originSalesOrderId, customerId, warehouseId, bizDate, totalAmount, totalQty, period, remark, requestId, createdBy});
    }

    public void insertLine(long orderId, int lineNo, long originLineId, long skuId, int qty,
                           long unitPriceAmount, int discountRate, long amount, String batchNo,
                           LocalDate productionDate, LocalDate expiryDate) {
        jdbc.update("""
                INSERT INTO return_order_lines
                    (order_id, line_no, origin_line_id, sku_id, qty,
                     unit_price_amount, discount_rate, amount, batch_no,
                     production_date, expiry_date)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, orderId, lineNo, originLineId, skuId, qty, unitPriceAmount, discountRate,
                amount, batchNo == null ? "" : batchNo, productionDate, expiryDate);
    }

    /**
     * 查主表，<b>并施加数据权限</b>：越权 id 查不出行 → 上层 404（不是 403）。
     */
    public Optional<ReturnOrder> findHeaderById(long id) {
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(id);
        String predicate = scope.predicates("warehouse_id", "created_by", args);
        return jdbc.query("SELECT " + HEADER_COLUMNS + " FROM return_orders WHERE id = ?"
                + predicate, ORDER_MAPPER, args.toArray()).stream().findFirst();
    }

    /** 建单幂等：同一 request_id 只能建成一张退货单（不变量 INV-9）。 */
    public Optional<ReturnOrder> findByRequestId(String requestId) {
        if (requestId == null) {
            return Optional.empty();
        }
        return jdbc.query("SELECT " + HEADER_COLUMNS + " FROM return_orders WHERE request_id = ?",
                ORDER_MAPPER, requestId).stream().findFirst();
    }

    public List<ReturnOrderLine> findLines(long orderId) {
        return jdbc.query("""
                SELECT id, line_no, origin_line_id, sku_id, qty, unit_price_amount,
                       discount_rate, amount, batch_no, production_date, expiry_date
                  FROM return_order_lines WHERE order_id = ? ORDER BY line_no
                """, LINE_MAPPER, orderId);
    }

    public Optional<ReturnOrder> findById(long id) {
        return findHeaderById(id).map(h -> withLines(h, findLines(id)));
    }

    /**
     * 带乐观锁的状态更新。
     *
     * @return 是否更新成功（false = 版本冲突或状态已被他人改动）
     */
    public boolean updateStatus(long id, DocumentStatus from, DocumentStatus to, int expectedVersion) {
        return jdbc.update("""
                UPDATE return_orders
                   SET status = ?, version = version + 1, updated_at = now()
                 WHERE id = ? AND status = ? AND version = ?
                """, to.name(), id, from.name(), expectedVersion) == 1;
    }

    /**
     * 一步完成"标记入库幂等键 + 推进到 COMPLETED"。
     *
     * <p>两件事必须同一条 UPDATE：若先写幂等键再改状态，中间崩溃会留下
     * "有入库标记但库存没回补"的单子；若先改状态再写标记，重试请求会看到
     * COMPLETED 却不知道是哪次入库，幂等就无从判断。
     *
     * @return 是否更新成功（false = 并发冲突）
     */
    public boolean completeReceive(long id, DocumentStatus from, int expectedVersion,
                                   String receiveRequestId) {
        return jdbc.update("""
                UPDATE return_orders
                   SET status = 'COMPLETED', receive_request_id = ?,
                       version = version + 1, updated_at = now()
                 WHERE id = ? AND status = ? AND version = ?
                """, receiveRequestId, id, from.name(), expectedVersion) == 1;
    }

    public PageResult<ReturnOrder> page(DocumentStatus status, Long customerId,
                                        Long originSalesOrderId, int page, int size) {
        int p = PageResult.normalizePage(page);
        int s = PageResult.normalizeSize(size);
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        if (status != null) {
            where.append(" AND status = ?");
            args.add(status.name());
        }
        if (customerId != null) {
            where.append(" AND customer_id = ?");
            args.add(customerId);
        }
        if (originSalesOrderId != null) {
            where.append(" AND origin_sales_order_id = ?");
            args.add(originSalesOrderId);
        }
        where.append(scope.predicates("warehouse_id", "created_by", args));
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM return_orders" + where,
                Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(s);
        pageArgs.add(PageResult.offset(p, s));
        List<ReturnOrder> headers = jdbc.query("SELECT " + HEADER_COLUMNS
                + " FROM return_orders" + where + " ORDER BY id DESC LIMIT ? OFFSET ?",
                ORDER_MAPPER, pageArgs.toArray());
        List<ReturnOrder> items = headers.stream()
                .map(h -> withLines(h, findLines(h.id())))
                .toList();
        return new PageResult<>(items, total == null ? 0 : total, p, s);
    }

    private static ReturnOrder withLines(ReturnOrder h, List<ReturnOrderLine> lines) {
        return new ReturnOrder(h.id(), h.orderNo(), h.status(), h.originSalesOrderId(),
                h.customerId(), h.warehouseId(), h.bizDate(), h.totalAmount(), h.totalQty(),
                h.period(), h.remark(), h.version(), h.receiveRequestId(), lines);
    }
}
