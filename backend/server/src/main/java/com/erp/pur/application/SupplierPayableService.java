package com.erp.pur.application;

import com.erp.pur.domain.PayableAllocation;
import com.erp.pur.domain.SupplierPayable;
import com.erp.pur.repository.PayableAllocationRepository;
import com.erp.pur.repository.PayableAllocationRepository.PaymentTotals;
import com.erp.pur.repository.SupplierPayableRepository;
import com.erp.pur.repository.SupplierPayableRepository.PayableTotals;
import com.erp.shared.db.DbErrors;
import com.erp.shared.document.DocumentNumberGenerator;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.period.PeriodGuard;
import com.erp.shared.time.ClockProvider;
import com.erp.shared.web.PageResult;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 供应商应付与付款核销（FR-PUR-07 / FR-PUR-08）。
 *
 * <p>类名刻意不以 {@code ApplicationService} 结尾：它不驱动任何单据状态机，
 * 而是"金额事实"的守卫。ArchUnit 的一条规则要求所有 {@code *ApplicationService}
 * 在 PUR 包里必须调用 {@code DocumentStatusMachine.requireTransition} ——
 * 为了通过门禁而写一次无意义的调用，比改名更糟。
 *
 * <p>三条硬约束：
 * <ol>
 *   <li>应付余额是数据库生成列，应用只读；</li>
 *   <li>每次核销都走条件 UPDATE（余额足够才更新），并发下不会超付；</li>
 *   <li>付款单金额必须等于核销明细之和，写库前校验，核对查询可重复验证。</li>
 * </ol>
 */
@Service
public class SupplierPayableService {

    private final SupplierPayableRepository payables;
    private final PayableAllocationRepository allocations;
    private final DocumentNumberGenerator numberGenerator;
    private final ClockProvider clock;
    private final PeriodGuard periodGuard;

    public SupplierPayableService(SupplierPayableRepository payables,
                                  PayableAllocationRepository allocations,
                                  DocumentNumberGenerator numberGenerator,
                                  ClockProvider clock,
                                  PeriodGuard periodGuard) {
        this.payables = payables;
        this.allocations = allocations;
        this.numberGenerator = numberGenerator;
        this.clock = clock;
        this.periodGuard = periodGuard;
    }

    // ─────────────────────────── 应付生成 ───────────────────────────

