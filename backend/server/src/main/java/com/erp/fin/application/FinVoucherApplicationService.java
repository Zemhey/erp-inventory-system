package com.erp.fin.application;

import com.erp.fin.domain.AccountDirection;
import com.erp.fin.domain.BankAccount;
import com.erp.fin.domain.Expense;
import com.erp.fin.domain.FinAccount;
import com.erp.fin.domain.StandardChartOfAccounts;
import com.erp.fin.domain.Voucher;
import com.erp.fin.domain.VoucherLine;
import com.erp.fin.domain.VoucherSourceType;
import com.erp.fin.domain.VoucherStatus;
import com.erp.fin.repository.FinAccountRepository;
import com.erp.fin.repository.FinBankRepository;
import com.erp.fin.repository.FinExpenseRepository;
import com.erp.fin.repository.FinSourceRepository;
import com.erp.fin.repository.FinVoucherRepository;
import com.erp.shared.document.DocumentNumberGenerator;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.finance.VoucherTrigger;
import com.erp.shared.period.PeriodGuard;
import com.erp.shared.security.ActorContext;
import com.erp.shared.time.ClockProvider;
import com.erp.shared.web.PageResult;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 凭证管理（FR-V3-07）+ 业务单据自动生成凭证（FR-V3-08，实现 {@link VoucherTrigger}）。
 *
 * <p><b>借贷平衡是凭证的入场券</b>：不等直接 400，details 回
 * {@code totalDebit/totalCredit/diff} —— 简报验收"不平衡被拒绝且提示差额"，
 * 差额必须在响应里，而不是让用户自己算。数据库 CHECK（ck_fin_voucher_balanced）
 * 是第二道防线。
 *
 * <p><b>自动生成的凭证直接落 POSTED</b>：单据"确认"即业务上的记账触发点；
 * 停在 DRAFT 会让报表（只统计 POSTED）与业务事实脱节（见 VoucherStatus 注释）。
 * 手工凭证走完整状态机 DRAFT → AUDITED → POSTED。
 *
 * <p><b>幂等</b>：同一 {@code (source_type, source_id)} 只生成一次；
 * 应用先查后插，并发窗口由 {@code uq_fin_voucher_source} 兜底并回查返回既有凭证。
 */
@Service
public class FinVoucherApplicationService implements VoucherTrigger {

    /** 手工录入的单行分录（金额是"元"，两位小数）。 */
    public record LineCommand(Long accountId, String summary, BigDecimal debit, BigDecimal credit) {
    }

    public record VoucherCommand(LocalDate voucherDate, String remark, List<LineCommand> lines) {
    }

    private final FinVoucherRepository vouchers;
    private final FinAccountApplicationService accountService;
    private final FinAccountRepository accounts;
    private final FinSourceRepository sources;
    private final FinBankRepository bank;
    private final FinExpenseRepository expenses;
    private final DocumentNumberGenerator numbers;
    private final ClockProvider clock;
    private final PeriodGuard periodGuard;
    private final ActorContext actors;

    public FinVoucherApplicationService(FinVoucherRepository vouchers,
                                        FinAccountApplicationService accountService,
                                        FinAccountRepository accounts,
                                        FinSourceRepository sources,
                                        FinBankRepository bank,
                                        FinExpenseRepository expenses,
                                        DocumentNumberGenerator numbers,
                                        ClockProvider clock,
                                        PeriodGuard periodGuard,
                                        ActorContext actors) {
        this.vouchers = vouchers;
        this.accountService = accountService;
        this.accounts = accounts;
        this.sources = sources;
        this.bank = bank;
        this.expenses = expenses;
        this.numbers = numbers;
        this.clock = clock;
        this.periodGuard = periodGuard;
        this.actors = actors;
    }

    // ─────────────────────────── 手工凭证 CRUD / 状态机 ───────────────────────────

    @Transactional(readOnly = true)
    public PageResult<Voucher> page(VoucherStatus status, VoucherSourceType sourceType,
                                    String period, LocalDate from, LocalDate to, String keyword,
                                    int page, int size) {
        return vouchers.page(status, sourceType, period, from, to, keyword, page, size);
    }

