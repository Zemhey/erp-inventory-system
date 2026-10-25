package com.erp.inv.repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 结存 / 预警 / 补货的只读查询仓储（FR-INV-11 ~ FR-INV-13）。
 *
 * <p>本类<b>只读</b>，不提供任何写方法 —— 与 rpt 的只读纪律同源：
 * 查询模型失控变成第二套写路径，是库存系统最常见的数据漂移来源。
 *
 * <p>补货建议需要判断"已下单未到货"，这里直接读 {@code purchase_orders} 表。
 * 这是<b>只读跨域查询</b>，不 import PUR 的任何类，因此不会形成包依赖环
 * （PUR 仍然只通过 InventoryApplicationService 改库存）。
 */
@Repository
public class InventoryInsightRepository {

    private final JdbcTemplate jdbc;

    public InventoryInsightRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 五维结存的数量切片，状态展开在应用层完成。 */
    public record BalanceSlice(long skuId, long warehouseId, String batchNo,
                               int onHandQty, int reservedQty, int frozenQty) {
    }

    public record SafetyRow(long skuId, long warehouseId, int onHandQty, int availableQty,
                            int minLevel, int warehousePriority) {
    }

    public record ExpiringRow(long skuId, long warehouseId, String batchNo, LocalDate expiryDate,
                              int onHandQty, int availableQty, int warehousePriority) {
    }

    public record SlowMovingRow(long skuId, long warehouseId, int onHandQty, int availableQty,
                                OffsetDateTime lastOutboundAt, OffsetDateTime firstMovementAt,
                                int warehousePriority) {
    }

    public record ReplenishmentRow(long skuId, long warehouseId, int availableQty, int minLevel,
                                   int maxLevel, int pendingPurchaseQty, int warehousePriority) {
    }

    private static final RowMapper<BalanceSlice> SLICE_MAPPER = (rs, i) -> new BalanceSlice(
            rs.getLong("sku_id"), rs.getLong("warehouse_id"), rs.getString("batch_no"),
            rs.getInt("on_hand_qty"), rs.getInt("reserved_qty"), rs.getInt("frozen_qty"));

    public List<BalanceSlice> balanceSlices(Long skuId, Long warehouseId, String batchNo) {
        StringBuilder sql = new StringBuilder("""
                SELECT sku_id, warehouse_id, batch_no, on_hand_qty, reserved_qty, frozen_qty
                  FROM inventory_balances WHERE 1 = 1
                """);
        List<Object> args = new ArrayList<>();
        if (skuId != null) {
            sql.append(" AND sku_id = ?");
            args.add(skuId);
        }
        if (warehouseId != null) {
            sql.append(" AND warehouse_id = ?");
            args.add(warehouseId);
        }
        if (batchNo != null && !batchNo.isBlank()) {
            sql.append(" AND batch_no = ?");
            args.add(batchNo);
        }
        sql.append(" ORDER BY warehouse_id, sku_id, batch_no");
        return jdbc.query(sql.toString(), SLICE_MAPPER, args.toArray());
    }

    /**
     * 安全库存预警：可用量（汇总到 SKU+仓库）低于 skus.min_level。
     *
     * <p>只看已经有余额行的 (SKU, 仓库) —— 从未在该仓建过账的 SKU 不产生噪音。
     */
    public List<SafetyRow> safetyAlerts(Long warehouseId) {
        return jdbc.query("""
                SELECT b.sku_id, b.warehouse_id,
                       SUM(b.on_hand_qty)     AS on_hand_qty,
                       SUM(b.available_qty)   AS available_qty,
                       s.min_level,
                       w.priority             AS warehouse_priority
                  FROM inventory_balances b
                  JOIN skus s ON s.id = b.sku_id AND s.enabled AND s.min_level > 0
                  JOIN warehouses w ON w.id = b.warehouse_id AND w.enabled
                 WHERE (?::bigint IS NULL OR b.warehouse_id = ?)
                 GROUP BY b.sku_id, b.warehouse_id, s.min_level, w.priority
                HAVING SUM(b.available_qty) < s.min_level
                 ORDER BY w.priority DESC, b.sku_id
                """, (rs, i) -> new SafetyRow(
                        rs.getLong("sku_id"), rs.getLong("warehouse_id"),
                        rs.getInt("on_hand_qty"), rs.getInt("available_qty"),
                        rs.getInt("min_level"), rs.getInt("warehouse_priority")),
                warehouseId, warehouseId);
    }

