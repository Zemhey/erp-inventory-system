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
            rs.getInt("qc_qty"),
            com.erp.pur.domain.QcStatus.valueOf(rs.getString("qc_status")),
            rs.getString("qc_note"),
            rs.getLong("unit_price_amount"),
            rs.getLong("amount"));

    private final JdbcTemplate jdbc;

    /**
     * 数据权限（FR-IAM-04）。
     *
     * <p><b>★ 为什么过滤注入在仓储而不是 Controller</b>：分页、详情、导出、
     * 关联查询、统计聚合是五条不同的取数路径（蓝图 §7.1 的越权矩阵正是这五种）。
     * 在 Controller 里筛结果只能覆盖前两条，且 {@code total} 会算错。
     * 把谓词拼进 SQL，出口只有一个（本类），于是"漏改一条路径"这件事不可能发生。
     */
    private final com.erp.shared.scope.ScopeContext scope;

    public PurchaseOrderRepository(JdbcTemplate jdbc, com.erp.shared.scope.ScopeContext scope) {
        this.jdbc = jdbc;
        this.scope = scope;
    }

    /** 插入主表，返回新 id。 */
    /**
     * 插入主表，返回新 id。
     *
     * <p>{@code createdBy} 参与数据权限的"按本人"判定（FR-IAM-04），
     * 因此建单时必须写入真实创建人；{@code null} 仅用于系统任务/历史迁移。
     */
    public long insertHeader(String orderNo, long supplierId, long warehouseId, LocalDate bizDate,
                             long totalAmount, int totalQty, String period, String remark,
                             String requestId, Long createdBy) {
        return insertHeader(orderNo, supplierId, warehouseId, bizDate, totalAmount, totalQty,
                period, remark, requestId, "PURCHASE", null, createdBy);
    }

    /**
     * 插入主表（V2-2 支持请购单与"请购转订单"的溯源列）。
     *
     * @param docType              PURCHASE / REQUISITION
     * @param sourceRequisitionId  转单生成时指向来源请购单；普通建单为 null
     */
    public long insertHeader(String orderNo, long supplierId, long warehouseId, LocalDate bizDate,
                             long totalAmount, int totalQty, String period, String remark,
                             String requestId, String docType, Long sourceRequisitionId) {
        return insertHeader(orderNo, supplierId, warehouseId, bizDate, totalAmount, totalQty,
                period, remark, requestId, docType, sourceRequisitionId, null);
    }

    /**
     * 插入主表。
     *
     * <p>{@code createdBy} 参与数据权限的"按本人"判定（FR-IAM-04）；
     * {@code null} 仅用于系统任务/历史迁移。
     */
    public long insertHeader(String orderNo, long supplierId, long warehouseId, LocalDate bizDate,
                             long totalAmount, int totalQty, String period, String remark,
                             String requestId, String docType, Long sourceRequisitionId,
                             Long createdBy) {
        return com.erp.shared.tenant.TenantJdbc.insertReturningId(jdbc,
                """
                    INSERT INTO purchase_orders
                        (order_no, status, supplier_id, warehouse_id, biz_date,
                         total_amount, total_qty, period, remark, request_id,
                         doc_type, source_requisition_id, created_by)
                    VALUES (?, 'DRAFT', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    RETURNING id
                    """,
                new Object[]{orderNo, supplierId, warehouseId, bizDate, totalAmount, totalQty, period, remark, requestId, docType, sourceRequisitionId, createdBy});
    }

    public void insertLine(long orderId, int lineNo, long skuId, int qty,
                           long unitPriceAmount, long amount) {
        jdbc.update("""
                INSERT INTO purchase_order_lines
                    (order_id, line_no, sku_id, qty, received_qty, unit_price_amount, amount)
                VALUES (?, ?, ?, ?, 0, ?, ?)
                """, orderId, lineNo, skuId, qty, unitPriceAmount, amount);
    }

    /**
     * 查主表（不含明细），<b>并施加数据权限</b>。
     *
     * <p><b>★ 越权直查在此返回"不存在"而不是"无权限"</b>：
     * 条件拼进 SQL 之后，越权的 id 根本查不出行 → 上层照常抛
     * {@code PUR_ORDER_NOT_FOUND}（404）。若改成"先查出来再判权限"，
     * 就必须返回 403，而 403 会告诉攻击者"这个 id 是存在的，只是你没权限"——
     * 攻击者据此按 id 遍历就能枚举出全部单据 id（蓝图 §7.1 的越权矩阵里
     * 「跨仓直查 id」这一行的断言必须是 404，理由就在这里）。
     */
    public Optional<PurchaseOrder> findHeaderById(long id) {
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(id);
        String predicate = scope.predicates("warehouse_id", "created_by", args);
        return jdbc.query("""
                SELECT id, order_no, status, supplier_id, warehouse_id, biz_date,
                       total_amount, total_qty, period, remark, version
                  FROM purchase_orders WHERE id = ? AND doc_type = 'PURCHASE'
                """ + predicate, ORDER_MAPPER, args.toArray()).stream().findFirst();
    }

    public Optional<PurchaseOrder> findHeaderByNo(String orderNo) {
        return jdbc.query("""
                SELECT id, order_no, status, supplier_id, warehouse_id, biz_date,
                       total_amount, total_qty, period, remark, version
                  FROM purchase_orders WHERE order_no = ? AND doc_type = 'PURCHASE'
                """, ORDER_MAPPER, orderNo).stream().findFirst();
    }

    public Optional<PurchaseOrder> findByRequestId(String requestId) {
        return jdbc.query("""
                SELECT id, order_no, status, supplier_id, warehouse_id, biz_date,
                       total_amount, total_qty, period, remark, version
                  FROM purchase_orders WHERE request_id = ? AND doc_type = 'PURCHASE'
                """, ORDER_MAPPER, requestId).stream().findFirst();
    }

    public List<PurchaseOrderLine> findLines(long orderId) {
        return jdbc.query("""
                SELECT id, line_no, sku_id, qty, received_qty, qc_qty, qc_status, qc_note,
                       unit_price_amount, amount
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
                 WHERE id = ? AND status = ? AND version = ? AND doc_type = 'PURCHASE'
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
                 WHERE id = ? AND status = ? AND version = ? AND doc_type = 'PURCHASE'
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
                   AND order_id IN (SELECT id FROM purchase_orders WHERE doc_type = 'PURCHASE')
                """, qty, lineId, qty) == 1;
    }

    /**
     * 分页列表，<b>并施加数据权限</b>。
     *
     * <p><b>★ "列表注入"越权方式的防线</b>（蓝图 §7.1 五种越权方式之一）：
     * 攻击者不会老老实实传 {@code id}，他会塞一个把他仓的仓库 id 写进
     * 查询参数里、或者在请求里加一个不存在的过滤字段。本方法只认
     * {@code status / supplierId} 两个<b>白名单</b>参数，用户的仓库参数
     * 一律走 {@code scope} 解析出来的值 —— 换句话说，<b>仓库过滤不接受外部输入</b>，
     * 它只接受"当前登录人配置的数据权限"。这是"列表注入"无法绕过的原因。
     *
     * <p>{@code total} 用同一个 WHERE 算，所以总条数与可见条数一致，
     * 不会出现"第 1 页 3 条、第 2 页 0 条"的假象。
     */
    /**
     * 写回累计质检量（FR-PUR-05）。
     *
     * <p>状态由<b>累加后的数量</b>推导，而不是由本次调用方传入 ——
     * 否则分批到货时两批的质检结果会互相覆盖。
     *
     * @return 是否更新成功（false = 行不存在或合格量超过累计到货量）
     */
    public boolean applyQc(long lineId, int qcQty, String note) {
        return jdbc.update("""
                UPDATE purchase_order_lines
                   SET qc_qty = qc_qty + ?,
                       qc_status = CASE
                           WHEN qc_qty + ? = 0 THEN 'FAILED'
                           WHEN qc_qty + ? < received_qty THEN 'PARTIAL'
                           ELSE 'PASSED'
                       END,
                       qc_note = COALESCE(?, qc_note)
                 WHERE id = ? AND qc_qty + ? <= received_qty
                """, qcQty, qcQty, qcQty, note, lineId, qcQty) == 1;
    }

    /**
     * 锁住一行进货明细，串行化并发退货（FR-PUR-06）。
     *
     * <p>退货数量上限要靠"合格量 − 已退量"计算，先锁行再聚合，避免两个并发退货
     * 都读到同一个已退量而各自通过。
     */
    public boolean lockLine(long lineId) {
        return !jdbc.queryForList(
                "SELECT id FROM purchase_order_lines WHERE id = ? FOR UPDATE", Long.class, lineId)
                .isEmpty();
    }
    public PageResult<PurchaseOrder> page(DocumentStatus status, Long supplierId, int page, int size) {
        int p = PageResult.normalizePage(page);
        int s = PageResult.normalizeSize(size);
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE doc_type = 'PURCHASE'");
        if (status != null) {
            where.append(" AND status = ?");
            args.add(status.name());
        }
        if (supplierId != null) {
            where.append(" AND supplier_id = ?");
            args.add(supplierId);
        }
        // ★ 数据权限：仓库 + 本人（参数按 SQL 出现顺序追加，紧跟用户过滤条件之后）
        where.append(scope.predicates("warehouse_id", "created_by", args));
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
