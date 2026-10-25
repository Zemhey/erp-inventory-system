package com.erp.fin.application;

import com.erp.fin.domain.Expense;
import com.erp.fin.domain.ExpenseLine;
import com.erp.fin.domain.ExpenseStatus;
import com.erp.shared.document.DocumentNumberGenerator;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.period.PeriodGuard;
import com.erp.shared.security.ActorContext;
import com.erp.shared.time.ClockProvider;
import com.erp.fin.repository.FinExpenseRepository;
import com.erp.shared.web.PageResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 费用报销（FR-V3-10）。
 *
 * <p>审批口径（简报："接 APS 可选，做不了就简单两级，不要卡住"）：
 * 本批实现<b>应用内两级</b> —— 申请人提交（SUBMITTED），财务（fin:expense:approve）
 * 审批或驳回；付款后自动生成凭证（借 6602 管理费用 / 贷 1002 银行存款）并落银行日记账。
 * 不接 APS 是刻意决定：APS 的七态审批流与报销五态不是一套状态机，
 * 硬接会让"审批只输出可否"（FR-APS-02）与报销状态推进纠缠，属下期能力。
 */
@Service
public class FinExpenseApplicationService {

    public record LineInput(String expenseItem, BigDecimal amount, String invoiceNo, String remark) {
    }

    public record ExpenseCommand(String expenseType, Long departmentId, String departmentName,
                                 LocalDate applyDate, String remark, List<LineInput> lines) {
    }

    private final FinExpenseRepository expenses;
    private final FinVoucherApplicationService vouchers;
    private final DocumentNumberGenerator numbers;
    private final ClockProvider clock;
    private final ActorContext actors;
    private final PeriodGuard periodGuard;

    public FinExpenseApplicationService(FinExpenseRepository expenses,
                                        FinVoucherApplicationService vouchers,
                                        DocumentNumberGenerator numbers,
                                        ClockProvider clock,
                                        ActorContext actors,
                                        PeriodGuard periodGuard) {
        this.expenses = expenses;
        this.vouchers = vouchers;
        this.numbers = numbers;
        this.clock = clock;
        this.actors = actors;
        this.periodGuard = periodGuard;
    }

    @Transactional(readOnly = true)
    public PageResult<Expense> page(ExpenseStatus status, String keyword, int page, int size) {
        return expenses.page(status, keyword, page, size);
    }

