package com.erp.pur;

import com.erp.inv.application.InventoryApplicationService;
import com.erp.mdm.application.ProductApplicationService;
import com.erp.mdm.application.SkuApplicationService;
import com.erp.mdm.application.SupplierApplicationService;
import com.erp.mdm.application.WarehouseApplicationService;
import com.erp.pur.application.PurchaseOrderApplicationService;
import com.erp.pur.application.PurchaseOrderApplicationService.CreateCommand;
import com.erp.pur.application.PurchaseOrderApplicationService.CreateLineCommand;
import com.erp.pur.application.PurchaseOrderApplicationService.ReceiveCommand;
import com.erp.pur.application.PurchaseOrderApplicationService.ReceiveLineCommand;
import com.erp.pur.application.PurchaseRequisitionApplicationService;
import com.erp.pur.application.PurchaseReturnService;
import com.erp.pur.application.SupplierPayableService;
import com.erp.pur.domain.PurchaseOrder;
import com.erp.pur.domain.PurchaseRequisition;
import com.erp.shared.web.PageResult;
import com.erp.support.PostgresTestBase;
import com.erp.support.TestDataFactory;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 采购闭环测试基座（V2-2）。
 *
 * <p>集中构造"已审核进货单 / 已审核请购单 / 到货质检"这些每个用例都要的前置，
 * 让用例本身只表达业务断言。测试数据一律用本类自己的供应商/仓库/SKU，
 * 不依赖开发库既有数据，每条用例前 truncate。
 */
@SpringBootTest
abstract class PurchaseLoopTestSupport extends PostgresTestBase {

    protected static final LocalDate BIZ_DATE = LocalDate.of(2025, 6, 1);
    protected static final String PERIOD = "2025-06";

    @Autowired
    protected PurchaseOrderApplicationService purchaseOrders;
    @Autowired
    protected PurchaseRequisitionApplicationService requisitions;
    @Autowired
    protected PurchaseReturnService returns;
    @Autowired
    protected SupplierPayableService payables;
    @Autowired
    protected InventoryApplicationService inventory;
    @Autowired
    protected SupplierApplicationService supplierService;
    @Autowired
    protected WarehouseApplicationService warehouseService;
    @Autowired
    protected ProductApplicationService productService;
    @Autowired
    protected SkuApplicationService skuService;
    @Autowired
    protected JdbcTemplate jdbc;

    protected TestDataFactory data;
    protected long supplierId;
    protected long warehouseId;
    protected long skuA;
    protected long skuB;

    @BeforeEach
    void setUpPurchaseLoop() {
        data = new TestDataFactory(jdbc);
        data.truncateAll();

        supplierId = supplierService.create(new SupplierApplicationService.CreateCommand(
                "SUP-V22-01", "V2-2 测试供应商", null, null, null, true)).id();
        warehouseId = warehouseService.create(new WarehouseApplicationService.CreateCommand(
                "WH-V22-01", "V2-2 测试仓", null, true)).id();
        var product = productService.create(new ProductApplicationService.CreateCommand(
                "P-V22-01", "V2-2 测试商品", null, null, true));
        skuA = skuService.create(new SkuApplicationService.CreateCommand(
                product.id(), "白色", "L", null, 12900L, true)).id();
        skuB = skuService.create(new SkuApplicationService.CreateCommand(
                product.id(), "黑色", "M", null, 12900L, true)).id();
    }

    // ─────────────────────────── 构造器 ───────────────────────────

    protected PurchaseOrder createApprovedOrder(String requestId, int qtyA, long priceA) {
        return createApprovedOrder(requestId, qtyA, priceA, 0, 0L);
    }

    protected PurchaseOrder createApprovedOrder(String requestId, int qtyA, long priceA,
                                                int qtyB, long priceB) {
        List<CreateLineCommand> lines = new ArrayList<>();
        lines.add(new CreateLineCommand(skuA, qtyA, priceA));
        if (qtyB > 0) {
            lines.add(new CreateLineCommand(skuB, qtyB, priceB));
        }
        PurchaseOrder o = purchaseOrders.create(new CreateCommand(
                supplierId, warehouseId, BIZ_DATE, "V2-2 测试进货单", lines, requestId));
        purchaseOrders.submit(o.id());
        purchaseOrders.approve(o.id());
        return purchaseOrders.require(o.id());
    }

    protected PurchaseOrder receive(long orderId, long lineId, int qty, Integer qcQty,
                                    String batchNo, String requestId) {
        return purchaseOrders.receive(orderId, new ReceiveCommand(
                List.of(new ReceiveLineCommand(lineId, qty, batchNo, null, null,
                        qcQty, qcQty != null && qcQty < qty ? "质检不合格" : null)),
                requestId));
    }

    protected PurchaseRequisition createApprovedRequisition(String requestId, int qty, long price) {
        PurchaseRequisition r = requisitions.create(
                new PurchaseRequisitionApplicationService.CreateCommand(
                        supplierId, warehouseId, BIZ_DATE, "V2-2 测试请购单",
                        List.of(new PurchaseRequisitionApplicationService.CreateLineCommand(
                                skuA, qty, price)), requestId));
        requisitions.submit(r.id());
        requisitions.approve(r.id());
        return requisitions.require(r.id());
    }

    // ─────────────────────────── 查询辅助 ───────────────────────────

    protected long latestReceiptId(long orderId) {
        return jdbc.queryForObject(
                "SELECT id FROM po_receipts WHERE purchase_order_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, orderId);
    }

    protected long latestReceiptLineId(long orderId) {
        return jdbc.queryForObject("""
                SELECT l.id FROM po_receipt_lines l
                  JOIN po_receipts r ON r.id = l.receipt_id
                 WHERE r.purchase_order_id = ? ORDER BY l.id DESC LIMIT 1
                """, Long.class, orderId);
    }

    protected long payableIdOfReceipt(long receiptId) {
        return jdbc.queryForObject(
                "SELECT id FROM supplier_payables WHERE receipt_id = ?", Long.class, receiptId);
    }

    protected void closePeriod(String period) {
        jdbc.update("""
                INSERT INTO settlement_periods (period, status)
                VALUES (?, 'CLOSED')
                ON CONFLICT (period) DO UPDATE SET status = 'CLOSED'
                """, period);
    }

    protected <T> List<T> items(PageResult<T> page) {
        return page.items();
    }
}