    /** 临期预警：效期不晚于 cutoff 且仍有实存。cutoff 由 ClockProvider 计算。 */
    public List<ExpiringRow> expiringAlerts(Long warehouseId, LocalDate cutoff) {
        return jdbc.query("""
                SELECT b.sku_id, b.warehouse_id, b.batch_no, b.expiry_date,
                       b.on_hand_qty, b.available_qty,
                       w.priority AS warehouse_priority
                  FROM inventory_balances b
                  JOIN skus s ON s.id = b.sku_id AND s.enabled
                  JOIN warehouses w ON w.id = b.warehouse_id AND w.enabled
                 WHERE b.on_hand_qty > 0
                   AND b.expiry_date IS NOT NULL
                   AND b.expiry_date <= ?
                   AND (?::bigint IS NULL OR b.warehouse_id = ?)
                 ORDER BY b.expiry_date, w.priority DESC, b.sku_id
                """, (rs, i) -> new ExpiringRow(
                        rs.getLong("sku_id"), rs.getLong("warehouse_id"), rs.getString("batch_no"),
                        rs.getObject("expiry_date", LocalDate.class),
                        rs.getInt("on_hand_qty"), rs.getInt("available_qty"),
                        rs.getInt("warehouse_priority")),
                cutoff, warehouseId, warehouseId);
    }

    /**
     * 滞销候选：按 (SKU, 仓库) 汇总实存，并找出最后出库时间。
     *
     * <p>从未出过库时退回第一条流水时间（建账时间），避免"刚入库就判滞销"；
     * 一条流水都没有的余额（测试/初始化直写）不参与滞销判断。
     */
    public List<SlowMovingRow> slowMovingCandidates(Long warehouseId) {
        // ★ 流水必须先按 (SKU, 仓库) 聚合成一行再 LEFT JOIN：
        //   直接 JOIN inventory_ledger 会让每条余额被流水条数放大，
        //   SUM(on_hand_qty) 变成 N 倍实存（典型的"一对多 join 后再 SUM"错误）。
        return jdbc.query("""
                SELECT b.sku_id, b.warehouse_id,
                       SUM(b.on_hand_qty)   AS on_hand_qty,
                       SUM(b.available_qty) AS available_qty,
                       l.last_outbound_at,
                       l.first_movement_at,
                       w.priority AS warehouse_priority
                  FROM inventory_balances b
                  JOIN skus s ON s.id = b.sku_id AND s.enabled
                  JOIN warehouses w ON w.id = b.warehouse_id AND w.enabled
                  LEFT JOIN (
                      SELECT sku_id, warehouse_id,
                             MAX(occurred_at) FILTER (WHERE change_qty < 0) AS last_outbound_at,
                             MIN(occurred_at) AS first_movement_at
                        FROM inventory_ledger
                       GROUP BY sku_id, warehouse_id
                  ) l ON l.sku_id = b.sku_id AND l.warehouse_id = b.warehouse_id
                 WHERE (?::bigint IS NULL OR b.warehouse_id = ?)
                 GROUP BY b.sku_id, b.warehouse_id, w.priority,
                          l.last_outbound_at, l.first_movement_at
                HAVING SUM(b.on_hand_qty) > 0
                 ORDER BY w.priority DESC, b.sku_id
                """, (rs, i) -> new SlowMovingRow(
                        rs.getLong("sku_id"), rs.getLong("warehouse_id"),
                        rs.getInt("on_hand_qty"), rs.getInt("available_qty"),
                        rs.getObject("last_outbound_at", OffsetDateTime.class),
                        rs.getObject("first_movement_at", OffsetDateTime.class),
                        rs.getInt("warehouse_priority")),
                warehouseId, warehouseId);
    }

    /**
     * 补货候选：可用量低于 min_level 的 (SKU, 仓库)。
     *
     * <p>同时带出"已下单未到货"的数量（APPROVED/PARTIAL 且未收满的进货行）；
     * 大于 0 时应用层跳过该 SKU，避免重复建议。
     */
    public List<ReplenishmentRow> replenishmentCandidates(Long warehouseId) {
        return jdbc.query("""
                SELECT b.sku_id, b.warehouse_id,
                       SUM(b.available_qty) AS available_qty,
                       s.min_level, s.max_level,
                       COALESCE((SELECT SUM(pol.qty - pol.received_qty)
                                   FROM purchase_order_lines pol
                                   JOIN purchase_orders po ON po.id = pol.order_id
                                  WHERE pol.sku_id = b.sku_id
                                    AND po.warehouse_id = b.warehouse_id
                                    AND po.status IN ('APPROVED','PARTIAL')
                                    AND pol.qty > pol.received_qty), 0) AS pending_purchase_qty,
                       w.priority AS warehouse_priority
                  FROM inventory_balances b
                  JOIN skus s ON s.id = b.sku_id AND s.enabled AND s.max_level > 0
                  JOIN warehouses w ON w.id = b.warehouse_id AND w.enabled
                 WHERE (?::bigint IS NULL OR b.warehouse_id = ?)
                 GROUP BY b.sku_id, b.warehouse_id, s.min_level, s.max_level, w.priority
                HAVING SUM(b.available_qty) < s.min_level
                 ORDER BY w.priority DESC, b.sku_id
                """, (rs, i) -> new ReplenishmentRow(
                        rs.getLong("sku_id"), rs.getLong("warehouse_id"),
                        rs.getInt("available_qty"), rs.getInt("min_level"), rs.getInt("max_level"),
                        rs.getInt("pending_purchase_qty"), rs.getInt("warehouse_priority")),
                warehouseId, warehouseId);
    }
}
