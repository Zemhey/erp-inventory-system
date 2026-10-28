package com.erp.nl.application;

import com.erp.mdm.application.CustomerApplicationService;
import com.erp.mdm.application.SkuApplicationService;
import com.erp.mdm.application.SupplierApplicationService;
import com.erp.mdm.application.WarehouseApplicationService;
import com.erp.mdm.domain.Customer;
import com.erp.mdm.domain.Sku;
import com.erp.mdm.domain.Supplier;
import com.erp.mdm.domain.Warehouse;
import com.erp.nl.domain.NlCommand;
import com.erp.nl.domain.NlIntent;
import com.erp.nl.domain.NlPlan;
import com.erp.pur.application.PurchaseOrderApplicationService;
import com.erp.pur.domain.PurchaseOrder;
import com.erp.rma.application.ReturnOrderApplicationService;
import com.erp.rma.domain.ReturnOrder;
import com.erp.sal.application.SalesOrderApplicationService;
import com.erp.sal.domain.SalesOrder;
import com.erp.shared.error.BusinessException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 生成"执行计划"（FR-V4-03 · S3）：这条指令打算做什么、做完会发生什么。
 *
 * <p><b>本类不执行任何写操作。</b>它对写意图只做三件事：
 * 校验参数形状 → 用<b>只读</b>服务确认引用对象存在（供应商/客户/仓库/SKU）→ 算一遍金额给人看。
 * 一行都不会落库，所以"点一次预览"是绝对安全的，可以反复点。
 *
 * <p><b>金额不自己发明规则</b>：直接调 {@code PurchaseOrderApplicationService.lineAmount} /
 * {@code SalesOrderApplicationService.lineAmount} —— 那是真建单时用的同一个函数。
 * 如果预览自己乘一遍，迟早出现"预览 ¥100、建出来 ¥98"这种把用户信任一次烧光的差异。
 *
 * <p><b>权限在预览阶段就判</b>：否则会出现最招人恨的体验——预览得很顺，点确认却 403。
 */
@Service
public class NlPlanService {

    /** 低于 1 元的单价视为可疑（本库 SKU 零售价在百元级，1 元大概率是"分/元"搞混了）。 */
    private static final long LOW_PRICE_THRESHOLD = 100L;
    /** 单行数量超一万件视为可疑（服装单款单仓极少一次进这么多）。 */
    private static final int HUGE_QTY_THRESHOLD = 10_000;
    /** 合计金额超 10 万元要人特别看一眼。 */
    private static final long LARGE_AMOUNT_THRESHOLD = 10_000_000L;
    /** 明细行过多时提醒：这类单子更可能该走批量导入而不是一句话。 */
    private static final int MANY_LINES_THRESHOLD = 20;

    private final NlPolicy policy;
    private final NlAccessGuard access;
    private final SupplierApplicationService suppliers;
    private final CustomerApplicationService customers;
    private final WarehouseApplicationService warehouses;
    private final SkuApplicationService skus;
    // 提交计划要回查单据当前状态。这三个是**只读**使用（require），
    // 真正的状态流转只在 dispatcher 里调它们的 submit。
    private final PurchaseOrderApplicationService purchaseOrders;
    private final SalesOrderApplicationService salesOrders;
    private final ReturnOrderApplicationService returnOrders;

    public NlPlanService(NlPolicy policy,
                         NlAccessGuard access,
                         SupplierApplicationService suppliers,
                         CustomerApplicationService customers,
                         WarehouseApplicationService warehouses,
                         SkuApplicationService skus,
                         PurchaseOrderApplicationService purchaseOrders,
                         SalesOrderApplicationService salesOrders,
                         ReturnOrderApplicationService returnOrders) {
        this.policy = policy;
        this.access = access;
        this.suppliers = suppliers;
        this.customers = customers;
        this.warehouses = warehouses;
        this.skus = skus;
        this.purchaseOrders = purchaseOrders;
        this.salesOrders = salesOrders;
        this.returnOrders = returnOrders;
    }

    public NlPlan plan(NlCommand command) {
        String permission = policy.requiredPermission(command);
        if (permission != null) {
            access.require(permission);
        }
        return command.intent().write() ? writePlan(command) : readPlan(command);
    }

    // ─────────────────────────── 只读意图 ───────────────────────────

