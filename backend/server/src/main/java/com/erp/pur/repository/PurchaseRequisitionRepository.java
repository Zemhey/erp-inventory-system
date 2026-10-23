package com.erp.pur.repository;

import com.erp.pur.domain.PurchaseRequisition;
import com.erp.shared.document.DocumentStatus;
import com.erp.shared.web.PageResult;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 请购单仓储（FR-PUR-04）。
 *
 * <p>请购单与进货单共用 {@code purchase_orders} 表，靠 {@code doc_type} 区分；
 * 本仓储所有查询都固定 {@code doc_type='REQUISITION'}，不会看见进货单。
 * 明细行由 {@link PurchaseOrderRepository#findLines(long)} 装载。
 */
@Repository
public class PurchaseRequisitionRepository {

    private static final RowMapper<PurchaseRequisition> MAPPER = (rs, i) -> new PurchaseRequisition(
            rs.getLong("id"),
            rs.getString("order_no"),
            DocumentStatus.valueOf(rs.getString("status")),
            rs.getLong("supplier_id"),
            rs.getLong("warehouse_id"),
            rs.getObject("biz_date", LocalDate.class),
            rs.getLong("total_amount"),
            rs.getInt("total_qty"),
            rs.getString("period"),
            rs.getString("remark"),
            rs.getInt("version"),
            rs.getObject("converted_order_id", Long.class),
            List.of());

    private final JdbcTemplate jdbc;
    private final PurchaseOrderRepository orders;

    public PurchaseRequisitionRepository(JdbcTemplate jdbc, PurchaseOrderRepository orders) {
        this.jdbc = jdbc;
        this.orders = orders;
    }

    public long insertHeader(String orderNo, long supplierId, long warehouseId, LocalDate bizDate,
                             long totalAmount, int totalQty, String period, String remark,
                             String requestId) {
        return orders.insertHeader(orderNo, supplierId, warehouseId, bizDate, totalAmount, totalQty,
                period, remark, requestId, "REQUISITION", null);
    }

    public void insertLine(long orderId, int lineNo, long skuId, int qty,
                           long unitPriceAmount, long amount) {
        orders.insertLine(orderId, lineNo, skuId, qty, unitPriceAmount, amount);
    }

    public Optional<PurchaseRequisition> findById(long id) {
        return findHeaderById(id).map(h -> withLines(h, orders.findLines(id)));
    }

    public Optional<PurchaseRequisition> findByRequestId(String requestId) {
        return jdbc.query("""
                SELECT id, order_no, status, supplier_id, warehouse_id, biz_date,
                       total_amount, total_qty, period, remark, version, converted_order_id
                  FROM purchase_orders
                 WHERE request_id = ? AND doc_type = 'REQUISITION'
                """, MAPPER, requestId).stream().findFirst()
                .map(h -> withLines(h, orders.findLines(h.id())));
    }

    public Optional<PurchaseRequisition> findByConversionRequestId(String requestId) {
        return jdbc.query("""
                SELECT id, order_no, status, supplier_id, warehouse_id, biz_date,
                       total_amount, total_qty, period, remark, version, converted_order_id
                  FROM purchase_orders
                 WHERE conversion_request_id = ? AND doc_type = 'REQUISITION'
                """, MAPPER, requestId).stream().findFirst()
                .map(h -> withLines(h, orders.findLines(h.id())));
    }

    private Optional<PurchaseRequisition> findHeaderById(long id) {
        return jdbc.query("""
                SELECT id, order_no, status, supplier_id, warehouse_id, biz_date,
                       total_amount, total_qty, period, remark, version, converted_order_id
                  FROM purchase_orders
                 WHERE id = ? AND doc_type = 'REQUISITION'
                """, MAPPER, id).stream().findFirst();
    }

    private static PurchaseRequisition withLines(PurchaseRequisition h,
                                                 List<com.erp.pur.domain.PurchaseOrderLine> lines) {
        return new PurchaseRequisition(h.id(), h.orderNo(), h.status(), h.supplierId(),
                h.warehouseId(), h.bizDate(), h.totalAmount(), h.totalQty(), h.period(),
                h.remark(), h.version(), h.convertedOrderId(), lines);
    }

    public boolean updateStatus(long id, DocumentStatus from, DocumentStatus to, int version) {
        return jdbc.update("""
                UPDATE purchase_orders
                   SET status = ?, version = version + 1, updated_at = now()
                 WHERE id = ? AND status = ? AND version = ? AND doc_type = 'REQUISITION'
                """, to.name(), id, from.name(), version) == 1;
    }

    /**
     * 标记已转化：状态 → COMPLETED 并写回订单 id 与幂等键。
     *
     * <p>条件更新是并发转化的串行化点；唯一索引 {@code uq_po_conversion_request}
     * 与 {@code uq_po_converted_order} 是数据库级兜底。
     */
    public boolean markConverted(long id, int expectedVersion, long convertedOrderId,
                                 String requestId) {
        return jdbc.update("""
                UPDATE purchase_orders
                   SET status = 'COMPLETED', converted_order_id = ?, conversion_request_id = ?,
                       converted_at = now(), version = version + 1, updated_at = now()
                 WHERE id = ? AND status = 'APPROVED' AND version = ?
                   AND doc_type = 'REQUISITION' AND converted_order_id IS NULL
                """, convertedOrderId, requestId, id, expectedVersion) == 1;
    }

    /** 同键重放判据：该请购单是否由指定幂等键完成转化。 */
    public boolean isConvertedByRequest(long id, String requestId) {
        Boolean v = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM purchase_orders
                                WHERE id = ? AND doc_type = 'REQUISITION'
                                  AND converted_order_id IS NOT NULL
                                  AND conversion_request_id = ?)
                """, Boolean.class, id, requestId);
        return Boolean.TRUE.equals(v);
    }

    public PageResult<PurchaseRequisition> page(DocumentStatus status, Long supplierId,
                                                int page, int size) {
        int p = PageResult.normalizePage(page);
        int s = PageResult.normalizeSize(size);
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE doc_type = 'REQUISITION'");
        if (status != null) {
            where.append(" AND status = ?");
            args.add(status.name());
        }
        if (supplierId != null) {
            where.append(" AND supplier_id = ?");
            args.add(supplierId);
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM purchase_orders" + where,
                Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(s);
        pageArgs.add(PageResult.offset(p, s));
        List<PurchaseRequisition> headers = jdbc.query("""
                SELECT id, order_no, status, supplier_id, warehouse_id, biz_date,
                       total_amount, total_qty, period, remark, version, converted_order_id
                  FROM purchase_orders
                """ + where + " ORDER BY id DESC LIMIT ? OFFSET ?", MAPPER, pageArgs.toArray());
        List<PurchaseRequisition> items = headers.stream()
                .map(h -> withLines(h, orders.findLines(h.id())))
                .toList();
        return new PageResult<>(items, total == null ? 0 : total, p, s);
    }
}
