package com.erp.rpt.application;

import com.erp.rpt.repository.DashboardRepository;
import com.erp.rpt.repository.TodoRepository;
import com.erp.shared.time.ClockProvider;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 工作台聚合服务（FR-V3-02）。
 *
 * <p>只读：所有数据来自 {@code DashboardRepository} 的聚合查询，不写任何表。
 * 业务时间来自 ClockProvider（不直接 now()），满足架构测试对系统时间的要求。
 */
@Service
public class DashboardService {

    private final DashboardRepository repository;
    private final TodoRepository todoRepository;
    private final ClockProvider clock;

    public DashboardService(DashboardRepository repository, TodoRepository todoRepository,
                            ClockProvider clock) {
        this.repository = repository;
        this.todoRepository = todoRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> buildSummary() {
        Map<String, Object> result = new LinkedHashMap<>();

        Map<String, Object> kpis = new LinkedHashMap<>();
        kpis.put("productTotal", repository.countProducts());
        kpis.put("skuTotal", repository.countSkus());
        kpis.put("customerTotal", repository.countCustomers());
        kpis.put("supplierTotal", repository.countSuppliers());
        kpis.put("purchaseOrderTotal", repository.countPurchaseOrders());
        kpis.put("salesOrderTotal", repository.countSalesOrders());
        kpis.put("inventoryRowTotal", repository.countInventoryRows());
        kpis.put("lowStockTotal", repository.countZeroAvailable());
        result.put("kpis", kpis);

        LocalDate to = clock.today();
        LocalDate from = to.minusDays(6);
        Map<String, Object> charts = new LinkedHashMap<>();
        charts.put("purchaseTrend", repository.orderTrend("purchase_orders", from, to));
        charts.put("salesTrend", repository.orderTrend("sales_orders", from, to));
        charts.put("docStatusPie", repository.salesStatusPie());
        charts.put("inventoryByWarehouse", repository.inventoryByWarehouse());
        result.put("charts", charts);

        Map<String, Object> todos = new LinkedHashMap<>();
        todos.put("approvalPending", repository.countPendingApprovals());
        todos.put("purchaseReceipt", repository.countPurchaseToReceive());
        todos.put("salesShip", repository.countSalesToShip());
        todos.put("lowStock", todoRepository.countLowStock());
        todos.put("items", repository.recentPendingApprovals(5));
        result.put("todos", todos);

        // ★ 各单据表的"单号 / 数量 / 金额 / 往来单位"列名并不统一：
        //   进货单、出货单、退货单是 order_no + total_qty + total_amount，
        //   调拨单是 transfer_no，而且没有数量金额列（它的明细在 transfer_lines）。
        //   所以这里按单据类型显式声明真实列名，不再用一套通用列名套所有表 ——
        //   通用列名会让"最近单据"整块 500，进而把整个工作台打成错误页。
        List<Map<String, Object>> recent = new ArrayList<>();
        recent.addAll(repository.recentDocuments(new DashboardRepository.DocSource(
                "purchase_orders", "purchase", "order_no", "supplier_id", "total_qty", "total_amount"), 5));
        recent.addAll(repository.recentDocuments(new DashboardRepository.DocSource(
                "sales_orders", "sales", "order_no", "customer_id", "total_qty", "total_amount"), 5));
        recent.addAll(repository.recentDocuments(new DashboardRepository.DocSource(
                "return_orders", "return", "order_no", "customer_id", "total_qty", "total_amount"), 5));
        recent.addAll(repository.recentDocuments(new DashboardRepository.DocSource(
                "inventory_transfers", "transfer", "transfer_no", null, null, null), 5));
        recent.sort(Comparator.comparing((Map<String, Object> m) -> (String) m.get("bizDate"))
                .reversed());
        if (recent.size() > 8) {
            recent = new ArrayList<>(recent.subList(0, 8));
        }
        result.put("recentDocuments", recent);

        return result;
    }
}
