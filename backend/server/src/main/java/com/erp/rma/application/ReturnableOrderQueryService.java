package com.erp.rma.application;

import com.erp.mdm.application.CustomerApplicationService;
import com.erp.mdm.application.SkuApplicationService;
import com.erp.sal.application.ReturnableSalesOrderService;
import com.erp.sal.application.ReturnableSalesOrderService.ReturnableLine;
import com.erp.sal.application.ReturnableSalesOrderService.ReturnableOrder;
import com.erp.shared.web.PageResult;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 可退出货单查询 —— 退货建单页"选原出货单"的数据源（计划书 §6 坑 13 / 坑 14）。
 *
 * <p><b>为什么这份只读数据挂在 RMA，而不是让前端直接查 SAL</b>：
 * 退货页要回答的是"哪些单还能退、每行还能退几件"，它是退货动作的前置条件，
 * 不是出货业务本身。而 {@code sal:read} 属于出货域权限，V3 并没有发给销售员角色
 * （V4 给了 {@code rma:read/create/submit}）—— 让前端跨模块调 {@code /sales-orders}，
 * 只有 {@code rma:*} 的销售员只会看到一个空下拉，页面看起来像"系统里没有可退的单"，
 * 真相是"没有权限看"。权限与业务动作对齐之后，建单页只需要 {@code rma:read}。
 *
 * <p><b>本类不含可退口径</b>：数量一律来自 {@link ReturnableSalesOrderService}，
 * 这里只做两件事 —— 分页转发展开、批量补名称。RMA 不得直连
 * {@code com.erp.sal.repository}（ArchUnit 规则 8，自检见 TC-ARCH-006）；
 * 若在退货模块里抄一份 {@code shipped - returned}，出货规则一变就会长成
 * "下拉说还能退、建单预检说不能退"的脏数据。
 */
@Service
public class ReturnableOrderQueryService {

    private final ReturnableSalesOrderService returnableOrders;
    private final CustomerApplicationService customers;
    private final SkuApplicationService skus;

    public ReturnableOrderQueryService(ReturnableSalesOrderService returnableOrders,
                                       CustomerApplicationService customers,
                                       SkuApplicationService skus) {
        this.returnableOrders = returnableOrders;
        this.customers = customers;
        this.skus = skus;
    }

    /**
     * 可退明细的对外视图。
     *
     * <p>{@code unitPriceAmount} / {@code discountRate} 只供只读展示：
     * 建单请求体不接受这两个字段（金额一律由服务端从原行派生，防客户端改写）。
     */
    public record ReturnableLineView(long originLineId, int lineNo, long skuId, String skuName,
                                     int shippedQty, int returnedQty, int returnableQty,
                                     long unitPriceAmount, int discountRate) {
    }

    /** 可退出货单的对外视图：客户名与 SKU 名已由服务端补齐，前端不需要另拉主数据字典。 */
    public record ReturnableView(long orderId, String orderNo, String status,
                                 long customerId, String customerName, long warehouseId,
                                 LocalDate bizDate, List<ReturnableLineView> lines) {
    }

    @Transactional(readOnly = true)
    public PageResult<ReturnableView> page(int page, int size) {
        PageResult<ReturnableOrder> found = returnableOrders.pageReturnable(page, size);
        Set<Long> customerIds = new LinkedHashSet<>();
        Set<Long> skuIds = new LinkedHashSet<>();
        for (ReturnableOrder order : found.items()) {
            customerIds.add(order.customerId());
            for (ReturnableLine line : order.lines()) {
                skuIds.add(line.skuId());
            }
        }
        Map<Long, String> customerNames = customers.findNamesByIds(customerIds);
        Map<Long, String> skuNames = skus.findDisplayNamesByIds(skuIds);
        List<ReturnableView> items = new ArrayList<>(found.items().size());
        for (ReturnableOrder order : found.items()) {
            items.add(new ReturnableView(order.orderId(), order.orderNo(),
                    order.status().name(), order.customerId(),
                    customerNames.get(order.customerId()), order.warehouseId(), order.bizDate(),
                    order.lines().stream()
                            .map(l -> new ReturnableLineView(l.originLineId(), l.lineNo(),
                                    l.skuId(), skuNames.get(l.skuId()), l.shippedQty(),
                                    l.returnedQty(), l.returnableQty(), l.unitPriceAmount(),
                                    l.discountRate()))
                            .toList()));
        }
        return new PageResult<>(items, found.total(), found.page(), found.size());
    }
}
