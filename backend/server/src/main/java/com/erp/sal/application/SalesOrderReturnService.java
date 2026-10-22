package com.erp.sal.application;

import com.erp.sal.domain.SalesOrderLine;
import com.erp.sal.repository.SalesOrderRepository;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 登记退货（FR-RMA-01 的 SAL 侧写入口）。
 *
 * <p>退货单入库时必须把数量记回原出货行，但<b>只有出货域知道上限怎么算</b>
 * （上限是 shipped_qty，且要扣掉其他退货单已经占用的额度）。
 * 因此 RMA 调用本方法，而不是自己写 {@code UPDATE sales_order_lines}。
 */
@Service
public class SalesOrderReturnService {

    private final SalesOrderRepository orders;

    public SalesOrderReturnService(SalesOrderRepository orders) {
        this.orders = orders;
    }

    /**
     * 原子累加某条出货明细的累计退货量。
     *
     * <p>并发超退的拦截点就在这里：条件 UPDATE 匹配 0 行即"退超了"，
     * 应用层<b>不</b>做"先查剩余可退再写"的判断（那样存在读后写窗口，
     * 两张退货单会双双通过预检）。
     *
     * <p>失败时抛出的异常会让调用方（退货入库）的整个事务回滚，
     * 因此库存与流水不会留下半成品。
     *
     * @throws BusinessException 数量非正 → {@code SYS_VALIDATION_ERROR}；
     *                           明细不存在 → {@code SYS_NOT_FOUND}；
     *                           累计退货超过已出货 → {@code RMA_QTY_EXCEED_ORIGIN}
     */
    @Transactional
    public void registerReturn(long originLineId, int qty) {
        if (qty <= 0) {
            throw new BusinessException(ErrorCode.SYS_VALIDATION_ERROR,
                    "退货数量必须为正：originLineId=%d, qty=%d".formatted(originLineId, qty));
        }
        if (orders.applyReturnedQty(originLineId, qty)) {
            return;
        }

        SalesOrderLine line = orders.findLineById(originLineId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SYS_NOT_FOUND,
                        "原出货明细行不存在：originLineId=%d".formatted(originLineId)));
        throw new BusinessException(ErrorCode.RMA_QTY_EXCEED_ORIGIN,
                "超出可退数量：出货明细行 %d（SKU %d）已出货 %d，已退货 %d，本次 %d，最多还能退 %d"
                        .formatted(line.id(), line.skuId(), line.shippedQty(),
                                line.returnedQty(), qty, line.returnableQty()),
                java.util.Map.of("originLineId", line.id(), "skuId", line.skuId(),
                        "shippedQty", line.shippedQty(), "returnedQty", line.returnedQty(),
                        "requested", qty, "returnable", line.returnableQty()));
    }
}