    private NlPlan readPlan(NlCommand command) {
        Map<String, Object> args = command.arguments();
        List<Map<String, Object>> impact = new ArrayList<>();
        impact.add(NlPlan.row("操作类型", "只读查询"));
        impact.add(NlPlan.row("数据变更", "无"));
        impact.add(NlPlan.row("副作用", "不写库、不动库存、不产生单据"));

        List<String> steps = new ArrayList<>();
        steps.add("解析意图与参数");
        steps.add("调用既有查询服务（与页面同一套）");
        steps.add("返回结果；本次不写任何数据");

        // ★ 只读计划里刻意**不做任何查询**。
        //   原因：只读意图在前端是"直通"的（requiresReview=false，用户看不到这张计划卡），
        //   如果这里为了"预计命中 N 个 SKU"去扫一遍全仓，就等于同一条指令查了两遍。
        //   计划阶段只回答"这条指令会不会改数据"，不承担"先把结果算一半"的职能。

        return new NlPlan(
                command.intent().code(),
                false,
                false,
                policy.reviewReason(command),
                true,
                "只读：" + intentLabel(command.intent()) + "（不改数据，可直接执行）",
                steps,
                impact,
                List.of(),
                List.of(),
                args);
    }

    // ─────────────────────────── 写意图 ───────────────────────────

    private NlPlan writePlan(NlCommand command) {
        return switch (command.intent()) {
            case CREATE_PURCHASE_ORDER -> orderPlan(command, true);
            case CREATE_SALES_ORDER -> orderPlan(command, false);
            case SUBMIT_DOCUMENT -> submitPlan(command);
            // 到不了这里：write() 的意图必须在这里有分支，
            // 否则会落到下面这个异常上——fail-fast 比默默生成一个"什么都没有"的计划好。
            default -> throw new IllegalStateException(
                    "写意图缺少计划模板：" + command.intent().code());
        };
    }

    /**
     * 提交单据的计划（受限 L2）。
     *
     * <p>校验只有两条：单号填了没有、这张单现在是不是草稿。
     * "能不能提交"的完整判断仍在 {@code ApplicationService} 里——这里只做
     * **预演**（提前把明显不行的情况说清楚），不复制状态机。
     */
    private NlPlan submitPlan(NlCommand command) {
        Map<String, Object> args = command.arguments();
        List<String> steps = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> blockers = new ArrayList<>();
        List<Map<String, Object>> impact = new ArrayList<>();

        String docType = args.get("docType") == null ? null
                : String.valueOf(args.get("docType")).toUpperCase(java.util.Locale.ROOT);
        Long id = NlArguments.optionalLong(args, "id");
        if (docType == null) {
            blockers.add("缺少单据类型（docType：PURCHASE / SALES / RETURN）");
        }
        if (id == null) {
            blockers.add("缺少单据 id");
        }

        String label = docLabel(docType);
        String docNo = null;
        String status = null;
        if (docType != null && id != null) {
            try {
                switch (docType) {
                    case "PURCHASE" -> {
                        PurchaseOrder order = purchaseOrders.require(id);
                        docNo = order.orderNo();
                        status = order.status().name();
                    }
                    case "SALES" -> {
                        SalesOrder order = salesOrders.require(id);
                        docNo = order.orderNo();
                        status = order.status().name();
                    }
                    case "RETURN" -> {
                        ReturnOrder order = returnOrders.require(id);
                        docNo = order.orderNo();
                        status = order.status().name();
                    }
                    default -> blockers.add("单据类型非法：" + docType);
                }
            } catch (BusinessException ex) {
                blockers.add(label + "不存在：id=" + id);
            }
        }
        if (status != null && !"DRAFT".equals(status)) {
            blockers.add("只有草稿能提交，这张单当前状态是 " + status);
        }
        if (status != null && "DRAFT".equals(status)) {
            steps.add("校验" + label + " " + docNo + " 处于草稿状态");
            steps.add("调用既有提交服务（状态机、审批流实例都由它负责）");
            warnings.add("提交后单据内容不可再改；要改只能先撤回（由审核人操作）");
        }

        impact.add(NlPlan.row("单据", docNo == null ? describeId(id) : docNo + "（" + label + "）"));
        impact.add(NlPlan.row("当前状态", status == null ? "（未知）" : status));
        impact.add(NlPlan.row("提交后", "SUBMITTED —— 等待审核"));
        impact.add(NlPlan.row("影响", "不改库存、不动账；只是把这张单推到审核人面前"));
        impact.add(NlPlan.row("不做", "不审核、不入库/出货 —— 那两步必须人工在页面上做"));

        String summary = blockers.isEmpty()
                ? "将提交%s %s 进入待审".formatted(label, docNo)
                : "%s无法提交：还有 %d 个问题需要先解决".formatted(label, blockers.size());

        return new NlPlan(command.intent().code(), true, true, policy.reviewReason(command),
                blockers.isEmpty(), summary, steps, impact, warnings, blockers, args);
    }

