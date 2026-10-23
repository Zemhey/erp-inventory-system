package com.erp.set.repository;

import com.erp.set.domain.SettlementPeriodRow;
import com.erp.set.domain.SettlementPeriodStatus;
import com.erp.set.domain.SettlementTotals;
import com.erp.shared.web.PageResult;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 账期<b>只读</b>仓储（{@code settlement_periods} 的列表查询）。
 *
 * <p><b>本类没有 insert / update / delete，也不会加</b>：它存在的理由是计划书 §6 坑 15 ——
 * 只有 {@code set:read} 的账号（VIEWER / FINANCE）此前没有任何可消费的读出口，
 * 而补这个口绝不能顺手把结算页变成第二个写账期的地方。
 * 写路径继续由 {@link SettlementPeriodRepository} 独占（锁账只有月结一个入口）。
 *
 * <p><b>不做重算</b>：返回的是已经落在账本上的行。期间数字对不对由
 * {@code GET /settlements/reconcile}（{@code set:reconcile}）负责，两者口径不同、
 * 各用一个权限码，别在只读列表里偷偷做对账。
 */
@Repository
public class SettlementPeriodQueryRepository {

    private static final RowMapper<SettlementPeriodRow> ROW_MAPPER = (rs, n) ->
            new SettlementPeriodRow(
                    rs.getString("period"),
                    SettlementPeriodStatus.valueOf(rs.getString("status")),
                    new SettlementTotals(
                            rs.getLong("purchase_qty"), rs.getLong("purchase_amount"),
                            rs.getLong("sales_qty"), rs.getLong("sales_amount"),
                            rs.getLong("return_qty"), rs.getLong("return_amount")),
                    rs.getObject("closed_at", OffsetDateTime.class),
                    rs.getObject("closed_by", Long.class));

    private final JdbcTemplate jdbc;

    public SettlementPeriodQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 分页列出账期，按 {@code period} 倒序（最新期间在前 —— 结算页第一眼要看到的是最近一个月）。
     *
     * @param status null = 全部状态；否则只给该状态的账期
     */
    public PageResult<SettlementPeriodRow> page(SettlementPeriodStatus status, int page, int size) {
        int p = PageResult.normalizePage(page);
        int s = PageResult.normalizeSize(size);
        List<Object> args = new ArrayList<>();
        String where = "";
        if (status != null) {
            where = " WHERE status = ?";
            args.add(status.name());
        }
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM settlement_periods" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(s);
        pageArgs.add(PageResult.offset(p, s));
        List<SettlementPeriodRow> items = jdbc.query("SELECT * FROM settlement_periods"
                + where + " ORDER BY period DESC LIMIT ? OFFSET ?", ROW_MAPPER, pageArgs.toArray());
        return new PageResult<>(items, total == null ? 0 : total, p, s);
    }
}
