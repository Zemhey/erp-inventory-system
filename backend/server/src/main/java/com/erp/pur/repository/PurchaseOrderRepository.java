package com.erp.pur.repository;

import com.erp.pur.domain.PurchaseOrder;
import com.erp.pur.domain.PurchaseOrderLine;
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
 * 进货单仓储（FR-PUR-01/02）。
 *
 * <p><b>乐观锁</b>：所有会改状态的 UPDATE 都带 {@code version = ?} 条件，
 * 更新 0 行即表示"别人抢先改了这张单"，由应用层翻译成业务冲突。
 * 这是审核/入库两个动作并发时的护栏 —— 没有它，两次"入库确认"
 * 可能各自读到 received_qty=0 然后都写 5，实际入库 5 但账面说 10。
 */
@Repository
public class PurchaseOrderRepository {

    private static final RowMapper<PurchaseOrder> ORDER_MAPPER = (rs, i) -> new PurchaseOrder(
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
            List.of());   // 明细另行装载

    private static final RowMapper<PurchaseOrderLine> LINE_MAPPER = (rs, i) -> new PurchaseOrderLine(
            rs.getLong("id"),
            rs.getInt("line_no"),
            rs.getLong("sku_id"),
            rs.getInt("qty"),
            rs.getInt("received_qty"),
            rs.getLong("unit_price_amount"),
            rs.getLong("amount"));

    private final JdbcTemplate jdbc;

