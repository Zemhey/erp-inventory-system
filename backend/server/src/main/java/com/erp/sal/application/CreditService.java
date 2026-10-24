package com.erp.sal.application;

import com.erp.mdm.application.CustomerApplicationService;
import com.erp.mdm.domain.Customer;
import com.erp.mdm.repository.CustomerRepository;
import com.erp.sal.repository.CreditSnapshotRepository;
import com.erp.sal.repository.ReceivableRepository;
import com.erp.sal.repository.SalesOrderRepository;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.time.ClockProvider;
import java.time.LocalDate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 客户信用与账期控制（FR-SAL-09）。
 *
 * <p><b>敞口口径</b>：
 * {@code 敞口 = Σ 应收余额（已出货未收） + Σ 未出货订单的未出金额}。
 * 新单金额先计入敞口再与额度比较；**校验必须发生在任何预占之前**，
 * 保证被拒的订单不会在库存上留下预占痕迹（硬验收 6）。
 *
 * <p>额度为 0 视为不设限：存量客户不需要迁移数据就能继续下单。
 */
@Service
public class CreditService {

    private final CustomerRepository customers;
    private final CustomerApplicationService customerService;
    private final SalesOrderRepository salesOrders;
    private final ReceivableRepository receivables;
    private final CreditSnapshotRepository snapshots;
    private final ClockProvider clock;

    public CreditService(CustomerRepository customers, CustomerApplicationService customerService,
                         SalesOrderRepository salesOrders, ReceivableRepository receivables,
                         CreditSnapshotRepository snapshots, ClockProvider clock) {
        this.customers = customers;
        this.customerService = customerService;
        this.salesOrders = salesOrders;
        this.receivables = receivables;
        this.snapshots = snapshots;
        this.clock = clock;
    }

    /** 授信结果：controlled=false 表示额度为 0（不设限），exposure 未参与校验。 */
    public record Approval(Customer customer, boolean controlled, long exposureAmount) {
    }

    public record CreditView(long customerId, long creditLimit, int creditDays,
                             long exposureAmount, long availableCredit, long overdueAmount,
                             LocalDate snapshotDate) {
    }

    /**
     * 建单前授信检查。
     *
     * <p>受控客户会把客户行锁进当前事务，避免两个并发订单同时读到“还有额度”。
     */
    @Transactional
    public Approval check(long customerId, long orderAmount) {
        if (orderAmount < 0) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "订单金额不能为负：%d".formatted(orderAmount));
        }
        Customer customer = customers.findById(customerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SYS_NOT_FOUND,
                        "客户不存在：id=%d".formatted(customerId)));
        if (!customer.enabled()) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "客户已停用，不能建单：%s".formatted(customer.code()));
        }
        if (!customer.creditControlled()) {
            return new Approval(customer, false, 0);
        }
        Customer locked = customers.findByIdForUpdate(customerId).orElseThrow();
        long exposure = Math.addExact(exposure(customerId), orderAmount);
        if (exposure > locked.creditLimit()) {
            throw new BusinessException(ErrorCode.SAL_CREDIT_LIMIT_EXCEEDED,
                    "客户 %s 信用超额：额度 %d 分，当前敞口 %d 分（含本单 %d 分）"
                            .formatted(locked.code(), locked.creditLimit(), exposure, orderAmount),
                    java.util.Map.of("customerId", customerId,
                            "creditLimit", locked.creditLimit(),
                            "exposure", exposure,
                            "orderAmount", orderAmount));
        }
        return new Approval(locked, true, exposure);
    }

    /** 授信通过后落快照；日期来自 ClockProvider，禁止系统时间。 */
    @Transactional
    public void recordSnapshot(Approval approval, long orderAmount, long salesOrderId,
                               String requestId) {
        if (!approval.controlled()) {
            return;
        }
        snapshots.insert(approval.customer().id(), clock.today(),
                approval.customer().creditLimit(), approval.customer().creditDays(),
                approval.exposureAmount(), orderAmount, salesOrderId, requestId);
    }

    @Transactional(readOnly = true)
    public CreditView view(long customerId) {
        Customer customer = customers.findById(customerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SYS_NOT_FOUND,
                        "客户不存在：id=%d".formatted(customerId)));
        long exposure = exposure(customerId);
        long available = customer.creditLimit() == 0
                ? -1L : Math.max(0, customer.creditLimit() - exposure);
        long overdue = receivables.summary(customerId, clock.today()).overdueAmount();
        return new CreditView(customerId, customer.creditLimit(), customer.creditDays(),
                exposure, available, overdue, clock.today());
    }

    @Transactional
    public CreditView updateCredit(long customerId, long creditLimit, int creditDays) {
        Customer customer = customerService.updateCredit(customerId,
                new CustomerApplicationService.CreditCommand(creditLimit, creditDays));
        return view(customer.id());
    }

    /** 当前敞口（不含本单）：应收余额 + 未出货金额。 */
    @Transactional(readOnly = true)
    public long exposure(long customerId) {
        long openOrderAmount = 0;
        for (SalesOrderRepository.OpenLine line : salesOrders.findOpenLinesByCustomer(customerId)) {
            int openQty = line.qty() - line.shippedQty();
            if (openQty > 0) {
                openOrderAmount = Math.addExact(openOrderAmount,
                        SalesOrderApplicationService.lineAmount(
                                openQty, line.unitPriceAmount(), line.discountRate()));
            }
        }
        return Math.addExact(receivables.openBalance(customerId), openOrderAmount);
    }
}
