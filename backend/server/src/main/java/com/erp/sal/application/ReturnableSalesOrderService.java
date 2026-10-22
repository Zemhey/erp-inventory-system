package com.erp.sal.application;

import com.erp.sal.domain.SalesOrder;
import com.erp.sal.domain.SalesOrderLine;
import com.erp.sal.repository.SalesOrderRepository;
import com.erp.shared.document.DocumentStatus;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 可退查询（FR-RMA-01 的 SAL 侧只读出口）。
 *
 * <p><b>为什么由 SAL 提供查询，而不是让 RMA 直接读 {@code sales_order_*} 表</b>：
 * "什么算可退"这条规则属于出货域 —— 上限是 shipped_qty 而不是 qty，
 * 这个口径一旦在退货模块里抄一遍，出货规则变化时（例如将来支持分批出货）
 * 两边就会分叉，而分叉的表现是"退了多少库存不知道"，属于最难查的一类脏数据。
 * RMA 只通过本服务与出货域交互。
 */
@Service
public class ReturnableSalesOrderService {

    private final SalesOrderRepository orders;

    public ReturnableSalesOrderService(SalesOrderRepository orders) {
        this.orders = orders;
    }

    /**
     * 一条可退明细。
     *
     * @param originLineId 原出货明细行 id（退货单引用的就是它）
     * @param returnableQty 剩余可退 = shippedQty − returnedQty
     * @param unitPriceAmount 原行单价（分）—— 退货金额必须由它派生，不接受前端传入
     */
    public record ReturnableLine(long originLineId, int lineNo, long skuId, int qty,
                                 int shippedQty, int returnedQty, int returnableQty,
                                 long unitPriceAmount, int discountRate) {
    }

    /** 可退的出货单：客户与仓库由退货单直接继承。 */
    public record ReturnableOrder(long orderId, String orderNo, DocumentStatus status,
                                  long customerId, long warehouseId, LocalDate bizDate,
                                  List<ReturnableLine> lines) {
    }

    /**
     * 取出一张<b>可以退货</b>的出货单及其可退明细。
     *
     * @throws BusinessException 单据不存在 → {@code SAL_ORDER_NOT_FOUND}；
     *                           状态未到出货阶段或已全部退完 → {@code RMA_ORIGIN_NOT_SHIPPED}
     */
    @Transactional(readOnly = true)
    public ReturnableOrder requireShippedOrder(long orderId) {
        SalesOrder o = orders.findById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SAL_ORDER_NOT_FOUND,
                        "原出货单不存在：id=%d".formatted(orderId)));

        if (o.status() != DocumentStatus.PARTIAL && o.status() != DocumentStatus.COMPLETED) {
            throw new BusinessException(ErrorCode.RMA_ORIGIN_NOT_SHIPPED,
                    "出货单 %s 当前状态 %s 不可退货（仅 PARTIAL / COMPLETED 的单据才有已出货数量）"
                            .formatted(o.orderNo(), o.status()));
        }

        List<ReturnableLine> returnable = o.lines().stream()
                .filter(l -> l.returnableQty() > 0)
                .map(l -> new ReturnableLine(l.id(), l.lineNo(), l.skuId(), l.qty(),
                        l.shippedQty(), l.returnedQty(), l.returnableQty(),
                        l.unitPriceAmount(), l.discountRate()))
                .toList();
        if (returnable.isEmpty()) {
            int shipped = o.lines().stream().mapToInt(SalesOrderLine::shippedQty).sum();
            int returned = o.lines().stream().mapToInt(SalesOrderLine::returnedQty).sum();
            throw new BusinessException(ErrorCode.RMA_ORIGIN_NOT_SHIPPED,
                    "出货单 %s 没有可退明细：已出货 %d，已退货 %d，可退 0"
                            .formatted(o.orderNo(), shipped, returned));
        }
        return new ReturnableOrder(o.id(), o.orderNo(), o.status(), o.customerId(),
                o.warehouseId(), o.bizDate(), returnable);
    }
}