    public PurchaseOrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 插入主表，返回新 id。 */
    public long insertHeader(String orderNo, long supplierId, long warehouseId, LocalDate bizDate,
                             long totalAmount, int totalQty, String period, String remark,
                             String requestId) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO purchase_orders
                        (order_no, status, supplier_id, warehouse_id, biz_date,
                         total_amount, total_qty, period, remark, request_id)
                    VALUES (?, 'DRAFT', ?, ?, ?, ?, ?, ?, ?, ?)
                    """, new String[]{"id"});
            ps.setString(1, orderNo);
            ps.setLong(2, supplierId);
            ps.setLong(3, warehouseId);
            ps.setObject(4, bizDate);
            ps.setLong(5, totalAmount);
            ps.setInt(6, totalQty);
            ps.setString(7, period);
            ps.setString(8, remark);
            ps.setString(9, requestId);
            return ps;
        }, keys);
        Number id = keys.getKey();
        if (id == null) {
            throw new IllegalStateException("插入进货单后未取得主键：orderNo=" + orderNo);
        }
        return id.longValue();
    }

    public void insertLine(long orderId, int lineNo, long skuId, int qty,
                           long unitPriceAmount, long amount) {
        jdbc.update("""
                INSERT INTO purchase_order_lines
                    (order_id, line_no, sku_id, qty, received_qty, unit_price_amount, amount)
                VALUES (?, ?, ?, ?, 0, ?, ?)
                """, orderId, lineNo, skuId, qty, unitPriceAmount, amount);
    }

    /** 查主表（不含明细）。 */
    public Optional<PurchaseOrder> findHeaderById(long id) {
        return jdbc.query("""
                SELECT id, order_no, status, supplier_id, warehouse_id, biz_date,
                       total_amount, total_qty, period, remark, version
                  FROM purchase_orders WHERE id = ?
                """, ORDER_MAPPER, id).stream().findFirst();
    }

    public Optional<PurchaseOrder> findHeaderByNo(String orderNo) {
        return jdbc.query("""
                SELECT id, order_no, status, supplier_id, warehouse_id, biz_date,
                       total_amount, total_qty, period, remark, version
                  FROM purchase_orders WHERE order_no = ?
                """, ORDER_MAPPER, orderNo).stream().findFirst();
    }

    public Optional<PurchaseOrder> findByRequestId(String requestId) {
        return jdbc.query("""
                SELECT id, order_no, status, supplier_id, warehouse_id, biz_date,
                       total_amount, total_qty, period, remark, version
                  FROM purchase_orders WHERE request_id = ?
                """, ORDER_MAPPER, requestId).stream().findFirst();
    }

    public List<PurchaseOrderLine> findLines(long orderId) {
        return jdbc.query("""
                SELECT id, line_no, sku_id, qty, received_qty, unit_price_amount, amount
                  FROM purchase_order_lines WHERE order_id = ? ORDER BY line_no
                """, LINE_MAPPER, orderId);
    }

    /** 查主表并装载明细。 */
    public Optional<PurchaseOrder> findById(long id) {
        return findHeaderById(id).map(h -> new PurchaseOrder(h.id(), h.orderNo(), h.status(),
                h.supplierId(), h.warehouseId(), h.bizDate(), h.totalAmount(), h.totalQty(),
                h.period(), h.remark(), h.version(), findLines(id)));
    }

    /**
     * 带乐观锁的状态更新。
     *
     * @return 是否更新成功（false = 版本冲突或状态已被他人改动）
     */
    public boolean updateStatus(long id, DocumentStatus from, DocumentStatus to, int expectedVersion) {
        return jdbc.update("""
                UPDATE purchase_orders
                   SET status = ?, version = version + 1, updated_at = now()
                 WHERE id = ? AND status = ? AND version = ?
                """, to.name(), id, from.name(), expectedVersion) == 1;
    }

    /**
     * 作废/红冲的状态推进（M2.5）：同一语句写入幂等键。
     *
     * <p>「状态 + version」是并发串行化点：两个作废请求只有一个能成功；
     * 后到者重读后按同键/异键分别做幂等返回或状态非法。
     */
    public boolean markVoided(long id, DocumentStatus from, int expectedVersion, String requestId) {
        return jdbc.update("""
                UPDATE purchase_orders
                   SET status = 'VOIDED', void_request_id = ?,
                       version = version + 1, updated_at = now()
                 WHERE id = ? AND status = ? AND version = ?
                """, requestId, id, from.name(), expectedVersion) == 1;
    }

    /** 该单是否由指定幂等键作废（同键重放判据）。 */
    public boolean isVoidedByRequest(long id, String requestId) {
        Boolean v = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM purchase_orders
                                WHERE id = ? AND status = 'VOIDED' AND void_request_id = ?)
                """, Boolean.class, id, requestId);
        return Boolean.TRUE.equals(v);
    }

    /**
     * 推进单行累计入库量，并<b>原子地</b>保证不超量（BR-DOC-04）。
     *
     * <p>条件 {@code received_qty + ? <= qty} 在数据库行锁下求值：
     * 两个并发入库请求不会都通过 —— 后到者的条件求值为假，更新 0 行。
     * 这与库存预占的防超卖是同一套思路（见 {@code InventoryBalanceRepository.tryReserve}）。
     *
     * @return 是否更新成功（false = 超量或行不存在）
     */
    public boolean applyReceivedQty(long lineId, int qty) {
        return jdbc.update("""
                UPDATE purchase_order_lines
                   SET received_qty = received_qty + ?
                 WHERE id = ? AND received_qty + ? <= qty
                """, qty, lineId, qty) == 1;
    }

    public PageResult<PurchaseOrder> page(DocumentStatus status, Long supplierId, int page, int size) {
        int p = PageResult.normalizePage(page);
        int s = PageResult.normalizeSize(size);
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
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
        List<PurchaseOrder> headers = jdbc.query("""
                SELECT id, order_no, status, supplier_id, warehouse_id, biz_date,
                       total_amount, total_qty, period, remark, version
                  FROM purchase_orders
                """ + where + " ORDER BY id DESC LIMIT ? OFFSET ?", ORDER_MAPPER, pageArgs.toArray());
        List<PurchaseOrder> items = headers.stream()
                .map(h -> new PurchaseOrder(h.id(), h.orderNo(), h.status(), h.supplierId(),
                        h.warehouseId(), h.bizDate(), h.totalAmount(), h.totalQty(),
                        h.period(), h.remark(), h.version(), findLines(h.id())))
                .toList();
        return new PageResult<>(items, total == null ? 0 : total, p, s);
    }
}