    private static String docLabel(String docType) {
        if (docType == null) {
            return "单据";
        }
        return switch (docType) {
            case "PURCHASE" -> "进货单";
            case "SALES" -> "出货单";
            case "RETURN" -> "退货单";
            default -> "单据";
        };
    }

    /**
     * 进货单 / 出货单共用一个模板：差异只在"往来单位是供应商还是客户"和"有没有折扣"。
     * 硬拆成两份会导致两块代码各自演化，最后一边校验了、另一边忘了。
     */
    private NlPlan orderPlan(NlCommand command, boolean purchase) {
        Map<String, Object> args = command.arguments();
        List<String> steps = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> blockers = new ArrayList<>();
        List<Map<String, Object>> impact = new ArrayList<>();

        String partnerLabel = purchase ? "供应商" : "客户";
        String docLabel = purchase ? "进货单" : "出货单";

        // ① 往来单位
        Long partnerId = NlArguments.optionalLong(args, purchase ? "supplierId" : "customerId");
        String partnerName = null;
        if (partnerId == null) {
            blockers.add("缺少" + partnerLabel + "（" + (purchase ? "supplierId" : "customerId") + "）");
        } else if (purchase) {
            Supplier found = tryRequire(() -> suppliers.require(partnerId), blockers,
                    partnerLabel + "不存在：id=" + partnerId);
            if (found != null) {
                partnerName = found.name();
                if (!found.enabled()) {
                    warnings.add(partnerLabel + "「" + found.name() + "」已停用，新单据通常不应再选它");
                }
            }
        } else {
            Customer found = tryRequire(() -> customers.require(partnerId), blockers,
                    partnerLabel + "不存在：id=" + partnerId);
            if (found != null) {
                partnerName = found.name();
                if (!found.enabled()) {
                    warnings.add(partnerLabel + "「" + found.name() + "」已停用，新单据通常不应再选它");
                }
            }
        }
        steps.add("校验" + partnerLabel + " " + describeId(partnerId) + " 存在且可用");

        // ② 仓库
        Long warehouseId = NlArguments.optionalLong(args, "warehouseId");
        String warehouseName = null;
        if (warehouseId == null) {
            blockers.add("缺少仓库（warehouseId）");
        } else {
            Warehouse found = tryRequire(() -> warehouses.require(warehouseId), blockers,
                    "仓库不存在：id=" + warehouseId);
            if (found != null) {
                warehouseName = found.name();
                if (!found.enabled()) {
                    warnings.add("仓库「" + found.name() + "」已停用");
                }
            }
        }
        steps.add("校验仓库 " + describeId(warehouseId) + " 存在且可用");

        // ③ 明细：逐行校验 SKU + 算金额（调的是真建单用的同一个函数）
        List<Map<String, Object>> rawLines = softLines(args);
        int noDiscount = SalesOrderApplicationService.NO_DISCOUNT;
        long totalAmount = 0L;
        int totalQty = 0;
        int validLines = 0;

        if (rawLines.isEmpty()) {
            blockers.add("至少需要一行明细（SKU + 数量 + 单价）");
        }
        for (int i = 0; i < rawLines.size(); i++) {
            Map<String, Object> line = rawLines.get(i);
            String at = "第 " + (i + 1) + " 行";
            Long skuId = asLong(line.get("skuId"));
            Integer qty = asInt(line.get("qty"));
            Long price = asLong(line.get("unitPriceAmount"));
            Integer discount = purchase ? null : asInt(line.get("discountRate"));

            if (skuId == null) {
                blockers.add(at + "缺少 SKU（skuId）");
                continue;
            }
            if (qty == null || qty <= 0) {
                blockers.add(at + "数量必须为正整数");
                continue;
            }
            if (price == null || price < 0) {
                blockers.add(at + "单价（分）必须是非负整数");
                continue;
            }

            Sku sku = tryRequire(() -> skus.require(skuId), blockers,
                    at + "SKU 不存在：id=" + skuId);
            if (sku == null) {
                continue;
            }
            if (!sku.enabled()) {
                warnings.add(at + "SKU " + sku.id() + "（" + sku.color() + "/" + sku.size() + "）已停用");
            }
            if (price < LOW_PRICE_THRESHOLD) {
                warnings.add(at + "单价 " + yuan(price) + " 偏低，确认单位是不是\"分\"");
            }
            if (qty > HUGE_QTY_THRESHOLD) {
                warnings.add(at + "数量 " + qty + " 偏大，请确认");
            }

            int rate = purchase ? noDiscount : (discount == null ? noDiscount : discount);
            long amount;
            try {
                amount = purchase
                        ? PurchaseOrderApplicationService.lineAmount(qty, price)
                        : SalesOrderApplicationService.lineAmount(qty, price, rate);
            } catch (ArithmeticException ex) {
                blockers.add(at + "金额溢出（数量 × 单价过大）");
                continue;
            }
            totalAmount = Math.addExact(totalAmount, amount);
            totalQty += qty;
            validLines++;
        }
        steps.add("逐行校验 SKU 并按现行规则计算金额（" + (purchase ? "数量 × 单价" : "数量 × 单价 × 折扣") + "）");

        if (validLines > MANY_LINES_THRESHOLD) {
            warnings.add("明细共 " + validLines + " 行，行数较多；这类单子更适合在页面上批量录入");
        }
        if (totalAmount > LARGE_AMOUNT_THRESHOLD) {
            warnings.add("合计 " + yuan(totalAmount) + "，属于大额单据，请特别核对");
        }

        steps.add("生成 " + docLabel + " 草稿（状态 DRAFT）；本次不审核、不入库、不出货");

        LocalDate bizDate = NlArguments.optionalDate(args, "bizDate");
        impact.add(NlPlan.row("单据类型", docLabel + "（草稿）"));
        impact.add(NlPlan.row(partnerLabel, partnerName == null ? describeId(partnerId) : partnerName + "（" + partnerId + "）"));
        impact.add(NlPlan.row("仓库", warehouseName == null ? describeId(warehouseId) : warehouseName + "（" + warehouseId + "）"));
        impact.add(NlPlan.row("业务日期", bizDate == null ? "今天（后端默认）" : bizDate));
        impact.add(NlPlan.row("明细", validLines + " 行 / 合计 " + totalQty + " 件"));
        impact.add(NlPlan.row("合计金额", yuan(totalAmount)));
        impact.add(NlPlan.row("落地状态", "DRAFT —— 审核、入库/出货仍需人工在页面上操作"));

        String summary = blockers.isEmpty()
                ? "将创建%s草稿：%s → %s，%d 行，合计 %s".formatted(
                        docLabel,
                        partnerName == null ? describeId(partnerId) : partnerName,
                        warehouseName == null ? describeId(warehouseId) : warehouseName,
                        validLines,
                        yuan(totalAmount))
                : "%s草稿无法生成：还有 %d 个问题需要先解决".formatted(docLabel, blockers.size());

        return new NlPlan(
                command.intent().code(),
                true,
                true,
                policy.reviewReason(command),
                blockers.isEmpty(),
                summary,
                steps,
                impact,
                warnings,
                blockers,
                args);
    }

