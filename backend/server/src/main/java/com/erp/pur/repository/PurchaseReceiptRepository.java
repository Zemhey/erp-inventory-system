package com.erp.pur.repository;

import com.erp.pur.domain.PurchaseReceipt;
import com.erp.pur.domain.PurchaseReceiptLine;
import com.erp.pur.domain.QcStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 到货/质检仓储（FR-PUR-05）。
 *
 * <p>到货单是"到货量 / 合格量 / 不合格量"的事实记录；
 * 应付与库存都只能从这里的合格量派生。
 */
@Repository
public class PurchaseReceiptRepository {

    private static final RowMapper<PurchaseReceipt> HEADER_MAPPER = (rs, i) -> new PurchaseReceipt(
            rs.getLong("id"),
            rs.getString("receipt_no"),
            rs.getLong("purchase_order_id"),
            rs.getLong("supplier_id"),
            rs.getLong("warehouse_id"),
            rs.getObject("biz_date", LocalDate.class),
            rs.getString("period"),
            rs.getInt("total_qty"),
            rs.getInt("accepted_qty"),
            rs.getInt("rejected_qty"),
            rs.getLong("accepted_amount"),
            List.of());

    private static final RowMapper<PurchaseReceiptLine> LINE_MAPPER = (rs, i) ->
            new PurchaseReceiptLine(
                    rs.getLong("id"),
                    rs.getLong("receipt_id"),
                    rs.getInt("line_no"),
                    rs.getLong("purchase_order_line_id"),
                    rs.getLong("sku_id"),
                    rs.getString("batch_no"),
                    rs.getObject("production_date", LocalDate.class),
                    rs.getObject("expiry_date", LocalDate.class),
                    rs.getInt("received_qty"),
                    rs.getInt("qc_qty"),
                    rs.getInt("rejected_qty"),
                    rs.getLong("unit_price_amount"),
                    rs.getLong("accepted_amount"),
                    QcStatus.valueOf(rs.getString("qc_status")),
                    rs.getString("qc_note"));

    private final JdbcTemplate jdbc;

    public PurchaseReceiptRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insertHeader(String receiptNo, long purchaseOrderId, long supplierId,
                             long warehouseId, LocalDate bizDate, String period,
                             int totalQty, int acceptedQty, int rejectedQty,
                             long acceptedAmount, String requestId,
                             java.time.LocalDateTime receivedAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO po_receipts
                        (receipt_no, purchase_order_id, supplier_id, warehouse_id, biz_date,
                         period, total_qty, accepted_qty, rejected_qty, accepted_amount,
                         request_id, received_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, new String[]{"id"});
            ps.setString(1, receiptNo);
            ps.setLong(2, purchaseOrderId);
            ps.setLong(3, supplierId);
            ps.setLong(4, warehouseId);
            ps.setObject(5, bizDate);
            ps.setString(6, period);
            ps.setInt(7, totalQty);
            ps.setInt(8, acceptedQty);
            ps.setInt(9, rejectedQty);
            ps.setLong(10, acceptedAmount);
            ps.setString(11, requestId);
            ps.setObject(12, receivedAt);
            return ps;
        }, keys);
        Number id = keys.getKey();
        if (id == null) {
            throw new IllegalStateException("插入到货单后未取得主键：receiptNo=" + receiptNo);
        }
        return id.longValue();
    }

    public void insertLine(long receiptId, int lineNo, long purchaseOrderLineId, long skuId,
                           String batchNo, LocalDate productionDate, LocalDate expiryDate,
                           int receivedQty, int qcQty, int rejectedQty,
                           long unitPriceAmount, long acceptedAmount,
                           QcStatus qcStatus, String qcNote) {
        jdbc.update("""
                INSERT INTO po_receipt_lines
                    (receipt_id, line_no, purchase_order_line_id, sku_id, batch_no,
                     production_date, expiry_date, received_qty, qc_qty, rejected_qty,
                     unit_price_amount, accepted_amount, qc_status, qc_note)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, receiptId, lineNo, purchaseOrderLineId, skuId, batchNo,
                productionDate, expiryDate, receivedQty, qcQty, rejectedQty,
                unitPriceAmount, acceptedAmount, qcStatus.name(), qcNote);
    }

    public Optional<PurchaseReceipt> findByRequestId(String requestId) {
        return jdbc.query("""
                SELECT id, receipt_no, purchase_order_id, supplier_id, warehouse_id, biz_date,
                       period, total_qty, accepted_qty, rejected_qty, accepted_amount
                  FROM po_receipts WHERE request_id = ?
                """, HEADER_MAPPER, requestId).stream().findFirst();
    }

    public Optional<PurchaseReceipt> findById(long id) {
        return jdbc.query("""
                SELECT id, receipt_no, purchase_order_id, supplier_id, warehouse_id, biz_date,
                       period, total_qty, accepted_qty, rejected_qty, accepted_amount
                  FROM po_receipts WHERE id = ?
                """, HEADER_MAPPER, id).stream().findFirst()
                .map(h -> new PurchaseReceipt(h.id(), h.receiptNo(), h.purchaseOrderId(),
                        h.supplierId(), h.warehouseId(), h.bizDate(), h.period(), h.totalQty(),
                        h.acceptedQty(), h.rejectedQty(), h.acceptedAmount(), findLines(h.id())));
    }

    public List<PurchaseReceipt> findByOrderId(long purchaseOrderId) {
        List<PurchaseReceipt> headers = jdbc.query("""
                SELECT id, receipt_no, purchase_order_id, supplier_id, warehouse_id, biz_date,
                       period, total_qty, accepted_qty, rejected_qty, accepted_amount
                  FROM po_receipts WHERE purchase_order_id = ? ORDER BY id
                """, HEADER_MAPPER, purchaseOrderId);
        return headers.stream()
                .map(h -> new PurchaseReceipt(h.id(), h.receiptNo(), h.purchaseOrderId(),
                        h.supplierId(), h.warehouseId(), h.bizDate(), h.period(), h.totalQty(),
                        h.acceptedQty(), h.rejectedQty(), h.acceptedAmount(), findLines(h.id())))
                .toList();
    }

    public List<PurchaseReceiptLine> findLines(long receiptId) {
        return jdbc.query("""
                SELECT id, receipt_id, line_no, purchase_order_line_id, sku_id, batch_no,
                       production_date, expiry_date, received_qty, qc_qty, rejected_qty,
                       unit_price_amount, accepted_amount, qc_status, qc_note
                  FROM po_receipt_lines WHERE receipt_id = ? ORDER BY line_no
                """, LINE_MAPPER, receiptId);
    }

    public Optional<PurchaseReceiptLine> findLineById(long id) {
        return jdbc.query("""
                SELECT id, receipt_id, line_no, purchase_order_line_id, sku_id, batch_no,
                       production_date, expiry_date, received_qty, qc_qty, rejected_qty,
                       unit_price_amount, accepted_amount, qc_status, qc_note
                  FROM po_receipt_lines WHERE id = ?
                """, LINE_MAPPER, id).stream().findFirst();
    }
}
