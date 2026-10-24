package com.erp.inv.repository;

import com.erp.inv.domain.InventoryBalance;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 库存余额仓储。
 *
 * <p><b>本类是整个系统唯一允许修改库存数量的地方</b>（docs/02-领域模型 §2 依赖方向规则）。
 * 出货、入库、调拨、盘点都必须经由这里，禁止任何模块直接 UPDATE 库存表。
 */
@Repository
public class InventoryBalanceRepository {

    private final JdbcTemplate jdbc;

    /**
     * 数据权限（FR-IAM-04）：库存余额只有仓库维度（它不记录"谁建的" ——
     * 余额是单据执行产生的派生结果，不是人直接创建的行）。
     */
    private final com.erp.shared.scope.ScopeContext scope;

    public InventoryBalanceRepository(JdbcTemplate jdbc,
                                      com.erp.shared.scope.ScopeContext scope) {
        this.jdbc = jdbc;
        this.scope = scope;
    }

    private static final RowMapper<InventoryBalance> MAPPER = (rs, i) -> new InventoryBalance(
            rs.getLong("id"),
            rs.getLong("sku_id"),
            rs.getLong("warehouse_id"),
            rs.getString("batch_no"),
            rs.getObject("production_date", java.time.LocalDate.class),
            rs.getObject("expiry_date", java.time.LocalDate.class),
            rs.getInt("on_hand_qty"),
            rs.getInt("reserved_qty"),
            rs.getInt("frozen_qty"),
            rs.getInt("available_qty"),
            rs.getInt("version"));

    /**
     * 按三维键取一行，<b>并施加数据权限</b>。
     *
     * <p>⚠️ 本方法<b>只用于读路径</b>（查询、详情）。写路径（预占/入库/出库）
     * 必须能操作任何仓库的行 —— 否则一张跨仓调拨单在"看不到 B 仓"的
     * 操作员手里会静默失败。写路径走 {@code tryReserve/consumeReserved}
     * 这些不带权限谓词的方法，由单据侧的权限码把关。
     */
    public Optional<InventoryBalance> find(long skuId, long warehouseId, String batchNo) {
        java.util.List<Object> args = new java.util.ArrayList<>(List.of(skuId, warehouseId, batchNo));
        String predicate = scope.warehouseOnly("warehouse_id", args);
        List<InventoryBalance> rows = jdbc.query("""
                SELECT id, sku_id, warehouse_id, batch_no, production_date, expiry_date,
                       on_hand_qty, reserved_qty, frozen_qty, available_qty, version
                  FROM inventory_balances
                 WHERE sku_id = ? AND warehouse_id = ? AND batch_no = ?
                """ + predicate, MAPPER, args.toArray());
        return rows.stream().findFirst();
    }

