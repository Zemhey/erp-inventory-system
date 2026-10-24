package com.erp.sal.repository;

import com.erp.sal.domain.Shipment;
import com.erp.sal.domain.ShipmentLine;
import com.erp.sal.domain.ShipmentPlan;
import com.erp.sal.domain.ShipmentStatus;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.web.PageResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** 发货计划/发货单/拣货行仓储（FR-SAL-06）。 */
@Repository
public class ShipmentRepository {

    private static final RowMapper<Shipment> SHIPMENT_MAPPER = (rs, i) -> new Shipment(
            rs.getLong("id"),
            rs.getString("shipment_no"),
            rs.getLong("plan_id"),
            rs.getLong("sales_order_id"),
            rs.getLong("warehouse_id"),
            ShipmentStatus.valueOf(rs.getString("status")),
            rs.getString("confirm_request_id"),
            rs.getInt("version"),
            List.of());

    private static final RowMapper<ShipmentLine> LINE_MAPPER = (rs, i) -> new ShipmentLine(
            rs.getLong("id"),
            rs.getInt("line_no"),
            rs.getLong("sales_order_line_id"),
            rs.getLong("sku_id"),
            rs.getLong("reservation_id"),
            rs.getString("batch_no"),
            rs.getInt("planned_qty"),
            rs.getInt("picked_qty"));

    private static final String SHIPMENT_COLUMNS = """
            id, shipment_no, plan_id, sales_order_id, warehouse_id, status,
            confirm_request_id, version
            """;

    private final JdbcTemplate jdbc;

    public ShipmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ─────────────────────────── 计划 ───────────────────────────

    /** @return 新建计划 id；空 = 并发同键已被另一请求创建 */
    public Optional<Long> tryInsertPlan(String planNo, long salesOrderId, String requestId) {
        return jdbc.query("""
                INSERT INTO so_shipment_plans (plan_no, sales_order_id, request_id)
                VALUES (?, ?, ?)
                ON CONFLICT (request_id) DO NOTHING
                RETURNING id
                """, (rs, i) -> rs.getLong("id"), planNo, salesOrderId, requestId)
                .stream().findFirst();
    }

    public Optional<ShipmentPlan> findPlanByRequestId(String requestId) {
        return jdbc.query("""
                SELECT id, plan_no, sales_order_id, request_id
                  FROM so_shipment_plans WHERE request_id = ?
                """, (rs, i) -> new ShipmentPlan(rs.getLong("id"), rs.getString("plan_no"),
                        rs.getLong("sales_order_id"), rs.getString("request_id"), List.of()),
                requestId).stream().findFirst().map(this::withShipments);
    }

    public Optional<ShipmentPlan> findPlanById(long id) {
        return jdbc.query("""
                SELECT id, plan_no, sales_order_id, request_id
                  FROM so_shipment_plans WHERE id = ?
                """, (rs, i) -> new ShipmentPlan(rs.getLong("id"), rs.getString("plan_no"),
                        rs.getLong("sales_order_id"), rs.getString("request_id"), List.of()),
                id).stream().findFirst().map(this::withShipments);
    }

    private ShipmentPlan withShipments(ShipmentPlan plan) {
        return new ShipmentPlan(plan.id(), plan.planNo(), plan.salesOrderId(), plan.requestId(),
                findShipmentsByPlan(plan.id()));
    }

    public List<Shipment> findShipmentsByPlan(long planId) {
        return jdbc.query("SELECT " + SHIPMENT_COLUMNS
                + " FROM so_shipments WHERE plan_id = ? ORDER BY id",
                SHIPMENT_MAPPER, planId).stream().map(this::withLines).toList();
    }

    // ─────────────────────────── 发货单 ───────────────────────────

    public long insertShipment(long planId, String shipmentNo, long salesOrderId,
                               long warehouseId) {
        return com.erp.shared.tenant.TenantJdbc.insertReturningId(jdbc,
                """
                    INSERT INTO so_shipments
                        (plan_id, shipment_no, sales_order_id, warehouse_id, status)
                    VALUES (?, ?, ?, ?, 'DRAFT')
                    RETURNING id
                    """,
                new Object[]{planId, shipmentNo, salesOrderId, warehouseId});
    }