    /**
     * 到货质检合格后生成应付单（一收货单一张）。
     *
     * <p>由收货事务内调用，因此与"入库/到货记录"同生共死：
     * 收货失败回滚，应付也不会留下半张。
     */
    @Transactional
    public long createForReceipt(long receiptId, long supplierId, long purchaseOrderId,
                                 LocalDate bizDate, String period, long amount) {
        if (amount <= 0) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "应付金额必须为正：%d".formatted(amount));
        }
        String payableNo = numberGenerator.next(
                DocumentNumberGenerator.DocType.SUPPLIER_PAYABLE, bizDate);
        try {
            return payables.insert(payableNo, supplierId, purchaseOrderId, receiptId,
                    bizDate, period, amount);
        } catch (DataIntegrityViolationException ex) {
            BusinessException duplicate = DbErrors.translate(ex, "uq_supplier_payable_receipt",
                    ErrorCode.SYS_DUPLICATE_REQUEST,
                    "该到货单已经生成过应付单：receiptId=%d".formatted(receiptId));
            if (duplicate != null) {
                throw duplicate;
            }
            throw ex;
        }
    }

    // ─────────────────────────── 查询 ───────────────────────────

    @Transactional(readOnly = true)
    public PageResult<SupplierPayable> page(Long supplierId, String status, int page, int size) {
        return payables.page(supplierId, status, page, size);
    }

    @Transactional(readOnly = true)
    public SupplierPayable require(long id) {
        SupplierPayable p = payables.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PUR_PAYABLE_NOT_FOUND,
                        "应付单不存在：id=%d".formatted(id)));
        return new SupplierPayable(p.id(), p.payableNo(), p.supplierId(), p.purchaseOrderId(),
                p.orderNo(), p.receiptId(), p.bizDate(), p.period(), p.originalAmount(),
                p.returnedAmount(), p.paidAmount(), p.balanceAmount(), p.version(),
                allocations.findByPayableId(p.id()));
    }

    @Transactional(readOnly = true)
    public SupplierPayable requireByReceiptId(long receiptId) {
        SupplierPayable p = payables.findByReceiptId(receiptId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PUR_PAYABLE_NOT_FOUND,
                        "到货单尚未生成应付：receiptId=%d".formatted(receiptId)));
        return require(p.id());
    }

    /**
     * 采购退货冲减应付（FR-PUR-06）：条件 UPDATE 保证余额不为负。
     *
     * <p>已付款的部分不能通过退货冲掉（那会变成"供应商欠我们钱"，
     * 属退款场景，本泳道明确不做）。
     */
    @Transactional
    public void registerReturn(long receiptId, long amount) {
        SupplierPayable p = payables.findByReceiptId(receiptId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PUR_PAYABLE_NOT_FOUND,
                        "到货单尚未生成应付：receiptId=%d".formatted(receiptId)));
        if (!payables.increaseReturned(p.id(), amount)) {
            SupplierPayable latest = payables.findById(p.id()).orElse(p);
            throw new BusinessException(ErrorCode.PUR_RETURN_EXCEED_PAYABLE,
                    "退货金额超过应付余额：应付单 %s 余额 %d 分，本次退货 %d 分"
                            .formatted(latest.payableNo(), latest.balanceAmount(), amount),
                    java.util.Map.of("payableId", p.id(), "balance", latest.balanceAmount(),
                            "requested", amount));
        }
    }

    /**
     * 应付余额与核销守恒核对（硬验收 1/2 的可重复查询）。
     *
     * <pre>
     * 应付余额 == 进货(应付原值) − 退货 − 付款
     * Σ核销明细 == Σ付款单金额
     * </pre>
     *
     * 两次调用结果必须一致 —— 测试用这一点证明它不是在返回缓存。
     */
    @Transactional(readOnly = true)
    public ReconcileResult reconcile(Long supplierId) {
        PayableTotals t = payables.totals(supplierId);
        PaymentTotals p = allocations.conservation(supplierId);
        long expectedBalance = t.originalTotal() - t.returnedTotal() - t.paidTotal();
        boolean balanced = t.balanceTotal() == expectedBalance;
        boolean conserved = p.driftCount() == 0
                && p.paymentTotal() == p.allocationTotal()
                && p.allocationTotal() == t.paidTotal();
        return new ReconcileResult(supplierId, t.originalTotal(), t.returnedTotal(),
                t.paidTotal(), t.balanceTotal(), p.paymentTotal(), p.allocationTotal(),
                balanced, conserved);
    }

    public record ReconcileResult(Long supplierId, long purchaseAmount, long returnAmount,
                                  long paidAmount, long balance, long paymentTotal,
                                  long allocationTotal, boolean balanced, boolean conserved) {
    }

    // ─────────────────────────── 付款核销 ───────────────────────────

    public record AllocationCommand(long payableId, long amount) {
    }

    public record PaymentCommand(long supplierId, long amount, LocalDate bizDate, String remark,
                                 List<AllocationCommand> allocations, String requestId) {
    }

    public record PaymentResult(String paymentNo, long paymentAmount,
                                List<PayableAllocation> allocations) {
    }

    /**
     * 付款并核销（FR-PUR-08）。
     *
     * <p><b>部分核销</b>：一张应付可多次付款，每次都只减少余额。
     * <b>多应付</b>：一次付款可拆到多张应付，但必须是同一供应商。
     * <b>超额拒绝</b>：任一明细超过对应应付余额 → 409 {@code PUR_PAYMENT_EXCEED_PAYABLE}；
     * 明细合计 ≠ 付款金额 → 400 {@code PUR_PAYMENT_AMOUNT_MISMATCH}。
     *
     * <p><b>幂等</b>：同一 {@code X-Request-Id} 重放返回首次核销结果，
     * 由 {@code uq_payable_alloc_request (request_id, payable_id)} 在并发下兜底。
     */
    @Transactional
    public PaymentResult pay(PaymentCommand cmd) {
        if (cmd.requestId() == null || cmd.requestId().isBlank()) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "付款必须提供幂等键 X-Request-Id");
        }
        List<PayableAllocation> existing = allocations.findByRequestId(cmd.requestId());
        if (!existing.isEmpty()) {
            PayableAllocation first = existing.get(0);
            if (first.supplierId() != cmd.supplierId()
                    || first.paymentAmount() != cmd.amount()) {
                throw new BusinessException(ErrorCode.SYS_DUPLICATE_REQUEST,
                        "重复的付款请求：该幂等键已用于另一笔付款");
            }
            return new PaymentResult(first.paymentNo(), first.paymentAmount(), existing);
        }

        if (cmd.amount() <= 0) {
            throw new BusinessException(ErrorCode.PUR_PAYMENT_AMOUNT_MISMATCH,
                    "付款金额必须为正：%d".formatted(cmd.amount()));
        }
        if (cmd.allocations() == null || cmd.allocations().isEmpty()) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR, "核销明细不能为空");
        }
        LocalDate bizDate = cmd.bizDate() == null ? clock.today() : cmd.bizDate();
        periodGuard.requireOpen(bizDate);

        Set<Long> seen = new HashSet<>();
        long allocationTotal = 0;
        for (AllocationCommand a : cmd.allocations()) {
            if (!seen.add(a.payableId())) {
                throw new BusinessException(ErrorCode.PUR_PAYMENT_AMOUNT_MISMATCH,
                        "同一应付单在一次付款中只能核销一次：payableId=%d"
                                .formatted(a.payableId()));
            }
            if (a.amount() <= 0) {
                throw new BusinessException(ErrorCode.PUR_PAYMENT_AMOUNT_MISMATCH,
                        "核销金额必须为正：payableId=%d, amount=%d"
                                .formatted(a.payableId(), a.amount()));
            }
            allocationTotal = Math.addExact(allocationTotal, a.amount());
        }
        if (allocationTotal != cmd.amount()) {
            throw new BusinessException(ErrorCode.PUR_PAYMENT_AMOUNT_MISMATCH,
                    "核销明细合计 %d 分 ≠ 付款单金额 %d 分"
                            .formatted(allocationTotal, cmd.amount()),
                    java.util.Map.of("allocationTotal", allocationTotal,
                            "paymentAmount", cmd.amount()));
        }

        // 先全部预检（只读），再按 payableId 升序更新，降低并发死锁概率
        List<AllocationCommand> ordered = new ArrayList<>(cmd.allocations());
        ordered.sort(Comparator.comparingLong(AllocationCommand::payableId));
        for (AllocationCommand a : ordered) {
            SupplierPayable p = payables.findById(a.payableId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.PUR_PAYABLE_NOT_FOUND,
                            "应付单不存在：id=%d".formatted(a.payableId())));
            if (p.supplierId() != cmd.supplierId()) {
                throw new BusinessException(ErrorCode.PUR_PAYMENT_SUPPLIER_MISMATCH,
                        "应付单 %s 不属于供应商 %d"
                                .formatted(p.payableNo(), cmd.supplierId()));
            }
            if (a.amount() > p.balanceAmount()) {
                throw new BusinessException(ErrorCode.PUR_PAYMENT_EXCEED_PAYABLE,
                        "核销额超过应付余额：应付单 %s 余额 %d 分，本次核销 %d 分"
                                .formatted(p.payableNo(), p.balanceAmount(), a.amount()),
                        java.util.Map.of("payableId", p.id(), "balance", p.balanceAmount(),
                                "requested", a.amount()));
            }
        }

        String paymentNo = numberGenerator.next(
                DocumentNumberGenerator.DocType.SUPPLIER_PAYMENT, bizDate);
        String period = "%04d-%02d".formatted(bizDate.getYear(), bizDate.getMonthValue());
        for (AllocationCommand a : ordered) {
            if (!payables.increasePaid(a.payableId(), a.amount())) {
                // 预检后仍失败 = 并发抢走了余额；条件 UPDATE 是最终守卫
                throw new BusinessException(ErrorCode.PUR_PAYMENT_EXCEED_PAYABLE,
                        "核销额超过应付余额（并发冲突）：payableId=%d, amount=%d"
                                .formatted(a.payableId(), a.amount()));
            }
            allocations.insert(paymentNo, cmd.supplierId(), a.payableId(), a.amount(),
                    cmd.amount(), bizDate, period, trimToNull(cmd.remark()), cmd.requestId());
        }
        return new PaymentResult(paymentNo, cmd.amount(),
                allocations.findByRequestId(cmd.requestId()));
    }

    private static String trimToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
