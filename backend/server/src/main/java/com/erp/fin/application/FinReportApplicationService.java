package com.erp.fin.application;

import com.erp.fin.domain.AccountCategory;
import com.erp.fin.domain.AccountDirection;
import com.erp.fin.repository.FinBankRepository;
import com.erp.fin.repository.FinReportRepository;
import com.erp.fin.repository.FinReportRepository.Movement;
import com.erp.fin.repository.FinSourceRepository;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.time.ClockProvider;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 财务报表与财务总览（FR-V3-12 / FR-V3-13）。
 *
 * <p><b>配平口径</b>（简报 §4）：科目余额 = init_balance + Σ借 − Σ贷（按 direction 取号）；
 * 资产负债表要求 资产 = 负债 + 权益。损益类科目的净额尚未做"年末结转"（本批不做），
 * 以「4103 本年利润（未结转损益）」并入权益侧参与合计 —— 它就是复式记账的平衡项，
 * 但不该在权益之外单列，否则报表上会出现"权益 0 元"。
 * 利润用<b>截至报表期间的累计数</b>而非当期数 —— 与"余额截至该期末"同一时间轴，
 * 否则任何非首个期间都会平不了。
 *
 * <p>只统计 POSTED 凭证（未过账草稿不是账）。
 */
@Service
public class FinReportApplicationService {

    /** 资产负债表一侧的行。 */
    public record ReportRow(String code, String name, BigDecimal amount) {
    }

    /** 未结转损益在权益侧的挂账科目；科目表里没有 4103，不会与真实科目行重复计数。 */
    private static final String RETAINED_EARNINGS_CODE = "4103";

    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("yyyy-MM");

    private final FinReportRepository reports;
    private final FinBankRepository bank;
    private final FinSourceRepository sources;
    private final ClockProvider clock;