    @Transactional(readOnly = true)
    public Voucher require(long id) {
        return vouchers.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.FIN_VOUCHER_NOT_FOUND,
                        "凭证不存在：id=" + id));
    }

    @Transactional
    public Voucher create(VoucherCommand cmd) {
        LocalDate date = cmd.voucherDate() == null ? clock.today() : cmd.voucherDate();
        periodGuard.requireOpen(date);
        accountService.ensureSeeded();
        List<VoucherLine> lines = validateLines(cmd.lines());
        BigDecimal[] totals = totals(lines);
        requireBalanced(totals);
        Long actorId = actors.current().map(ActorContext.Actor::id).orElse(null);
        long id = vouchers.insertHeader(
                numbers.next(DocumentNumberGenerator.DocType.FIN_VOUCHER, date),
                date, periodOf(date), VoucherSourceType.MANUAL, null, null,
                VoucherStatus.DRAFT, totals[0], totals[1], actorId, trim(cmd.remark()), null);
        vouchers.replaceLines(id, lines);
        return require(id);
    }

    @Transactional
    public Voucher update(long id, VoucherCommand cmd) {
        Voucher existing = require(id);
        if (existing.status() != VoucherStatus.DRAFT) {
            throw new BusinessException(ErrorCode.FIN_VOUCHER_STATUS_ILLEGAL,
                    "凭证 %s 状态为 %s，只有草稿可修改（已过账只能红冲）"
                            .formatted(existing.voucherNo(), existing.status()));
        }
        LocalDate date = cmd.voucherDate() == null ? existing.voucherDate() : cmd.voucherDate();
        periodGuard.requireOpen(date);
        List<VoucherLine> lines = validateLines(cmd.lines());
        BigDecimal[] totals = totals(lines);
        requireBalanced(totals);
        if (!vouchers.updateDraftHead(id, date, periodOf(date), totals[0], totals[1],
                cmd.remark() == null ? existing.remark() : trim(cmd.remark()))) {
            throw new BusinessException(ErrorCode.FIN_VOUCHER_STATUS_ILLEGAL,
                    "凭证 %s 状态已被并发修改，请刷新后重试".formatted(existing.voucherNo()));
        }
        vouchers.replaceLines(id, lines);
        return require(id);
    }

    @Transactional
    public Voucher audit(long id) {
        Voucher v = require(id);
        transition(v, VoucherStatus.AUDITED);
        return require(id);
    }

    @Transactional
    public Voucher post(long id) {
        Voucher v = require(id);
        periodGuard.requireOpen(v.voucherDate());
        transition(v, VoucherStatus.POSTED);
        return require(id);
    }

    /**
     * 删除凭证（V4-1 接驳：录错了的草稿必须能删）。
     *
     * <p><b>只允许 DRAFT</b>。AUDITED / POSTED 一律 409 拒绝并指向既有语义：
     * 已过账的凭证进了报表与科目余额，删掉会让"期初 + 本期发生 = 期末"当场不平，
     * 那是数据事故而不是便利功能；正解是作废/红冲（本期尚未提供）。
     *
     * <p>自动生成的凭证（{@code sourceType != MANUAL}）落库即 POSTED，
     * 因此天然走不到这里 —— 要冲销请去冲销来源单据，凭证只是它的投影。
     *
     * <p>只有 DRAFT 可删：AUDITED/POSTED 已进账务流程，改动必须留痕，只能冲销。
     */
    @Transactional
    public void delete(long id) {
        Voucher v = require(id);
        if (v.status() != VoucherStatus.DRAFT) {
            String origin = v.sourceType() == VoucherSourceType.MANUAL
                    ? "如需冲销请走作废/红冲"
                    : "该凭证由业务单据自动生成，请改为冲销来源单据";
            throw new BusinessException(ErrorCode.FIN_VOUCHER_DELETE_FORBIDDEN,
                    "凭证 %s 状态为 %s，只有草稿可删除；%s"
                            .formatted(v.voucherNo(), v.status().label(), origin));
        }
        // 不做账期守卫：报表只统计 POSTED 凭证（见 FinReportRepository 的
        // WHERE v.status = 'POSTED'），而这里只允许删 DRAFT —— 草稿不可能出现在任何
        // 已封板期间的发生额里。加守卫只会造成"月结后垃圾草稿永远删不掉"。
        if (!vouchers.deleteDraft(id)) {
            throw new BusinessException(ErrorCode.FIN_VOUCHER_DELETE_FORBIDDEN,
                    "凭证 %s 在删除前已被并发改为非草稿状态，未删除"
                            .formatted(v.voucherNo()));
        }
    }

    private void transition(Voucher v, VoucherStatus target) {
        if (!v.status().canTransitionTo(target)) {
            throw new BusinessException(ErrorCode.FIN_VOUCHER_STATUS_ILLEGAL,
                    "凭证 %s 不允许从 %s 转移到 %s（状态机：DRAFT → AUDITED → POSTED）"
                            .formatted(v.voucherNo(), v.status(), target));
        }
        Long actorId = actors.current().map(ActorContext.Actor::id).orElse(null);
        LocalDateTime postedAt = target == VoucherStatus.POSTED ? clock.now() : null;
        if (!vouchers.transition(v.id(), v.status(), target, actorId, postedAt)) {
            throw new BusinessException(ErrorCode.FIN_VOUCHER_STATUS_ILLEGAL,
                    "凭证 %s 状态已被并发修改，请刷新后重试".formatted(v.voucherNo()));
        }
    }

    // ─────────────────────────── 校验与合计 ───────────────────────────

    /** 至少两行、每行单边非零、科目存在且为启用的末级科目。 */
    private List<VoucherLine> validateLines(List<LineCommand> raw) {
        if (raw == null || raw.size() < 2) {
            throw new BusinessException(ErrorCode.FIN_VOUCHER_LINE_INVALID,
                    "凭证至少需要两条分录（一借一贷）");
        }
        List<VoucherLine> lines = new ArrayList<>(raw.size());
        int lineNo = 1;
        for (LineCommand lc : raw) {
            if (lc.accountId() == null) {
                throw new BusinessException(ErrorCode.FIN_VOUCHER_LINE_INVALID,
                        "第 %d 行未选择科目".formatted(lineNo));
            }
            FinAccount account = requireAccount(lc.accountId());
            if (!account.leaf()) {
                throw new BusinessException(ErrorCode.FIN_VOUCHER_LINE_INVALID,
                        "第 %d 行科目 %s 是非末级科目，不能直接记账".formatted(lineNo, account.code()));
            }
            if (!account.enabled()) {
                throw new BusinessException(ErrorCode.FIN_VOUCHER_LINE_INVALID,
                        "第 %d 行科目 %s 已停用".formatted(lineNo, account.code()));
            }
            BigDecimal debit = scaleOrZero(lc.debit());
            BigDecimal credit = scaleOrZero(lc.credit());
            if (debit.signum() < 0 || credit.signum() < 0) {
                throw new BusinessException(ErrorCode.FIN_VOUCHER_LINE_INVALID,
                        "第 %d 行金额不能为负（红字请用相反方向）".formatted(lineNo));
            }
            if (debit.signum() == 0 && credit.signum() == 0) {
                throw new BusinessException(ErrorCode.FIN_VOUCHER_LINE_INVALID,
                        "第 %d 行借贷均为 0，是无效分录".formatted(lineNo));
            }
            if (debit.signum() > 0 && credit.signum() > 0) {
                throw new BusinessException(ErrorCode.FIN_VOUCHER_LINE_INVALID,
                        "第 %d 行借贷不能同时有值（一行只记一边）".formatted(lineNo));
            }
            String summary = lc.summary() == null || lc.summary().isBlank()
                    ? account.name() : lc.summary().trim();
            lines.add(new VoucherLine(0, lineNo++, account.id(), account.code(), summary,
                    debit, credit));
        }
        return lines;
    }

    private static BigDecimal[] totals(List<VoucherLine> lines) {
        BigDecimal debit = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;
        for (VoucherLine l : lines) {
            debit = debit.add(l.debit());
            credit = credit.add(l.credit());
        }
        return new BigDecimal[]{debit, credit};
    }

    private static void requireBalanced(BigDecimal[] totals) {
        if (totals[0].compareTo(totals[1]) != 0) {
            throw new BusinessException(ErrorCode.FIN_VOUCHER_UNBALANCED,
                    "借贷不平：借方合计 %s 元，贷方合计 %s 元，差额 %s 元"
                            .formatted(totals[0].toPlainString(), totals[1].toPlainString(),
                                    totals[0].subtract(totals[1]).toPlainString()),
                    Map.of("totalDebit", totals[0], "totalCredit", totals[1],
                            "diff", totals[0].subtract(totals[1])));
        }
    }

    private static BigDecimal scaleOrZero(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    // ─────────────────────────── FR-V3-08 自动生成 ───────────────────────────

    /**
     * 业务单据 → 凭证。在调用方（单据确认）事务内同步执行，同生共死。
     *
     * <p>分录规则表（答辩口径，详见 .plan/V3-2-财务中心-报告.md）：
     * <ul>
     *   <li>PUR_IN：借 1405 库存商品 / 贷 2202 应付账款（系统暂无税项，按简报口径
     *       "税额为 0 不生成税费行"）；</li>
     *   <li>SAL_OUT：借 1122 应收账款 / 贷 6001 主营业务收入；
     *       成本引擎已有结转额时追加 借 6401 / 贷 1405；</li>
     *   <li>PUR_PAY：借 2202 / 贷 1002 银行存款 + 银行日记账（贷）；</li>
     *   <li>SAL_RCV：借 1002 / 贷 1122 + 银行日记账（借）；</li>
     *   <li>EXPENSE：借 6602 管理费用 / 贷 1002 + 银行日记账（贷）。内部付款路径调用。</li>
     * </ul>
     */
    @Override
    @Transactional
    public long generateForSource(String sourceTypeRaw, long sourceId) {
        VoucherSourceType sourceType;
        try {
            sourceType = VoucherSourceType.parse(sourceTypeRaw);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "未知的凭证来源类型：" + sourceTypeRaw);
        }
        if (sourceType == VoucherSourceType.MANUAL || sourceType == VoucherSourceType.INVOICE) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    sourceType + " 不是业务单据来源，不支持自动生成");
        }
        accountService.ensureSeeded();

        Optional<Voucher> existing = vouchers.findBySource(sourceType, sourceId);
        if (existing.isPresent()) {
            return existing.get().id();
        }

        List<VoucherLine> lines = new ArrayList<>();
        LocalDate date;
        String sourceNo;
        String remark;
        JournalPlan journal = null;

        switch (sourceType) {
            case PUR_IN -> {
                FinSourceRepository.PurInDoc doc = sources.purchaseReceipt(sourceId)
                        .orElseThrow(() -> notFound("进货入库单", sourceId));
                BigDecimal amount = fenToYuan(doc.amountFen());
                if (amount.signum() <= 0) {
                    return 0L; // 全不合格入库没有金额，无账可做（不生成零额凭证）
                }
                date = doc.bizDate();
                sourceNo = doc.receiptNo();
                remark = "采购入库自动生成（供应商 %s）".formatted(doc.supplierName());
                lines.add(debitLine(StandardChartOfAccounts.MERCHANDISE,
                        "采购入库 %s".formatted(doc.receiptNo()), amount));
                lines.add(creditLine(StandardChartOfAccounts.PAYABLE,
                        "应付 %s".formatted(doc.supplierName()), amount));
            }
            case SAL_OUT -> {
                FinSourceRepository.SalOutDoc doc = sources.shipment(sourceId)
                        .orElseThrow(() -> notFound("发货单", sourceId));
                BigDecimal revenue = fenToYuan(doc.revenueFen());
                BigDecimal cost = fenToYuan(doc.costFen());
                if (revenue.signum() <= 0) {
                    return 0L;
                }
                date = doc.bizDate();
                sourceNo = doc.shipmentNo();
                remark = "销售出库自动生成（客户 %s）".formatted(doc.customerName());
                lines.add(debitLine(StandardChartOfAccounts.RECEIVABLE,
                        "应收 %s".formatted(doc.customerName()), revenue));
                lines.add(creditLine(StandardChartOfAccounts.MAIN_REVENUE,
                        "销售收入 %s".formatted(doc.shipmentNo()), revenue));
                if (cost.signum() > 0) {
                    lines.add(debitLine(StandardChartOfAccounts.MAIN_COST,
                            "结转销售成本 %s".formatted(doc.shipmentNo()), cost));
                    lines.add(creditLine(StandardChartOfAccounts.MERCHANDISE,
                            "库存出库 %s".formatted(doc.shipmentNo()), cost));
                }
            }
            case SAL_ORDER_OUT -> {
                FinSourceRepository.SalOutDoc doc = sources.salesOrderOut(sourceId)
                        .orElseThrow(() -> notFound("出货单", sourceId));
                BigDecimal revenue = fenToYuan(doc.revenueFen());
                BigDecimal cost = fenToYuan(doc.costFen());
                if (revenue.signum() <= 0) {
                    return 0L;
                }
                date = doc.bizDate();
                sourceNo = doc.shipmentNo();
                remark = "出货单直接出货自动生成（客户 %s）".formatted(doc.customerName());
                lines.add(debitLine(StandardChartOfAccounts.RECEIVABLE,
                        "应收 %s".formatted(doc.customerName()), revenue));
                lines.add(creditLine(StandardChartOfAccounts.MAIN_REVENUE,
                        "销售收入 %s".formatted(doc.shipmentNo()), revenue));
                if (cost.signum() > 0) {
                    lines.add(debitLine(StandardChartOfAccounts.MAIN_COST,
                            "结转销售成本 %s".formatted(doc.shipmentNo()), cost));
                    lines.add(creditLine(StandardChartOfAccounts.MERCHANDISE,
                            "库存出库 %s".formatted(doc.shipmentNo()), cost));
                }
            }
            case PUR_PAY -> {
                FinSourceRepository.PayDoc doc = sources.payment(sourceId)
                        .orElseThrow(() -> notFound("付款单", sourceId));
                BigDecimal amount = fenToYuan(doc.amountFen());
                date = doc.bizDate();
                sourceNo = doc.paymentNo();
                remark = "付款核销自动生成（供应商 %s）".formatted(doc.supplierName());
                lines.add(debitLine(StandardChartOfAccounts.PAYABLE,
                        "应付 %s".formatted(doc.supplierName()), amount));
                lines.add(creditLine(StandardChartOfAccounts.BANK_DEPOSIT,
                        "银行付款 %s".formatted(doc.paymentNo()), amount));
                journal = new JournalPlan(AccountDirection.CREDIT, amount,
                        "付款 %s（%s）".formatted(doc.paymentNo(), doc.supplierName()));
            }
            case SAL_RCV -> {
                FinSourceRepository.RcvDoc doc = sources.customerReceipt(sourceId)
                        .orElseThrow(() -> notFound("收款单", sourceId));
                BigDecimal amount = fenToYuan(doc.amountFen());
                date = doc.bizDate();
                sourceNo = doc.receiptNo();
                remark = "收款核销自动生成（客户 %s）".formatted(doc.customerName());
                lines.add(debitLine(StandardChartOfAccounts.BANK_DEPOSIT,
                        "银行收款 %s".formatted(doc.receiptNo()), amount));
                lines.add(creditLine(StandardChartOfAccounts.RECEIVABLE,
                        "冲应收 %s".formatted(doc.customerName()), amount));
                journal = new JournalPlan(AccountDirection.DEBIT, amount,
                        "收款 %s（%s）".formatted(doc.receiptNo(), doc.customerName()));
            }
            case EXPENSE -> {
                Expense exp = expenses.findById(sourceId)
                        .orElseThrow(() -> notFound("报销单", sourceId));
                date = clock.today();
                sourceNo = exp.expenseNo();
                remark = "费用报销付款自动生成（%s）".formatted(exp.applicantName());
                BigDecimal amount = scaleOrZero(exp.amount());
                lines.add(debitLine(StandardChartOfAccounts.ADMIN_EXPENSE,
                        "%s报销".formatted(exp.expenseType()), amount));
                lines.add(creditLine(StandardChartOfAccounts.BANK_DEPOSIT,
                        "支付报销 %s".formatted(exp.expenseNo()), amount));
                journal = new JournalPlan(AccountDirection.CREDIT, amount,
                        "报销付款 %s（%s）".formatted(exp.expenseNo(), exp.applicantName()));
            }
            default -> throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "不支持自动生成凭证的来源：" + sourceType);
        }

        BigDecimal[] totals = totals(lines);
        requireBalanced(totals);
        Long actorId = actors.current().map(ActorContext.Actor::id).orElse(null);
        long voucherId;
        try {
            voucherId = vouchers.insertHeader(
                    numbers.next(DocumentNumberGenerator.DocType.FIN_VOUCHER, date),
                    date, periodOf(date), sourceType, sourceId, sourceNo,
                    VoucherStatus.POSTED, totals[0], totals[1], actorId, remark, clock.now());
        } catch (DataIntegrityViolationException raced) {
            // 并发兜底：uq_fin_voucher_source 撞了 = 另一个请求已生成，返回它的凭证
            return vouchers.findBySource(sourceType, sourceId)
                    .map(Voucher::id)
                    .orElseThrow(() -> raced);
        }
        int lineNo = 1;
        for (VoucherLine l : lines) {
            vouchers.insertLine(voucherId, lineNo++, l.accountId(), l.accountCode(),
                    l.summary(), l.debit(), l.credit());
        }
        if (journal != null) {
            recordBankJournal(date, journal.direction(), journal.amount(),
                    sourceType.name(), sourceId, journal.summary());
        }
        return voucherId;
    }

    /** 日记账落账：没有可用银行账户时跳过（凭证照出），报告记录该口径。 */
    private void recordBankJournal(LocalDate date, AccountDirection direction,
                                   BigDecimal amount, String sourceType, long sourceId,
                                   String summary) {
        bank.defaultAccount().ifPresent(account -> writeJournal(account, date, direction,
                amount, sourceType, sourceId, summary));
    }

    private void writeJournal(BankAccount account, LocalDate date, AccountDirection direction,
                             BigDecimal amount, String sourceType, Long sourceId, String summary) {
        // 收付款的 biz_date 常常早于今天（补录核销），所以这里不能拿"最新一笔的余额"推算，
        // 交给仓储整链重算（口径见 FinBankRepository.appendJournal）
        bank.appendJournal(account.id(), date, direction, amount, sourceType, sourceId, summary);
    }

    private record JournalPlan(AccountDirection direction, BigDecimal amount, String summary) {
    }

    // ─────────────────────────── 科目定位 ───────────────────────────

    private VoucherLine debitLine(String accountCode, String summary, BigDecimal amount) {
        return line(accountCode, summary, amount, BigDecimal.ZERO);
    }

    private VoucherLine creditLine(String accountCode, String summary, BigDecimal amount) {
        return line(accountCode, summary, BigDecimal.ZERO, amount);
    }

    private FinAccount requireAccount(long accountId) {
        return accounts.findById(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.FIN_ACCOUNT_NOT_FOUND,
                        "科目不存在：id=" + accountId));
    }

    private FinAccount requireAccountByCode(String code) {
        return accounts.findByCode(code)
                .orElseThrow(() -> new BusinessException(ErrorCode.FIN_ACCOUNTING_TEMPLATE_MISSING,
                        "标准科目 %s 在当前租户缺失（科目模板被破坏），无法自动生成凭证"
                                .formatted(code)));
    }

    private VoucherLine line(String accountCode, String summary, BigDecimal debit,
                             BigDecimal credit) {
        FinAccount account = requireAccountByCode(accountCode);
        return new VoucherLine(0, 0, account.id(), account.code(), summary, debit, credit);
    }

    // ─────────────────────────── 工具 ───────────────────────────

    /** 业务域整数分 → 财务域"元"（精确换算，无浮点、无舍入歧义）。 */
    public static BigDecimal fenToYuan(long fen) {
        return BigDecimal.valueOf(fen).movePointLeft(2);
    }

    private static BusinessException notFound(String docName, long id) {
        return new BusinessException(ErrorCode.SYS_NOT_FOUND,
                "%s不存在：id=%d，无法生成凭证".formatted(docName, id));
    }

    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("yyyy-MM",
            Locale.ROOT);

    private static String periodOf(LocalDate date) {
        return date.format(PERIOD);
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
