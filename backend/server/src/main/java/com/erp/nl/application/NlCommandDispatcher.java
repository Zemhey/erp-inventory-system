package com.erp.nl.application;

import com.erp.mdm.application.CustomerApplicationService;
import com.erp.mdm.application.ProductApplicationService;
import com.erp.mdm.application.SkuApplicationService;
import com.erp.mdm.application.SupplierApplicationService;
import com.erp.mdm.application.WarehouseApplicationService;
import com.erp.mdm.domain.Warehouse;
import com.erp.nl.domain.NlCommand;
import com.erp.nl.domain.NlCommandResult;
import com.erp.nl.domain.NlIntent;
import com.erp.shared.document.DocumentStatus;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.time.ClockProvider;
import com.erp.shared.web.PageResult;
import com.erp.inv.application.InventoryApplicationService;
import com.erp.inv.domain.InventoryBalance;
import com.erp.pur.application.PurchaseOrderApplicationService;
import com.erp.pur.domain.PurchaseOrder;
import com.erp.rma.application.ReturnOrderApplicationService;
import com.erp.rma.domain.ReturnOrder;
import com.erp.rpt.application.ReportApplicationService;
import com.erp.rpt.domain.InventorySummaryRow;
import com.erp.sal.application.SalesOrderApplicationService;
import com.erp.sal.domain.SalesOrder;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 意图调度器：把 {@link NlCommand} 交给既有 ApplicationService 执行（FR-SYS-04）。
 *
 * <p><b>本类刻意"薄"</b>：每个分支只做三件事 —— 校验参数形状、校验意图对应的既有权限、
 * 调用业务服务并组织返回。没有一条库存/状态机/锁账/金额规则在这里重新实现。
 * 这条边界由 ArchUnit 规则 9「NL 不得依赖 repository」与自检 TC-ARCH-007 守住。
 *
 * <p>写意图（建进货/出货草稿）把 HTTP 的 {@code X-Request-Id} 原样交给建单服务，
 * 幂等由数据库唯一索引保证，调度器不自己做"先查后写"。
 */
@Service
public class NlCommandDispatcher {

    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_SIZE = 20;

    private final NlAccessGuard access;
    private final ClockProvider clock;
    private final InventoryApplicationService inventory;
    private final PurchaseOrderApplicationService purchaseOrders;
    private final SalesOrderApplicationService salesOrders;
    private final ReturnOrderApplicationService returnOrders;
    private final ReportApplicationService reports;
    private final ProductApplicationService products;
    private final SkuApplicationService skus;
    private final SupplierApplicationService suppliers;
    private final CustomerApplicationService customers;
    private final WarehouseApplicationService warehouses;

    public NlCommandDispatcher(NlAccessGuard access,
                               ClockProvider clock,
                               InventoryApplicationService inventory,
                               PurchaseOrderApplicationService purchaseOrders,
                               SalesOrderApplicationService salesOrders,
                               ReturnOrderApplicationService returnOrders,
                               ReportApplicationService reports,
                               ProductApplicationService products,
                               SkuApplicationService skus,
                               SupplierApplicationService suppliers,
                               CustomerApplicationService customers,
                               WarehouseApplicationService warehouses) {
        this.access = access;
        this.clock = clock;
        this.inventory = inventory;
        this.purchaseOrders = purchaseOrders;
        this.salesOrders = salesOrders;
        this.returnOrders = returnOrders;
        this.reports = reports;
        this.products = products;
        this.skus = skus;
        this.suppliers = suppliers;
        this.customers = customers;
        this.warehouses = warehouses;
    }

    public NlCommandResult execute(NlCommand command, String requestId) {
        return switch (command.intent()) {
            case HELP -> help();
            case QUERY_INVENTORY -> queryInventory(command.arguments());
            case LIST_DOCUMENTS -> listDocuments(command.arguments());
            case GET_DOCUMENT -> getDocument(command.arguments());
            case INVENTORY_SUMMARY -> inventorySummary(command.arguments());
            case QUERY_MASTER_DATA -> queryMasterData(command.arguments());
            case CREATE_PURCHASE_ORDER -> createPurchaseOrder(command.arguments(), requestId);
            case CREATE_SALES_ORDER -> createSalesOrder(command.arguments(), requestId);
        };
    }

    // ─────────────────────────── 读意图 ───────────────────────────

