package com.erp.inv.repository;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 库存流水仓储（不可变事实源，ADR-0007）。
 *
 * <p>本类<b>只提供 insert 与查询</b>，刻意不提供 update / delete ——
 * 不变量 INV-2（实存 == 流水净和）依赖流水的不可变性。
 */
@Repository
public class InventoryLedgerRepository {

    private final JdbcTemplate jdbc;

    public InventoryLedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 写入一条流水。
     *
     * <p>{@code occurredAt} 由 {@link com.erp.shared.time.ClockProvider} 注入，
     * 因此结算与效期相关测试可以完全控制业务时间。
     *
     * <p>{@code requestId} 参与唯一索引 {@code uq_ledger_request}：
     * 重复提交会触发约束冲突而非产生第二条流水（不变量 INV-9）。
     */
    public void insert(long skuId, long warehouseId, String batchNo, int changeQty,
                       String bizType, String bizRefNo, Long bizRefId,
                       Long unitCostAmount, String requestId, LocalDateTime occurredAt) {
        jdbc.update("""
                INSERT INTO inventory_ledger
                    (sku_id, warehouse_id, batch_no, change_qty, biz_type,
                     biz_ref_no, biz_ref_id, unit_cost_amount, request_id, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, skuId, warehouseId, batchNo, changeQty, bizType,
                bizRefNo, bizRefId, unitCostAmount, requestId, occurredAt);
    }

    /**
     * 某 (SKU, 仓库, 批次) 的流水净和 —— 用于校验不变量 INV-2。
     */
    public int netChange(long skuId, long warehouseId, String batchNo) {
        Integer v = jdbc.queryForObject("""
                SELECT COALESCE(SUM(change_qty), 0) FROM inventory_ledger
                 WHERE sku_id = ? AND warehouse_id = ? AND batch_no = ?
                """, Integer.class, skuId, warehouseId, batchNo);
        return v == null ? 0 : v;
    }

    public int countByBizRef(String bizRefNo) {
        Integer v = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_ledger WHERE biz_ref_no = ?",
                Integer.class, bizRefNo);
        return v == null ? 0 : v;
    }

    public List<String> bizTypesByRequestId(String requestId) {
        return jdbc.queryForList(
                "SELECT biz_type FROM inventory_ledger WHERE request_id = ? ORDER BY id",
                String.class, requestId);
    }
}
