package com.erp.set.repository;

import com.erp.set.domain.SettlementPeriod;
import com.erp.set.domain.SettlementPeriodStatus;
import com.erp.set.domain.SettlementTotals;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 账期仓储（{@code settlement_periods}）：锁账状态与期间汇总的唯一读写入口。
 *
 * <p>三条约定决定了这里的方法形状：
 * <ul>
 *   <li><b>锁账只认 CLOSED</b>：{@link #isClosed} 是给 {@code SettlementPeriodGuard} 用的，
 *       行不存在与 OPEN 一律视为"可继续记账"（计划书 §6 坑 3）；</li>
 *   <li><b>乐观锁</b>：{@link #updateToClosed} 用 {@code WHERE version = ?}，
 *       返回 false 表示并发月结已抢先，由应用层重新判定而不是覆盖；</li>
 *   <li><b>不提供解锁</b>：没有把 CLOSED 改回 OPEN 的方法 —— 锁账不可逆是
 *       计划书 §3.4 的产品决定，少一个入口就少一类"偷偷把已锁账期改开"的事故。</li>
 * </ul>
 */
@Repository
public class SettlementPeriodRepository {

    private static final RowMapper<SettlementPeriod> MAPPER = (rs, i) -> new SettlementPeriod(
            rs.getString("period"),
            SettlementPeriodStatus.valueOf(rs.getString("status")),
            new SettlementTotals(
                    rs.getLong("purchase_qty"), rs.getLong("purchase_amount"),
                    rs.getLong("sales_qty"), rs.getLong("sales_amount"),
                    rs.getLong("return_qty"), rs.getLong("return_amount")),
            rs.getObject("closed_at", OffsetDateTime.class),
            rs.getInt("version"));

    private final JdbcTemplate jdbc;

    public SettlementPeriodRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 账期是否已锁。<b>单列查询</b>而不是取回整个 {@code SettlementPeriod}：
     * 这个方法挂在 PUR/SAL/RMA 每一次写操作上，属于最热的路径之一。
     */
    public boolean isClosed(String period) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM settlement_periods
                 WHERE period = ? AND status = 'CLOSED'
                """, Integer.class, period);
        return n != null && n > 0;
    }

    public Optional<SettlementPeriod> findByPeriod(String period) {
        return jdbc.query("SELECT * FROM settlement_periods WHERE period = ?", MAPPER, period)
                .stream().findFirst();
    }

    /**
     * 首次月结：直接以 CLOSED 落一行。
     *
     * <p><b>为什么用 {@code ON CONFLICT DO NOTHING} 而不是 try/catch 主键冲突</b>：
     * PostgreSQL 里<b>任何</b>语句报错都会把整个事务打进 aborted 状态
     * （"current transaction is aborted, commands ignored until end of transaction block"），
     * 捕获异常后继续查询必然失败。DO NOTHING 不报错，还会阻塞到并发对手出结果，
     * 因此"返回 0 行"就是干净的"你来晚了"，调用方可以立刻重读并按幂等分支收敛。
     *
     * @return true 表示这一行由本次请求创建；false 表示账期已存在（并发或重复月结）
     */
    public boolean insertClosed(String period, SettlementTotals totals, OffsetDateTime closedAt) {
        int rows = jdbc.update("""
                INSERT INTO settlement_periods
                    (period, status, purchase_qty, purchase_amount, sales_qty, sales_amount,
                     return_qty, return_amount, net_amount, closed_at, version)
                VALUES (?, 'CLOSED', ?, ?, ?, ?, ?, ?, ?, ?, 0)
                ON CONFLICT (period) DO NOTHING
                """, period, totals.purchaseQty(), totals.purchaseAmount(),
                totals.salesQty(), totals.salesAmount(), totals.returnQty(),
                totals.returnAmount(), totals.netAmount(), closedAt);
        return rows == 1;
    }

    /**
     * 对已有（OPEN）账期推进到 CLOSED，带乐观锁。
     *
     * @return 更新行数是否为 1；0 表示版本已被他人推进
     */
    public boolean updateToClosed(String period, SettlementTotals totals, OffsetDateTime closedAt,
                                  int expectedVersion) {
        int rows = jdbc.update("""
                UPDATE settlement_periods
                   SET status = 'CLOSED',
                       purchase_qty = ?, purchase_amount = ?,
                       sales_qty = ?, sales_amount = ?,
                       return_qty = ?, return_amount = ?,
                       net_amount = ?, closed_at = ?,
                       version = version + 1
                 WHERE period = ? AND status = 'OPEN' AND version = ?
                """, totals.purchaseQty(), totals.purchaseAmount(), totals.salesQty(),
                totals.salesAmount(), totals.returnQty(), totals.returnAmount(),
                totals.netAmount(), closedAt, period, expectedVersion);
        return rows == 1;
    }
}