    private NlCommandResult help() {
        List<Map<String, Object>> intents = new ArrayList<>();
        intents.add(entry(NlIntent.QUERY_INVENTORY, "inv:read", "查询 SKU 12 在仓库 3 的库存"));
        intents.add(entry(NlIntent.LIST_DOCUMENTS, "pur:read / sal:read / rma:read",
                "进货单列表 状态 已审核 第1页"));
        intents.add(entry(NlIntent.GET_DOCUMENT, "pur:read / sal:read / rma:read",
                "查看进货单详情 id=12"));
        intents.add(entry(NlIntent.INVENTORY_SUMMARY, "rpt:read",
                "账期 2026-10 进销存汇总"));
        intents.add(entry(NlIntent.QUERY_MASTER_DATA, "mdm:read",
                "查询商品 关键字 T恤"));
        intents.add(entry(NlIntent.CREATE_PURCHASE_ORDER, "pur:create",
                "创建进货单 供应商=1 仓库=1 SKU=5 数量=10 单价=1000"));
        intents.add(entry(NlIntent.CREATE_SALES_ORDER, "sal:create",
                "创建出货单 客户=1 仓库=1 SKU=5 数量=2 单价=12900"));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("intents", intents);
        return new NlCommandResult(NlIntent.HELP.code(),
                "支持 %d 个意图；写意图只建草稿，审核/入库/出货/作废请走页面或 REST 接口"
                        .formatted(intents.size()),
                false, data);
    }

    private NlCommandResult queryInventory(Map<String, Object> args) {
        access.require("inv:read");
        List<InventoryBalance> rows = inventory.query(
                NlArguments.optionalLong(args, "skuId"),
                NlArguments.optionalLong(args, "warehouseId"),
                NlArguments.optionalString(args, "batchNo"),
                NlArguments.optionalBoolean(args, "onlyAvailable", false));
        long onHand = rows.stream().mapToLong(InventoryBalance::onHandQty).sum();
        long available = rows.stream().mapToLong(InventoryBalance::availableQty).sum();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", rows.stream().map(NlCommandDispatcher::inventoryToMap).toList());
        return new NlCommandResult(NlIntent.QUERY_INVENTORY.code(),
                "查询库存：共 %d 行，实存合计 %d，可用合计 %d".formatted(rows.size(), onHand, available),
                false, data);
    }

    private NlCommandResult listDocuments(Map<String, Object> args) {
        String docType = docType(args);
        DocumentStatus status = parseStatus(NlArguments.optionalString(args, "status"));
        int page = NlArguments.optionalInt(args, "page", DEFAULT_PAGE);
        int size = NlArguments.optionalInt(args, "size", DEFAULT_SIZE);
        Long partyId = NlArguments.optionalLong(args, "partyId");
        Object data;
        long total;
        switch (docType) {
            case "PURCHASE" -> {
                access.require("pur:read");
                PageResult<PurchaseOrder> result = purchaseOrders.page(status, partyId, page, size);
                data = pageData(result, NlCommandDispatcher::purchaseToMap);
                total = result.total();
            }
            case "SALES" -> {
                access.require("sal:read");
                PageResult<SalesOrder> result = salesOrders.page(status, partyId, page, size);
                data = pageData(result, NlCommandDispatcher::salesToMap);
                total = result.total();
            }
            case "RETURN" -> {
                access.require("rma:read");
                PageResult<ReturnOrder> result = returnOrders.page(status, partyId,
                        NlArguments.optionalLong(args, "originSalesOrderId"), page, size);
                data = pageData(result, NlCommandDispatcher::returnToMap);
                total = result.total();
            }
            default -> throw NlArguments.invalid("docType 取值非法：" + docType);
        }
        return new NlCommandResult(NlIntent.LIST_DOCUMENTS.code(),
                "%s列表：第 %d 页，共 %d 张".formatted(docTypeLabel(docType), page, total),
                false, data);
    }

    private NlCommandResult getDocument(Map<String, Object> args) {
        String docType = docType(args);
        long id = NlArguments.requiredLong(args, "id", "单据 id");
        Object order;
        String description;
        switch (docType) {
            case "PURCHASE" -> {
                access.require("pur:read");
                PurchaseOrder found = purchaseOrders.require(id);
                order = purchaseToMap(found);
                description = "进货单 %s（%s），金额 %d 分"
                        .formatted(found.orderNo(), found.status(), found.totalAmount());
            }
            case "SALES" -> {
                access.require("sal:read");
                SalesOrder found = salesOrders.require(id);
                order = salesToMap(found);
                description = "出货单 %s（%s），金额 %d 分"
                        .formatted(found.orderNo(), found.status(), found.totalAmount());
            }
            case "RETURN" -> {
                access.require("rma:read");
                ReturnOrder found = returnOrders.require(id);
                order = returnToMap(found);
                description = "退货单 %s（%s），金额 %d 分"
                        .formatted(found.orderNo(), found.status(), found.totalAmount());
            }
            default -> throw NlArguments.invalid("docType 取值非法：" + docType);
        }
        return new NlCommandResult(NlIntent.GET_DOCUMENT.code(), description, false, order);
    }

