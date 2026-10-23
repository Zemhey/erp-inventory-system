package com.erp.sal.repository;

import com.erp.sal.domain.LineReservation;
import com.erp.sal.domain.SalesOrder;
import com.erp.sal.domain.SalesOrderLine;
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
 * 出货单仓储（FR-SAL-01/02）。
 *
 * <p>与进货单仓储同构（乐观锁 + 原子累计量守卫），差别在于：
 * 出货的"累计量"是 {@code shipped_qty}，且出货确认要配合预占与 FEFO 扣减。
 */
@Repository
public class SalesOrderRepository {

    private static final RowMapper<SalesOrder> ORDER_MAPPER = (rs, i) -> new SalesOrder(
            rs.getLong("id"),
            rs.getString("order_no"),
            DocumentStatus.valueOf(rs.getString("status")),
            rs.getLong("customer_id"),
            rs.getLong("warehouse_id"),
            rs.getObject("biz_date", LocalDate.class),
            rs.getLong("total_amount"),
            rs.getInt("total_qty"),
            rs.getString("period"),
            rs.getString("remark"),
            rs.getInt("version"),
            List.of());

    private static final RowMapper<SalesOrderLine> LINE_MAPPER = (rs, i) -> new SalesOrderLine(
            rs.getLong("id"),
            rs.getInt("line_no"),
            rs.getLong("sku_id"),
            rs.getInt("qty"),
            rs.getInt("shipped_qty"),
            rs.getInt("returned_qty"),
            rs.getLong("unit_price_amount"),
            rs.getInt("discount_rate"),
            rs.getLong("amount"),
            List.of());   // 预占分段另行装载

    private final JdbcTemplate jdbc;