    public void insertLine(long shipmentId, int lineNo, long salesOrderLineId, long skuId,
                           long reservationId, String batchNo, int plannedQty) {
        try {
            jdbc.update("""
                    INSERT INTO shipment_lines
                        (shipment_id, line_no, sales_order_line_id, sku_id, reservation_id,
                         batch_no, planned_qty, picked_qty)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 0)
                    """, shipmentId, lineNo, salesOrderLineId, skuId, reservationId,
                    batchNo == null ? "" : batchNo, plannedQty);
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException(ErrorCode.SAL_SHIPMENT_STATUS_ILLEGAL,
                    "预占 %d 已被其他发货计划占用，不能重复计划".formatted(reservationId));
        }
    }

    public List<ShipmentLine> findLines(long shipmentId) {
        return jdbc.query("""
                SELECT id, line_no, sales_order_line_id, sku_id, reservation_id,
                       batch_no, planned_qty, picked_qty
                  FROM shipment_lines WHERE shipment_id = ? ORDER BY line_no
                """, LINE_MAPPER, shipmentId);
    }

    public Optional<Shipment> findById(long id) {
        return jdbc.query("SELECT " + SHIPMENT_COLUMNS + " FROM so_shipments WHERE id = ?",
                SHIPMENT_MAPPER, id).stream().findFirst().map(this::withLines);
    }

    public Optional<Shipment> findByIdForUpdate(long id) {
        return jdbc.query("SELECT " + SHIPMENT_COLUMNS
                + " FROM so_shipments WHERE id = ? FOR UPDATE", SHIPMENT_MAPPER, id).stream()
                .findFirst().map(this::withLines);
    }

    private Shipment withLines(Shipment s) {
        return new Shipment(s.id(), s.shipmentNo(), s.planId(), s.salesOrderId(),
                s.warehouseId(), s.status(), s.confirmRequestId(), s.version(), findLines(s.id()));
    }

    public boolean updateStatus(long id, ShipmentStatus from, ShipmentStatus to,
                                int expectedVersion) {
        return jdbc.update("""
                UPDATE so_shipments
                   SET status = ?, version = version + 1, updated_at = now()
                 WHERE id = ? AND status = ? AND version = ?
                """, to.name(), id, from.name(), expectedVersion) == 1;
    }

    public boolean complete(long id, ShipmentStatus from, int expectedVersion, String requestId) {
        return jdbc.update("""
                UPDATE so_shipments
                   SET status = 'COMPLETED', confirm_request_id = ?,
                       version = version + 1, updated_at = now()
                 WHERE id = ? AND status = ? AND version = ?
                """, requestId, id, from.name(), expectedVersion) == 1;
    }

    /** 扫码累计：原子守卫不得超计划。 */
    public boolean addPicked(long lineId, int qty) {
        return jdbc.update("""
                UPDATE shipment_lines
                   SET picked_qty = picked_qty + ?
                 WHERE id = ? AND picked_qty + ? <= planned_qty
                """, qty, lineId, qty) == 1;
    }

    /** 该订单是否还有预占已被别的发货计划占用（防止同一条预占重复计划）。 */
    public boolean isReservationPlanned(long reservationId) {
        Boolean v = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM shipment_lines WHERE reservation_id = ?)
                """, Boolean.class, reservationId);
        return Boolean.TRUE.equals(v);
    }

    public PageResult<Shipment> page(ShipmentStatus status, Long salesOrderId, int page, int size) {
        int p = PageResult.normalizePage(page);
        int s = PageResult.normalizeSize(size);
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        if (status != null) {
            where.append(" AND status = ?");
            args.add(status.name());
        }
        if (salesOrderId != null) {
            where.append(" AND sales_order_id = ?");
            args.add(salesOrderId);
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM so_shipments" + where,
                Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(s);
        pageArgs.add(PageResult.offset(p, s));
        List<Shipment> items = jdbc.query("SELECT " + SHIPMENT_COLUMNS
                + " FROM so_shipments" + where + " ORDER BY id DESC LIMIT ? OFFSET ?",
                SHIPMENT_MAPPER, pageArgs.toArray()).stream().map(this::withLines).toList();
        return new PageResult<>(items, total == null ? 0 : total, p, s);
    }
}
