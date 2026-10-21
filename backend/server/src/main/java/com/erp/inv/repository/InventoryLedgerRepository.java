package com.erp.inv.repository;

import com.erp.inv.domain.InventoryLedgerEntry;
import com.erp.shared.web.PageResult;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 库存流水仓储（不可变事实源，ADR-0007）。
 *
 * <p>本类<b>只提供 insert 与查询</b>，刻意不提供 update / delete ——
 * 不变量 INV-2（实存 == 流水净和）依赖流水的不可变性。
 */
@Repository
public class InventoryLedgerRepository {

    private static final RowMapper<InventoryLedgerEntry> MAPPER = (rs, rowNum) ->
            new InventoryLedgerEntry(
                    rs.getLong("id"),
                    rs.getLong("sku_id"),
                    rs.getLong("warehouse_id"),
                    rs.getString("batch_no"),
                    rs.getInt("change_qty"),
                    rs.getString("biz_type"),
                    rs.getString("biz_ref_no"),
                    rs.getObject("unit_cost_amount", Long.class),
                    rs.getObject("occurred_at", OffsetDateTime.class));

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

    /**
     * 流水分页查询（FR-INV-02）。
     *
     * <p>按 {@code occurred_at DESC, id DESC} 排序：业务时间相同时用自增 id 兜底，
     * 否则同一毫秒内写入的多条流水顺序不稳定，翻页会漏行/重复行。
     */
    public PageResult<InventoryLedgerEntry> page(Long skuId, Long warehouseId, String bizRefNo,
                                                 OffsetDateTime from, OffsetDateTime to,
                                                 int page, int size) {
        int p = PageResult.normalizePage(page);
        int s = PageResult.normalizeSize(size);

        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (skuId != null) {
            where.append(" AND sku_id = ?");
            args.add(skuId);
        }
        if (warehouseId != null) {
            where.append(" AND warehouse_id = ?");
            args.add(warehouseId);
        }
        if (bizRefNo != null && !bizRefNo.isBlank()) {
            where.append(" AND biz_ref_no = ?");
            args.add(bizRefNo);
        }
        if (from != null) {
            where.append(" AND occurred_at >= ?");
            args.add(from);
        }
        if (to != null) {
            where.append(" AND occurred_at <= ?");
            args.add(to);
        }

        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_ledger" + where, Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(s);
        pageArgs.add(PageResult.offset(p, s));
        List<InventoryLedgerEntry> items = jdbc.query("""
                SELECT id, sku_id, warehouse_id, batch_no, change_qty, biz_type,
                       biz_ref_no, unit_cost_amount, occurred_at
                  FROM inventory_ledger
                """ + where + " ORDER BY occurred_at DESC, id DESC LIMIT ? OFFSET ?",
                MAPPER, pageArgs.toArray());

        return new PageResult<>(items, total == null ? 0 : total, p, s);
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
