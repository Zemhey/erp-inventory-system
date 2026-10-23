package com.erp.sal.application;

import com.erp.mdm.domain.Customer;
import com.erp.mdm.repository.CustomerRepository;
import com.erp.sal.domain.CustomerReceivable;
import com.erp.sal.domain.ReceivableSummary;
import com.erp.sal.repository.ReceivableRepository;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.time.ClockProvider;
import com.erp.shared.web.PageResult;
import java.time.LocalDate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 客户应收应用服务（FR-SAL-07）。
 *
 * <p>余额只用增量维护：出货加、退货减、回款减。
 * 恒等式 {@code balance = shipped - returned - received} 由数据库 CHECK 兜底，
 * 任何一次增量都不能绕过它。
 */
@Service
public class ReceivableService {

    private final ReceivableRepository receivables;
    private final CustomerRepository customers;
    private final ClockProvider clock;

    public ReceivableService(ReceivableRepository receivables, CustomerRepository customers,
                             ClockProvider clock) {
        this.receivables = receivables;
        this.customers = customers;
        this.clock = clock;
    }

    /** 出货确认后登记应收增量（FR-SAL-06/07 联动）。 */
    @Transactional
    public void recordShipment(long salesOrderId, long customerId, LocalDate bizDate, long amount) {
        if (amount <= 0) {
            return;
        }
        CustomerReceivable receivable = receivables.findByOrderId(salesOrderId).orElse(null);
        if (receivable == null) {
            int creditDays = customers.findById(customerId).map(Customer::creditDays).orElse(0);
            // ★ 到期日只能来自注入时钟：跨月/跨年测试才能精确摆布（FR-SAL-09）
            LocalDate dueDate = clock.today().plusDays(creditDays);
            receivables.tryInsertReceivable(salesOrderId, customerId, bizDate, dueDate);
            receivable = receivables.findByOrderId(salesOrderId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.SYS_NOT_FOUND,
                            "应收创建后回读失败：orderId=%d".formatted(salesOrderId)));
        }
        if (!receivables.addShipped(receivable.id(), amount)) {
            throw new BusinessException(ErrorCode.SAL_STATUS_ILLEGAL,
                    "出货单 %d 的应收已作废，不能再登记出货金额".formatted(salesOrderId));
        }
    }

    /** 退货入库后冲减应收（FR-RMA-01 与 FR-SAL-07 联动）。 */
    @Transactional
    public void recordReturn(long salesOrderId, long amount) {
        if (amount <= 0) {
            return;
        }
        CustomerReceivable receivable = receivables.findByOrderId(salesOrderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SAL_RECEIVABLE_NOT_FOUND,
                        "出货单 %d 没有应收记录，无法登记退货金额".formatted(salesOrderId)));
        if (!receivables.addReturned(receivable.id(), amount)) {
            throw new BusinessException(ErrorCode.SAL_STATUS_ILLEGAL,
                    "出货单 %d 的应收已作废，不能再登记退货金额".formatted(salesOrderId));
        }
    }

    /**
     * 已出货订单红冲前/后的应收处理。
     *
     * <p>已收到款的单据拒绝红冲：否则会形成“钱收了、单作废了”的死账。
     */
    @Transactional
    public void reverseForVoid(long salesOrderId) {
        CustomerReceivable receivable = receivables.findByOrderId(salesOrderId).orElse(null);
        if (receivable == null) {
            return;
        }
        if (receivable.receivedAmount() > 0) {
            throw new BusinessException(ErrorCode.SAL_STATUS_ILLEGAL,
                    "出货单 %d 的应收已收款 %d 分，不能红冲（请先处理退款/反核销）"
                            .formatted(salesOrderId, receivable.receivedAmount()));
        }
        receivables.markVoided(receivable.id());
    }

    @Transactional(readOnly = true)
    public CustomerReceivable require(long id) {
        return receivables.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.SAL_RECEIVABLE_NOT_FOUND,
                        "应收不存在：id=%d".formatted(id)));
    }

    @Transactional(readOnly = true)
    public PageResult<CustomerReceivable> page(Long customerId, String status, Boolean overdue,
                                               int page, int size) {
        return receivables.page(customerId, status, overdue, clock.today(), page, size);
    }

    @Transactional(readOnly = true)
    public ReceivableSummary summary(long customerId) {
        return receivables.summary(customerId, clock.today());
    }

    @Transactional(readOnly = true)
    public long openBalance(long customerId) {
        return receivables.openBalance(customerId);
    }
}
