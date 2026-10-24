package com.erp.rpt;

import com.erp.pur.application.PurchaseOrderApplicationService;
import com.erp.pur.domain.PurchaseOrder;
import com.erp.rpt.application.ReportApplicationService;
import com.erp.sal.application.SalesOrderApplicationService;
import com.erp.sal.domain.SalesOrder;
import com.erp.support.PostgresTestBase;
import com.erp.support.TestClockConfig.ControllableClock;
import com.erp.support.TestDataFactory;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * V2-8 报表测试基座：固定主数据 + 单据/流水造数。
 *
 * <p>每条用例前 truncate；时钟固定为 2026-03-05 10:00，涉及流水时间的用例
 * 显式用 {@link #at} 移动时钟，避免真实时间让跨月/呆滞断言漂移。
 */
@SpringBootTest
@ActiveProfiles("clock")
public abstract class ReportV28TestSupport extends PostgresTestBase {

    protected static final LocalDate RANGE_FROM = LocalDate.of(2026, 3, 1);
    protected static final LocalDate RANGE_TO = LocalDate.of(2026, 3, 31);
    protected static final LocalDateTime DEFAULT_NOW = LocalDateTime.of(2026, 3, 5, 10, 0);

    @Autowired
    protected ReportApplicationService reports;
    @Autowired
    protected PurchaseOrderApplicationService purchaseOrders;
    @Autowired
    protected SalesOrderApplicationService salesOrders;
    @Autowired
    protected ControllableClock clock;
    @Autowired
    protected JdbcTemplate jdbc;

    protected TestDataFactory data;
    protected long wh1;
    protected long wh2;
    protected long supplier1;
    protected long supplier2;
    protected long customer1;
    protected long customer2;
    protected long customer3;
    protected long sku1;
    protected long sku2;

    protected final List<PurchaseOrder> purchases = new ArrayList<>();
    protected final List<SalesOrder> shipments = new ArrayList<>();

    @BeforeEach
    void setUpV28() {
        data = new TestDataFactory(jdbc);
        data.truncateAll();
        purchases.clear();
        shipments.clear();
        clock.set(DEFAULT_NOW);

        wh1 = data.warehouse("WH-V28-1");
        wh2 = data.warehouse("WH-V28-2");
        supplier1 = data.supplier("SUP-V28-1");
        supplier2 = data.supplier("SUP-V28-2");
        customer1 = data.customer("CUS-V28-1", 10000);
        customer2 = data.customer("CUS-V28-2", 10000);
        customer3 = data.customer("CUS-V28-3", 10000);
        long product1 = data.product("P-V28-1", "V2-8 商品A");
        long product2 = data.product("P-V28-2", "V2-8 商品B");
        sku1 = data.sku(product1, "白色", "L");
        sku2 = data.sku(product2, "黑色", "M");
    }

    protected void at(LocalDateTime moment) {
        clock.set(moment);
    }

    protected PurchaseOrder purchase(String req, long supplierId, long warehouseId,
                                     LocalDate bizDate, long skuId, int qty, long unitPrice) {
        var created = purchaseOrders.create(new PurchaseOrderApplicationService.CreateCommand(
                supplierId, warehouseId, bizDate, "V2-8 备货",
                List.of(new PurchaseOrderApplicationService.CreateLineCommand(
                        skuId, qty, unitPrice)), req));
        purchaseOrders.submit(created.id());
        purchaseOrders.approve(created.id());
        var received = purchaseOrders.receive(created.id(),
                new PurchaseOrderApplicationService.ReceiveCommand(
                        List.of(new PurchaseOrderApplicationService.ReceiveLineCommand(
                                created.lines().get(0).id(), qty, null, null, null)),
                        req + "-recv"));
        purchases.add(received);
        return received;
    }

    protected PurchaseOrder purchaseApprovedOnly(String req, long supplierId, long warehouseId,
                                                 LocalDate bizDate, long skuId,
                                                 int qty, long unitPrice) {
        var created = purchaseOrders.create(new PurchaseOrderApplicationService.CreateCommand(
                supplierId, warehouseId, bizDate, "V2-8 未入库",
                List.of(new PurchaseOrderApplicationService.CreateLineCommand(
                        skuId, qty, unitPrice)), req));
        purchaseOrders.submit(created.id());
        var approved = purchaseOrders.approve(created.id());
        purchases.add(approved);
        return approved;
    }

    protected SalesOrder ship(String req, long customerId, long warehouseId,
                              LocalDate bizDate, long skuId, int qty,
                              long unitPrice, int discountRate) {
        var created = salesOrders.create(new SalesOrderApplicationService.CreateCommand(
                customerId, warehouseId, bizDate, "V2-8 出货",
                List.of(new SalesOrderApplicationService.CreateLineCommand(
                        skuId, qty, unitPrice, discountRate)), req));
        salesOrders.submit(created.id());
        salesOrders.approve(created.id());
        var shipped = salesOrders.ship(created.id(), req + "-ship");
        shipments.add(shipped);
        return shipped;
    }

    /** 出货但不写库：仅用于核对报表状态过滤的负样本。 */
    protected SalesOrder salesApprovedOnly(String req, long customerId, long warehouseId,
                                           LocalDate bizDate, long skuId, int qty,
                                           long unitPrice, int discountRate) {
        var created = salesOrders.create(new SalesOrderApplicationService.CreateCommand(
                customerId, warehouseId, bizDate, "V2-8 未出货",
                List.of(new SalesOrderApplicationService.CreateLineCommand(
                        skuId, qty, unitPrice, discountRate)), req));
        salesOrders.submit(created.id());
        var approved = salesOrders.approve(created.id());
        shipments.add(approved);
        return approved;
    }

    protected long receivableIdOf(long salesOrderId) {
        return jdbc.queryForObject(
                "SELECT id FROM customer_receivables WHERE sales_order_id = ?",
                Long.class, salesOrderId);
    }

    protected long payableIdOfOrder(long purchaseOrderId) {
        return jdbc.queryForObject(
                "SELECT id FROM supplier_payables WHERE purchase_order_id = ?",
                Long.class, purchaseOrderId);
    }

    protected void setReceivableDueDate(long salesOrderId, LocalDate dueDate) {
        jdbc.update("UPDATE customer_receivables SET due_date = ? WHERE sales_order_id = ?",
                dueDate, salesOrderId);
    }

    protected void setPayableBizDate(long purchaseOrderId, LocalDate bizDate) {
        jdbc.update("UPDATE supplier_payables SET biz_date = ? WHERE purchase_order_id = ?",
                bizDate, purchaseOrderId);
    }

    protected static String monthOf(LocalDate date) {
        return "%04d-%02d".formatted(date.getYear(), date.getMonthValue());
    }
}
