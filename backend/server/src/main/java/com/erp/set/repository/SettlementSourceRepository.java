package com.erp.set.repository;

import com.erp.set.domain.SalesSourceLine;
import com.erp.set.domain.SourceAmountLine;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 结算的<b>源数据</b>仓储：跨 PUR / SAL / RMA 三域的<b>只读</b>聚合查询。
 *
 * <p><b>为什么 SET 自己写 SQL 而不是调用三个应用服务</b>（计划书 §3.1）：
 * 结算是"把已经发生的事实按日期重新数一遍"，它不需要单据模块的任何业务规则；
 * 若反过来让 SET 依赖 {@code PurchaseOrderApplicationService}，
 * 而 PUR/SAL/RMA 又依赖 {@code PeriodGuard}（由 SET 实现），包依赖立刻成环。
 * 只读 SQL 是唯一不破分层、也不成环的做法 —— 本类<b>没有任何 UPDATE</b>。
 *
 * <p><b>口径（计划书 §3.4 / §7 决策 1）</b>：一律按<b>单据 {@code biz_date}</b> 归属，
 * 不按 {@code inventory_ledger.occurred_at} 的实际执行时刻。
 * 一张单分几批入库，全部记到单据那一天。这是明确接受的简化，
 * 换来的是"锁账后历史日清行永不再变"。
 */
@Repository
public class SettlementSourceRepository {

    private static final RowMapper<SourceAmountLine> AMOUNT_MAPPER = (rs, i) ->
            new SourceAmountLine(rs.getObject("biz_date", LocalDate.class),
                    rs.getLong("warehouse_id"), rs.getLong("qty"), rs.getLong("amount"));

    private static final RowMapper<SalesSourceLine> SALES_MAPPER = (rs, i) ->
            new SalesSourceLine(rs.getObject("biz_date", LocalDate.class),
                    rs.getLong("warehouse_id"), rs.getInt("shipped_qty"),
                    rs.getLong("unit_price_amount"), rs.getInt("discount_rate"));

    private final JdbcTemplate jdbc;

    public SettlementSourceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 采购入库：{@code status IN ('PARTIAL','COMPLETED')} 的进货单，按累计入库量计。
     *
     * <p>用 {@code received_qty} 而不是 {@code qty} —— 已审核但还没入库的单
     * 不构成账务事实。金额是"单价 × 入库量"的纯整数乘积，可以安全地在 SQL 里 SUM。
     */
    public List<SourceAmountLine> purchaseByDate(LocalDate from, LocalDate to) {
        return jdbc.query("""
                SELECT o.biz_date, o.warehouse_id,
                       SUM(l.received_qty)::bigint                  AS qty,
                       SUM(l.unit_price_amount * l.received_qty)::bigint AS amount
                  FROM purchase_orders o
                  JOIN purchase_order_lines l ON l.order_id = o.id
                 WHERE o.biz_date BETWEEN ? AND ?
                   AND o.status IN ('PARTIAL', 'COMPLETED')
                   AND l.received_qty > 0
                 GROUP BY o.biz_date, o.warehouse_id
                 ORDER BY o.biz_date, o.warehouse_id
                """, AMOUNT_MAPPER, from, to);
    }

    /**
     * 销售出库：{@code status IN ('PARTIAL','COMPLETED')} 的出货单，按实际出货量计。
     *
     * <p><b>刻意不聚合</b>：行金额要用<em>出货数量</em>重算折扣
     * （{@code sales_order_lines.amount} 记的是订购数量的金额），
     * 而舍入必须与 SAL 建单时逐位一致。把 round 抄一份进 SQL 就是计划书 §6 坑 7
     * 点名的尾差来源，所以这里逐行取回，由应用服务调用
     * {@code SalesOrderApplicationService.lineAmount} 计算。
     */
    public List<SalesSourceLine> salesLinesByDate(LocalDate from, LocalDate to) {
        return jdbc.query("""
                SELECT o.biz_date, o.warehouse_id,
                       l.shipped_qty, l.unit_price_amount, l.discount_rate
                  FROM sales_orders o
                  JOIN sales_order_lines l ON l.order_id = o.id
                 WHERE o.biz_date BETWEEN ? AND ?
                   AND o.status IN ('PARTIAL', 'COMPLETED')
                   AND l.shipped_qty > 0
                 ORDER BY o.biz_date, o.warehouse_id
                """, SALES_MAPPER, from, to);
    }

    /**
     * 退货入库：只认 {@code status = 'COMPLETED'}（未入库的退货在账务上还没发生）。
     *
     * <p>金额直接取行表 {@code amount}：退货建单时已用 SAL 同一公式定稿，
     * 重算反而会引入"两处公式各自演化"的风险。
     */
    public List<SourceAmountLine> returnsByDate(LocalDate from, LocalDate to) {
        return jdbc.query("""
                SELECT o.biz_date, o.warehouse_id,
                       SUM(l.qty)::bigint    AS qty,
                       SUM(l.amount)::bigint AS amount
                  FROM return_orders o
                  JOIN return_order_lines l ON l.order_id = o.id
                 WHERE o.biz_date BETWEEN ? AND ?
                   AND o.status = 'COMPLETED'
                 GROUP BY o.biz_date, o.warehouse_id
                 ORDER BY o.biz_date, o.warehouse_id
                """, AMOUNT_MAPPER, from, to);
    }

    /** 全部仓库 id（日清省略 warehouseId 时的目标集合，含已停用仓 —— 历史账目不能因为停用就消失）。 */
    public List<Long> allWarehouseIds() {
        return jdbc.queryForList("SELECT id FROM warehouses ORDER BY id", Long.class);
    }
}
