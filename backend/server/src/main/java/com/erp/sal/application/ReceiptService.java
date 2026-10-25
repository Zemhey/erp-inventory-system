package com.erp.sal.application;

import com.erp.mdm.repository.CustomerRepository;
import com.erp.sal.domain.CustomerReceipt;
import com.erp.sal.repository.ReceivableRepository;
import com.erp.shared.document.DocumentNumberGenerator;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.period.PeriodGuard;
import com.erp.shared.time.ClockProvider;
import com.erp.shared.web.PageResult;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 收款与核销（FR-SAL-08）。
 *
 * <p><b>核销守恒</b>：Σ allocations == receipt.amount（精确相等）。
 * 少分配是“收款单没核完”，多分配是“凭空多收”，两者都拒绝。
 * 单笔分配不得超过对应应收余额 —— 由条件 UPDATE {@code balance >= ?} 原子守住。
 */
@Service
public class ReceiptService {

    private final ReceivableRepository receivables;
    private final CustomerRepository customers;
    private final DocumentNumberGenerator numberGenerator;
    private final PeriodGuard periodGuard;
    private final ClockProvider clock;
    /** 凭证触发出口（V3-2 FR-V3-08）：收款核销 → 借 1002 / 贷 1122 + 银行日记账。 */
    private final com.erp.shared.finance.VoucherTrigger vouchers;

    public ReceiptService(ReceivableRepository receivables, CustomerRepository customers,
                          DocumentNumberGenerator numberGenerator, PeriodGuard periodGuard,
                          ClockProvider clock,
                          com.erp.shared.finance.VoucherTrigger vouchers) {
        this.receivables = receivables;
        this.customers = customers;
        this.numberGenerator = numberGenerator;
        this.periodGuard = periodGuard;
        this.clock = clock;
        this.vouchers = vouchers;
    }

    public record AllocationCommand(long receivableId, long amount) {
    }

    public record CreateCommand(long customerId, long amount, LocalDate bizDate, String remark,
                                List<AllocationCommand> allocations, String requestId) {
    }

    @Transactional
    public CustomerReceipt create(CreateCommand cmd) {
        Optional<CustomerReceipt> existing = receivables.findReceiptByRequestId(cmd.requestId());
        if (existing.isPresent()) {
            if (existing.get().customerId() != cmd.customerId()) {
                throw new BusinessException(ErrorCode.SYS_DUPLICATE_REQUEST,
                        "该幂等键已用于另一客户的收款单，不能复用");
            }
            return existing.get();
        }
        if (cmd.amount() <= 0) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "收款金额必须为正：%d".formatted(cmd.amount()));
        }
        if (cmd.allocations() == null || cmd.allocations().isEmpty()) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "收款单至少需要一条核销明细");
        }
        customers.findById(cmd.customerId())
                .orElseThrow(() -> new BusinessException(ErrorCode.SYS_NOT_FOUND,
                        "客户不存在：id=%d".formatted(cmd.customerId())));

        long allocated = 0;
        Set<Long> seen = new HashSet<>();
        for (AllocationCommand allocation : cmd.allocations()) {
            if (allocation.amount() <= 0) {
                throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                        "核销金额必须为正：receivableId=%d, amount=%d"
                                .formatted(allocation.receivableId(), allocation.amount()));
            }
            if (!seen.add(allocation.receivableId())) {
                throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                        "同一应收在一张收款单里只能核销一次：receivableId=%d"
                                .formatted(allocation.receivableId()));
            }
            allocated = Math.addExact(allocated, allocation.amount());
        }
        if (allocated != cmd.amount()) {
            throw new BusinessException(ErrorCode.SAL_RECEIPT_UNBALANCED,
                    "核销明细合计 %d 分与收款金额 %d 分不一致（必须精确相等）"
                            .formatted(allocated, cmd.amount()),
                    java.util.Map.of("amount", cmd.amount(), "allocated", allocated));
        }

        LocalDate bizDate = cmd.bizDate() == null ? clock.today() : cmd.bizDate();
        periodGuard.requireOpen(bizDate);
        String receiptNo = numberGenerator.next(DocumentNumberGenerator.DocType.RECEIPT, bizDate);

        long receiptId;
        try {
            receiptId = receivables.insertReceipt(receiptNo, cmd.customerId(), cmd.amount(),
                    bizDate, trimToNull(cmd.remark()), cmd.requestId());
        } catch (DataIntegrityViolationException ex) {
            // 并发同键：另一个请求已完成，返回首次结果
            Optional<CustomerReceipt> first = receivables.findReceiptByRequestId(cmd.requestId());
            if (first.isPresent()) {
                if (first.get().customerId() != cmd.customerId()) {
                    throw new BusinessException(ErrorCode.SYS_DUPLICATE_REQUEST,
                            "该幂等键已用于另一客户的收款单，不能复用");
                }
                return first.get();
            }
            throw new BusinessException(ErrorCode.SYS_DUPLICATE_REQUEST,
                    "重复的收款请求：该幂等键已经用于另一张收款单");
        }

        for (AllocationCommand allocation : cmd.allocations()) {
            var receivable = receivables.findByIdForUpdate(allocation.receivableId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.SAL_RECEIVABLE_NOT_FOUND,
                            "应收不存在：id=%d".formatted(allocation.receivableId())));
            if (receivable.customerId() != cmd.customerId()) {
                throw new BusinessException(ErrorCode.SAL_RECEIVABLE_NOT_FOUND,
                        "应收 %d 不属于客户 %d，不能跨客户核销"
                                .formatted(receivable.id(), cmd.customerId()));
            }
            if (receivable.balance() < allocation.amount()) {
                throw new BusinessException(ErrorCode.SAL_RECEIPT_OVER_ALLOCATED,
                        "核销超额：应收 %d 余额 %d 分，本次核销 %d 分"
                                .formatted(receivable.id(), receivable.balance(),
                                        allocation.amount()),
                        java.util.Map.of("receivableId", receivable.id(),
                                "balance", receivable.balance(),
                                "requested", allocation.amount()));
            }
            if (!receivables.applyAllocation(receivable.id(), allocation.amount())) {
                throw new BusinessException(ErrorCode.SAL_RECEIPT_OVER_ALLOCATED,
                        "核销超额（并发）：应收 %d 余额已不足 %d 分"
                                .formatted(receivable.id(), allocation.amount()),
                        java.util.Map.of("receivableId", receivable.id(),
                                "requested", allocation.amount()));
            }
            receivables.insertAllocation(receiptId, receivable.id(), allocation.amount());
        }
        // V3-2 FR-V3-08：收款核销 → 自动生成凭证（幂等键 (SAL_RCV, receiptId)）。
        vouchers.generateForSource("SAL_RCV", receiptId);
        return receivables.findReceiptById(receiptId).orElseThrow();
    }

    @Transactional(readOnly = true)
    public CustomerReceipt require(long id) {
        return receivables.findReceiptById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.SYS_NOT_FOUND,
                        "收款单不存在：id=%d".formatted(id)));
    }

    @Transactional(readOnly = true)
    public PageResult<CustomerReceipt> page(Long customerId, int page, int size) {
        return receivables.pageReceipts(customerId, page, size);
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