    @Transactional(readOnly = true)
    public Expense require(long id) {
        return expenses.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.FIN_EXPENSE_NOT_FOUND,
                        "报销单不存在：id=" + id));
    }

    @Transactional
    public Expense create(ExpenseCommand cmd) {
        List<ExpenseLine> lines = validateLines(cmd.lines());
        BigDecimal total = sum(lines);
        LocalDate applyDate = cmd.applyDate() == null ? clock.today() : cmd.applyDate();
        String applicantName = actors.current()
                .map(ActorContext.Actor::username).orElse("系统");
        Long applicantId = actors.current().map(ActorContext.Actor::id).orElse(null);
        long id = expenses.insert(
                numbers.next(DocumentNumberGenerator.DocType.FIN_EXPENSE, applyDate),
                applicantId, applicantName, cmd.departmentId(), trim(cmd.departmentName()),
                required(cmd.expenseType(), "费用类型必填"), total, applyDate, trim(cmd.remark()));
        expenses.replaceLines(id, lines);
        return require(id);
    }

    @Transactional
    public Expense update(long id, ExpenseCommand cmd) {
        Expense existing = require(id);
        if (existing.status() != ExpenseStatus.DRAFT
                && existing.status() != ExpenseStatus.REJECTED) {
            throw new BusinessException(ErrorCode.FIN_EXPENSE_STATUS_ILLEGAL,
                    "报销单 %s 状态 %s 不可修改（仅草稿/已驳回）"
                            .formatted(existing.expenseNo(), existing.status()));
        }
        List<ExpenseLine> lines = validateLines(cmd.lines());
        BigDecimal total = sum(lines);
        LocalDate applyDate = cmd.applyDate() == null ? existing.applyDate() : cmd.applyDate();
        if (!expenses.updateDraft(id, cmd.expenseType() == null ? existing.expenseType()
                        : required(cmd.expenseType(), "费用类型必填"),
                total, applyDate, cmd.remark() == null ? existing.remark() : trim(cmd.remark()))) {
            throw new BusinessException(ErrorCode.FIN_EXPENSE_STATUS_ILLEGAL,
                    "报销单 %s 状态已被并发修改".formatted(existing.expenseNo()));
        }
        expenses.replaceLines(id, lines);
        return require(id);
    }

    @Transactional
    public Expense submit(long id) {
        Expense e = require(id);
        transition(e, ExpenseStatus.SUBMITTED, null);
        return require(id);
    }

    @Transactional
    public Expense approve(long id) {
        Expense e = require(id);
        periodGuard.requireOpen(e.applyDate());
        transition(e, ExpenseStatus.APPROVED, null);
        return require(id);
    }

    @Transactional
    public Expense reject(long id, String reason) {
        Expense e = require(id);
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR, "驳回必须填写原因");
        }
        // 简报口径：驳回回 DRAFT 并留原因（reject_reason 列记录被驳回的历史）
        if (!e.status().canTransitionTo(ExpenseStatus.DRAFT)) {
            throw new BusinessException(ErrorCode.FIN_EXPENSE_STATUS_ILLEGAL,
                    "报销单 %s 当前状态 %s 不可驳回".formatted(e.expenseNo(), e.status()));
        }
        if (!expenses.transition(id, e.status(), ExpenseStatus.DRAFT, trim(reason), null)) {
            throw new BusinessException(ErrorCode.FIN_EXPENSE_STATUS_ILLEGAL,
                    "报销单 %s 状态已被并发修改".formatted(e.expenseNo()));
        }
        return require(id);
    }

    /** 付款：置 PAID + 生成凭证（借 6602 / 贷 1002）+ 银行日记账。幂等由凭证来源唯一键兜底。 */
    @Transactional
    public Expense pay(long id) {
        Expense e = require(id);
        if (e.status() != ExpenseStatus.APPROVED) {
            throw new BusinessException(ErrorCode.FIN_EXPENSE_STATUS_ILLEGAL,
                    "报销单 %s 当前状态 %s 不可付款（须先审批）".formatted(e.expenseNo(), e.status()));
        }
        periodGuard.requireOpen(e.applyDate());
        // 先转移状态：REJECTED→…的非法组合已被状态机排除；PAID 重复调用在这里被拦
        if (!expenses.transition(id, e.status(), ExpenseStatus.PAID, null, clock.now())) {
            throw new BusinessException(ErrorCode.FIN_EXPENSE_STATUS_ILLEGAL,
                    "报销单 %s 状态已被并发修改".formatted(e.expenseNo()));
        }
        vouchers.generateForSource("EXPENSE", id);
        return require(id);
    }

    @Transactional
    public void delete(long id) {
        Expense e = require(id);
        if (e.status() != ExpenseStatus.DRAFT) {
            throw new BusinessException(ErrorCode.FIN_EXPENSE_STATUS_ILLEGAL,
                    "报销单 %s 状态 %s 不可删除（仅草稿）".formatted(e.expenseNo(), e.status()));
        }
        expenses.deleteById(id);
    }

    private void transition(Expense e, ExpenseStatus target, String reason) {
        if (!e.status().canTransitionTo(target)) {
            throw new BusinessException(ErrorCode.FIN_EXPENSE_STATUS_ILLEGAL,
                    "报销单 %s 不允许从 %s 转移到 %s"
                            .formatted(e.expenseNo(), e.status(), target));
        }
        if (!expenses.transition(e.id(), e.status(), target, reason, null)) {
            throw new BusinessException(ErrorCode.FIN_EXPENSE_STATUS_ILLEGAL,
                    "报销单 %s 状态已被并发修改，请刷新后重试".formatted(e.expenseNo()));
        }
    }

    private static List<ExpenseLine> validateLines(List<LineInput> raw) {
        if (raw == null || raw.isEmpty()) {
            throw new BusinessException(ErrorCode.FIN_EXPENSE_AMOUNT_MISMATCH,
                    "报销明细至少一行", Map.of("lines", 0));
        }
        List<ExpenseLine> lines = new ArrayList<>(raw.size());
        int lineNo = 1;
        for (LineInput li : raw) {
            if (li.expenseItem() == null || li.expenseItem().isBlank()) {
                throw new BusinessException(ErrorCode.FIN_EXPENSE_AMOUNT_MISMATCH,
                        "第 %d 行费用项目必填".formatted(lineNo));
            }
            if (li.amount() == null || li.amount().signum() <= 0) {
                throw new BusinessException(ErrorCode.FIN_EXPENSE_AMOUNT_MISMATCH,
                        "第 %d 行金额必须为正".formatted(lineNo));
            }
            lines.add(new ExpenseLine(0, lineNo++, li.expenseItem().trim(),
                    li.amount().setScale(2, RoundingMode.HALF_UP), trim(li.invoiceNo()),
                    trim(li.remark())));
        }
        return lines;
    }

    private static BigDecimal sum(List<ExpenseLine> lines) {
        BigDecimal total = BigDecimal.ZERO;
        for (ExpenseLine l : lines) {
            total = total.add(l.amount());
        }
        return total;
    }

    private static String required(String v, String message) {
        if (v == null || v.isBlank()) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR, message);
        }
        return v.trim();
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
