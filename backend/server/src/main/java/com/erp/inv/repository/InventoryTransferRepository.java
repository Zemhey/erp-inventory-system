package com.erp.inv.repository;

import com.erp.inv.domain.InventoryTransfer;
import com.erp.inv.domain.TransferLine;
import com.erp.inv.domain.TransferStatus;
import com.erp.shared.web.PageResult;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** 调拨单仓储（FR-INV-07）。 */
@Repository
public class InventoryTransferRepository {

    private final JdbcTemplate jdbc;

    public InventoryTransferRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<TransferLine> LINE_MAPPER = (rs, i) -> new TransferLine(
            rs.getLong("id"),
            rs.getLong("transfer_id"),
            rs.getInt("line_no"),
            rs.getLong("sku_id"),
            rs.getString("batch_no"),
            rs.getInt("qty"),
            rs.getInt("in_transit_qty"),
            rs.getObject("production_date", LocalDate.class),
            rs.getObject("expiry_date", LocalDate.class),
            rs.getObject("unit_cost_amount", Long.class));

    private record Header(long id, String transferNo, long fromWarehouseId, long toWarehouseId,
                          TransferStatus status, LocalDate bizDate, String remark,
                          OffsetDateTime receivedAt, OffsetDateTime createdAt,
                          String requestId, String receiveRequestId) {
    }

    private static final RowMapper<Header> HEADER_MAPPER = (rs, i) -> new Header(
            rs.getLong("id"),
            rs.getString("transfer_no"),
            rs.getLong("from_warehouse_id"),
            rs.getLong("to_warehouse_id"),
            TransferStatus.valueOf(rs.getString("status")),
            rs.getObject("biz_date", LocalDate.class),
            rs.getString("remark"),
            rs.getObject("received_at", OffsetDateTime.class),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getString("request_id"),
            rs.getString("receive_request_id"));

    public Optional<InventoryTransfer> findById(long id) {
        return findHeader("WHERE id = ?", id).map(this::withLines);
    }

    public Optional<InventoryTransfer> findByRequestId(String requestId) {
        return findHeader("WHERE request_id = ?", requestId).map(this::withLines);
    }