    public SalesOrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insertHeader(String orderNo, long customerId, long warehouseId, LocalDate bizDate,
                             long totalAmount, int totalQty, String period, String remark,
                             String requestId) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO sales_orders
                        (order_no, status, customer_id, warehouse_id, biz_date,
                         total_amount, total_qty, period, remark, request_id)
                    VALUES (?, 'DRAFT', ?, ?, ?, ?, ?, ?, ?, ?)
                    """, new String[]{"id"});
            ps.setString(1, orderNo);
            ps.setLong(2, customerId);
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
            throw new IllegalStateException("插入出货单后未取得主键：orderNo=" + orderNo);
        }
        return id.longValue();
    }

    public void insertLine(long orderId, int lineNo, long skuId, int qty,
                           long unitPriceAmount, int discountRate, long amount) {
        jdbc.update("""
                INSERT INTO sales_order_lines
                    (order_id, line_no, sku_id, qty, shipped_qty,
                     unit_price_amount, discount_rate, amount)
                VALUES (?, ?, ?, ?, 0, ?, ?, ?)
                """, orderId, lineNo, skuId, qty, unitPriceAmount, discountRate, amount);
    }

    /** 记录一段预占（一行出货可有多段）。 */
    public void attachReservation(long lineId, long reservationId, int seq, int reservedQty) {
        jdbc.update("""
                INSERT INTO sales_order_line_reservations
                    (line_id, reservation_id, seq, reserved_qty)
                VALUES (?, ?, ?, ?)
                """, lineId, reservationId, seq, reservedQty);
    }

    /** 某明细行的全部预占分段，按段序号排序。 */
    public List<LineReservation> findReservations(long lineId) {
        return jdbc.query("""
                SELECT reservation_id, seq, reserved_qty
                  FROM sales_order_line_reservations
                 WHERE line_id = ? ORDER BY seq
                """, (rs, i) -> new LineReservation(
                        rs.getLong("reservation_id"), rs.getInt("seq"), rs.getInt("reserved_qty")),
                lineId);
    }

    public Optional<SalesOrder> findHeaderById(long id) {
        return jdbc.query("""
                SELECT id, order_no, status, customer_id, warehouse_id, biz_date,
                       total_amount, total_qty, period, remark, version
                  FROM sales_orders WHERE id = ?
                """, ORDER_MAPPER, id).stream().findFirst();
    }

    public Optional<SalesOrder> findByRequestId(String requestId) {
        return jdbc.query("""
                SELECT id, order_no, status, customer_id, warehouse_id, biz_date,
                       total_amount, total_qty, period, remark, version
                  FROM sales_orders WHERE request_id = ?
                """, ORDER_MAPPER, requestId).stream().findFirst();
    }

    public List<SalesOrderLine> findLines(long orderId) {
        List<SalesOrderLine> rows = jdbc.query("""
                SELECT id, line_no, sku_id, qty, shipped_qty, returned_qty,
                       unit_price_amount, discount_rate, amount
                  FROM sales_order_lines WHERE order_id = ? ORDER BY line_no
                """, LINE_MAPPER, orderId);
        return rows.stream()
                .map(l -> new SalesOrderLine(l.id(), l.lineNo(), l.skuId(), l.qty(), l.shippedQty(),
                        l.returnedQty(), l.unitPriceAmount(), l.discountRate(), l.amount(),
                        findReservations(l.id())))
                .toList();
    }

    /** 单条明细（不含预占分段）—— 退货入库时用于给出"差多少"的定位信息。 */
    public Optional<SalesOrderLine> findLineById(long lineId) {
        return jdbc.query("""
                SELECT id, line_no, sku_id, qty, shipped_qty, returned_qty,
                       unit_price_amount, discount_rate, amount
                  FROM sales_order_lines WHERE id = ?
                """, LINE_MAPPER, lineId).stream().findFirst();
    }

    public Optional<SalesOrder> findById(long id) {
        return findHeaderById(id).map(h -> new SalesOrder(h.id(), h.orderNo(), h.status(),
                h.customerId(), h.warehouseId(), h.bizDate(), h.totalAmount(), h.totalQty(),
                h.period(), h.remark(), h.version(), findLines(id)));
    }

    public boolean updateStatus(long id, DocumentStatus from, DocumentStatus to, int expectedVersion) {
        return jdbc.update("""
                UPDATE sales_orders
                   SET status = ?, version = version + 1, updated_at = now()
                 WHERE id = ? AND status = ? AND version = ?
                """, to.name(), id, from.name(), expectedVersion) == 1;
    }

    /**
     * 推进单行累计出库量，原子守卫不超量。
     *
     * @return 是否更新成功（false = 超量或并发冲突）
     */
    public boolean applyShippedQty(long lineId, int qty) {
        return jdbc.update("""
                UPDATE sales_order_lines
                   SET shipped_qty = shipped_qty + ?
                 WHERE id = ? AND shipped_qty + ? <= qty
                """, qty, lineId, qty) == 1;
    }

    /**
     * 累计退货量（FR-RMA-01），<b>原子地</b>保证不超过已出货数量。
     *
     * <p>条件写成 {@code returned_qty + ? <= shipped_qty} 而不是让应用层先查后判：
     * 两张退货单并发入库时，应用层的读数都可能是"还能退 3 件"，
     * 而数据库求值只有一份 —— 后到者条件为假、更新 0 行，超退就被拦在这里。
     * 这是本方法存在的唯一理由，调用方不得改用"先读再写"。
     *
     * @return 是否更新成功（false = 超退或行不存在）
     */
    public boolean applyReturnedQty(long lineId, int qty) {
        return jdbc.update("""
                UPDATE sales_order_lines
                   SET returned_qty = returned_qty + ?
                 WHERE id = ? AND returned_qty + ? <= shipped_qty
                """, qty, lineId, qty) == 1;
    }

    public PageResult<SalesOrder> page(DocumentStatus status, Long customerId, int page, int size) {
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
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM sales_orders" + where,
                Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(s);
        pageArgs.add(PageResult.offset(p, s));
        List<SalesOrder> headers = jdbc.query("""
                SELECT id, order_no, status, customer_id, warehouse_id, biz_date,
                       total_amount, total_qty, period, remark, version
                  FROM sales_orders
                """ + where + " ORDER BY id DESC LIMIT ? OFFSET ?", ORDER_MAPPER, pageArgs.toArray());
        List<SalesOrder> items = headers.stream()
                .map(h -> new SalesOrder(h.id(), h.orderNo(), h.status(), h.customerId(),
                        h.warehouseId(), h.bizDate(), h.totalAmount(), h.totalQty(),
                        h.period(), h.remark(), h.version(), findLines(h.id())))
                .toList();
        return new PageResult<>(items, total == null ? 0 : total, p, s);
    }

    /**
     * 分页取出<b>还能退货</b>的出货单（含全部明细行）。
     *
     * <p>"可退"的判定刻意放在 SQL 里用 EXISTS 完成，而不是先取一页单据再在内存里筛：
     * 后者会让 {@code total} 与 items 口径不一致（整单退完的行被筛掉后这一页就不满了），
     * 调用方只能"多取几条补页"或翻两遍 —— 正是 §6 坑 14 里"拿 200 条上限当全量"的成因。
     *
     * <p>谓词 {@code shipped_qty - returned_qty > 0} 与 {@code SalesOrderLine.returnableQty()}
     * 同源；改口径时两处必须一起改，由 TC-RMA-015 盯住。
     */
    public PageResult<SalesOrder> pageReturnable(int page, int size) {
        int p = PageResult.normalizePage(page);
        int s = PageResult.normalizeSize(size);
        String returnable = """
                 WHERE o.status IN ('PARTIAL', 'COMPLETED')
                   AND EXISTS (SELECT 1 FROM sales_order_lines l
                                WHERE l.order_id = o.id
                                  AND l.shipped_qty - l.returned_qty > 0)
                """;
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sales_orders o" + returnable, Long.class);
        List<SalesOrder> headers = jdbc.query("""
                SELECT o.id, o.order_no, o.status, o.customer_id, o.warehouse_id, o.biz_date,
                       o.total_amount, o.total_qty, o.period, o.remark, o.version
                  FROM sales_orders o
                """ + returnable + " ORDER BY o.id DESC LIMIT ? OFFSET ?",
                ORDER_MAPPER, s, PageResult.offset(p, s));
        List<SalesOrder> items = headers.stream()
                .map(h -> new SalesOrder(h.id(), h.orderNo(), h.status(), h.customerId(),
                        h.warehouseId(), h.bizDate(), h.totalAmount(), h.totalQty(),
                        h.period(), h.remark(), h.version(), findLines(h.id())))
                .toList();
        return new PageResult<>(items, total == null ? 0 : total, p, s);
    }
}
