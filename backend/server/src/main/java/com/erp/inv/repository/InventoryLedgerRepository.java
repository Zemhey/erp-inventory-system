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

    /** 数据权限（FR-IAM-04）：流水只有仓库维度（它是单据产生的，不是人建的）。 */
    private final com.erp.shared.scope.ScopeContext scope;

    public InventoryLedgerRepository(JdbcTemplate jdbc,
                                     com.erp.shared.scope.ScopeContext scope) {
        this.jdbc = jdbc;
        this.scope = scope;
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
        // ★ 数据权限：流水是"关联查询/统计聚合"最常用的入口，收窄必须在这里也生效，
        //   否则前四个接口都挡住了、从流水反查却能看到别仓的每一笔进出。
        where.append(scope.warehouseOnly("warehouse_id", args));

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

    /**
     * 按来源单号数流水条数（关联查询的一种）。
     *
     * <p>刻意<b>不</b>加数据权限：本方法只返回一个整数，用于
     * "这张单是否产生过库存变动"这类<b>安全不变量</b>校验
     * （作废前置检查、幂等判定）。把权限塞进不变量校验会让
     * "看不到 B 仓的人以为 B 仓的单没入库" —— 那是个正确性 bug，不是权限问题。
     * 面向用户的流水条数走 {@link #page}，那里有权限谓词。
     */
    public int countByBizRef(String bizRefNo) {
        Integer v = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_ledger WHERE biz_ref_no = ?",
                Integer.class, bizRefNo);
        return v == null ? 0 : v;
    }

    /**
     * 按来源单号 + 业务类型取原始流水（M2.5 红冲专用）。
     *
     * <p>红冲必须知道"当初扣/入了哪些批次、各多少"，否则无法逐批次反向。
     * 按 id 升序返回，保证同一单多次入库/出货时反向顺序稳定、可复现。
     */
    public List<InventoryLedgerEntry> findByBizRefAndBizType(String bizRefNo, String bizType) {
        return jdbc.query("""
                SELECT id, sku_id, warehouse_id, batch_no, change_qty, biz_type,
                       biz_ref_no, unit_cost_amount, occurred_at
                  FROM inventory_ledger
                 WHERE biz_ref_no = ? AND biz_type = ?
                 ORDER BY id
                """, MAPPER, bizRefNo, bizType);
    }

    public List<String> bizTypesByRequestId(String requestId) {
        return jdbc.queryForList(
                "SELECT biz_type FROM inventory_ledger WHERE request_id = ? ORDER BY id",
                String.class, requestId);
    }
}