    // ─────────────────────────── 小工具 ───────────────────────────

    /** 只读校验：对象不存在算"阻塞"，不算系统异常——用户少填一个 id 不该看到 500。 */
    private <T> T tryRequire(java.util.function.Supplier<T> lookup, List<String> blockers, String message) {
        try {
            return lookup.get();
        } catch (BusinessException ex) {
            blockers.add(message);
            return null;
        }
    }

    private static String describeId(Long id) {
        return id == null ? "（未填）" : "id=" + id;
    }

    private static String yuan(long fen) {
        return "¥%.2f".formatted(fen / 100.0);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> softLines(Map<String, Object> args) {
        Object raw = args.get("lines");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                out.add((Map<String, Object>) map);
            }
        }
        return out;
    }

    private static Long asLong(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(raw).trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Integer asInt(Object raw) {
        Long value = asLong(raw);
        if (value == null || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            return null;
        }
        return value.intValue();
    }

    private static String intentLabel(NlIntent intent) {
        return switch (intent) {
            case HELP -> "查看能力清单";
            case WHO_AM_I -> "询问当前模型";
            case QUERY_INVENTORY -> "查库存";
            case LIST_DOCUMENTS -> "查单据列表";
            case GET_DOCUMENT -> "查单据详情";
            case INVENTORY_SUMMARY -> "查进销存汇总";
            case REPLENISH_ADVICE -> "查补货建议";
            case QUERY_MASTER_DATA -> "查主数据";
            case SQL_QUERY -> "自定义查询";
            case CREATE_PURCHASE_ORDER -> "建进货单草稿";
            case CREATE_SALES_ORDER -> "建出货单草稿";
            case SUBMIT_DOCUMENT -> "提交单据";
        };
    }
}
