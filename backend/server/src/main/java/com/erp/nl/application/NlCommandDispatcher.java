package com.erp.nl.application;

import com.erp.mdm.application.CustomerApplicationService;
import com.erp.mdm.application.ProductApplicationService;
import com.erp.mdm.application.SkuApplicationService;
import com.erp.mdm.application.SupplierApplicationService;
import com.erp.mdm.application.WarehouseApplicationService;
import com.erp.mdm.domain.Customer;
import com.erp.mdm.domain.Product;
import com.erp.mdm.domain.Sku;
import com.erp.mdm.domain.Supplier;
import com.erp.mdm.domain.Warehouse;
import com.erp.nl.domain.NlCommand;
import com.erp.nl.domain.NlCommandResult;
import com.erp.nl.domain.NlIntent;
import com.erp.shared.db.SortColumns;
import com.erp.shared.document.DocumentStatus;
import com.erp.shared.document.PartyDealSummary;
import com.erp.shared.error.BusinessException;
import com.erp.shared.error.ErrorCode;
import com.erp.shared.nl.NlModelConfigPort;
import com.erp.shared.nl.NlModelSettings;
import com.erp.shared.time.ClockProvider;
import com.erp.shared.web.PageResult;
import com.erp.sys.application.SqlSandbox;
import com.erp.inv.application.InventoryApplicationService;
import com.erp.inv.application.InventoryInsightApplicationService;
import com.erp.inv.domain.InventoryBalance;
import com.erp.inv.domain.ReplenishmentSuggestion;
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
    private final NlPolicy policy;
    private final ClockProvider clock;
    private final InventoryApplicationService inventory;
    private final InventoryInsightApplicationService insights;
    private final PurchaseOrderApplicationService purchaseOrders;
    private final SalesOrderApplicationService salesOrders;
    private final ReturnOrderApplicationService returnOrders;
    private final ReportApplicationService reports;
    private final ProductApplicationService products;
    private final SkuApplicationService skus;
    private final SupplierApplicationService suppliers;
    private final CustomerApplicationService customers;
    private final WarehouseApplicationService warehouses;
    private final NlModelConfigPort modelConfig;
    private final SqlSandbox sandbox;
    private final NlAnswerWriter answers;

    public NlCommandDispatcher(NlAccessGuard access,
                               NlPolicy policy,
                               ClockProvider clock,
                               InventoryApplicationService inventory,
                               InventoryInsightApplicationService insights,
                               PurchaseOrderApplicationService purchaseOrders,
                               SalesOrderApplicationService salesOrders,
                               ReturnOrderApplicationService returnOrders,
                               ReportApplicationService reports,
                               ProductApplicationService products,
                               SkuApplicationService skus,
                               SupplierApplicationService suppliers,
                               CustomerApplicationService customers,
                               WarehouseApplicationService warehouses,
                               NlModelConfigPort modelConfig,
                               SqlSandbox sandbox,
                               NlAnswerWriter answers) {
        this.access = access;
        this.policy = policy;
        this.clock = clock;
        this.inventory = inventory;
        this.insights = insights;
        this.purchaseOrders = purchaseOrders;
        this.salesOrders = salesOrders;
        this.returnOrders = returnOrders;
        this.reports = reports;
        this.products = products;
        this.skus = skus;
        this.suppliers = suppliers;
        this.customers = customers;
        this.warehouses = warehouses;
        this.modelConfig = modelConfig;
        this.sandbox = sandbox;
        this.answers = answers;
    }

    public NlCommandResult execute(NlCommand command, String requestId) {
        // 权限集中在入口判一次（规则在 NlPolicy，与 /nl/plan 共用同一份）。
        // 分散在各分支时，一旦加了新意图就容易漏判 —— 漏判的后果是越权，代价太大。
        // 非法 docType 时 requiredPermission 返回 null，交由下面的 switch 报参数错误。
        String permission = policy.requiredPermission(command);
        if (permission != null) {
            access.require(permission);
        }
        return switch (command.intent()) {
            case HELP -> help();
            case WHO_AM_I -> whoAmI(command.arguments());
            case QUERY_INVENTORY -> queryInventory(command.arguments());
            case LIST_DOCUMENTS -> listDocuments(command.arguments());
            case GET_DOCUMENT -> getDocument(command.arguments());
            case INVENTORY_SUMMARY -> inventorySummary(command.arguments());
            case REPLENISH_ADVICE -> replenishAdvice(command.arguments());
            case QUERY_MASTER_DATA -> queryMasterData(command.arguments());
            case SQL_QUERY -> sqlQuery(command.arguments());
            case CREATE_PURCHASE_ORDER -> createPurchaseOrder(command.arguments(), requestId);
            case CREATE_SALES_ORDER -> createSalesOrder(command.arguments(), requestId);
            case SUBMIT_DOCUMENT -> submitDocument(command.arguments());
        };
    }

    // ─────────────────────────── 读意图 ───────────────────────────

    /**
     * 自述：如实回答"你现在用的是什么、能帮我做什么"。
     *
     * <p><b>为什么同一份事实要交给模型组织、而不是直接回一段固定文案</b>：
     * 用户可能从任何角度问（你是什么模型 / 你对系统有什么了解 / 你能帮我干嘛 / 你有什么本事）。
     * 固定文案会让所有问法得到同一句话，一眼就能看出是背稿。
     * 事实由代码给（不许编），表述交给模型按问法调整 —— 这才是"理解了再回答"。
     *
     * <p>模型不可用时退回固定文案：宁可显得生硬，也不能没有答案。
     */
    private NlCommandResult whoAmI(Map<String, Object> args) {
        NlModelSettings settings = modelConfig.current();
        boolean llm = settings.llmEnabled();
        String question = NlArguments.optionalString(args, "question");

        String facts = """
                当前解析引擎：%s
                复核方式：%s（只对写操作生效，查询不会触发第二次模型调用）
                今天日期：%s
                能做的：查库存与可用量；查进货/出货/退货单（可按时间、状态、金额筛选并排序）；
                  查商品/SKU/供应商/客户/仓库；按任意条件做自定义统计与排行（自己组织查询）；
                  给补货建议；建进货单或出货单草稿；把草稿提交成待审。
                不能做的：审核、入库、出库、作废、删除、直接改账 —— 这些必须由人在页面上操作，
                  接口本身就不提供，不是权限没开。
                规则：所有写操作都要先出计划、由人确认后才落库。
                数据范围：仅当前租户，且受仓库与本人的数据权限限制。
                """.formatted(
                llm ? settings.model() + "（通过 OpenAI 兼容接口做 function calling）" : "没有接模型，用本地关键词规则匹配",
                settings.aiReviewEnabled() ? "规则 + AI 复核" : "仅确定性规则",
                clock.now().toLocalDate());

        String narrative = answers == null ? null : answers.narrate(question, facts);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("解析引擎", llm ? settings.model() : "未接入（关键词规则）");
        data.put("复核方式", settings.aiReviewEnabled() ? "规则 + AI 复核" : "仅确定性规则");
        data.put("可用意图数", NlIntent.values().length - 2);
        return new NlCommandResult(NlIntent.WHO_AM_I.code(),
                narrative == null || narrative.isBlank()
                        ? (llm
                                ? "我在用 " + settings.model() + " 解析你的话：它负责把你的意思翻译成一次系统调用。"
                                : "当前没有接入大模型，是按关键词规则匹配的（可在「系统 → AI 接入」里配置）。")
                        : narrative,
                false, data);
    }

    private NlCommandResult help() {
        List<Map<String, Object>> intents = new ArrayList<>();
        intents.add(entry(NlIntent.QUERY_INVENTORY, "inv:read", "查询 SKU 12 在仓库 3 的库存"));
        intents.add(entry(NlIntent.LIST_DOCUMENTS, "pur:read / sal:read / rma:read",
                "进货单列表 状态 已审核 第1页"));
        intents.add(entry(NlIntent.GET_DOCUMENT, "pur:read / sal:read / rma:read",
                "查看进货单详情 id=12"));
        intents.add(entry(NlIntent.INVENTORY_SUMMARY, "rpt:read",
                "账期 2026-10 进销存汇总"));
        intents.add(entry(NlIntent.REPLENISH_ADVICE, "inv:read",
                "补货建议 仓库 1"));
        intents.add(entry(NlIntent.QUERY_MASTER_DATA, "mdm:read",
                "查询商品 关键字 T恤"));
        intents.add(entry(NlIntent.CREATE_PURCHASE_ORDER, "pur:create",
                "创建进货单 供应商=1 仓库=1 SKU=5 数量=10 单价=1000"));
        intents.add(entry(NlIntent.CREATE_SALES_ORDER, "sal:create",
                "创建出货单 客户=1 仓库=1 SKU=5 数量=2 单价=12900"));
        intents.add(entry(NlIntent.SUBMIT_DOCUMENT, "pur:submit / sal:submit / rma:submit",
                "提交进货单 12"));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("intents", intents);
        return new NlCommandResult(NlIntent.HELP.code(),
                // 用枚举总数而不是 intents.size()：上面的列表不列 help 自己，
                // 两个数不一致时用户会以为"帮助说 8 个，报错却列了 9 个"。
                "支持 %d 个意图（含 help）；写意图只建草稿或提交待审，"
                        .formatted(NlIntent.values().length)
                        + "审核 / 入库 / 出货 / 作废必须人工在页面上做",
                false, data);
    }

    private NlCommandResult queryInventory(Map<String, Object> args) {
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
        // ★ 日期区间与排序是"自然语言里最常说的两个条件"（"一个月内的""按时间排序"）。
        //   早期工具定义里没有这两项，模型只能把它们丢掉、回一份全量列表 ——
        //   用户看到的就是"我说了时间，它把所有的都列出来了"。
        LocalDate dateFrom = NlArguments.optionalDate(args, "dateFrom");
        LocalDate dateTo = NlArguments.optionalDate(args, "dateTo");
        String orderBy = NlArguments.optionalString(args, "orderBy");
        boolean ascending = SortColumns.ascending(NlArguments.optionalString(args, "sortDir"));
        Object data;
        long total;
        switch (docType) {
            case "PURCHASE" -> {
                PageResult<PurchaseOrder> result =
                        purchaseOrders.page(status, partyId, dateFrom, dateTo, orderBy, ascending, page, size);
                data = pageData(result, NlCommandDispatcher::purchaseToMap);
                total = result.total();
            }
            case "SALES" -> {
                PageResult<SalesOrder> result =
                        salesOrders.page(status, partyId, dateFrom, dateTo, orderBy, ascending, page, size);
                data = pageData(result, NlCommandDispatcher::salesToMap);
                total = result.total();
            }
            case "RETURN" -> {
                PageResult<ReturnOrder> result = returnOrders.page(status, partyId,
                        NlArguments.optionalLong(args, "originSalesOrderId"),
                        dateFrom, dateTo, orderBy, ascending, page, size);
                data = pageData(result, NlCommandDispatcher::returnToMap);
                total = result.total();
            }
            default -> throw NlArguments.invalid("docType 取值非法：" + docType);
        }
        return new NlCommandResult(NlIntent.LIST_DOCUMENTS.code(),
                "%s列表%s：第 %d 页，共 %d 张，%s".formatted(
                        docTypeLabel(docType), describeRange(dateFrom, dateTo),
                        page, total, describeSort(orderBy, ascending)),
                false, data);
    }

    /**
     * 把筛选条件写进摘要。
     *
     * <p>这不是装饰：用户说"一个月内的"，如果结果里看不出这个条件被采用了没有，
     * 他无法判断是"确实只有 3 张"还是"条件被忽略了"。摘要必须能自证。
     */
    private static String describeRange(LocalDate from, LocalDate to) {
        if (from == null && to == null) {
            return "";
        }
        return "（%s ~ %s）".formatted(
                from == null ? "不限" : from.toString(),
                to == null ? "不限" : to.toString());
    }

    private static String describeSort(String orderBy, boolean ascending) {
        String label = switch (orderBy == null ? "" : orderBy.toLowerCase()) {
            case "bizdate", "biz_date" -> "业务日期";
            case "totalamount", "total_amount" -> "金额";
            case "totalqty", "total_qty" -> "数量";
            case "orderno", "order_no" -> "单号";
            default -> "id";
        };
        return "按" + label + (ascending ? "升序" : "降序");
    }

    private NlCommandResult getDocument(Map<String, Object> args) {
        String docType = docType(args);
        long id = NlArguments.requiredLong(args, "id", "单据 id");
        Object order;
        String description;
        switch (docType) {
            case "PURCHASE" -> {
                PurchaseOrder found = purchaseOrders.require(id);
                order = purchaseToMap(found);
                description = "进货单 %s（%s），金额 %d 分"
                        .formatted(found.orderNo(), found.status(), found.totalAmount());
            }
            case "SALES" -> {
                SalesOrder found = salesOrders.require(id);
                order = salesToMap(found);
                description = "出货单 %s（%s），金额 %d 分"
                        .formatted(found.orderNo(), found.status(), found.totalAmount());
            }
            case "RETURN" -> {
                ReturnOrder found = returnOrders.require(id);
                order = returnToMap(found);
                description = "退货单 %s（%s），金额 %d 分"
                        .formatted(found.orderNo(), found.status(), found.totalAmount());
            }
            default -> throw NlArguments.invalid("docType 取值非法：" + docType);
        }
        return new NlCommandResult(NlIntent.GET_DOCUMENT.code(), description, false, order);
    }

    /**
     * 提交单据（FR-V4-03 · S7 受限 L2 的第一档）。
     *
     * <p><b>只做一件事：调既有服务的 {@code submit}</b>。状态机、前置校验、
     * 审批流实例的创建全部由 {@code ApplicationService} 负责——NL 层一旦自己判
     * "这张单能不能提交"，就等于在自然语言入口复制了第二套状态机。
     *
     * <p>它的风险不在于"改数据"（提交只改状态，不改库存与账），而在于
     * **把单子推到了别人面前**。所以它仍然要过三道：权限（{@code *:submit}）、
     * 人工确认（回执或队列）、审计留痕。
     */
    private NlCommandResult submitDocument(Map<String, Object> args) {
        String docType = docType(args);
        long id = NlArguments.requiredLong(args, "id", "单据 id");
        Object order;
        String description;
        switch (docType) {
            case "PURCHASE" -> {
                PurchaseOrder submitted = purchaseOrders.submit(id);
                order = purchaseToMap(submitted);
                description = "已提交进货单 %s，当前状态 %s"
                        .formatted(submitted.orderNo(), submitted.status());
            }
            case "SALES" -> {
                SalesOrder submitted = salesOrders.submit(id);
                order = salesToMap(submitted);
                description = "已提交出货单 %s，当前状态 %s"
                        .formatted(submitted.orderNo(), submitted.status());
            }
            case "RETURN" -> {
                ReturnOrder submitted = returnOrders.submit(id);
                order = returnToMap(submitted);
                description = "已提交退货单 %s，当前状态 %s"
                        .formatted(submitted.orderNo(), submitted.status());
            }
            default -> throw NlArguments.invalid("docType 取值非法：" + docType);
        }
        return new NlCommandResult(NlIntent.SUBMIT_DOCUMENT.code(), description, true, order);
    }

    private NlCommandResult inventorySummary(Map<String, Object> args) {
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

    /**
     * 目标级意图：一句话拿到"该补什么货"。
     *
     * <p><b>这里刻意不写任何补货规则</b>——候选集在 {@code InventoryInsightRepository}，
     * 公式在 {@code ReplenishmentFormula}，与页面「补货建议」用的是同一条链路。
     * 本方法只做三件事：校验权限、取参数、把结果装盘。
     * 换句话说，NL 层增加的是"入口"，不是"第二套业务逻辑"——它要是自己算一遍，
     * 页面上算的数和它算的数迟早会对不上。
     */
    private NlCommandResult replenishAdvice(Map<String, Object> args) {
        Long warehouseId = NlArguments.optionalLong(args, "warehouseId");
        List<ReplenishmentSuggestion> rows = insights.replenishment(warehouseId);
        long totalSuggested = rows.stream().mapToLong(ReplenishmentSuggestion::suggestedQty).sum();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", rows.size());
        data.put("totalSuggestedQty", totalSuggested);
        if (warehouseId != null) {
            data.put("warehouseId", warehouseId);
        }
        data.put("items", rows);
        String scope = warehouseId == null ? "全部仓库" : "仓库 " + warehouseId;
        return new NlCommandResult(NlIntent.REPLENISH_ADVICE.code(),
                "补货建议（%s）：%d 个 SKU 需补，合计建议补货 %d 件"
                        .formatted(scope, rows.size(), totalSuggested),
                false, data);
    }

    /**
     * 主数据查询。
     *
     * <p><b>为什么返回中文键而不是实体字段名</b>：原来直接把 {@code Supplier} 记录丢给
     * Jackson，用户看到的是 {@code code / contactName / enabled} 和 {@code id=1} ——
     * 这是把数据库表结构当成了回答。字段名一旦变成中文，同一个响应既给人看也给 Agent 看，
     * 不必在前端再维护一张映射表（那张表正是"新增字段就露英文"的来源）。
     *
     * <p><b>为什么单条命中时换成"画像"</b>：用户问"杭州面料供应商的信息"，想知道的不是
     * 地址和电话，而是"这个供应商跟我做了多少生意、多久没来了"。这些数不在
     * {@code suppliers} 表里，得从单据聚合 —— 这才是"总结"，而不是把一行字段念一遍。
     * 多条命中时仍给列表：这时候用户在做筛选，需要对比。
     */
    private NlCommandResult queryMasterData(Map<String, Object> args) {
        String type = NlArguments.requiredString(args, "type", "主数据类型").toUpperCase(Locale.ROOT);
        String keyword = NlArguments.optionalString(args, "keyword");
        int page = NlArguments.optionalInt(args, "page", DEFAULT_PAGE);
        int size = NlArguments.optionalInt(args, "size", DEFAULT_SIZE);
        Object data;
        String summary;
        switch (type) {
            case "WAREHOUSE" -> {
                boolean onlyEnabled = NlArguments.optionalBoolean(args, "onlyEnabled", false);
                List<Warehouse> rows = warehouses.list(onlyEnabled);
                data = pageOf(rows.stream().map(NlCommandDispatcher::warehouseFields).toList(),
                        rows.size(), 1, rows.size());
                summary = "仓库：共 %d 个%s".formatted(rows.size(), onlyEnabled ? "（仅启用）" : "");
            }
            case "PRODUCT" -> {
                PageResult<Product> result = products.page(keyword, page, size);
                data = pageOf(result.items().stream().map(NlCommandDispatcher::productFields).toList(),
                        result.total(), result.page(), result.size());
                summary = "商品：共 %d 条%s".formatted(result.total(), keywordHint(keyword));
            }
            case "SKU" -> {
                PageResult<Sku> result = skus.page(
                        NlArguments.optionalLong(args, "productId"),
                        NlArguments.optionalString(args, "color"),
                        NlArguments.optionalString(args, "skuSize"),
                        page, size);
                data = pageOf(result.items().stream().map(NlCommandDispatcher::skuFields).toList(),
                        result.total(), result.page(), result.size());
                summary = "SKU：共 %d 条%s".formatted(result.total(), keywordHint(keyword));
            }
            case "SUPPLIER" -> {
                PageResult<Supplier> result = suppliers.page(keyword, page, size);
                if (result.total() == 1 && !result.items().isEmpty()) {
                    Supplier s = result.items().get(0);
                    PartyDealSummary deal = purchaseOrders.summaryBySupplier(s.id());
                    data = partyProfile(supplierFields(s), deal, "采购");
                    summary = "供应商「%s」：%s".formatted(s.name(), dealText(deal, "采购"));
                } else {
                    data = pageOf(result.items().stream().map(NlCommandDispatcher::supplierFields).toList(),
                            result.total(), result.page(), result.size());
                    summary = "供应商：共 %d 条%s".formatted(result.total(), keywordHint(keyword));
                }
            }
            case "CUSTOMER" -> {
                PageResult<Customer> result = customers.page(keyword, page, size);
                if (result.total() == 1 && !result.items().isEmpty()) {
                    Customer c = result.items().get(0);
                    PartyDealSummary deal = salesOrders.summaryByCustomer(c.id());
                    data = partyProfile(customerFields(c), deal, "销售");
                    summary = "客户「%s」：%s".formatted(c.name(), dealText(deal, "销售"));
                } else {
                    data = pageOf(result.items().stream().map(NlCommandDispatcher::customerFields).toList(),
                            result.total(), result.page(), result.size());
                    summary = "客户：共 %d 条%s".formatted(result.total(), keywordHint(keyword));
                }
            }
            default -> throw NlArguments.invalid(
                    "type 取值非法：%s（允许 WAREHOUSE / PRODUCT / SKU / SUPPLIER / CUSTOMER）"
                            .formatted(type));
        }
        return new NlCommandResult(NlIntent.QUERY_MASTER_DATA.code(), summary, false, data);
    }

    /**
     * 自定义查询：模型写 SQL → 沙箱执行 → 模型把结果讲成人话。
     *
     * <p><b>为什么"回答"放在 description 而不是 data 里</b>：description 是结果头部
     * 那句话，本来就该是"系统对你说的话"。把自然语言塞进 data 会让表格去渲染一段文字，
     * 而表格只认标量列。
     *
     * <p>组织语言失败不影响数据返回：{@code answer} 为 null 时退化为
     * "共 N 行（已截断）"——数据是主产物，措辞是加分项。
     */
    private NlCommandResult sqlQuery(Map<String, Object> args) {
        String sql = NlArguments.requiredString(args, "sql", "SQL");
        String question = NlArguments.optionalString(args, "question");
        SqlSandbox.Result result = sandbox.run(sql);

        String answer = answers == null ? null : answers.summarize(question, sql, result);
        if (answer == null || answer.isBlank()) {
            answer = result.empty()
                    ? "没有查到符合条件的数据。"
                    : "查到 %d 行数据%s。".formatted(result.rows().size(),
                            result.truncated() ? "（超过 200 行，已截断）" : "");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", result.rows());
        data.put("total", result.rows().size());
        data.put("page", 1);
        data.put("size", result.rows().size());
        return new NlCommandResult(NlIntent.SQL_QUERY.code(), answer, false, data);
    }

    // ─────────────── 主数据的展示形状（中文键，去掉 id/version 这类内部字段） ───────────────
    private static Map<String, Object> supplierFields(Supplier s) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("编码", s.code());
        row.put("名称", s.name());
        row.put("联系人", joinContact(s.contactName(), s.contactPhone()));
        row.put("地址", dash(s.address()));
        row.put("状态", s.enabled() ? "启用" : "停用");
        return row;
    }

    private static Map<String, Object> customerFields(Customer c) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("编码", c.code());
        row.put("名称", c.name());
        row.put("电话", dash(c.phone()));
        row.put("等级", dash(c.level()));
        // 折扣率是万分比：9000 → 9 折。直接显示 9000 没人看得懂。
        row.put("折扣", c.discountRate() >= 10000 ? "无折扣"
                : "%.1f 折".formatted(c.discountRate() / 1000.0));
        row.put("信用额度", money(c.creditLimit()));
        row.put("账期", c.creditDays() + " 天");
        row.put("状态", c.enabled() ? "启用" : "停用");
        return row;
    }

    private static Map<String, Object> productFields(Product p) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("编码", p.code());
        row.put("名称", p.name());
        row.put("品类", dash(p.category()));
        row.put("季节", dash(p.season()));
        row.put("状态", p.enabled() ? "启用" : "停用");
        return row;
    }

    private static Map<String, Object> skuFields(Sku s) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("条码", dash(s.barcode()));
        row.put("颜色", dash(s.color()));
        row.put("尺码", dash(s.size()));
        row.put("零售价", money(s.retailPriceAmount()));
        row.put("上下限", s.minLevel() + " / " + s.maxLevel());
        row.put("状态", s.enabled() ? "启用" : "停用");
        return row;
    }

    private static Map<String, Object> warehouseFields(Warehouse w) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("编码", w.code());
        row.put("名称", w.name());
        row.put("地址", dash(w.address()));
        row.put("优先级", w.priority());
        row.put("状态", w.enabled() ? "启用" : "停用");
        return row;
    }

    /**
     * 单条命中的画像：基本信息 + 往来情况。
     *
     * <p>分两层而不是平铺，是为了让"这是档案、这是经营数据"一眼可分；
     * 平铺会让 {@code 累计金额} 与 {@code 电话} 看起来同级。
     */
    private static Map<String, Object> partyProfile(Map<String, Object> info,
                                                    PartyDealSummary deal, String bizLabel) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("基本信息", info);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put(bizLabel + "单数", deal.orderCount());
        d.put("累计" + bizLabel + "金额", money(deal.totalAmount()));
        d.put("最近交易", deal.lastBizDate() == null ? "从未交易" : deal.lastBizDate().toString());
        d.put("待审单据", deal.pendingCount());
        out.put("往来情况", d);
        return out;
    }

    private static String dealText(PartyDealSummary deal, String bizLabel) {
        if (deal.empty()) {
            return "还没有" + bizLabel + "往来记录";
        }
        StringBuilder sb = new StringBuilder("%d 笔%s、累计 %s，最近一次 %s".formatted(
                deal.orderCount(), bizLabel, money(deal.totalAmount()), deal.lastBizDate()));
        if (deal.pendingCount() > 0) {
            sb.append("，其中 ").append(deal.pendingCount()).append(" 张待审");
        }
        return sb.toString();
    }

    private static Map<String, Object> pageOf(List<Map<String, Object>> items, long total,
                                              int page, int size) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("total", total);
        body.put("page", page);
        body.put("size", size);
        return body;
    }

    private static String keywordHint(String keyword) {
        return keyword == null || keyword.isBlank() ? "" : "（关键词「" + keyword + "」）";
    }

    /** 金额统一从「分」换成「元」再展示，保留两位小数与千分位。 */
    private static String money(long cents) {
        return "¥" + String.format("%,.2f", cents / 100.0);
    }

    private static String joinContact(String name, String phone) {
        boolean hasName = name != null && !name.isBlank();
        boolean hasPhone = phone != null && !phone.isBlank();
        if (hasName && hasPhone) {
            return name + " / " + phone;
        }
        if (hasName) {
            return name;
        }
        return hasPhone ? phone : "—";
    }

    private static String dash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    // ─────────────────────────── 写意图（只建草稿） ───────────────────────────

    private NlCommandResult createPurchaseOrder(Map<String, Object> args, String requestId) {
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
