package com.erp.set.repository;

import com.erp.set.domain.DailySettlement;
import com.erp.set.domain.SettlementTotals;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 日清行仓储（{@code daily_settlements}）。
 *
 * <p><b>幂等写在 SQL 里，不是写在 if 里</b>：{@link #upsert} 用
 * {@code INSERT .. ON CONFLICT (biz_date, warehouse_id) DO UPDATE SET = EXCLUDED}，
 * 语义是"这个键上的账目<b>等于</b>本次重算值"，而不是"累加"。
 * 两个请求同时对同一天点日清时，后来者会阻塞在唯一索引上，等前者提交后再整体覆盖，
 * 结果仍是同一行、同一金额 —— 这正是 TC-SET-013 要的性质。
 *
 * <p>如果写成"累加"（{@code SET purchase_qty = daily_settlements.purchase_qty + ?}），
 * 并发日清就会把当天的账算成两倍，而且测试还得靠运气才能复现。
 */
@Repository
public class DailySettlementRepository {

    private static final RowMapper<DailySettlement> MAPPER = (rs, i) -> new DailySettlement(
            rs.getObject("biz_date", LocalDate.class),
            rs.getLong("warehouse_id"),
            new SettlementTotals(
                    rs.getLong("purchase_qty"), rs.getLong("purchase_amount"),
                    rs.getLong("sales_qty"), rs.getLong("sales_amount"),
                    rs.getLong("return_qty"), rs.getLong("return_amount")));

    private final JdbcTemplate jdbc;

    public DailySettlementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 写入（或覆盖）一条日清行。 */
    public void upsert(DailySettlement row, String requestId, OffsetDateTime settledAt) {
        SettlementTotals t = row.totals();
        jdbc.update("""
                INSERT INTO daily_settlements
                    (biz_date, warehouse_id, purchase_qty, purchase_amount,
                     sales_qty, sales_amount, return_qty, return_amount, net_amount,
                     request_id, settled_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (biz_date, warehouse_id) DO UPDATE SET
                    purchase_qty    = EXCLUDED.purchase_qty,
                    purchase_amount = EXCLUDED.purchase_amount,
                    sales_qty       = EXCLUDED.sales_qty,
                    sales_amount    = EXCLUDED.sales_amount,
                    return_qty      = EXCLUDED.return_qty,
                    return_amount   = EXCLUDED.return_amount,
                    net_amount      = EXCLUDED.net_amount,
                    request_id      = EXCLUDED.request_id,
                    settled_at      = EXCLUDED.settled_at
                """, row.bizDate(), row.warehouseId(), t.purchaseQty(), t.purchaseAmount(),
                t.salesQty(), t.salesAmount(), t.returnQty(), t.returnAmount(), t.netAmount(),
                requestId, settledAt);
    }

    /** 读取某天的日清行；{@code warehouseId} 为 null 表示全部仓库。 */
    public List<DailySettlement> findByDate(LocalDate bizDate, Long warehouseId) {
        String sql = """
                SELECT biz_date, warehouse_id, purchase_qty, purchase_amount,
                       sales_qty, sales_amount, return_qty, return_amount
                  FROM daily_settlements
                 WHERE biz_date = ?
                """ + (warehouseId == null ? "" : " AND warehouse_id = ?")
                + " ORDER BY warehouse_id";
        return warehouseId == null
                ? jdbc.query(sql, MAPPER, bizDate)
                : jdbc.query(sql, MAPPER, bizDate, warehouseId);
    }

    /** 读取一个日期区间内的全部日清行（月结前比对"已清账"与"重算值"是否漂移）。 */
    public List<DailySettlement> findInRange(LocalDate from, LocalDate to) {
        return jdbc.query("""
                SELECT biz_date, warehouse_id, purchase_qty, purchase_amount,
                       sales_qty, sales_amount, return_qty, return_amount
                  FROM daily_settlements
                 WHERE biz_date BETWEEN ? AND ?
                 ORDER BY biz_date, warehouse_id
                """, MAPPER, from, to);
    }
}