    public String receiveRequestId(long id) {
        List<String> rows = jdbc.queryForList(
                "SELECT receive_request_id FROM inventory_transfers WHERE id = ?",
                String.class, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Optional<Header> findHeader(String where, Object arg) {
        List<Header> rows = jdbc.query("""
                SELECT id, transfer_no, from_warehouse_id, to_warehouse_id, status,
                       biz_date, remark, received_at, created_at, request_id, receive_request_id
                  FROM inventory_transfers
                """ + where, HEADER_MAPPER, arg);
        return rows.stream().findFirst();
    }

    private InventoryTransfer withLines(Header h) {
        return new InventoryTransfer(h.id(), h.transferNo(), h.fromWarehouseId(),
                h.toWarehouseId(), h.status(), h.bizDate(), h.remark(), h.receivedAt(),
                h.createdAt(), lines(h.id()));
    }

    public List<TransferLine> lines(long transferId) {
        return jdbc.query("""
                SELECT id, transfer_id, line_no, sku_id, batch_no, qty, in_transit_qty,
                       production_date, expiry_date, unit_cost_amount
                  FROM transfer_lines
                 WHERE transfer_id = ?
                 ORDER BY line_no
                """, LINE_MAPPER, transferId);
    }

    /**
     * 插入调拨单头。
     *
     * <p>用 {@code ON CONFLICT (request_id) DO NOTHING RETURNING id} 做并发幂等：
     * 两个持有相同幂等键的请求同时到达时，只有一个能拿到 id，
     * 另一个拿到空结果并直接返回首次建单结果（不变量 INV-9）。
     *
     * @return 新建单 id；幂等命中时返回 {@link Optional#empty()}
     */
    public Optional<Long> insertHeader(String transferNo, long fromWarehouseId, long toWarehouseId,
                                       LocalDate bizDate, String remark, String requestId) {
        List<Long> ids = jdbc.queryForList("""
                INSERT INTO inventory_transfers
                    (transfer_no, from_warehouse_id, to_warehouse_id, status, biz_date, remark, request_id)
                VALUES (?, ?, ?, 'IN_TRANSIT', ?, ?, ?)
                ON CONFLICT (request_id) WHERE request_id IS NOT NULL DO NOTHING
                RETURNING id
                """, Long.class, transferNo, fromWarehouseId, toWarehouseId, bizDate, remark, requestId);
        return ids.stream().findFirst();
    }

    public void insertLine(long transferId, int lineNo, long skuId, String batchNo, int qty,
                           LocalDate productionDate, LocalDate expiryDate, Long unitCostAmount) {
        jdbc.update("""
                INSERT INTO transfer_lines
                    (transfer_id, line_no, sku_id, batch_no, qty, in_transit_qty,
                     production_date, expiry_date, unit_cost_amount)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, transferId, lineNo, skuId, batchNo, qty, qty,
                productionDate, expiryDate, unitCostAmount);
    }

    /** 到货：结清所有明细的在途量（条件更新，重复调用不会产生副作用）。 */
    public int clearInTransit(long transferId) {
        return jdbc.update("""
                UPDATE transfer_lines SET in_transit_qty = 0
                 WHERE transfer_id = ? AND in_transit_qty > 0
                """, transferId);
    }

    /**
     * 标记到货（IN_TRANSIT → COMPLETED）。
     *
     * <p>条件更新保证并发下只有一个请求能把状态推进；
     * {@code receive_request_id} 的唯一索引是幂等键的数据库级保证。
     */
    public boolean markReceived(long id, String receiveRequestId, LocalDateTime receivedAt) {
        return jdbc.update("""
                UPDATE inventory_transfers
                   SET status = 'COMPLETED', receive_request_id = ?, received_at = ?,
                       updated_at = now()
                 WHERE id = ? AND status = 'IN_TRANSIT'
                """, receiveRequestId, receivedAt, id) == 1;
    }

    public PageResult<InventoryTransfer> page(TransferStatus status, Long fromWarehouseId,
                                               Long toWarehouseId, int page, int size) {
        int p = PageResult.normalizePage(page);
        int s = PageResult.normalizeSize(size);
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (status != null) {
            where.append(" AND status = ?");
            args.add(status.name());
        }
        if (fromWarehouseId != null) {
            where.append(" AND from_warehouse_id = ?");
            args.add(fromWarehouseId);
        }
        if (toWarehouseId != null) {
            where.append(" AND to_warehouse_id = ?");
            args.add(toWarehouseId);
        }
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_transfers" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(s);
        pageArgs.add(PageResult.offset(p, s));
        List<Header> headers = jdbc.query("""
                SELECT id, transfer_no, from_warehouse_id, to_warehouse_id, status,
                       biz_date, remark, received_at, created_at, request_id, receive_request_id
                  FROM inventory_transfers
                """ + where + " ORDER BY id DESC LIMIT ? OFFSET ?", HEADER_MAPPER, pageArgs.toArray());
        List<InventoryTransfer> items = headers.stream().map(this::withLines).toList();
        return new PageResult<>(items, total == null ? 0 : total, p, s);
    }

    /** 某单的源头流水净和（TRANSFER_OUT 为负、TRANSFER_IN 为正）。 */
    public int ledgerNet(String transferNo) {
        Integer v = jdbc.queryForObject("""
                SELECT COALESCE(SUM(change_qty), 0) FROM inventory_ledger
                 WHERE biz_ref_no = ? AND biz_type IN ('TRANSFER_OUT','TRANSFER_IN')
                """, Integer.class, transferNo);
        return v == null ? 0 : v;
    }

    public int inTransitQty(long transferId) {
        Integer v = jdbc.queryForObject("""
                SELECT COALESCE(SUM(in_transit_qty), 0) FROM transfer_lines WHERE transfer_id = ?
                """, Integer.class, transferId);
        return v == null ? 0 : v;
    }
}