    public List<InventoryBalance> query(Long skuId, Long warehouseId, String batchNo, boolean onlyAvailable) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, sku_id, warehouse_id, batch_no, production_date, expiry_date,
                       on_hand_qty, reserved_qty, frozen_qty, available_qty, version
                  FROM inventory_balances WHERE 1 = 1
                """);
        List<Object> args = new java.util.ArrayList<>();
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
        // ★ 数据权限：A 仓仓管查 B 仓库存必须得到**空结果**（不是 403）——
        //   条件拼进 SQL 之后，"库里有行但你看不到"与"库里本来没有行"
        //   在响应上是同一个形状，因此不泄露资源存在性（硬验收第 3 条）。
        sql.append(scope.warehouseOnly("warehouse_id", args));
        if (onlyAvailable) {
            sql.append(" AND available_qty > 0");
        }
        sql.append(" ORDER BY sku_id, warehouse_id, expiry_date NULLS LAST, batch_no");
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    /**
     * 按 FEFO 顺序取可扣减的批次（BR-INV-06：优先扣减最早效期）。
     *
     * <p>无批次（batch_no = ''）的行排在最后，因为无批次意味着无时效约束。
     */
    public List<InventoryBalance> findFefoCandidates(long skuId, long warehouseId) {
        return jdbc.query("""
                SELECT id, sku_id, warehouse_id, batch_no, production_date, expiry_date,
                       on_hand_qty, reserved_qty, frozen_qty, available_qty, version
                  FROM inventory_balances
                 WHERE sku_id = ? AND warehouse_id = ? AND on_hand_qty > 0
                 ORDER BY expiry_date NULLS LAST, batch_no
                """, MAPPER, skuId, warehouseId);
    }

    /**
     * 按 FEFO 顺序取<b>有待出库预占</b>的批次 —— 出货确认专用。
     *
     * <p><b>为什么不能用 {@link #findFefoCandidates}</b>：
     * 出货确认是把"预占"转为"实际出库"，此时该批次的可用量可能已经是 0
     * （可用量 = 实存 − 预占，预占已被本单占用）。
     * 若按可用量判断可扣减数量，会得出 0，导致预占的货反而发不出去 ——
     * 这是本项目在集成测试中真实捕获到的缺陷。
     *
     * <p>因此出货路径必须按 {@code reserved_qty} 判断可扣减量。
     */
    public List<InventoryBalance> findFefoShippableCandidates(long skuId, long warehouseId) {
        return jdbc.query("""
                SELECT id, sku_id, warehouse_id, batch_no, production_date, expiry_date,
                       on_hand_qty, reserved_qty, frozen_qty, available_qty, version
                  FROM inventory_balances
                 WHERE sku_id = ? AND warehouse_id = ? AND reserved_qty > 0
                 ORDER BY expiry_date NULLS LAST, batch_no
                """, MAPPER, skuId, warehouseId);
    }

    /**
     * ★ 原子预占（防超卖的核心）。
     *
     * <p>把"校验可用量"和"写入预占"压进<b>一条带条件的 UPDATE</b>：
     * {@code WHERE reserved_qty + ? <= on_hand_qty - frozen_qty}
     * 由数据库在行锁下原子求值（冻结量不可被预占，FR-INV-10）。
     *
     * <p>为什么这样做：先 SELECT 判断、再 UPDATE 的写法在并发下必然超卖
     * （两个事务都读到可用量 5，各自预占 5，结果预占 10）。
     * 而"检查后写入"作为一条语句执行时，PostgreSQL 会在行级锁下串行化这两个请求，
     * 后到者的条件求值为假 → 更新 0 行 → 我们据此拒绝请求。
     *
     * <p>因此本方法的返回值即结论：{@code true} 预占成功，{@code false} 可用量不足。
     * 不需要额外重试，也不依赖乐观锁版本号。
     *
     * @return 是否更新成功（即预占是否成功）
     */
    public boolean tryReserve(long skuId, long warehouseId, String batchNo, int qty) {
        int updated = jdbc.update("""
                UPDATE inventory_balances
                   SET reserved_qty = reserved_qty + ?,
                       version      = version + 1,
                       updated_at   = now()
                 WHERE sku_id = ? AND warehouse_id = ? AND batch_no = ?
                   AND reserved_qty + ? <= on_hand_qty - frozen_qty
                """, qty, skuId, warehouseId, batchNo, qty);
        return updated == 1;
    }

    /**
     * 释放预占（取消订单 / 超时释放）。
     *
     * <p>条件保证不会把预占释放成负数。
     */
    public boolean release(long skuId, long warehouseId, String batchNo, int qty) {
        int updated = jdbc.update("""
                UPDATE inventory_balances
                   SET reserved_qty = reserved_qty - ?,
                       version      = version + 1,
                       updated_at   = now()
                 WHERE sku_id = ? AND warehouse_id = ? AND batch_no = ?
                   AND reserved_qty >= ?
                """, qty, skuId, warehouseId, batchNo, qty);
        return updated == 1;
    }

    /**
     * 入库：增加实存。
     *
     * <p>入库不会触碰预占量，因此无需条件守卫；但若余额行不存在则先建行。
     */
    public void increaseOnHand(long skuId, long warehouseId, String batchNo,
                               java.time.LocalDate productionDate,
                               java.time.LocalDate expiryDate,
                               int qty) {
        int updated = jdbc.update("""
                UPDATE inventory_balances
                   SET on_hand_qty = on_hand_qty + ?,
                       version      = version + 1,
                       updated_at   = now()
                 WHERE sku_id = ? AND warehouse_id = ? AND batch_no = ?
                """, qty, skuId, warehouseId, batchNo);
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO inventory_balances
                        (sku_id, warehouse_id, batch_no, production_date, expiry_date, on_hand_qty, reserved_qty)
                    VALUES (?, ?, ?, ?, ?, ?, 0)
                    """, skuId, warehouseId, batchNo, productionDate, expiryDate, qty);
        }
    }

    /**
     * 出库扣减：同时减少实存与预占（预占转为实际出库）。
     *
     * <p>条件 {@code reserved_qty >= ?} 保证不会扣成负数。
     */
    public boolean consumeReserved(long skuId, long warehouseId, String batchNo, int qty) {
        int updated = jdbc.update("""
                UPDATE inventory_balances
                   SET on_hand_qty  = on_hand_qty - ?,
                       reserved_qty = reserved_qty - ?,
                       version      = version + 1,
                       updated_at   = now()
                 WHERE sku_id = ? AND warehouse_id = ? AND batch_no = ?
                   AND reserved_qty >= ?
                   AND on_hand_qty >= ?
                """, qty, qty, skuId, warehouseId, batchNo, qty, qty);
        return updated == 1;
    }

    /**
     * 回退实存（M2.5 进货红冲）：只减 {@code on_hand_qty}，不动预占。
     *
     * <p>两个条件缺一不可：
     * <ul>
     *   <li>{@code on_hand_qty >= qty} —— 不把实存减成负数（DB CHECK 是最后兜底）；</li>
     *   <li>{@code reserved_qty + frozen_qty <= on_hand_qty - qty} —— 回退后预占/冻结量
     *       不得超过实存（否则会违反 {@code ck_frozen_reserved_le_onhand}，且可用量变负）。</li>
     * </ul>
     *
     * <p>返回 {@code false} 表示该批次当前库存不足以回退（货已卖出或被预占），
     * 由应用层翻译成 {@code INV_NEGATIVE_NOT_ALLOWED}，不跨批次挪用。
     */
    public boolean decreaseOnHand(long skuId, long warehouseId, String batchNo, int qty) {
        int updated = jdbc.update("""
                UPDATE inventory_balances
                   SET on_hand_qty = on_hand_qty - ?,
                       version     = version + 1,
                       updated_at  = now()
                 WHERE sku_id = ? AND warehouse_id = ? AND batch_no = ?
                   AND on_hand_qty >= ?
                   AND reserved_qty + frozen_qty <= on_hand_qty - ?
                """, qty, skuId, warehouseId, batchNo, qty, qty);
        return updated == 1;
    }

    /**
     * 冻结库存（FR-INV-10）。
     *
     * <p>与预占同样用<b>一条带条件的 UPDATE</b> 完成"检查 + 写入"：
     * {@code frozen_qty + qty <= on_hand_qty - reserved_qty}。
     * 条件不满足说明可用量不足（已被预占或已冻结），返回 false 由应用层翻译成业务错误。
     */
    public boolean freeze(long skuId, long warehouseId, String batchNo, int qty) {
        int updated = jdbc.update("""
                UPDATE inventory_balances
                   SET frozen_qty = frozen_qty + ?,
                       version    = version + 1,
                       updated_at = now()
                 WHERE sku_id = ? AND warehouse_id = ? AND batch_no = ?
                   AND frozen_qty + ? <= on_hand_qty - reserved_qty
                """, qty, skuId, warehouseId, batchNo, qty);
        return updated == 1;
    }

    /** 解冻（人工解冻 / 超时解冻）：只减冻结量，不动实存与预占。 */
    public boolean releaseFrozen(long skuId, long warehouseId, String batchNo, int qty) {
        int updated = jdbc.update("""
                UPDATE inventory_balances
                   SET frozen_qty = frozen_qty - ?,
                       version    = version + 1,
                       updated_at = now()
                 WHERE sku_id = ? AND warehouse_id = ? AND batch_no = ?
                   AND frozen_qty >= ?
                """, qty, skuId, warehouseId, batchNo, qty);
        return updated == 1;
    }

    /** 某 (SKU, 仓库, 批次) 当前冻结量；行不存在返回 0。 */
    public int frozenQty(long skuId, long warehouseId, String batchNo) {
        Integer v = jdbc.queryForObject("""
                SELECT COALESCE(MAX(frozen_qty), 0) FROM inventory_balances
                 WHERE sku_id = ? AND warehouse_id = ? AND batch_no = ?
                """, Integer.class, skuId, warehouseId, batchNo);
        return v == null ? 0 : v;
    }
}