    private NlCommandResult inventorySummary(Map<String, Object> args) {
        access.require("rpt:read");
        YearMonth period = Optional.ofNullable(NlArguments.optionalPeriod(args, "period"))
                .orElseGet(() -> YearMonth.from(clock.now()));
        Long warehouseId = NlArguments.optionalLong(args, "warehouseId");
        List<InventorySummaryRow> rows = reports.inventorySummaryReport(period, warehouseId);
        long closing = rows.stream().mapToLong(InventorySummaryRow::closingQty).sum();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("period", period.toString());
        data.put("items", rows);
        return new NlCommandResult(NlIntent.INVENTORY_SUMMARY.code(),
                "进销存汇总 %s：共 %d 行，期末合计 %d 件".formatted(period, rows.size(), closing),
                false, data);
    }

    private NlCommandResult queryMasterData(Map<String, Object> args) {
        access.require("mdm:read");
        String type = NlArguments.requiredString(args, "type", "主数据类型").toUpperCase(Locale.ROOT);
        String keyword = NlArguments.optionalString(args, "keyword");
        int page = NlArguments.optionalInt(args, "page", DEFAULT_PAGE);
        int size = NlArguments.optionalInt(args, "size", DEFAULT_SIZE);
        Object data;
        int count;
        String label;
        switch (type) {
            case "WAREHOUSE" -> {
                boolean onlyEnabled = NlArguments.optionalBoolean(args, "onlyEnabled", false);
                List<Warehouse> rows = warehouses.list(onlyEnabled);
                Map<String, Object> pageData = new LinkedHashMap<>();
                pageData.put("items", rows);
                pageData.put("total", rows.size());
                pageData.put("page", 1);
                pageData.put("size", rows.size());
                data = pageData;
                count = rows.size();
                label = "仓库";
            }
            case "PRODUCT" -> {
                PageResult<?> result = products.page(keyword, page, size);
                data = pageDataRaw(result);
                count = (int) result.total();
                label = "商品";
            }
            case "SKU" -> {
                PageResult<?> result = skus.page(
                        NlArguments.optionalLong(args, "productId"),
                        NlArguments.optionalString(args, "color"),
                        NlArguments.optionalString(args, "skuSize"),
                        page, size);
                data = pageDataRaw(result);
                count = (int) result.total();
                label = "SKU";
            }
            case "SUPPLIER" -> {
                PageResult<?> result = suppliers.page(keyword, page, size);
                data = pageDataRaw(result);
                count = (int) result.total();
                label = "供应商";
            }
            case "CUSTOMER" -> {
                PageResult<?> result = customers.page(keyword, page, size);
                data = pageDataRaw(result);
                count = (int) result.total();
                label = "客户";
            }
            default -> throw NlArguments.invalid(
                    "type 取值非法：%s（允许 WAREHOUSE / PRODUCT / SKU / SUPPLIER / CUSTOMER）"
                            .formatted(type));
        }
        return new NlCommandResult(NlIntent.QUERY_MASTER_DATA.code(),
                "%s：共 %d 条".formatted(label, count), false, data);
    }

    // ─────────────────────────── 写意图（只建草稿） ───────────────────────────

    private NlCommandResult createPurchaseOrder(Map<String, Object> args, String requestId) {
        access.require("pur:create");
        long supplierId = NlArguments.requiredLong(args, "supplierId", "供应商 id");
        long warehouseId = NlArguments.requiredLong(args, "warehouseId", "仓库 id");
        LocalDate bizDate = NlArguments.optionalDate(args, "bizDate");
        String remark = remark(args);
        List<PurchaseOrderApplicationService.CreateLineCommand> lines = new ArrayList<>();
        for (Map<String, Object> line : NlArguments.requiredLines(args, "lines")) {
            lines.add(new PurchaseOrderApplicationService.CreateLineCommand(
                    NlArguments.requiredLong(line, "skuId", "SKU id"),
                    intArg(line, "qty", "数量"),
                    NlArguments.requiredLong(line, "unitPriceAmount", "单价（分）")));
        }
        PurchaseOrder created = purchaseOrders.create(
                new PurchaseOrderApplicationService.CreateCommand(
                        supplierId, warehouseId, bizDate, remark, lines, requestId));
        return new NlCommandResult(NlIntent.CREATE_PURCHASE_ORDER.code(),
                "已创建进货单草稿 %s（%s，共 %d 分）"
                        .formatted(created.orderNo(), created.status(), created.totalAmount()),
                true, purchaseToMap(created));
    }