    public FinReportApplicationService(FinReportRepository reports, FinBankRepository bank,
                                       FinSourceRepository sources, ClockProvider clock) {
        this.reports = reports;
        this.bank = bank;
        this.sources = sources;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> balanceSheet(String period) {
        String p = requirePeriod(period);
        List<Movement> movements = reports.movementsUpTo(p);

        List<ReportRow> assets = new ArrayList<>();
        List<ReportRow> liabilities = new ArrayList<>();
        List<ReportRow> equity = new ArrayList<>();
        Map<AccountCategory, BigDecimal> nets = new EnumMap<>(AccountCategory.class);
        for (AccountCategory c : AccountCategory.values()) {
            nets.put(c, BigDecimal.ZERO);
        }
        for (Movement m : movements) {
            AccountCategory category = AccountCategory.valueOf(m.category());
            AccountDirection direction = AccountDirection.valueOf(m.direction());
            BigDecimal signed = direction == AccountDirection.DEBIT
                    ? m.initBalance().add(m.sumDebit()).subtract(m.sumCredit())
                    : m.initBalance().add(m.sumCredit()).subtract(m.sumDebit());
            ReportRow row = new ReportRow(m.accountCode(), m.accountName(), signed);
            switch (category) {
                case ASSET -> assets.add(row);
                case LIABILITY -> liabilities.add(row);
                case EQUITY -> equity.add(row);
                // 损益三类的"净影响"：收入/权益类取贷减借为正；成本费用类取借减贷为正
                case REVENUE -> nets.merge(AccountCategory.REVENUE,
                        m.sumCredit().subtract(m.sumDebit()), BigDecimal::add);
                case COST -> nets.merge(AccountCategory.COST,
                        m.sumDebit().subtract(m.sumCredit()), BigDecimal::add);
                case EXPENSE -> nets.merge(AccountCategory.EXPENSE,
                        m.sumDebit().subtract(m.sumCredit()), BigDecimal::add);
            }
        }
        BigDecimal accumulatedProfit = nets.get(AccountCategory.REVENUE)
                .subtract(nets.get(AccountCategory.COST))
                .subtract(nets.get(AccountCategory.EXPENSE));
        // 未结转损益挂进权益，报表才是 资产 = 负债 + 权益；放在独立行上会让人以为权益是 0
        equity.add(new ReportRow(RETAINED_EARNINGS_CODE, "本年利润（未结转损益）", accumulatedProfit));

        BigDecimal assetTotal = sumRows(assets);
        BigDecimal liabilityTotal = sumRows(liabilities);
        BigDecimal equityTotal = sumRows(equity);
        BigDecimal liabilityEquityTotal = liabilityTotal.add(equityTotal);
        BigDecimal diff = assetTotal.subtract(liabilityEquityTotal);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("period", p);
        out.put("assets", assets);
        out.put("liabilities", liabilities);
        out.put("equity", equity);
        out.put("assetTotal", assetTotal);
        out.put("liabilityTotal", liabilityTotal);
        out.put("equityTotal", equityTotal);
        out.put("accumulatedProfit", accumulatedProfit);
        out.put("liabilityEquityTotal", liabilityEquityTotal);
        out.put("diff", diff);
        out.put("balanced", diff.signum() == 0);
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> incomeStatement(String period) {
        String p = requirePeriod(period);
        List<Movement> movements = reports.movementsInPeriod(p);
        List<ReportRow> revenue = new ArrayList<>();
        List<ReportRow> cost = new ArrayList<>();
        List<ReportRow> expense = new ArrayList<>();
        for (Movement m : movements) {
            AccountCategory.valueOf(m.category());
            switch (AccountCategory.valueOf(m.category())) {
                case REVENUE -> revenue.add(new ReportRow(m.accountCode(), m.accountName(),
                        m.sumCredit().subtract(m.sumDebit())));
                case COST -> cost.add(new ReportRow(m.accountCode(), m.accountName(),
                        m.sumDebit().subtract(m.sumCredit())));
                case EXPENSE -> expense.add(new ReportRow(m.accountCode(), m.accountName(),
                        m.sumDebit().subtract(m.sumCredit())));
                default -> { /* 资产/负债/权益不进利润表 */ }
            }
        }
        BigDecimal revenueTotal = sumRows(revenue);
        BigDecimal costTotal = sumRows(cost);
        BigDecimal expenseTotal = sumRows(expense);
        BigDecimal grossProfit = revenueTotal.subtract(costTotal);
        BigDecimal netProfit = grossProfit.subtract(expenseTotal);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("period", p);
        out.put("revenue", revenue);
        out.put("cost", cost);
        out.put("expense", expense);
        out.put("revenueTotal", revenueTotal);
        out.put("costTotal", costTotal);
        out.put("expenseTotal", expenseTotal);
        out.put("grossProfit", grossProfit);
        out.put("netProfit", netProfit);
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> overview() {
        YearMonth current = YearMonth.from(clock.today());
        String currentPeriod = current.format(PERIOD);
        String fromPeriod = current.minusMonths(5).format(PERIOD);

        BigDecimal funds = bank.totalBalance();
        BigDecimal receivables =
                FinVoucherApplicationService.fenToYuan(sources.receivableBalanceFen());
        BigDecimal payables =
                FinVoucherApplicationService.fenToYuan(sources.payableBalanceFen());
        List<Map<String, Object>> cashFlow = bank.monthlyCashFlow(fromPeriod, currentPeriod);
        BigDecimal monthIn = BigDecimal.ZERO;
        BigDecimal monthOut = BigDecimal.ZERO;
        for (Map<String, Object> row : cashFlow) {
            if (currentPeriod.equals(row.get("period"))) {
                monthIn = (BigDecimal) row.get("inflow");
                monthOut = (BigDecimal) row.get("outflow");
            }
        }

        Map<String, Object> cards = new LinkedHashMap<>();
        cards.put("fundsBalance", funds);
        cards.put("receivableTotal", receivables);
        cards.put("payableTotal", payables);
        cards.put("monthInflow", monthIn);
        cards.put("monthOutflow", monthOut);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("period", currentPeriod);
        out.put("cards", cards);
        out.put("cashFlow", cashFlow);
        out.put("postedVoucherCount", reports.allPostedVoucherCount());
        out.put("periodVoucherCount", reports.postedVoucherCount(currentPeriod));
        return out;
    }

    private static String requirePeriod(String raw) {
        if (raw == null || !raw.matches("\\d{4}-\\d{2}")) {
            throw new BusinessException(ErrorCode.FIN_REPORT_PERIOD_INVALID,
                    "period 必须是 yyyy-MM，收到：" + raw);
        }
        return raw;
    }

    private static BigDecimal sumRows(List<ReportRow> rows) {
        BigDecimal t = BigDecimal.ZERO;
        for (ReportRow r : rows) {
            t = t.add(r.amount());
        }
        return t;
    }
}
