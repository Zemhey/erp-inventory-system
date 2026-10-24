package com.erp.pur.repository;

import com.erp.pur.domain.PurchaseReturn;
import com.erp.pur.domain.PurchaseReturnLine;
import com.erp.pur.domain.PurchaseReturnStatus;
import com.erp.shared.web.PageResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 采购退货仓储（FR-PUR-06）。
 *
 * <p>明细存 JSONB（迁移只允许 5 张新表）；本类负责与 Java 列表互转。
 */
@Repository
public class PurchaseReturnRepository {

    private static final TypeReference<List<PurchaseReturnLine>> LINES_TYPE =
            new TypeReference<>() {
            };

    private static final RowMapper<PurchaseReturn> MAPPER = (rs, i) -> new PurchaseReturn(
            rs.getLong("id"),
            rs.getString("return_no"),
            rs.getLong("purchase_order_id"),
            rs.getLong("supplier_id"),
            rs.getLong("warehouse_id"),
            rs.getObject("biz_date", LocalDate.class),
            rs.getString("period"),
            PurchaseReturnStatus.valueOf(rs.getString("status")),
            rs.getInt("total_qty"),
            rs.getLong("total_amount"),
            rs.getString("remark"),
            rs.getInt("version"),
            List.of());

    private static final String SELECT = """
            SELECT id, return_no, purchase_order_id, supplier_id, warehouse_id, biz_date,
                   period, status, total_qty, total_amount, remark, version
              FROM purchase_returns
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public PurchaseReturnRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public long insert(String returnNo, long purchaseOrderId, long supplierId, long warehouseId,
                       LocalDate bizDate, String period, int totalQty, long totalAmount,
                       String remark, List<PurchaseReturnLine> lines, String requestId) {
        return com.erp.shared.tenant.TenantJdbc.insertReturningId(jdbc,
                """
                    INSERT INTO purchase_returns
                        (return_no, purchase_order_id, supplier_id, warehouse_id, biz_date,
                         period, status, total_qty, total_amount, remark, lines, request_id)
                    VALUES (?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?, ?::jsonb, ?)
                    RETURNING id
                    """,
                new Object[]{returnNo, purchaseOrderId, supplierId, warehouseId, bizDate, period, totalQty, totalAmount, remark, linesJson(lines), requestId});
    }

    public Optional<PurchaseReturn> findById(long id) {
        return jdbc.query(SELECT + " WHERE id = ?", MAPPER, id).stream().findFirst()
                .map(this::withLines);
    }

    /**
     * 锁住退货单行（FR-PUR-06 执行串行化）。
     *
     * <p>执行/作废都先拿这把行锁：同键并发重放时，后到者等前者提交后
     * 重新读到 COMPLETED，走幂等返回，而不是再扣一次库存。
     */
    public boolean lockById(long id) {
        return !jdbc.queryForList(
                "SELECT id FROM purchase_returns WHERE id = ? FOR UPDATE", Long.class, id)
                .isEmpty();
    }

    public Optional<PurchaseReturn> findByRequestId(String requestId) {
        return jdbc.query(SELECT + " WHERE request_id = ?", MAPPER, requestId).stream().findFirst()
                .map(this::withLines);
    }

    public Optional<PurchaseReturn> findByApproveRequestId(String requestId) {
        return jdbc.query(SELECT + " WHERE approve_request_id = ?", MAPPER, requestId)
                .stream().findFirst().map(this::withLines);
    }

    public PageResult<PurchaseReturn> page(PurchaseReturnStatus status, Long supplierId,
                                           Long purchaseOrderId, int page, int size) {
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
        if (purchaseOrderId != null) {
            where.append(" AND purchase_order_id = ?");
            args.add(purchaseOrderId);
        }
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM purchase_returns" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(s);
        pageArgs.add(PageResult.offset(p, s));
        List<PurchaseReturn> items = jdbc.query(SELECT + where
                        + " ORDER BY id DESC LIMIT ? OFFSET ?", MAPPER, pageArgs.toArray())
                .stream().map(this::withLines).toList();
        return new PageResult<>(items, total == null ? 0 : total, p, s);
    }

    /**
     * 执行退货：DRAFT → COMPLETED，并写入执行幂等键。
     *
     * @return false = 状态/版本已被并发修改
     */
    public boolean markCompleted(long id, int expectedVersion, String approveRequestId) {
        return jdbc.update("""
                UPDATE purchase_returns
                   SET status = 'COMPLETED', approve_request_id = ?,
                       version = version + 1, updated_at = now()
                 WHERE id = ? AND status = 'DRAFT' AND version = ?
                """, approveRequestId, id, expectedVersion) == 1;
    }

    public boolean markVoided(long id, int expectedVersion, String voidRequestId) {
        return jdbc.update("""
                UPDATE purchase_returns
                   SET status = 'VOIDED', void_request_id = ?,
                       version = version + 1, updated_at = now()
                 WHERE id = ? AND status = 'DRAFT' AND version = ?
                """, voidRequestId, id, expectedVersion) == 1;
    }

    /** 同键重放判据：该退货单是否由指定幂等键作废。 */
    public boolean isVoidedByRequest(long id, String requestId) {
        Boolean v = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM purchase_returns
                                WHERE id = ? AND status = 'VOIDED' AND void_request_id = ?)
                """, Boolean.class, id, requestId);
        return Boolean.TRUE.equals(v);
    }

    /** 同键重放判据：该退货单是否由指定幂等键执行。 */
    public boolean isCompletedByRequest(long id, String requestId) {
        Boolean v = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM purchase_returns
                                WHERE id = ? AND status = 'COMPLETED' AND approve_request_id = ?)
                """, Boolean.class, id, requestId);
        return Boolean.TRUE.equals(v);
    }

    /**
     * 某到货行已被执行退货的数量（只统计 COMPLETED 的退货单）。
     *
     * <p>调用方必须先锁 {@code purchase_order_lines} 行，再调用本方法，
     * 才能把"先查后写"的窗口关掉（FR-PUR-06 并发超退）。
     */
    public int returnedQtyOfReceiptLine(long receiptLineId) {
        Integer qty = jdbc.queryForObject("""
                SELECT COALESCE(SUM((l ->> 'qty')::INTEGER), 0)
                  FROM purchase_returns pr
                  CROSS JOIN LATERAL jsonb_array_elements(pr.lines) AS l
                 WHERE pr.status = 'COMPLETED'
                   AND (l ->> 'receiptLineId')::BIGINT = ?
                """, Integer.class, receiptLineId);
        return qty == null ? 0 : qty;
    }

    private PurchaseReturn withLines(PurchaseReturn h) {
        return new PurchaseReturn(h.id(), h.returnNo(), h.purchaseOrderId(), h.supplierId(),
                h.warehouseId(), h.bizDate(), h.period(), h.status(), h.totalQty(),
                h.totalAmount(), h.remark(), h.version(), parseLines(h.id()));
    }

    private List<PurchaseReturnLine> parseLines(long id) {
        String text = jdbc.queryForObject(
                "SELECT lines::text FROM purchase_returns WHERE id = ?", String.class, id);
        if (text == null || text.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(text, LINES_TYPE);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("采购退货单明细 JSON 无法解析：id=" + id, ex);
        }
    }

    private String linesJson(List<PurchaseReturnLine> lines) {
        try {
            return json.writeValueAsString(lines);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("采购退货单明细无法序列化", ex);
        }
    }
}