    private NlCommandResult createSalesOrder(Map<String, Object> args, String requestId) {
        access.require("sal:create");
        long customerId = NlArguments.requiredLong(args, "customerId", "客户 id");
        long warehouseId = NlArguments.requiredLong(args, "warehouseId", "仓库 id");
        LocalDate bizDate = NlArguments.optionalDate(args, "bizDate");
        String remark = remark(args);
        List<SalesOrderApplicationService.CreateLineCommand> lines = new ArrayList<>();
        for (Map<String, Object> line : NlArguments.requiredLines(args, "lines")) {
            Long discount = NlArguments.optionalLong(line, "discountRate");
            if (discount != null && (discount < Integer.MIN_VALUE || discount > Integer.MAX_VALUE)) {
                throw NlArguments.invalid("折扣率超出 int 范围：" + discount);
            }
            lines.add(new SalesOrderApplicationService.CreateLineCommand(
                    NlArguments.requiredLong(line, "skuId", "SKU id"),
                    intArg(line, "qty", "数量"),
                    NlArguments.requiredLong(line, "unitPriceAmount", "单价（分）"),
                    discount == null ? null : discount.intValue()));
        }
        SalesOrder created = salesOrders.create(
                new SalesOrderApplicationService.CreateCommand(
                        customerId, warehouseId, bizDate, remark, lines, requestId));
        return new NlCommandResult(NlIntent.CREATE_SALES_ORDER.code(),
                "已创建出货单草稿 %s（%s，共 %d 分，库存已按既有规则预占）"
                        .formatted(created.orderNo(), created.status(), created.totalAmount()),
                true, salesToMap(created));
    }

    // ─────────────────────────── 参数与响应辅助 ───────────────────────────

