package com.erp.cst.repository;

import com.erp.cst.domain.CostDirection;
import com.erp.cst.domain.CostLayer;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 成本层仓储：{@code cost_layers} 的唯一读写入口，只被 cst 模块使用。
 *
 * <p>成本层是派生数据，因此允许整表重建（{@link #deleteAll()} + {@link #insertAll}）；
 * 但重建必须在 advisory lock 内完成，否则并发重建会互相删掉对方的行。
 */
@Repository
public class CostLayerRepository {

    private static final RowMapper<CostLayer> MAPPER = (rs, rowNum) -> new CostLayer(
            rs.getLong("ledger_id"),
            rs.getLong("sku_id"),
            rs.getLong("warehouse_id"),
            rs.getString("batch_no"),
            rs.getString("biz_type"),
            rs.getString("biz_ref_no"),
            CostDirection.valueOf(rs.getString("direction")),
            rs.getInt("qty"),
            rs.getLong("unit_cost_amount"),
            rs.getLong("total_cost_amount"),
            rs.getLong("pool_qty_after"),
            rs.getLong("pool_cost_after"),
            rs.getObject("occurred_at", java.time.OffsetDateTime.class));

    private static final String INSERT = """
            INSERT INTO cost_layers
                (ledger_id, sku_id, warehouse_id, batch_no, biz_type, biz_ref_no, direction,
                 qty, unit_cost_amount, total_cost_amount, pool_qty_after, pool_cost_after,
                 occurred_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbc;

    public CostLayerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void deleteAll() {
        jdbc.update("DELETE FROM cost_layers");
    }

    public void deletePool(long skuId, long warehouseId) {
        jdbc.update("DELETE FROM cost_layers WHERE sku_id = ? AND warehouse_id = ?",
                skuId, warehouseId);
    }

    public void insertAll(List<CostLayer> layers) {
        if (layers.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(INSERT, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                CostLayer l = layers.get(i);
                ps.setLong(1, l.ledgerId());
                ps.setLong(2, l.skuId());
                ps.setLong(3, l.warehouseId());
                ps.setString(4, l.batchNo());
                ps.setString(5, l.bizType());
                ps.setString(6, l.bizRefNo());
                ps.setString(7, l.direction().name());
                ps.setInt(8, l.qty());
                ps.setLong(9, l.unitCostAmount());
                ps.setLong(10, l.totalCostAmount());
                ps.setLong(11, l.poolQtyAfter());
                ps.setLong(12, l.poolCostAfter());
                ps.setObject(13, l.occurredAt());
            }

            @Override
            public int getBatchSize() {
                return layers.size();
            }
        });
    }

    public List<CostLayer> findByPool(long skuId, long warehouseId) {
        return jdbc.query("""
                SELECT * FROM cost_layers
                 WHERE sku_id = ? AND warehouse_id = ?
                 ORDER BY occurred_at, id
                """, MAPPER, skuId, warehouseId);
    }

    public List<CostLayer> findAll() {
        return jdbc.query("SELECT * FROM cost_layers ORDER BY occurred_at, id", MAPPER);
    }

    public long count() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM cost_layers", Long.class);
        return n == null ? 0 : n;
    }

    public long sumByDirection(CostDirection direction) {
        Long v = jdbc.queryForObject("""
                SELECT COALESCE(SUM(total_cost_amount), 0)
                  FROM cost_layers WHERE direction = ?
                """, Long.class, direction.name());
        return v == null ? 0 : v;
    }

    /** 当前结存成本 = 每个成本池最后一条成本层的 pool_cost_after 之和。 */
    public long endingCost() {
        Long v = jdbc.queryForObject("""
                SELECT COALESCE(SUM(pool_cost_after), 0) FROM (
                    SELECT DISTINCT ON (sku_id, warehouse_id) pool_cost_after
                      FROM cost_layers
                     ORDER BY sku_id, warehouse_id, occurred_at DESC, id DESC
                ) latest
                """, Long.class);
        return v == null ? 0 : v;
    }

    // ─────────────────────────── 事务级咨询锁 ───────────────────────────
    //
    // 成本池没有独立的状态表（池状态由最后一条成本层表达），无法 SELECT ... FOR UPDATE
    // 一把"还不存在的池"。advisory xact lock 正好补这个缺口：
    // 同一 (sku, warehouse) 的重算串行化，后到者读到前一个事务提交后的流水；
    // 锁在事务结束时自动释放，不需要额外的锁表或清理任务。

    public void lockGlobal() {
        advisoryLock("cost:global");
    }

    /**
     * 共用全局锁（PostgreSQL shared advisory lock）。
     *
     * <p>单池重算持共享全局锁 + 池级排他锁：不同池可以并行，
     * 但与全量重算的排他全局锁互斥。这样「全量重建删表」与
     * 「并发单池重算插入」不会交错，避免删掉对方刚写的行。
     */
    public void lockGlobalShared() {
        advisoryLockShared("cost:global");
    }

    public void lockPool(long skuId, long warehouseId) {
        advisoryLock("cost:pool:" + skuId + ":" + warehouseId);
    }

    public void lockPeriod(String period) {
        advisoryLock("cost:monthly:" + period);
    }

    private void advisoryLock(String key) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                rs -> { }, key);
    }

    private void advisoryLockShared(String key) {
        jdbc.query("SELECT pg_advisory_xact_lock_shared(hashtextextended(?, 0))",
                rs -> { }, key);
    }
}
