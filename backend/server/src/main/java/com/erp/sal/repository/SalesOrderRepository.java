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
            List.of(),   // 预占分段另行装载
            rs.getObject("bundle_id", Long.class),
            rs.getObject("bundle_group_no", Integer.class),
            rs.getObject("bundle_ratio", Integer.class));

    private final JdbcTemplate jdbc;

    /**
     * 数据权限（FR-IAM-04）：仓库 + 本人。
     *
     * <p>过滤注入在仓储而不是 Controller —— 分页/详情/导出/关联/统计五条取数路径
     * 只要有一个出口，就不会漏改（越权矩阵正是按这五种方式抽查）。
     */
    private final com.erp.shared.scope.ScopeContext scope;

    public SalesOrderRepository(JdbcTemplate jdbc, com.erp.shared.scope.ScopeContext scope) {
        this.jdbc = jdbc;
        this.scope = scope;
    }

    /** 插入主表。{@code createdBy} 供数据权限"按本人"判定，系统任务可为 null。 */
    public long insertHeader(String orderNo, long customerId, long warehouseId, LocalDate bizDate,
                             long totalAmount, int totalQty, String period, String remark,
                             String requestId, Long createdBy) {
        return com.erp.shared.tenant.TenantJdbc.insertReturningId(jdbc,
                """
                    INSERT INTO sales_orders
                        (order_no, status, customer_id, warehouse_id, biz_date,
                         total_amount, total_qty, period, remark, request_id, created_by)
                    VALUES (?, 'DRAFT', ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    RETURNING id
                    """,
                new Object[]{orderNo, customerId, warehouseId, bizDate, totalAmount, totalQty, period, remark, requestId, createdBy});
    }

    public void insertLine(long orderId, int lineNo, long skuId, int qty,
                           long unitPriceAmount, int discountRate, long amount) {
        insertLine(orderId, lineNo, skuId, qty, unitPriceAmount, discountRate, amount,
                null, null, null);
    }

    public void insertLine(long orderId, int lineNo, long skuId, int qty,
                           long unitPriceAmount, int discountRate, long amount,
                           Long bundleId, Integer bundleGroupNo, Integer bundleRatio) {
        jdbc.update("""
                INSERT INTO sales_order_lines
                    (order_id, line_no, sku_id, qty, shipped_qty,
                     unit_price_amount, discount_rate, amount,
                     bundle_id, bundle_group_no, bundle_ratio)
                VALUES (?, ?, ?, ?, 0, ?, ?, ?, ?, ?, ?)
                """, orderId, lineNo, skuId, qty, unitPriceAmount, discountRate, amount,
                bundleId, bundleGroupNo, bundleRatio);
    }

    /** 记录一段预占（一行出货可有多段）。 */
    public void attachReservation(long lineId, long reservationId, int seq, int reservedQty) {
        attachReservation(lineId, reservationId, seq, reservedQty, null, null);
    }

    public void attachReservation(long lineId, long reservationId, int seq, int reservedQty,
                                  Long warehouseId, String batchNo) {
        jdbc.update("""
                INSERT INTO sales_order_line_reservations
                    (line_id, reservation_id, seq, reserved_qty, warehouse_id, batch_no)
                VALUES (?, ?, ?, ?, ?, ?)
                """, lineId, reservationId, seq, reservedQty, warehouseId, batchNo);
    }

    /** 某明细行的全部预占分段，按段序号排序。 */
    public List<LineReservation> findReservations(long lineId) {
        return jdbc.query("""
                SELECT reservation_id, seq, reserved_qty, warehouse_id, batch_no
                  FROM sales_order_line_reservations
                 WHERE line_id = ? ORDER BY seq
                """, (rs, i) -> new LineReservation(
                        rs.getLong("reservation_id"), rs.getInt("seq"), rs.getInt("reserved_qty"),
                        rs.getObject("warehouse_id", Long.class), rs.getString("batch_no")),
                lineId);
    }

    /**
     * 查主表，<b>并施加数据权限</b>。
     *
     * <p>越权的 id 直接查不出行 → 上层抛 {@code SAL_ORDER_NOT_FOUND}（<b>404 而非 403</b>）：
     * 403 会泄露"这个 id 存在"，攻击者据此遍历即可枚举全部单据 id。
     */
    public Optional<SalesOrder> findHeaderById(long id) {
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(id);
        String predicate = scope.predicates("warehouse_id", "created_by", args);
        return jdbc.query("""
                SELECT id, order_no, status, customer_id, warehouse_id, biz_date,
                       total_amount, total_qty, period, remark, version
                  FROM sales_orders WHERE id = ?
                """ + predicate, ORDER_MAPPER, args.toArray()).stream().findFirst();
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
                       unit_price_amount, discount_rate, amount,
                       bundle_id, bundle_group_no, bundle_ratio
                  FROM sales_order_lines WHERE order_id = ? ORDER BY line_no
                """, LINE_MAPPER, orderId);
        return rows.stream()
                .map(l -> new SalesOrderLine(l.id(), l.lineNo(), l.skuId(), l.qty(), l.shippedQty(),
                        l.returnedQty(), l.unitPriceAmount(), l.discountRate(), l.amount(),
                        findReservations(l.id()), l.bundleId(), l.bundleGroupNo(), l.bundleRatio()))
                .toList();
    }

    /** 单条明细（不含预占分段）—— 退货入库时用于给出"差多少"的定位信息。 */
    public Optional<SalesOrderLine> findLineById(long lineId) {
        return jdbc.query("""
                SELECT id, line_no, sku_id, qty, shipped_qty, returned_qty,
                       unit_price_amount, discount_rate, amount,
                       bundle_id, bundle_group_no, bundle_ratio
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
     * 作废/红冲的状态推进（M2.5）：同一语句写入幂等键。
     *
     * <p>用「状态 + version」作为并发串行化点：两个作废请求只有一个能更新成功；
     * 后到者重读后按同键/异键分别做幂等返回或状态非法。
     */
    public boolean markVoided(long id, DocumentStatus from, int expectedVersion, String requestId) {
        return jdbc.update("""
                UPDATE sales_orders
                   SET status = 'VOIDED', void_request_id = ?,
                       version = version + 1, updated_at = now()
                 WHERE id = ? AND status = ? AND version = ?
                """, requestId, id, from.name(), expectedVersion) == 1;
    }

    /**
     * 出货红冲的状态推进：除状态/版本外，还要求当前没有任何退货数量。
     *
     * <p>`NOT EXISTS` 放在 UPDATE 里而不是只在应用层先查：红冲与退货入库并发时，
     * 退货入库会先锁住同一张出货单头（见 {@code SalesOrderReturnService}），
     * 两者由这把行锁串行化 —— 退货先提交则本条件为假，红冲先提交则退货入库看到 VOIDED。
     */
    public boolean markVoidedWithNoReturns(long id, DocumentStatus from, int expectedVersion,
                                           String requestId) {
        return jdbc.update("""
                UPDATE sales_orders o
                   SET status = 'VOIDED', void_request_id = ?,
                       version = version + 1, updated_at = now()
                 WHERE o.id = ? AND o.status = ? AND o.version = ?
                   AND NOT EXISTS (SELECT 1 FROM sales_order_lines l
                                    WHERE l.order_id = o.id AND l.returned_qty > 0)
                """, requestId, id, from.name(), expectedVersion) == 1;
    }

    /** 该单是否由指定幂等键作废（同键重放判据）。 */
    public boolean isVoidedByRequest(long id, String requestId) {
        Boolean v = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM sales_orders
                                WHERE id = ? AND status = 'VOIDED' AND void_request_id = ?)
                """, Boolean.class, id, requestId);
        return Boolean.TRUE.equals(v);
    }

    /**
     * 退货入库前锁定原出货单头并取状态（M2.5）。
     *
     * <p>红冲更新的是同一行 `sales_orders`；退货入库先拿这把行锁，
     * 两边就按同一顺序串行化：要么退货先登记（红冲的条件 UPDATE 随后看到
     * `returned_qty > 0` 而失败），要么红冲先完成（退货入库看到 VOIDED 而失败）。
     * 没有这把锁，"红冲 + 并发退货入库"会把同一批货回补两次。
     */
    public Optional<DocumentStatus> lockOrderStatusByLineId(long lineId) {
        return jdbc.query("""
                SELECT o.status
                  FROM sales_orders o
                  JOIN sales_order_lines l ON l.order_id = o.id
                 WHERE l.id = ?
                 FOR UPDATE OF o
                """, (rs, i) -> DocumentStatus.valueOf(rs.getString("status")), lineId)
                .stream().findFirst();
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

    /**
     * 某客户仍占用信用额度的“未出货行”：
     * 订单状态为 DRAFT/SUBMITTED/APPROVED/PARTIAL，且行还有未出数量。
     *
     * <p>已出货部分已进入应收余额，不能在敞口里重复计算；因此这里只取
     * {@code qty - shipped_qty}，由调用方用同一 lineAmount 公式折算金额。
     */
    public record OpenLine(int qty, int shippedQty, long unitPriceAmount, int discountRate) {
    }

    public List<OpenLine> findOpenLinesByCustomer(long customerId) {
        return jdbc.query("""
                SELECT l.qty, l.shipped_qty, l.unit_price_amount, l.discount_rate
                  FROM sales_order_lines l
                  JOIN sales_orders o ON o.id = l.order_id
                 WHERE o.customer_id = ?
                   AND o.status IN ('DRAFT','SUBMITTED','APPROVED','PARTIAL')
                   AND l.qty > l.shipped_qty
                """, (rs, i) -> new OpenLine(rs.getInt("qty"), rs.getInt("shipped_qty"),
                        rs.getLong("unit_price_amount"), rs.getInt("discount_rate")), customerId);
    }

    /**
     * 分页列表，<b>并施加数据权限</b>。
     *
     * <p>"列表注入"防线的关键：仓库过滤<b>不接受外部输入</b>，只认当前登录人的
     * 数据权限配置。攻击者把别仓的 warehouseId 塞进查询参数也不会影响这里。
     */
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
        where.append(scope.predicates("warehouse_id", "created_by", args));
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