    private static Map<String, Object> entry(NlIntent intent, String permission, String example) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("intent", intent.code());
        row.put("write", intent.write());
        row.put("requiredPermission", permission);
        row.put("example", example);
        return row;
    }

    /** 主数据分页：领域对象与 REST 响应同形，直接放 items 即可。 */
    private static Map<String, Object> pageDataRaw(PageResult<?> result) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", result.items());
        data.put("total", result.total());
        data.put("page", result.page());
        data.put("size", result.size());
        return data;
    }

    /**
     * 分页响应映射。
     *
     * <p>业务对象统一转成「与 REST 控制器同形」的 Map，而不是直接序列化领域 record：
     * 后者会带出 {@code version}、退货单的 {@code receiveRequestId} 等内部字段 ——
     * NL 是给人和 Agent 看的出口，不应该顺手泄露内部实现。
     */
    private static <T> Map<String, Object> pageData(PageResult<T> result,
                                                    java.util.function.Function<T, Object> mapper) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", result.items().stream().map(mapper).toList());
        data.put("total", result.total());
        data.put("page", result.page());
        data.put("size", result.size());
        return data;
    }

    private static Map<String, Object> inventoryToMap(InventoryBalance b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("skuId", b.skuId());
        m.put("warehouseId", b.warehouseId());
        m.put("batchNo", b.batchNo());
        m.put("expiryDate", b.expiryDate() == null ? null : b.expiryDate().toString());
        m.put("onHandQty", b.onHandQty());
        m.put("reservedQty", b.reservedQty());
        m.put("availableQty", b.availableQty());
        return m;
    }

    private static Map<String, Object> purchaseToMap(PurchaseOrder o) {
        Map<String, Object> m = baseOrderMap(o.id(), o.orderNo(), o.status(), o.bizDate(),
                o.totalAmount(), o.totalQty(), o.period(), o.remark());
        m.put("supplierId", o.supplierId());
        m.put("warehouseId", o.warehouseId());
        List<Map<String, Object>> lines = new ArrayList<>();
        for (var line : o.lines()) {
            Map<String, Object> lm = new LinkedHashMap<>();
            lm.put("id", line.id());
            lm.put("lineNo", line.lineNo());
            lm.put("skuId", line.skuId());
            lm.put("qty", line.qty());
            lm.put("receivedQty", line.receivedQty());
            lm.put("openQty", line.openQty());
            lm.put("unitPriceAmount", line.unitPriceAmount());
            lm.put("amount", line.amount());
            lines.add(lm);
        }
        m.put("lines", lines);
        return m;
    }

    private static Map<String, Object> salesToMap(SalesOrder o) {
        Map<String, Object> m = baseOrderMap(o.id(), o.orderNo(), o.status(), o.bizDate(),
                o.totalAmount(), o.totalQty(), o.period(), o.remark());
        m.put("customerId", o.customerId());
        m.put("warehouseId", o.warehouseId());
        List<Map<String, Object>> lines = new ArrayList<>();
        for (var line : o.lines()) {
            Map<String, Object> lm = new LinkedHashMap<>();
            lm.put("id", line.id());
            lm.put("lineNo", line.lineNo());
            lm.put("skuId", line.skuId());
            lm.put("qty", line.qty());
            lm.put("shippedQty", line.shippedQty());
            lm.put("returnedQty", line.returnedQty());
            lm.put("openQty", line.openQty());
            lm.put("unitPriceAmount", line.unitPriceAmount());
            lm.put("discountRate", line.discountRate());
            lm.put("amount", line.amount());
            lines.add(lm);
        }
        m.put("lines", lines);
        return m;
    }

    private static Map<String, Object> returnToMap(ReturnOrder o) {
        Map<String, Object> m = baseOrderMap(o.id(), o.orderNo(), o.status(), o.bizDate(),
                o.totalAmount(), o.totalQty(), o.period(), o.remark());
        m.put("originSalesOrderId", o.originSalesOrderId());
        m.put("customerId", o.customerId());
        m.put("warehouseId", o.warehouseId());
        List<Map<String, Object>> lines = new ArrayList<>();
        for (var line : o.lines()) {
            Map<String, Object> lm = new LinkedHashMap<>();
            lm.put("id", line.id());
            lm.put("lineNo", line.lineNo());
            lm.put("originLineId", line.originLineId());
            lm.put("skuId", line.skuId());
            lm.put("qty", line.qty());
            lm.put("unitPriceAmount", line.unitPriceAmount());
            lm.put("discountRate", line.discountRate());
            lm.put("amount", line.amount());
            lm.put("batchNo", line.batchNo());
            lm.put("productionDate",
                    line.productionDate() == null ? null : line.productionDate().toString());
            lm.put("expiryDate", line.expiryDate() == null ? null : line.expiryDate().toString());
            lines.add(lm);
        }
        m.put("lines", lines);
        return m;
    }

    private static Map<String, Object> baseOrderMap(long id, String orderNo, DocumentStatus status,
                                                    LocalDate bizDate, long totalAmount, int totalQty,
                                                    String period, String remark) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("orderNo", orderNo);
        m.put("status", status.name());
        m.put("bizDate", bizDate == null ? null : bizDate.toString());
        m.put("totalAmount", totalAmount);
        m.put("totalQty", totalQty);
        m.put("period", period);
        m.put("remark", remark);
        return m;
    }

    private static int intArg(Map<String, Object> line, String key, String label) {
        long value = NlArguments.requiredLong(line, key, label);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw NlArguments.invalid("参数 %s 超出 int 范围：%d".formatted(key, value));
        }
        return (int) value;
    }

    /** 与 REST 建单接口的 @Size(max=500) 保持一致，避免把超长备注带到数据库层变成 500。 */
    private static String remark(Map<String, Object> args) {
        String remark = NlArguments.optionalString(args, "remark");
        if (remark != null && remark.length() > 500) {
            throw NlArguments.invalid("remark 长度不能超过 500：实际 %d".formatted(remark.length()));
        }
        return remark;
    }

    private static String docType(Map<String, Object> args) {
        return NlArguments.requiredString(args, "docType", "单据类型")
                .toUpperCase(Locale.ROOT);
    }

    private static String docTypeLabel(String docType) {
        return switch (docType) {
            case "PURCHASE" -> "进货单";
            case "SALES" -> "出货单";
            case "RETURN" -> "退货单";
            default -> docType;
        };
    }

    private static DocumentStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return DocumentStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.NL_COMMAND_INVALID_ARGUMENT,
                    "status 取值非法：%s（允许 %s）".formatted(raw, List.of(DocumentStatus.values())));
        }
    }
}
